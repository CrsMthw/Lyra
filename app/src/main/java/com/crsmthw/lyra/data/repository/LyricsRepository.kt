package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.remote.LrcLibApiService
import com.crsmthw.lyra.data.remote.model.LrcLibResponse
import com.crsmthw.lyra.util.LrcParser
import com.crsmthw.lyra.util.LyricLine
import retrofit2.HttpException
import kotlin.math.abs

sealed class LyricsState {
    object Loading : LyricsState()
    data class Synced(val lines: List<LyricLine>) : LyricsState()
    data class Plain(val text: String) : LyricsState()
    object None : LyricsState()
}

class LyricsRepository(private val api: LrcLibApiService) {

    private val cache = mutableMapOf<String, LyricsState>()

    suspend fun fetchLyrics(
        trackId    : String,
        trackName  : String,
        artistName : String,
        albumName  : String,
        durationMs : Long,
    ): LyricsState {
        cache[trackId]?.let { return it }

        return try {
            val durationSecs = (durationMs / 1000).toInt()
            val response = try {
                api.get(artistName, trackName, albumName, durationSecs)
            } catch (e: HttpException) {
                if (e.code() == 404) searchFallback(trackName, artistName, durationSecs)
                else null
            } ?: return LyricsState.None.also { cache[trackId] = it }

            val result = response.toState()
            cache[trackId] = result
            result
        } catch (_: Exception) {
            LyricsState.None
        }
    }

    private suspend fun searchFallback(
        trackName  : String,
        artistName : String,
        durationSecs: Int,
    ): LrcLibResponse? = try {
        api.search("$trackName $artistName")
            .filter { it.syncedLyrics != null || it.plainLyrics != null }
            .minByOrNull { abs((it.duration ?: 0.0) - durationSecs) }
    } catch (_: Exception) { null }

    private fun LrcLibResponse.toState(): LyricsState {
        if (instrumental == true) return LyricsState.None
        val synced = syncedLyrics
        if (!synced.isNullOrBlank()) {
            val lines = LrcParser.parse(synced)
            if (lines.isNotEmpty()) return LyricsState.Synced(lines)
        }
        val plain = plainLyrics
        if (!plain.isNullOrBlank()) return LyricsState.Plain(plain)
        return LyricsState.None
    }
}
