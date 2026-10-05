package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.remote.LrcLibApiService
import com.crsmthw.lyra.data.remote.model.LrcLibResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The lyrics cache is keyed on the uri and never caches a server failure (audit 2026-10-04 W1 / W2). */
class LyricsRepositoryTest {

    private class FakeLrcLib : LrcLibApiService {
        val calls = mutableListOf<String>()
        var failWith: Int? = null
        override suspend fun get(artistName: String, trackName: String, albumName: String, durationSeconds: Int): LrcLibResponse {
            calls += trackName
            failWith?.let { code ->
                throw HttpException(Response.error<LrcLibResponse>(code, "".toResponseBody("application/json".toMediaType())))
            }
            return LrcLibResponse(1, trackName, artistName, albumName, durationSeconds.toDouble(), false, "Words of $trackName", null)
        }
        override suspend fun search(query: String): List<LrcLibResponse> = emptyList()
    }

    @Test
    fun `two local files with different uris both reach the service`() = runTest {
        val api = FakeLrcLib()
        val repo = LyricsRepository(api)
        val a = repo.fetchLyrics("spotify:local:A:B:One:100", "One", "Artist", "", 100_000L)
        val b = repo.fetchLyrics("spotify:local:A:B:Two:100", "Two", "Artist", "", 100_000L)
        assertEquals(listOf("One", "Two"), api.calls)
        assertEquals("Words of One", assertIs<LyricsState.Plain>(a).text)
        assertEquals("Words of Two", assertIs<LyricsState.Plain>(b).text)
    }

    @Test
    fun `the same uri is served from the cache`() = runTest {
        val api = FakeLrcLib()
        val repo = LyricsRepository(api)
        repo.fetchLyrics("spotify:track:x", "X", "Artist", "", 1_000L)
        repo.fetchLyrics("spotify:track:x", "X", "Artist", "", 1_000L)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `a server failure is answered None but NOT cached`() = runTest {
        val api = FakeLrcLib().apply { failWith = 503 }
        val repo = LyricsRepository(api)
        assertTrue(repo.fetchLyrics("spotify:track:y", "Y", "Artist", "", 1_000L) is LyricsState.None)
        api.failWith = null
        assertIs<LyricsState.Plain>(repo.fetchLyrics("spotify:track:y", "Y", "Artist", "", 1_000L))
        assertEquals(2, api.calls.size)
    }
}
