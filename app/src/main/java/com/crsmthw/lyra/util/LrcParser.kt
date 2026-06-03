package com.crsmthw.lyra.util

data class LyricLine(val timestampMs: Long, val text: String)

object LrcParser {
    private val lineRegex = Regex("""^\[(\d{2,3}):(\d{2})\.(\d{2,3})\](.*)$""")

    fun parse(lrc: String): List<LyricLine> =
        lrc.lines()
            .mapNotNull { lineRegex.matchEntire(it.trim()) }
            .map { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val ms  = m.groupValues[3].padEnd(3, '0').take(3).toLong()
                LyricLine(
                    timestampMs = min * 60_000L + sec * 1_000L + ms,
                    text        = m.groupValues[4].trim(),
                )
            }
            .sortedBy { it.timestampMs }
}
