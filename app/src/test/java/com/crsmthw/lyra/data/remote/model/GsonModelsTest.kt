package com.crsmthw.lyra.data.remote.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gson parsing tests using the project's real Gson configuration (plain `Gson()`, matching
 * `GsonConverterFactory.create()` as used in `AppContainer`). Every model is parsed from JSON,
 * never Kotlin-constructed, to catch the Unsafe-allocation hazards the codebase documents.
 */
class GsonModelsTest {

    private val gson = Gson()

    // ── SpotifyPlaylist — the 3.1.3 crash-loop case ──────────────────────────

    @Test
    fun `SpotifyPlaylist with null owner does not NPE on hashCode or equals`() {
        val json = """
            {
                "id": "p1", "name": "Test", "uri": "spotify:playlist:p1",
                "owner": null, "items": {"total": 5, "href": null}
            }
        """.trimIndent()
        val a = gson.fromJson(json, SpotifyPlaylist::class.java)
        val b = gson.fromJson(json, SpotifyPlaylist::class.java)
        assertNull(a.owner)
        // Field-level equality across distinct instances — exercises the generated equals
        // past the reference-identity short-circuit (a === b is false).
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        a.toString()
    }

    @Test
    fun `SpotifyPlaylist with owner having null id does not NPE`() {
        val jsonNullId = """
            {
                "id": "p2", "name": "Test", "uri": "spotify:playlist:p2",
                "owner": {"id": null, "display_name": "Someone"},
                "items": {"total": 3, "href": null}
            }
        """.trimIndent()
        val a = gson.fromJson(jsonNullId, SpotifyPlaylist::class.java)
        val b = gson.fromJson(jsonNullId, SpotifyPlaylist::class.java)
        val owner = assertNotNull(a.owner)
        assertNull(owner.id)
        // Field-level equality across distinct instances.
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `SpotifyPlaylist absent owner vs null-id owner are not equal`() {
        val jsonAbsent = """
            {
                "id": "p6", "name": "Test", "uri": "spotify:playlist:p6",
                "items": {"total": 1, "href": null}
            }
        """.trimIndent()
        val jsonNullId = """
            {
                "id": "p6", "name": "Test", "uri": "spotify:playlist:p6",
                "owner": {"id": null, "display_name": "Someone"},
                "items": {"total": 1, "href": null}
            }
        """.trimIndent()
        val absent = gson.fromJson(jsonAbsent, SpotifyPlaylist::class.java)
        val nullId = gson.fromJson(jsonNullId, SpotifyPlaylist::class.java)
        // Must not throw, and must not be equal (owner null vs owner non-null).
        assertFalse(absent == nullId)
        absent.hashCode()
        nullId.hashCode()
    }

    @Test
    fun `SpotifyPlaylist with absent owner field gets null from Unsafe alloc`() {
        val json = """
            {"id": "p3", "name": "Test", "uri": "spotify:playlist:p3", "items": {"total": 0}}
        """.trimIndent()
        val playlist = gson.fromJson(json, SpotifyPlaylist::class.java)
        assertNull(playlist.owner)
        playlist.hashCode()
    }

    @Test
    fun `SpotifyPlaylist trackCount reads from items meta`() {
        val json = """
            {"id": "p4", "name": "T", "uri": "u", "items": {"total": 42, "href": null}}
        """.trimIndent()
        val playlist = gson.fromJson(json, SpotifyPlaylist::class.java)
        assertEquals(42, playlist.trackCount)
    }

    @Test
    fun `SpotifyPlaylist trackCount reads from tracks alternate name`() {
        val json = """
            {"id": "p5", "name": "T", "uri": "u", "tracks": {"total": 99, "href": null}}
        """.trimIndent()
        val playlist = gson.fromJson(json, SpotifyPlaylist::class.java)
        assertEquals(99, playlist.trackCount)
    }

    // ── Paged — null slots inside items ──────────────────────────────────────

    @Test
    fun `Paged items filters out null slots`() {
        val json = """
            {"items": [null, {"id":"t1","name":"A","uri":"u1"}, null, {"id":"t2","name":"B","uri":"u2"}], "total": 10}
        """.trimIndent()
        val type = TypeToken.getParameterized(Paged::class.java, SpotifyTrack::class.java).type
        val paged: Paged<SpotifyTrack> = gson.fromJson(json, type)
        assertEquals(2, paged.items.size)
        assertEquals(4, paged.rawCount)
        assertEquals("t1", paged.items[0].id)
        assertEquals("t2", paged.items[1].id)
    }

    @Test
    fun `Paged with absent items key returns empty items and rawCount 0`() {
        val json = """{"total": 5}"""
        val type = TypeToken.getParameterized(Paged::class.java, SpotifyTrack::class.java).type
        val paged: Paged<SpotifyTrack> = gson.fromJson(json, type)
        assertTrue(paged.items.isEmpty())
        assertEquals(0, paged.rawCount)
    }

    @Test
    fun `Paged with empty items returns empty items and rawCount 0`() {
        val json = """{"items": [], "total": 0}"""
        val type = TypeToken.getParameterized(Paged::class.java, SpotifyTrack::class.java).type
        val paged: Paged<SpotifyTrack> = gson.fromJson(json, type)
        assertTrue(paged.items.isEmpty())
        assertEquals(0, paged.rawCount)
    }

    // ── PlaylistTracksResponse ───────────────────────────────────────────────

    @Test
    fun `PlaylistTracksResponse items filters null slots`() {
        val json = """
            {"items": [null, {"added_at":"2024-01-01","track":{"id":"t1","name":"T","uri":"u"}}], "total": 5}
        """.trimIndent()
        val resp = gson.fromJson(json, PlaylistTracksResponse::class.java)
        assertEquals(1, resp.items?.size)
        assertEquals(2, resp.rawCount)
    }

    @Test
    fun `PlaylistTracksResponse with absent items key returns null items`() {
        val json = """{"total": 5}"""
        val resp = gson.fromJson(json, PlaylistTracksResponse::class.java)
        assertNull(resp.items)
        assertEquals(0, resp.rawCount)
    }

    @Test
    fun `PlaylistTracksResponse with empty items returns empty not null`() {
        val json = """{"items": [], "total": 0}"""
        val resp = gson.fromJson(json, PlaylistTracksResponse::class.java)
        assertNotNull(resp.items)
        assertTrue(resp.items!!.isEmpty())
    }

    // ── SavedTracksResponse ──────────────────────────────────────────────────

    @Test
    fun `SavedTracksResponse items filters null slots`() {
        val json = """
            {"items": [null, {"added_at":"2024-01-01","track":{"id":"t","name":"T","uri":"u"}}], "total": 10}
        """.trimIndent()
        val resp = gson.fromJson(json, SavedTracksResponse::class.java)
        assertEquals(1, resp.items?.size)
        assertEquals(2, resp.rawCount)
    }

    @Test
    fun `SavedTracksResponse with absent items key returns null items`() {
        val resp = gson.fromJson("{}", SavedTracksResponse::class.java)
        assertNull(resp.items)
        assertEquals(0, resp.rawCount)
    }

    // ── ShowPage ─────────────────────────────────────────────────────────────

    @Test
    fun `ShowPage items filters null episode slots`() {
        val json = """
            {"items": [null, {"id":"e1","name":"Ep"}], "total": 5}
        """.trimIndent()
        val type = TypeToken.getParameterized(ShowPage::class.java, SpotifyEpisode::class.java).type
        val page: ShowPage<SpotifyEpisode> = gson.fromJson(json, type)
        assertEquals(1, page.items?.size)
        assertEquals(2, page.rawCount)
    }

    @Test
    fun `ShowPage with absent items key returns null items`() {
        val json = """{"total": 10}"""
        val type = TypeToken.getParameterized(ShowPage::class.java, SpotifyEpisode::class.java).type
        val page: ShowPage<SpotifyEpisode> = gson.fromJson(json, type)
        assertNull(page.items)
        assertEquals(0, page.rawCount)
    }

    // ── SavedShowsResponse ───────────────────────────────────────────────────

    @Test
    fun `SavedShowsResponse items filters null slots`() {
        val json = """
            {"items": [null, {"added_at":"2024-01-01","show":{"id":"s1","name":"Show"}}], "total": 5}
        """.trimIndent()
        val resp = gson.fromJson(json, SavedShowsResponse::class.java)
        assertEquals(1, resp.items?.size)
        assertEquals(2, resp.rawCount)
    }

    @Test
    fun `SavedShowsResponse with absent items key returns null items`() {
        val resp = gson.fromJson("{}", SavedShowsResponse::class.java)
        assertNull(resp.items)
    }

    // ── QueueResponse ────────────────────────────────────────────────────────

    @Test
    fun `QueueResponse queue filters null slots`() {
        val json = """
            {"currently_playing": {"id":"c","name":"C","uri":"u"},
             "queue": [null, {"id":"q1","name":"Q","uri":"u2"}, null]}
        """.trimIndent()
        val resp = gson.fromJson(json, QueueResponse::class.java)
        assertEquals(1, resp.queue.size)
        assertEquals("q1", resp.queue[0].id)
    }

    @Test
    fun `QueueResponse with absent queue key returns empty queue`() {
        val json = """{"currently_playing": null}"""
        val resp = gson.fromJson(json, QueueResponse::class.java)
        assertTrue(resp.queue.isEmpty())
    }

    // ── DevicesResponse ──────────────────────────────────────────────────────

    @Test
    fun `DevicesResponse devices filters null slots`() {
        val json = """
            {"devices": [null, {"name":"Phone","type":"Smartphone","is_active":true}]}
        """.trimIndent()
        val resp = gson.fromJson(json, DevicesResponse::class.java)
        assertEquals(1, resp.devices.size)
        assertEquals("Phone", resp.devices[0].name)
    }

    @Test
    fun `DevicesResponse with absent devices key returns empty list`() {
        val resp = gson.fromJson("{}", DevicesResponse::class.java)
        assertTrue(resp.devices.isEmpty())
    }

    // ── UserPlaylistsResponse ────────────────────────────────────────────────

    @Test
    fun `UserPlaylistsResponse items filters null slots`() {
        val json = """
            {"items": [null, {"id":"p","name":"P","uri":"u","items":{"total":1}}], "total": 5}
        """.trimIndent()
        val resp = gson.fromJson(json, UserPlaylistsResponse::class.java)
        assertEquals(1, resp.items.size)
        assertEquals(2, resp.rawCount)
    }

    @Test
    fun `UserPlaylistsResponse with absent items key returns empty items`() {
        val resp = gson.fromJson("{}", UserPlaylistsResponse::class.java)
        assertTrue(resp.items.isEmpty())
        assertEquals(0, resp.rawCount)
    }

    // ── SavedAlbumsResponse ──────────────────────────────────────────────────

    @Test
    fun `SavedAlbumsResponse items filters null slots`() {
        val json = """
            {"items": [null, {"added_at":"2024","album":{"id":"a1","name":"A"}}], "total": 2}
        """.trimIndent()
        val resp = gson.fromJson(json, SavedAlbumsResponse::class.java)
        assertEquals(1, resp.items?.size)
        assertEquals(2, resp.rawCount)
    }

    @Test
    fun `SavedAlbumsResponse with absent items returns null items`() {
        val resp = gson.fromJson("{}", SavedAlbumsResponse::class.java)
        assertNull(resp.items)
    }

    // ── FollowedArtistsPage ──────────────────────────────────────────────────

    @Test
    fun `FollowedArtistsPage items filters null slots`() {
        val json = """
            {"items": [null, {"id":"ar1","name":"Artist"}], "total": 3}
        """.trimIndent()
        val page = gson.fromJson(json, FollowedArtistsPage::class.java)
        assertEquals(1, page.items?.size)
        assertEquals(2, page.rawCount)
    }

    @Test
    fun `FollowedArtistsPage with absent items returns null items`() {
        val page = gson.fromJson("{}", FollowedArtistsPage::class.java)
        assertNull(page.items)
    }

    // ── RecentlyPlayedResponse ───────────────────────────────────────────────

    @Test
    fun `RecentlyPlayedResponse items filters null slots`() {
        val json = """
            {"items": [null, {"track":{"id":"t","name":"T","uri":"u"},"played_at":"2024"}]}
        """.trimIndent()
        val resp = gson.fromJson(json, RecentlyPlayedResponse::class.java)
        assertEquals(1, resp.items?.size)
        assertEquals(2, resp.rawCount)
    }

    @Test
    fun `RecentlyPlayedResponse with absent items returns null items`() {
        val resp = gson.fromJson("{}", RecentlyPlayedResponse::class.java)
        assertNull(resp.items)
    }

    // ── SpotifyTrack — shareUrl, isEpisode, display fallbacks ────────────────

    @Test
    fun `SpotifyTrack shareUrl is null for local file`() {
        val json = """{"id":"t1","name":"T","uri":"spotify:local:t1","is_local":true}"""
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertNull(track.shareUrl)
    }

    @Test
    fun `SpotifyTrack shareUrl is null when id absent from JSON`() {
        // Gson Unsafe alloc: the declared non-null id becomes null at runtime
        val json = """{"name":"T","uri":"spotify:track:x","is_local":false}"""
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertNull(track.shareUrl)
    }

    @Test
    fun `SpotifyTrack shareUrl is present for normal track`() {
        val json = """{"id":"abc123","name":"T","uri":"spotify:track:abc123"}"""
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertEquals("https://open.spotify.com/track/abc123", track.shareUrl)
    }

    @Test
    fun `SpotifyTrack shareUrl uses episode path for episodes`() {
        val json = """{"id":"ep1","name":"E","uri":"spotify:episode:ep1","type":"episode"}"""
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertEquals("https://open.spotify.com/episode/ep1", track.shareUrl)
    }

    @Test
    fun `SpotifyTrack isEpisode true for type episode`() {
        val json = """{"id":"e","name":"E","uri":"spotify:episode:e","type":"episode"}"""
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertTrue(track.isEpisode)
    }

    @Test
    fun `SpotifyTrack isEpisode true for episode uri even without type field`() {
        val json = """{"id":"e","name":"E","uri":"spotify:episode:e"}"""
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertTrue(track.isEpisode)
    }

    @Test
    fun `SpotifyTrack isEpisode false for track`() {
        val json = """{"id":"t","name":"T","uri":"spotify:track:t","type":"track"}"""
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertFalse(track.isEpisode)
    }

    @Test
    fun `SpotifyTrack artUrl falls back to show images for episode`() {
        val json = """
            {"id":"e","name":"E","uri":"spotify:episode:e","type":"episode",
             "show":{"id":"s","name":"S","images":[{"url":"show_art.jpg"}]}}
        """.trimIndent()
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertEquals("show_art.jpg", track.artUrl)
    }

    @Test
    fun `SpotifyTrack primaryArtist falls back to show name for episode`() {
        val json = """
            {"id":"e","name":"E","uri":"spotify:episode:e","type":"episode",
             "show":{"id":"s","name":"My Podcast"}}
        """.trimIndent()
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertEquals("My Podcast", track.primaryArtist)
    }

    @Test
    fun `SpotifyTrack primaryArtist is Unknown when no artists and no show`() {
        val json = """{"id":"t","name":"T","uri":"u"}"""
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertEquals("Unknown", track.primaryArtist)
    }

    @Test
    fun `SpotifyTrack allArtists joins with dot separator`() {
        val json = """
            {"id":"t","name":"T","uri":"u",
             "artists":[{"id":"a1","name":"Alice"},{"id":"a2","name":"Bob"}]}
        """.trimIndent()
        val track = gson.fromJson(json, SpotifyTrack::class.java)
        assertEquals("Alice · Bob", track.allArtists)
    }

    // ── PlaylistTrack — resolvedTrack prefers track over item ────────────────

    @Test
    fun `PlaylistTrack resolvedTrack returns track when both present`() {
        val json = """
            {"added_at":"2024",
             "track":{"id":"t1","name":"Track","uri":"u1"},
             "item":{"id":"t2","name":"Item","uri":"u2"}}
        """.trimIndent()
        val pt = gson.fromJson(json, PlaylistTrack::class.java)
        assertEquals("t1", pt.resolvedTrack?.id)
    }

    @Test
    fun `PlaylistTrack resolvedTrack falls back to item when track is null`() {
        val json = """
            {"added_at":"2024",
             "track": null,
             "item":{"id":"t2","name":"Item","uri":"u2"}}
        """.trimIndent()
        val pt = gson.fromJson(json, PlaylistTrack::class.java)
        assertEquals("t2", pt.resolvedTrack?.id)
    }

    @Test
    fun `PlaylistTrack resolvedTrack is null when both absent`() {
        val json = """{"added_at":"2024"}"""
        val pt = gson.fromJson(json, PlaylistTrack::class.java)
        assertNull(pt.resolvedTrack)
    }
}
