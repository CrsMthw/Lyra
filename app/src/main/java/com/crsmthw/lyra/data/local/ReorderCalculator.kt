package com.crsmthw.lyra.data.local

import com.crsmthw.lyra.data.remote.model.PlaylistTrack
import com.crsmthw.lyra.data.remote.model.SpotifyTrack

/**
 * A slot in the full raw list of a playlist. Visible rows are [Visible]; hidden ones (null slots,
 * `resolvedTrack == null`, or `isPlayable == false`) are [Hidden]. The raw list preserves the
 * server's ordering so that `rawPos[rowIndex]` gives the API position of a visible row.
 */
sealed interface RawSlot {
    data class Visible(val track: SpotifyTrack) : RawSlot
    data object Hidden : RawSlot
}

/**
 * Holds the full raw list of a playlist in reorder mode and translates row-index moves into the
 * API's `range_start` / `insert_before` parameters. Pure Kotlin — no Android imports — so a later
 * unit test can cover it directly.
 *
 * **Row indices vs. raw positions.** The rows the user drags are the FILTERED list (null slots,
 * unresolvable items and unplayable tracks dropped). The API's `range_start` / `insert_before`
 * index EVERY item the endpoint returns, including the ones we filter out. This class bridges the
 * two by walking `rawItems` once at construction, keeping both a display list and a position map.
 *
 * **Hidden-slot relocation.** A reorder moves one raw slot past all intervening slots, hidden or
 * not. Hidden slots that sat between the source and the destination therefore shift position. This
 * is invisible in Lyra but real in the playlist; it mirrors what `PUT /playlists/{id}/items` does.
 */
class ReorderCalculator private constructor(
    private val rawList: MutableList<RawSlot>,
) {
    /** The current display rows — the user's list. Updated in-place by [applyMove]. */
    val visibleTracks: List<SpotifyTrack>
        get() = rawList.filterIsInstance<RawSlot.Visible>().map { it.track }

    /** Raw-position index of every visible row, in display order. */
    private fun rawPosOf(rowIndex: Int): Int {
        var seen = -1
        for (i in rawList.indices) {
            if (rawList[i] is RawSlot.Visible) seen++
            if (seen == rowIndex) return i
        }
        throw IndexOutOfBoundsException("rowIndex $rowIndex beyond ${visibleTracks.size} visible rows")
    }

    /**
     * Translates a drag-and-drop move of a visible row from [fromRow] to [toRow] into the API's
     * parameters and applies the same move to the internal raw list so the map stays valid for the
     * next move. Returns `null` if the indices are equal (no move).
     */
    fun applyMove(fromRow: Int, toRow: Int): ReorderApiParams? {
        if (fromRow == toRow) return null
        val rangeStart  = rawPosOf(fromRow)
        val insertBefore = if (fromRow < toRow) {
            // Moving down: insert AFTER the destination row's raw position.
            rawPosOf(toRow) + 1
        } else {
            // Moving up: insert AT the destination row's raw position.
            rawPosOf(toRow)
        }
        // Apply to the raw list: remove at rangeStart, insert at the adjusted position.
        val slot = rawList.removeAt(rangeStart)
        val insertAt = if (insertBefore > rangeStart) insertBefore - 1 else insertBefore
        rawList.add(insertAt, slot)
        return ReorderApiParams(rangeStart, insertBefore)
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
         * Everything else becomes [RawSlot.Visible].
         */
        fun fromRawPages(pages: List<List<PlaylistTrack?>>): ReorderCalculator {
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
                    raw += RawSlot.Visible(track)
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
