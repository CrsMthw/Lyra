package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.remote.model.PlaylistOwner
import com.crsmthw.lyra.data.remote.model.PlaylistTracksMeta
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.UserPlaylistsResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SweepUserPlaylistsTest {

    private fun playlist(id: String) = SpotifyPlaylist(
        id = id, name = "P$id", description = null, uri = "spotify:playlist:$id",
        owner = PlaylistOwner("user", "User"), tracksMeta = PlaylistTracksMeta(0, null),
    )

    @Test
    fun `single page sweep returns all playlists`() = runTest {
        val playlists = listOf(playlist("1"), playlist("2"))
        val result = sweepUserPlaylists { offset ->
            assertEquals(0, offset)
            Result.success(UserPlaylistsResponse(
                rawItems = playlists, total = 2, next = null,
            ))
        }
        assertTrue(result.isSuccess)
        val sweep = result.getOrThrow()
        assertEquals(2, sweep.items.size)
        assertEquals(2, sweep.total)
        assertTrue(sweep.complete)
        assertNull(sweep.error)
    }

    @Test
    fun `multi-page sweep advances offset by rawCount`() = runTest {
        var callCount = 0
        val result = sweepUserPlaylists { offset ->
            callCount++
            when (offset) {
                0 -> Result.success(UserPlaylistsResponse(
                    rawItems = listOf(playlist("1"), playlist("2")),
                    total = 4, next = "url",
                ))
                2 -> Result.success(UserPlaylistsResponse(
                    rawItems = listOf(playlist("3"), playlist("4")),
                    total = 4, next = null,
                ))
                else -> error("Unexpected offset $offset")
            }
        }
        assertEquals(2, callCount)
        val sweep = result.getOrThrow()
        assertEquals(4, sweep.items.size)
        assertEquals(4, sweep.total)
        assertTrue(sweep.complete)
    }

    @Test
    fun `offset advances by rawCount not items size when page has null slots`() = runTest {
        var secondOffset = -1
        val result = sweepUserPlaylists { offset ->
            when (offset) {
                0 -> {
                    // Page with 3 raw slots, 1 null → rawCount=3, items=2
                    Result.success(UserPlaylistsResponse(
                        rawItems = listOf(playlist("1"), null, playlist("2")),
                        total = 5, next = "url",
                    ))
                }
                else -> {
                    secondOffset = offset
                    Result.success(UserPlaylistsResponse(
                        rawItems = listOf(playlist("3")),
                        total = 5, next = null,
                    ))
                }
            }
        }
        // Must advance by rawCount (3), not items.size (2)
        assertEquals(3, secondOffset)
        assertEquals(3, result.getOrThrow().items.size)
    }

    @Test
    fun `stops on rawCount zero even if next is non-null`() = runTest {
        var calls = 0
        val result = sweepUserPlaylists { _ ->
            if (++calls > 3) error("rawCount==0 guard regressed — infinite loop")
            Result.success(UserPlaylistsResponse(
                rawItems = emptyList(), total = 0, next = "some_url",
            ))
        }
        assertTrue(result.isSuccess)
        val sweep = result.getOrThrow()
        assertTrue(sweep.items.isEmpty())
        assertTrue(sweep.complete)
        assertEquals(1, calls)
    }

    @Test
    fun `first page failure returns Result failure`() = runTest {
        val error = RuntimeException("network down")
        val result = sweepUserPlaylists { _ ->
            Result.failure(error)
        }
        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `later page failure returns partial sweep with complete false`() = runTest {
        val error = RuntimeException("429 rate limit")
        val result = sweepUserPlaylists { offset ->
            when (offset) {
                0 -> Result.success(UserPlaylistsResponse(
                    rawItems = listOf(playlist("1"), playlist("2")),
                    total = 100, next = "url",
                ))
                else -> Result.failure(error)
            }
        }
        assertTrue(result.isSuccess)
        val sweep = result.getOrThrow()
        assertEquals(2, sweep.items.size)
        assertEquals(100, sweep.total)
        assertFalse(sweep.complete)
        assertEquals(error, sweep.error)
    }

    @Test
    fun `total is taken from first page only`() = runTest {
        val result = sweepUserPlaylists { offset ->
            when (offset) {
                0 -> Result.success(UserPlaylistsResponse(
                    rawItems = listOf(playlist("1")), total = 42, next = "url",
                ))
                else -> Result.success(UserPlaylistsResponse(
                    rawItems = listOf(playlist("2")), total = 999, next = null,
                ))
            }
        }
        assertEquals(42, result.getOrThrow().total)
    }

    @Test
    fun `sweep does not deduplicate playlist ids`() = runTest {
        val result = sweepUserPlaylists { _ ->
            Result.success(UserPlaylistsResponse(
                rawItems = listOf(playlist("1"), playlist("1")), total = 2, next = null,
            ))
        }
        assertEquals(2, result.getOrThrow().items.size)
    }
}
