package com.crsmthw.lyra.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LrcParserTest {

    @Test
    fun `parse simple timestamp line`() {
        val lines = LrcParser.parse("[01:23.45]Hello World")
        assertEquals(1, lines.size)
        assertEquals(83450L, lines[0].timestampMs)  // 1*60000 + 23*1000 + 450
        assertEquals("Hello World", lines[0].text)
    }

    @Test
    fun `parse three-digit minute`() {
        val lines = LrcParser.parse("[100:00.00]Long song")
        assertEquals(1, lines.size)
        assertEquals(100 * 60_000L, lines[0].timestampMs)
        assertEquals("Long song", lines[0].text)
    }

    @Test
    fun `parse two-digit fractional as centiseconds padded to milliseconds`() {
        // [00:05.99] → 99 padded to "990" → 990ms
        val lines = LrcParser.parse("[00:05.99]Text")
        assertEquals(1, lines.size)
        assertEquals(5 * 1000L + 990L, lines[0].timestampMs)
    }

    @Test
    fun `parse three-digit fractional as milliseconds truncated to 3 digits`() {
        // [00:05.123] → "123" take 3 → 123ms
        val lines = LrcParser.parse("[00:05.123]Text")
        assertEquals(1, lines.size)
        assertEquals(5 * 1000L + 123L, lines[0].timestampMs)
    }

    @Test
    fun `parse trims whitespace around text`() {
        val lines = LrcParser.parse("[00:01.00]  some text  ")
        assertEquals(1, lines.size)
        assertEquals("some text", lines[0].text)
    }

    @Test
    fun `parse empty text line`() {
        val lines = LrcParser.parse("[00:10.00]")
        assertEquals(1, lines.size)
        assertEquals("", lines[0].text)
    }

    @Test
    fun `parse multiple lines`() {
        val lrc = """
            [00:01.00]Line one
            [00:05.00]Line two
            [00:10.00]Line three
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)
        assertEquals("Line one", lines[0].text)
        assertEquals("Line two", lines[1].text)
        assertEquals("Line three", lines[2].text)
    }

    @Test
    fun `parse sorts by timestamp`() {
        val lrc = """
            [00:10.00]Second
            [00:01.00]First
            [00:20.00]Third
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)
        assertEquals("First", lines[0].text)
        assertEquals("Second", lines[1].text)
        assertEquals("Third", lines[2].text)
    }

    @Test
    fun `parse skips malformed lines`() {
        val lrc = """
            [00:01.00]Good line
            This is not a timestamp line
            [bad:time]Also bad
            [00:02.00]Another good line
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals("Good line", lines[0].text)
        assertEquals("Another good line", lines[1].text)
    }

    @Test
    fun `parse skips metadata tags`() {
        val lrc = """
            [ar:Artist Name]
            [ti:Song Title]
            [00:01.00]First lyric
        """.trimIndent()
        // Metadata tags don't match the timestamp regex (minutes are digits only)
        val lines = LrcParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals("First lyric", lines[0].text)
    }

    @Test
    fun `parse returns empty list for empty input`() {
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test
    fun `parse returns empty list for blank input`() {
        assertTrue(LrcParser.parse("   \n  \n  ").isEmpty())
    }

    @Test
    fun `parse returns empty list for all malformed lines`() {
        assertTrue(LrcParser.parse("no timestamps here\njust text").isEmpty())
    }

    @Test
    fun `parse handles zero timestamp`() {
        val lines = LrcParser.parse("[00:00.00]Start")
        assertEquals(1, lines.size)
        assertEquals(0L, lines[0].timestampMs)
    }

    @Test
    fun `parse handles lines with brackets in text`() {
        // The regex anchors at ^ and reads until $, the text is captured as group 4
        val lines = LrcParser.parse("[00:01.00]Text [with brackets]")
        // This should NOT match because the regex is anchored and "]" ends the timestamp group
        // Let's verify actual behavior
        assertEquals(1, lines.size)
        assertEquals("Text [with brackets]", lines[0].text)
    }
}
