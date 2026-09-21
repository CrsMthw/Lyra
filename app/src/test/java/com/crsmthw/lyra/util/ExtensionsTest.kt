package com.crsmthw.lyra.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensionsTest {

    // ── toTimeString (Long.toTimeString: millis → "m:ss") ────────────────────

    @Test
    fun `toTimeString at 0 ms returns 0 colon 00`() {
        assertEquals("0:00", 0L.toTimeString())
    }

    @Test
    fun `toTimeString at 59 seconds`() {
        assertEquals("0:59", 59_000L.toTimeString())
    }

    @Test
    fun `toTimeString at exactly 1 minute`() {
        assertEquals("1:00", 60_000L.toTimeString())
    }

    @Test
    fun `toTimeString at 1 minute 5 seconds pads seconds`() {
        assertEquals("1:05", 65_000L.toTimeString())
    }

    @Test
    fun `toTimeString at 1 hour`() {
        assertEquals("60:00", 3_600_000L.toTimeString())
    }

    @Test
    fun `toTimeString at 1 hour 23 minutes 45 seconds`() {
        // 1*3600 + 23*60 + 45 = 5025 seconds = 83:45
        assertEquals("83:45", 5_025_000L.toTimeString())
    }

    @Test
    fun `toTimeString truncates sub-second portion`() {
        // 1500ms = 1.5s → 0:01
        assertEquals("0:01", 1_500L.toTimeString())
    }

    @Test
    fun `toTimeString for negative value`() {
        // Negative millis: totalSeconds = -1000/1000 = -1
        // minutes = -1/60 = 0, seconds = -1%60 = -1
        // Kotlin's rem gives -1 for negative dividend with positive divisor
        // Format: "0:-1" — this is the actual behavior, testing it as-is
        val result = (-1000L).toTimeString()
        // The function doesn't guard against negatives — just pin the actual behavior
        assertEquals("0:-1", result)
    }

    // ── toDurationString (Long.toDurationString: millis → "Xh Ym" or "Ym") ──

    @Test
    fun `toDurationString at 0 ms returns 0m`() {
        assertEquals("0m", 0L.toDurationString())
    }

    @Test
    fun `toDurationString at 30 seconds returns 0m`() {
        // 30_000 / 1000 = 30 seconds, 0 hours, 0 minutes
        assertEquals("0m", 30_000L.toDurationString())
    }

    @Test
    fun `toDurationString at exactly 1 minute`() {
        assertEquals("1m", 60_000L.toDurationString())
    }

    @Test
    fun `toDurationString at 59 minutes`() {
        assertEquals("59m", (59 * 60_000L).toDurationString())
    }

    @Test
    fun `toDurationString at 1 hour`() {
        assertEquals("1h 0m", 3_600_000L.toDurationString())
    }

    @Test
    fun `toDurationString at 2 hours 30 minutes`() {
        assertEquals("2h 30m", (2 * 3_600_000L + 30 * 60_000L).toDurationString())
    }

    @Test
    fun `toDurationString at 1 hour 1 minute`() {
        assertEquals("1h 1m", (3_600_000L + 60_000L).toDurationString())
    }
}
