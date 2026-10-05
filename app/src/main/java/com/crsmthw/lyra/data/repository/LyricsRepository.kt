package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.remote.LrcLibApiService
import com.crsmthw.lyra.data.remote.model.LrcLibResponse
import com.crsmthw.lyra.util.LrcParser
import com.crsmthw.lyra.util.LyricLine
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import kotlin.math.abs

sealed class LyricsState {
    object Loading : LyricsState()
    data class Synced(val lines: List<LyricLine>) : LyricsState()
    data class Plain(val text: String) : LyricsState()
    object None : LyricsState()
}

class LyricsRepository(private val api: LrcLibApiService) {

    /**
     * Keyed on the track URI, never the id (audit 2026-10-04 W2): every local file has `id == null`,
     * so an id key made them all share one entry. Only a definite answer is cached — LRCLIB said
     * "no lyrics" (a 404 and an empty search) or returned some; a server failure or a cancellation
     * is NOT cached (W1: a 503 used to pin "no lyrics" for the process, and a swallowed cancellation
     * wrote None over the next track's Loading).
     */
    private val cache = mutableMapOf<String, LyricsState>()

    suspend fun fetchLyrics(
        trackUri   : String,
        trackName  : String,
        artistName : String,
        albumName  : String,
        durationMs : Long,
    ): LyricsState {
        cache[trackUri]?.let { return it }

        return try {
            val durationSecs = (durationMs / 1000).toInt()
            val response = try {
                api.get(artistName, trackName, albumName, durationSecs)
            } catch (e: HttpException) {
                if (e.code() == 404) searchFallback(trackName, artistName, durationSecs)
                else return LyricsState.None   // a transient server failure: answered, not cached
            } ?: return LyricsState.None.also { cache[trackUri] = it }

            val result = response.toState()
            cache[trackUri] = result
            result
        } catch (e: CancellationException) {
            throw e
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
