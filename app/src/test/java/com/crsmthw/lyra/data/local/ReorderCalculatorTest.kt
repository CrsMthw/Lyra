package com.crsmthw.lyra.data.local

import com.crsmthw.lyra.data.remote.model.PlaylistTrack
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ReorderCalculator]. Builds fixtures using Kotlin constructors (SpotifyTrack and
 * PlaylistTrack are plain data classes with defaults).
 */
class ReorderCalculatorTest {

    // ── Fixture helpers ─────────────────────────────────────────────────────────

    /** A playable visible track with a unique uri. */
    private fun track(label: String) = SpotifyTrack(
        id   = label,
        name = label,
        uri  = "spotify:track:$label",
    )

    /** A track marked unplayable. */
    private fun unplayableTrack(label: String) = SpotifyTrack(
        id         = label,
        name       = label,
        uri        = "spotify:track:$label",
        isPlayable = false,
    )

    /** Wraps a SpotifyTrack in a PlaylistTrack. */
    private fun pt(t: SpotifyTrack?) = PlaylistTrack(addedAt = null, track = t, item = null)

    /** Builds a calculator from a single page of PlaylistTrack?. */
    private fun calc(vararg slots: PlaylistTrack?): ReorderCalculator =
        ReorderCalculator.fromRawPages(listOf(slots.toList()))

    /** Returns the visible track labels (ids) in order. */
    private fun ReorderCalculator.labels(): List<String> = visibleTracks.map { it.id }

    // ── fromRawPages filtering ──────────────────────────────────────────────────

    @Test
    fun `fromRawPages filters null slots`() {
        val c = calc(pt(track("A")), null, pt(track("B")))
        assertEquals(listOf("A", "B"), c.labels())
        assertEquals(3, c.totalRawSlots)
    }

    @Test
    fun `fromRawPages filters resolvedTrack null`() {
        val c = calc(pt(track("A")), pt(null), pt(track("B")))
        assertEquals(listOf("A", "B"), c.labels())
        assertEquals(3, c.totalRawSlots)
    }

    @Test
    fun `fromRawPages filters isPlayable false`() {
        val c = calc(pt(track("A")), pt(unplayableTrack("X")), pt(track("B")))
        assertEquals(listOf("A", "B"), c.labels())
        assertEquals(3, c.totalRawSlots)
    }

    @Test
    fun `fromRawPages accepts visible tracks`() {
        val c = calc(pt(track("A")), pt(track("B")), pt(track("C")))
        assertEquals(listOf("A", "B", "C"), c.labels())
        assertEquals(3, c.totalRawSlots)
    }

    @Test
    fun `confirmedTracks returns defensive copy`() {
        val c = calc(pt(track("A")), pt(track("B")))
        val copy1 = c.confirmedTracks()
        c.beginDrag(0)
        c.applyLocalMove(0, 1)
        c.commitDrag()
        val copy2 = c.confirmedTracks()
        // copy1 was taken before the move and should NOT reflect it.
        assertEquals(listOf("A", "B"), copy1.map { it.id })
        assertEquals(listOf("B", "A"), copy2.map { it.id })
    }

    // ── Spotify doc examples ────────────────────────────────────────────────────

    @Test
    fun `doc example - first of 10 to end`() {
        // Spotify doc: range_start=0, insert_before=10 moves the first item to the end.
        val tracks = (0 until 10).map { pt(track("$it")) }
        val c = calc(*tracks.toTypedArray())
        c.beginDrag(0)
        // Walk step by step: row 0 → row 9
        for (i in 0 until 9) c.applyLocalMove(i, i + 1)
        val params = c.commitDrag()
        assertNotNull(params)
        assertEquals(0, params.rangeStart)
        assertEquals(10, params.insertBefore)
        assertEquals((1..9).map { "$it" } + "0", c.labels())
    }

    @Test
    fun `doc example - last of 10 to start`() {
        // Spotify doc: range_start=9, insert_before=0 moves the last item to the start.
        val tracks = (0 until 10).map { pt(track("$it")) }
        val c = calc(*tracks.toTypedArray())
        c.beginDrag(9)
        // Walk step by step: row 9 → row 0
        for (i in 9 downTo 1) c.applyLocalMove(i, i - 1)
        val params = c.commitDrag()
        assertNotNull(params)
        assertEquals(9, params.rangeStart)
        assertEquals(0, params.insertBefore)
        assertEquals(listOf("9") + (0..8).map { "$it" }, c.labels())
    }

    // ── No-op cases ─────────────────────────────────────────────────────────────

    @Test
    fun `applyLocalMove with equal indices returns false`() {
        val c = calc(pt(track("A")), pt(track("B")))
        assertFalse(c.applyLocalMove(0, 0))
    }

    @Test
    fun `commitDrag returns null when no net movement`() {
        // Drag starts at row 1, moves to row 2, then back to row 1.
        val c = calc(pt(track("A")), pt(track("B")), pt(track("C")))
        c.beginDrag(1)
        c.applyLocalMove(1, 2)
        c.applyLocalMove(2, 1) // back
        val params = c.commitDrag()
        assertNull(params)
        assertEquals(listOf("A", "B", "C"), c.labels())
    }

    // ── Hidden slot between source and destination ──────────────────────────────

    @Test
    fun `hidden slot between source and destination, moving down`() {
        // Raw: [A, hidden, B, C]  →  visible: [A, B, C]
        // Drag A from row 0 to row 2 (past B, past C).
        val c = calc(pt(track("A")), null, pt(track("B")), pt(track("C")))
        c.beginDrag(0)
        c.applyLocalMove(0, 1)
        c.applyLocalMove(1, 2)
        val params = c.commitDrag()
        assertNotNull(params)
        assertEquals(0, params.rangeStart)
        // A ends up at raw position 3 (after C). insertBefore = 3+1 = 4 = totalRawSlots.
        assertEquals(4, params.insertBefore)
        assertEquals(listOf("B", "C", "A"), c.labels())
    }

    @Test
    fun `hidden slot between source and destination, moving up`() {
        // Raw: [A, B, hidden, C]  →  visible: [A, B, C]
        // Drag C from row 2 to row 0.
        val c = calc(pt(track("A")), pt(track("B")), null, pt(track("C")))
        c.beginDrag(2)
        c.applyLocalMove(2, 1)
        c.applyLocalMove(1, 0)
        val params = c.commitDrag()
        assertNotNull(params)
        assertEquals(3, params.rangeStart)
        assertEquals(0, params.insertBefore)
        assertEquals(listOf("C", "A", "B"), c.labels())
    }

    @Test
    fun `hidden slot between source and destination, both directions, complex`() {
        // Raw: [A, h, B, h, C, D, h, E]  →  visible: [A, B, C, D, E]
        // Drag A (row 0, rawPos 0) to row 3 (after D).
        val c = calc(
            pt(track("A")), null, pt(track("B")), null,
            pt(track("C")), pt(track("D")), null, pt(track("E")),
        )
        c.beginDrag(0)
        for (i in 0 until 3) c.applyLocalMove(i, i + 1)
        val params = c.commitDrag()
        assertNotNull(params)
        assertEquals(0, params.rangeStart)
        assertEquals(listOf("B", "C", "D", "A", "E"), c.labels())
        // A is now at raw position 5 (after D at raw 5 in original, but positions shifted).
        // In the pending list: [h, B, h, C, D, A, h, E] — A at raw pos 5.
        // insert_before = 5 + 1 = 6 (moved down).
        assertEquals(6, params.insertBefore)
    }

    // ── Adjacent row (one step) ─────────────────────────────────────────────────

    @Test
    fun `move one step down`() {
        val c = calc(pt(track("A")), pt(track("B")), pt(track("C")))
        c.beginDrag(0)
        c.applyLocalMove(0, 1)
        val params = c.commitDrag()
        assertNotNull(params)
        assertEquals(0, params.rangeStart)
        assertEquals(2, params.insertBefore) // raw pos 1 + 1
        assertEquals(listOf("B", "A", "C"), c.labels())
    }

    @Test
    fun `move one step up`() {
        val c = calc(pt(track("A")), pt(track("B")), pt(track("C")))
        c.beginDrag(2)
        c.applyLocalMove(2, 1)
        val params = c.commitDrag()
        assertNotNull(params)
        assertEquals(2, params.rangeStart)
        assertEquals(1, params.insertBefore) // raw pos 1 (moved up)
        assertEquals(listOf("A", "C", "B"), c.labels())
    }

    // ── Step-wise vs. single-move equivalence ───────────────────────────────────
    // The key invariant: step-by-step local moves produce the same raw arrangement as the
    // single API move computed by commitDrag.

    /**
     * Applies a single Spotify API reorder to a raw list (remove at rangeStart, insert at
     * insertBefore adjusted for the removal).
     */
    private fun applyApiMove(raw: MutableList<String>, rangeStart: Int, insertBefore: Int) {
        val item = raw.removeAt(rangeStart)
        val insertAt = if (insertBefore > rangeStart) insertBefore - 1 else insertBefore
        raw.add(insertAt, item)
    }

    /**
     * Runs the equivalence test: builds two calculators from the same raw list, applies step-wise
     * moves on one (beginDrag + N applyLocalMove + commitDrag), applies the resulting single API
     * move on the other's raw snapshot, and verifies the results match.
     */
    private fun assertEquivalence(
        rawLabels: List<String?>,        // null = hidden slot
        fromRow: Int,
        toRow: Int,
        message: String = "from=$fromRow to=$toRow",
    ) {
        // Build raw pages
        val page = rawLabels.map { label ->
            if (label == null) null else pt(track(label))
        }

        // Calculator 1: step-wise drag
        val c1 = calc(*page.toTypedArray())
        c1.beginDrag(fromRow)
        val step = if (toRow > fromRow) 1 else -1
        var current = fromRow
        while (current != toRow) {
            c1.applyLocalMove(current, current + step)
            current += step
        }
        val params = c1.commitDrag()

        // Build a reference raw list (strings, for easy comparison)
        val rawRef = rawLabels.map { it ?: "<hidden>" }.toMutableList()

        if (params != null) {
            applyApiMove(rawRef, params.rangeStart, params.insertBefore)
        }

        // Calculator 1's raw list (read through visible tracks and hidden slot positions)
        // The visible tracks should match between the two approaches.
        val c1Labels = c1.labels()

        // Also build the reference's visible labels
        val refVisible = rawRef.filter { it != "<hidden>" }

        assertEquals(refVisible, c1Labels, "Visible tracks diverge for $message")
    }

    @Test
    fun `equivalence - no hidden slots, move down several rows`() {
        val labels = (0..7).map { "$it" }
        // Move row 1 to row 6
        assertEquivalence(labels, 1, 6)
    }

    @Test
    fun `equivalence - no hidden slots, move up several rows`() {
        val labels = (0..7).map { "$it" }
        // Move row 6 to row 1
        assertEquivalence(labels, 6, 1)
    }

    @Test
    fun `equivalence - hidden slots, move down`() {
        // [A, null, B, null, C, D, null, E]
        val labels: List<String?> = listOf("A", null, "B", null, "C", "D", null, "E")
        // Move visible row 0 (A) to visible row 4 (E) — all the way across 2 hidden slots
        assertEquivalence(labels, 0, 4)
    }

    @Test
    fun `equivalence - hidden slots, move up`() {
        val labels: List<String?> = listOf("A", null, "B", null, "C", "D", null, "E")
        // Move visible row 4 (E) to visible row 0 (A)
        assertEquivalence(labels, 4, 0)
    }

    @Test
    fun `equivalence - adjacent move down with hidden between`() {
        // [A, null, B] — move A (row 0) to row 1 (B)
        val labels: List<String?> = listOf("A", null, "B")
        assertEquivalence(labels, 0, 1)
    }

    @Test
    fun `equivalence - adjacent move up with hidden between`() {
        val labels: List<String?> = listOf("A", null, "B")
        assertEquivalence(labels, 1, 0)
    }

    @Test
    fun `equivalence - many rows, long drag down`() {
        // 20 visible rows, no hidden
        val labels = (0 until 20).map { "$it" }
        assertEquivalence(labels, 2, 18)
    }

    @Test
    fun `equivalence - many rows, long drag up`() {
        val labels = (0 until 20).map { "$it" }
        assertEquivalence(labels, 18, 2)
    }

    @Test
    fun `equivalence - first to last`() {
        val labels = (0 until 10).map { "$it" }
        assertEquivalence(labels, 0, 9)
    }

    @Test
    fun `equivalence - last to first`() {
        val labels = (0 until 10).map { "$it" }
        assertEquivalence(labels, 9, 0)
    }

    @Test
    fun `equivalence - middle segment with scattered hidden`() {
        // [null, A, B, null, C, null, null, D, E, F, null, G]
        val labels: List<String?> = listOf(null, "A", "B", null, "C", null, null, "D", "E", "F", null, "G")
        // Move B (row 1) to F (row 5)
        assertEquivalence(labels, 1, 5)
        // Also test reverse
    }

    @Test
    fun `equivalence - middle segment with scattered hidden, reverse`() {
        val labels: List<String?> = listOf(null, "A", "B", null, "C", null, null, "D", "E", "F", null, "G")
        // Move F (row 5) to B (row 1)
        assertEquivalence(labels, 5, 1)
    }

    // ── Stable ids ──────────────────────────────────────────────────────────────

    @Test
    fun `visibleTracksWithIds assigns unique stable ids`() {
        val c = calc(pt(track("A")), null, pt(track("B")), pt(track("C")))
        val ids = c.visibleTracksWithIds
        assertEquals(3, ids.size)
        assertEquals(listOf(0, 1, 2), ids.map { it.first })
        assertEquals(listOf("A", "B", "C"), ids.map { it.second.id })
    }

    @Test
    fun `stable ids survive reorder`() {
        val c = calc(pt(track("A")), pt(track("B")), pt(track("C")))
        val before = c.visibleTracksWithIds.map { it.first to it.second.id }
        // A=0, B=1, C=2

        c.beginDrag(0)
        c.applyLocalMove(0, 1)
        c.applyLocalMove(1, 2)
        c.commitDrag()

        val after = c.visibleTracksWithIds.map { it.first to it.second.id }
        // Now order is B, C, A — but ids follow the tracks.
        assertEquals(listOf(1 to "B", 2 to "C", 0 to "A"), after)
    }

    // ── Consecutive drags ───────────────────────────────────────────────────────

    @Test
    fun `second drag after first commit works correctly`() {
        val c = calc(pt(track("A")), pt(track("B")), pt(track("C")), pt(track("D")))
        // First drag: move A to after C → [B, C, A, D]
        c.beginDrag(0)
        c.applyLocalMove(0, 1)
        c.applyLocalMove(1, 2)
        val p1 = c.commitDrag()
        assertNotNull(p1)
        assertEquals(listOf("B", "C", "A", "D"), c.labels())

        // Second drag: move D to before B → [D, B, C, A]
        c.beginDrag(3)
        c.applyLocalMove(3, 2)
        c.applyLocalMove(2, 1)
        c.applyLocalMove(1, 0)
        val p2 = c.commitDrag()
        assertNotNull(p2)
        assertEquals(listOf("D", "B", "C", "A"), c.labels())
        // D was at raw pos 3 after first commit, now at raw pos 0.
        assertEquals(3, p2.rangeStart)
        assertEquals(0, p2.insertBefore)
    }

    // ── cancelDrag ──────────────────────────────────────────────────────────────

    @Test
    fun `cancelDrag reverts to snapshot`() {
        val c = calc(pt(track("A")), pt(track("B")), pt(track("C")))
        c.beginDrag(0)
        c.applyLocalMove(0, 1)
        c.applyLocalMove(1, 2)
        assertEquals(listOf("B", "C", "A"), c.labels())

        c.cancelDrag()
        assertEquals(listOf("A", "B", "C"), c.labels())
        assertFalse(c.isDragging)
    }

    // ── isDragging state ────────────────────────────────────────────────────────

    @Test
    fun `isDragging reflects drag lifecycle`() {
        val c = calc(pt(track("A")), pt(track("B")))
        assertFalse(c.isDragging)
        c.beginDrag(0)
        assertTrue(c.isDragging)
        c.applyLocalMove(0, 1)
        assertTrue(c.isDragging)
        c.commitDrag()
        assertFalse(c.isDragging)
    }

    // ── Accessibility path ──────────────────────────────────────────────────────
    // The a11y "Move up" / "Move down" custom actions call the same three VM functions as a
    // gesture: beginReorderDrag(row) → reorderTrackLocal(row, row±1) → commitReorderDrag().
    // This test anchors that contract at the calculator level.

    @Test
    fun `a11y - beginDrag + one applyLocalMove + commitDrag produces a single-move PUT`() {
        val c = calc(pt(track("A")), null, pt(track("B")), pt(track("C")))
        // A11y "Move B (row 1) down one step"
        c.beginDrag(1)
        c.applyLocalMove(1, 2)
        val params = c.commitDrag()
        assertNotNull(params)
        // B was at raw pos 2, C at raw pos 3. Moving B after C → insert_before = 3+1 = 4.
        assertEquals(2, params.rangeStart)
        assertEquals(4, params.insertBefore)
        assertEquals(listOf("A", "C", "B"), c.labels())
    }

    @Test
    fun `a11y - beginDrag + one applyLocalMove up + commitDrag`() {
        val c = calc(pt(track("A")), null, pt(track("B")), pt(track("C")))
        // A11y "Move C (row 2) up one step"
        c.beginDrag(2)
        c.applyLocalMove(2, 1)
        val params = c.commitDrag()
        assertNotNull(params)
        // C was at raw pos 3, B at raw pos 2. Moving C before B → insert_before = 2.
        assertEquals(3, params.rangeStart)
        assertEquals(2, params.insertBefore)
        assertEquals(listOf("A", "C", "B"), c.labels())
    }
}
