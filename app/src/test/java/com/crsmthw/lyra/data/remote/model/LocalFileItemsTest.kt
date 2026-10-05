package com.crsmthw.lyra.data.remote.model

import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Local files as Spotify's concepts page shows them (`"id": null`, `is_local: true`, a
 * `spotify:local:` uri) — parsed with a plain `Gson()`, never Kotlin-constructed, so the `Unsafe`
 * allocation hazards are the ones under test (audit 2026-10-04, decision 2).
 */
class LocalFileItemsTest {

    private val gson = Gson()

    /** The concepts-page local track, with the wrapper's `is_local` copied onto the item. */
    private val localTrackJson = """
        {"album":{"album_type":null,"artists":[],"id":null,"images":[],"name":"","type":"album","uri":null},
         "artists":[{"id":null,"name":"Local Artist","type":"artist","uri":null}],
         "duration_ms":127000,"explicit":false,"id":null,"is_local":true,"name":"Local Song",
         "preview_url":null,"track_number":0,"type":"track",
         "uri":"spotify:local:Local+Artist:Local+Album:Local+Song:127"}
    """.trimIndent()

    private fun local(json: String = localTrackJson) = gson.fromJson(json, SpotifyTrack::class.java)

    @Test
    fun `a local file is a local item, with no page, no like target and no artist page`() {
        val t = local()
        assertTrue(t.isLocalItem)
        assertNull(t.shareUrl)
        assertNull(t.likeTargetId)
        assertNull(t.primaryArtistId)
        assertEquals("Local Artist", t.allArtists)
    }

    @Test
    fun `the uri prefix alone makes a local item - the flag is not relied on`() {
        val t = local(localTrackJson.replace("\"is_local\":true", "\"is_local\":false"))
        assertFalse(t.isLocal)
        assertTrue(t.isLocalItem)
        assertNull(t.shareUrl)
        assertNull(t.likeTargetId)
    }

    @Test
    fun `a local file gets no song menu target - the sheet stays shut instead of crashing`() {
        assertNull(local().toTrackActionTarget())
    }

    @Test
    fun `a catalog track gets a target whose id is read off the uri`() {
        val t = gson.fromJson("""{"id":"abc","name":"Song","uri":"spotify:track:abc","artists":[{"id":"ar1","name":"A"}]}""", SpotifyTrack::class.java)
        val target = assertNotNull(t.toTrackActionTarget())
        assertEquals("abc", target.id)
        assertEquals("abc", t.likeTargetId)
        assertEquals("ar1", t.primaryArtistId)
    }

    @Test
    fun `an episode has no like target`() {
        val t = gson.fromJson("""{"id":"ep1","name":"Ep","uri":"spotify:episode:ep1","type":"episode"}""", SpotifyTrack::class.java)
        assertNull(t.likeTargetId)
    }

    @Test
    fun `trackIdOf reads an id only behind the track prefix`() {
        assertEquals("abc", trackIdOf("spotify:track:abc"))
        assertNull(trackIdOf("spotify:local:A:B:C:127"))
        assertNull(trackIdOf("spotify:episode:x"))
        assertNull(trackIdOf("spotify:user:u:collection"))
        assertNull(trackIdOf("spotify:track:"))
        assertNull(trackIdOf(null))
    }

    @Test
    fun `nullIfBlank reads Gson strings honestly`() {
        assertNull((null as String?).nullIfBlank())
        assertNull("".nullIfBlank())
        assertNull("  ".nullIfBlank())
        assertEquals("x", "x".nullIfBlank())
    }

    @Test
    fun `allArtists falls back on an EMPTY artists list, not only on null`() {
        val episode = gson.fromJson(
            """{"id":"e1","name":"Ep","uri":"spotify:episode:e1","type":"episode","artists":[],"show":{"name":"Pod"}}""",
            SpotifyTrack::class.java,
        )
        assertEquals("Pod", episode.allArtists)
        val albumTrack = gson.fromJson("""{"id":"t1","name":"T","uri":"spotify:track:t1","artists":[]}""", AlbumTrack::class.java)
        assertEquals("Unknown", albumTrack.allArtists)
    }

    @Test
    fun `a blank artist id is no artist page`() {
        val t = gson.fromJson("""{"id":"abc","name":"S","uri":"spotify:track:abc","artists":[{"id":"","name":"A"}]}""", SpotifyTrack::class.java)
        assertNull(t.primaryArtistId)
    }
}
