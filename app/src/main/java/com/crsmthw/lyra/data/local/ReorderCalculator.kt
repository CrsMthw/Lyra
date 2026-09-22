package com.crsmthw.lyra.data.local

import com.crsmthw.lyra.data.remote.model.PlaylistTrack
import com.crsmthw.lyra.data.remote.model.SpotifyTrack

/**
 * A slot in the full raw list of a playlist. Visible rows are [Visible]; hidden ones (null slots,
 * `resolvedTrack == null`, or `isPlayable == false`) are [Hidden]. The raw list preserves the
 * server's ordering so that `rawPos[rowIndex]` gives the API position of a visible row.
 */
sealed interface RawSlot {
    data class Visible(val track: SpotifyTrack, val stableId: Int) : RawSlot
    data object Hidden : RawSlot
}

/**
 * Holds the full raw list of a playlist in reorder mode and translates row-index moves into the
 * API's `range_start` / `insert_before` parameters. Pure Kotlin — no Android imports — so unit
 * tests cover it directly.
 *
 * **Row indices vs. raw positions.** The rows the user drags are the FILTERED list (null slots,
 * unresolvable items and unplayable tracks dropped). The API's `range_start` / `insert_before`
 * index EVERY item the endpoint returns, including the ones we filter out. This class bridges the
 * two by walking `rawItems` once at construction, keeping both a display list and a position map.
 *
 * **Hidden-slot relocation.** A reorder moves one raw slot past all intervening slots, hidden or
 * not. Hidden slots that sat between the source and the destination therefore shift position. This
 * is invisible in Lyra but real in the playlist; it mirrors what `PUT /playlists/{id}/items` does.
 *
 * **Drag lifecycle.** A drag consists of:
 * 1. [beginDrag] — snapshots the raw list and records the dragged slot's position.
 * 2. Zero or more [applyLocalMove] calls — each step swaps the dragged row past one neighbour
 *    in the pending list, so the UI tracks the finger. No API call.
 * 3. [commitDrag] — computes the **single** API `(range_start, insert_before)` that moves the
 *    slot from its drag-start position in the snapshot to its current position in the pending
 *    list. The snapshot is then discarded. Takes no end-row argument: the calculator already
 *    knows where the slot is.
 *
 * This model means consecutive drops are still serialised (one PUT per drop), but a single drag
 * across 30 rows produces one PUT, not 30.
 */
class ReorderCalculator private constructor(
    private val rawList: MutableList<RawSlot>,
) {
    /**
     * The current display rows paired with their stable ids. The id is assigned once at
     * construction and never changes, regardless of position changes — safe as a LazyColumn key.
     */
    val visibleTracksWithIds: List<Pair<Int, SpotifyTrack>>
        get() = rawList.filterIsInstance<RawSlot.Visible>().map { it.stableId to it.track }

    /** The current display rows — the user's list. Updated in-place by [applyLocalMove]. */
    val visibleTracks: List<SpotifyTrack>
        get() = rawList.filterIsInstance<RawSlot.Visible>().map { it.track }

    /** Raw-position index of every visible row, in display order. */
    private fun rawPosOf(rowIndex: Int): Int {
        var seen = -1
        for (i in rawList.indices) {
            if (rawList[i] is RawSlot.Visible) seen++
            if (seen == rowIndex) return i
        }
        throw IndexOutOfBoundsException("rowIndex $rowIndex beyond visible rows")
    }

    /** The raw-position index of a slot identified by reference (identity). */
    private fun rawPosOfSlot(slot: RawSlot): Int {
        for (i in rawList.indices) {
            if (rawList[i] === slot) return i
        }
        throw IllegalStateException("Dragged slot no longer in the raw list")
    }

    // ── Drag state ──────────────────────────────────────────────────────────────

    /** A snapshot of the raw list taken at [beginDrag], representing the server-confirmed state. */
    private var snapshot: List<RawSlot>? = null

    /** The raw position of the dragged slot in the [snapshot]. */
    private var dragStartRawPos: Int = -1

    /** The slot being dragged, identified by reference (=== identity). */
    private var draggedSlot: RawSlot? = null

    /** True while a drag is in progress (between [beginDrag] and [commitDrag]/[cancelDrag]). */
    val isDragging: Boolean get() = snapshot != null

    /**
     * Begins a drag on the visible row at [rowIndex]. Snapshots the raw list and records the
     * dragged slot so [commitDrag] can compute the net move later.
     */
    fun beginDrag(rowIndex: Int) {
        check(snapshot == null) { "beginDrag called while a drag is already in progress" }
        val slot = rawList[rawPosOf(rowIndex)]
        snapshot = rawList.toList()  // defensive copy
        dragStartRawPos = rawPosOf(rowIndex)
        draggedSlot = slot
    }

    /**
     * Applies one neighbour swap during a drag — the dragged row crosses one adjacent row. Updates
     * the pending raw list so the UI stays in sync. No API call is made; the net move is computed
     * at [commitDrag]. Returns `null` if the indices are equal (no move).
     */
    fun applyLocalMove(fromRow: Int, toRow: Int): Boolean {
        if (fromRow == toRow) return false
        val rangeStart = rawPosOf(fromRow)
        val insertBefore = if (fromRow < toRow) {
            rawPosOf(toRow) + 1
        } else {
            rawPosOf(toRow)
        }
        val slot = rawList.removeAt(rangeStart)
        val insertAt = if (insertBefore > rangeStart) insertBefore - 1 else insertBefore
        rawList.add(insertAt, slot)
        return true
    }

    /**
     * Ends the drag and computes the **single** API move that produces the same result as all
     * the [applyLocalMove] calls since [beginDrag]. Returns `null` if the slot did not move
     * (net no-op). Clears the drag state.
     *
     * The returned params are computed against the snapshot (= the server's list at drag start):
     * - `rangeStart` = the slot's position in the snapshot
     * - `insertBefore` = where the slot should end up, in the snapshot's position space
     */
    fun commitDrag(): ReorderApiParams? {
        snapshot ?: return null
        val slot = draggedSlot ?: return null
        val startPos = dragStartRawPos
        val currentPos = rawPosOfSlot(slot)

        // Clear drag state.
        snapshot = null
        draggedSlot = null
        dragStartRawPos = -1

        if (currentPos == startPos) return null

        // The Spotify API removes the item at range_start, then inserts it at insert_before
        // (both in the original list's position space). After removal, positions above range_start
        // shift down by 1, so the local insertion index is (insert_before - 1) when insert_before
        // > range_start, or insert_before when insert_before <= range_start. We need the item to
        // end up at currentPos:
        //
        // Moved down (currentPos > startPos): insert_before = currentPos + 1
        //   Insertion index = (currentPos + 1) - 1 = currentPos. Correct.
        // Moved up (currentPos < startPos): insert_before = currentPos
        //   Insertion index = currentPos (< startPos, no adjustment). Correct.
        val insertBefore = if (currentPos > startPos) currentPos + 1 else currentPos
        return ReorderApiParams(startPos, insertBefore)
    }

    /**
     * Cancels a drag in progress: reverts the pending raw list to the snapshot and clears the
     * drag state. Used when the server rejects a prior commit and the mode is exited.
     */
    fun cancelDrag() {
        val snap = snapshot ?: return
        rawList.clear()
        rawList.addAll(snap)
        snapshot = null
        draggedSlot = null
        dragStartRawPos = -1
    }

    /** Returns a snapshot of the current visible tracks for persisting as the confirmed order. */
    fun confirmedTracks(): List<SpotifyTrack> = visibleTracks.toList()

    /** Total raw slots — the value to persist as `rawOffset` when caching the finished order. */
    val totalRawSlots: Int get() = rawList.size

    companion object {
        /**
         * Builds from a concatenation of raw API pages. Each page's `rawItems` is walked in order,
         * applying the same three filters the normal display path uses:
         * 1. Null slots (Gson `Unsafe` alloc) → [RawSlot.Hidden]
         * 2. `resolvedTrack == null` (both `track` and `item` absent) → [RawSlot.Hidden]
         * 3. `isPlayable == false` → [RawSlot.Hidden]
         *
         * Everything else becomes [RawSlot.Visible] with a stable id.
         */
        fun fromRawPages(pages: List<List<PlaylistTrack?>>): ReorderCalculator {
            var nextId = 0
            val raw = mutableListOf<RawSlot>()
            for (page in pages) {
                for (slot in page) {
                    if (slot == null) {
                        raw += RawSlot.Hidden
                        continue
                    }
                    val track = slot.resolvedTrack
                    if (track == null || track.isPlayable == false) {
                        raw += RawSlot.Hidden
                        continue
                    }
                    raw += RawSlot.Visible(track, stableId = nextId++)
                }
            }
            return ReorderCalculator(raw)
        }
    }
}

/**
 * The API parameters for one reorder move, ready to pass to
 * `SpotifyRepository.reorderPlaylistItems`.
 */
data class ReorderApiParams(
    val rangeStart  : Int,
    val insertBefore: Int,
)
