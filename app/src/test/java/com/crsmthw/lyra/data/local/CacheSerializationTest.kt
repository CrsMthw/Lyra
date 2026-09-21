package com.crsmthw.lyra.data.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that Gson round-trips the cache data classes correctly, especially the Unsafe-allocation
 * hazards: fields absent from a cache file written by an older build land as null regardless of
 * declared Kotlin defaults.
 */
class CacheSerializationTest {

    private val gson = Gson()

    // ── CachedTrackList ──────────────────────────────────────────────────────

    @Test
    fun `CachedTrackList rawOffset is null when absent from legacy JSON`() {
        // Simulates a cache entry written before rawOffset existed
        val json = """{"snapshotId":"snap1","tracks":[]}"""
        val cached = gson.fromJson(json, CachedTrackList::class.java)
        assertNotNull(cached)
        assertEquals("snap1", cached.snapshotId)
        assertNull(cached.rawOffset)
    }

    @Test
    fun `CachedTrackList rawOffset is preserved when present`() {
        val json = """{"snapshotId":"snap2","tracks":[],"rawOffset":150}"""
        val cached = gson.fromJson(json, CachedTrackList::class.java)
        assertEquals(150, cached.rawOffset)
    }

    @Test
    fun `CachedTrackList round-trips through Gson`() {
        val original = CachedTrackList(snapshotId = "snap3", tracks = emptyList(), rawOffset = 42)
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, CachedTrackList::class.java)
        assertEquals(original.snapshotId, restored.snapshotId)
        assertEquals(original.rawOffset, restored.rawOffset)
    }

    // ── LibraryCacheData ─────────────────────────────────────────────────────

    @Test
    fun `LibraryCacheData nullable fields are null when absent from legacy JSON`() {
        // Simulates an old cache before forYou, savedAlbums, followedArtists, followedShows existed
        val json = """{"playlists":[],"likedSongCount":50}"""
        val data = gson.fromJson(json, LibraryCacheData::class.java)
        assertNotNull(data)
        assertEquals(50, data.likedSongCount)
        assertNull(data.forYou)
        assertNull(data.savedAlbums)
        assertNull(data.followedArtists)
        assertNull(data.followedShows)
    }

    @Test
    fun `LibraryCacheData round-trips with all fields`() {
        val original = LibraryCacheData(
            playlists = emptyList(),
            likedSongCount = 100,
            forYou = ForYouCacheData(jumpBackIn = emptyList(), topTracks = emptyList()),
            savedAlbums = emptyList(),
            followedArtists = emptyList(),
            followedShows = emptyList(),
        )
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, LibraryCacheData::class.java)
        assertEquals(100, restored.likedSongCount)
        assertNotNull(restored.forYou)
        assertNotNull(restored.savedAlbums)
        assertNotNull(restored.followedArtists)
        assertNotNull(restored.followedShows)
    }

    @Test
    fun `LibraryCacheData empty JSON produces safe defaults from Unsafe alloc`() {
        // Gson Unsafe alloc bypasses constructor, so declared defaults don't run.
        // Non-nullable Int fields will be 0, List fields null, etc.
        val data = gson.fromJson("{}", LibraryCacheData::class.java)
        assertNotNull(data)
        // likedSongCount is Int with default 0 — Unsafe gives 0 for primitives
        assertEquals(0, data.likedSongCount)
    }

    // ── RecentSearch ─────────────────────────────────────────────────────────

    @Test
    fun `RecentSearch round-trips through Gson`() {
        val original = RecentSearch(
            type = "track", id = "abc", uri = "spotify:track:abc",
            name = "Test Song", subtitle = "Artist", imageUrl = "https://img.url",
        )
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, RecentSearch::class.java)
        assertEquals(original.type, restored.type)
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.imageUrl, restored.imageUrl)
    }

    @Test
    fun `RecentSearch list round-trips through Gson`() {
        val list = listOf(
            RecentSearch("track", "1", "u1", "Song A", "Artist A", null),
            RecentSearch("album", "2", "u2", "Album B", "Artist B", "img.jpg"),
        )
        val type = TypeToken.getParameterized(List::class.java, RecentSearch::class.java).type
        val json = gson.toJson(list, type)
        val restored: List<RecentSearch> = gson.fromJson(json, type)
        assertEquals(2, restored.size)
        assertEquals("track", restored[0].type)
        assertEquals("album", restored[1].type)
    }
}
