package com.crsmthw.lyra.data.player

import com.crsmthw.lyra.data.remote.LocalPlayerSnapshot
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The App Remote PlayerState as a mirror source (2026-10-04 evening). */
class SdkMirrorTest {

    private val target = "spotify:track:target"
    private val other  = "spotify:track:other"
    private val gson   = Gson()

    private fun snap(
        uri     : String? = target,
        paused  : Boolean = false,
        pos     : Long    = 0L,
        at      : Long    = 1_000L,
        duration: Long    = 200_000L,
        shuffle : Boolean? = null,
        repeat  : Int?    = null,
        episode : Boolean = false,
        artists : List<Pair<String?, String?>> = listOf("spotify:artist:a1" to "Artist One", "spotify:artist:a2" to "Artist Two"),
    ) = LocalPlayerSnapshot(
        trackUri    = uri,
        name        = "Song",
        artists     = artists,
        albumUri    = "spotify:album:al1",
        albumName   = "Album",
        imageUri    = "spotify:image:abc",
        isEpisode   = episode,
        isPaused    = paused,
        positionMs  = pos,
        durationMs  = duration,
        isShuffling = shuffle,
        repeatMode  = repeat,
        atElapsedMs = at,
    )

    private fun obs(status: Int = 200, item: String? = null, playing: Boolean = false, device: String? = "phone") =
        PollObservation(status, item, null, playing, device)

    private fun track(json: String): SpotifyTrack = gson.fromJson(json, SpotifyTrack::class.java)

    // ── sdkReportsAudible ───────────────────────────────────────────────────

    @Test
    fun `two reports after the dispatch, advancing, on the target are audible`() {
        assertTrue(sdkReportsAudible(snap(pos = 100, at = 1_000), snap(pos = 700, at = 1_600), target, 900))
    }

    @Test
    fun `a report at or before the dispatch never counts`() {
        assertFalse(sdkReportsAudible(snap(pos = 100, at = 900), snap(pos = 900, at = 1_700), target, 900))
        assertFalse(sdkReportsAudible(snap(pos = 100, at = 800), snap(pos = 900, at = 1_700), target, 900))
    }

    @Test
    fun `a paused report never counts`() {
        assertFalse(sdkReportsAudible(snap(pos = 100, at = 1_000, paused = true), snap(pos = 900, at = 1_800), target, 900))
        assertFalse(sdkReportsAudible(snap(pos = 100, at = 1_000), snap(pos = 900, at = 1_800, paused = true), target, 900))
    }

    @Test
    fun `a stalled position under isPaused false is not audible`() {
        assertFalse(sdkReportsAudible(snap(pos = 100, at = 1_000), snap(pos = 400, at = 3_000), target, 900))
    }

    @Test
    fun `reports closer than 500 ms are not enough`() {
        assertFalse(sdkReportsAudible(snap(pos = 0, at = 1_000), snap(pos = 600, at = 1_400), target, 900))
    }

    @Test
    fun `another uri is not the target`() {
        assertFalse(sdkReportsAudible(snap(uri = other, pos = 0, at = 1_000), snap(uri = other, pos = 900, at = 1_900), target, 900))
        assertFalse(sdkReportsAudible(snap(pos = 0, at = 1_000), snap(uri = other, pos = 900, at = 1_900), null, 900))
    }

    @Test
    fun `a null target takes any one uri, a null dispatch any time`() {
        assertTrue(sdkReportsAudible(snap(uri = other, pos = 0, at = 1_000), snap(uri = other, pos = 900, at = 1_900), null, null))
    }

    @Test
    fun `no track is never audible`() {
        assertFalse(sdkReportsAudible(snap(uri = null, pos = 0, at = 1_000), snap(uri = null, pos = 900, at = 1_900), null, null))
    }

    // ── SdkAudibleWatch ─────────────────────────────────────────────────────

    @Test
    fun `the watch confirms on the second advancing report`() {
        val w = SdkAudibleWatch(target, 900)
        assertFalse(w.offer(snap(pos = 0, at = 1_000)))
        assertTrue(w.offer(snap(pos = 1_000, at = 2_000)))
    }

    @Test
    fun `the watch ignores reports from before the dispatch and nulls`() {
        val w = SdkAudibleWatch(target, 900)
        assertFalse(w.offer(null))
        assertFalse(w.offer(snap(pos = 5_000, at = 500)))
        assertFalse(w.offer(snap(pos = 0, at = 1_000)))
        assertTrue(w.offer(snap(pos = 800, at = 1_800)))
    }

    @Test
    fun `a pause between two reports resets the watch`() {
        val w = SdkAudibleWatch(target, 900)
        assertFalse(w.offer(snap(pos = 0, at = 1_000)))
        assertFalse(w.offer(snap(pos = 600, at = 1_600, paused = true)))
        assertFalse(w.offer(snap(pos = 600, at = 2_600)))
        assertTrue(w.offer(snap(pos = 1_200, at = 3_200)))
    }

    @Test
    fun `a position that went back re-bases the watch`() {
        val w = SdkAudibleWatch(target, 900)
        assertFalse(w.offer(snap(pos = 50_000, at = 1_000)))
        assertFalse(w.offer(snap(pos = 0, at = 1_200)))        // a seek / restart
        assertFalse(w.offer(snap(pos = 300, at = 1_500)))      // only 300 ms on from the new base
        assertTrue(w.offer(snap(pos = 700, at = 1_900)))
    }

    @Test
    fun `the same report twice is not an advance`() {
        val w = SdkAudibleWatch(target, 900)
        val s = snap(pos = 0, at = 1_000)
        assertFalse(w.offer(s))
        assertFalse(w.offer(s))
    }

    @Test
    fun `the watch with a target never confirms another song`() {
        val w = SdkAudibleWatch(target, 900)
        assertFalse(w.offer(snap(uri = other, pos = 0, at = 1_000)))
        assertFalse(w.offer(snap(uri = other, pos = 5_000, at = 6_000)))
    }

    @Test
    fun `a forward seek is not playback, and re-bases the watch`() {
        // The wake path: SDK play at 0, then seekTo(90 s) — the second read lands past the seek.
        assertFalse(sdkReportsAudible(snap(pos = 1_200, at = 1_000), snap(pos = 90_000, at = 2_000), target, 900))
        val w = SdkAudibleWatch(target, 900)
        assertFalse(w.offer(snap(pos = 1_200, at = 1_000)))
        assertFalse(w.offer(snap(pos = 90_000, at = 2_000)))   // the seek: re-based, not confirmed
        assertFalse(w.offer(snap(pos = 90_200, at = 2_300)))   // only 300 ms on from the new base
        assertTrue(w.offer(snap(pos = 91_000, at = 3_000)))    // playback from the seek point
    }

    @Test
    fun `normal and double-speed playback still confirm`() {
        assertTrue(sdkReportsAudible(snap(pos = 0, at = 1_000), snap(pos = 2_000, at = 3_000), target, 900))
        assertTrue(sdkReportsAudible(snap(pos = 0, at = 1_000), snap(pos = 4_000, at = 3_000), target, 900))
        assertFalse(sdkPositionJumped(snap(pos = 0, at = 1_000), snap(pos = 6_500, at = 3_000)))
        assertTrue(sdkPositionJumped(snap(pos = 0, at = 1_000), snap(pos = 6_501, at = 3_000)))
    }

    // ── webViewBlind ────────────────────────────────────────────────────────

    @Test
    fun `a 204 is blind`() {
        assertTrue(webViewBlind(obs(status = 204, device = null)) { false })
    }

    @Test
    fun `an item-less 200 is blind only on this phone`() {
        assertTrue(webViewBlind(obs()) { it == "phone" })
        assertFalse(webViewBlind(obs(device = "speaker")) { it == "phone" })
        assertFalse(webViewBlind(obs(device = null)) { true })
    }

    @Test
    fun `a 200 with an item, or playing, is not blind`() {
        assertFalse(webViewBlind(obs(item = target, playing = true)) { true })
        assertFalse(webViewBlind(obs(item = target, playing = false)) { true })
        assertFalse(webViewBlind(obs(item = null, playing = true)) { true })
    }

    @Test
    fun `no observation is not blind`() {
        assertFalse(webViewBlind(null) { true })
    }

    // ── freshness / repeat ──────────────────────────────────────────────────

    @Test
    fun `a snapshot is fresh for ten seconds`() {
        assertTrue(sdkSnapshotFresh(snap(at = 1_000), 11_000))
        assertFalse(sdkSnapshotFresh(snap(at = 1_000), 11_001))
        assertFalse(sdkSnapshotFresh(null, 1_000))
        assertFalse(sdkSnapshotFresh(snap(at = 5_000), 1_000))
    }

    @Test
    fun `the SDK repeat modes map to the mirror's`() {
        assertEquals("off", sdkRepeatState(0))
        assertEquals("context", sdkRepeatState(1))
        assertEquals("track", sdkRepeatState(2))
        assertNull(sdkRepeatState(null))
        assertNull(sdkRepeatState(7))
    }

    // ── mirrorFromSdk ───────────────────────────────────────────────────────

    @Test
    fun `the same uri keeps the previous track object and its art`() {
        val prev = track("""{"id":"target","name":"Song","uri":"$target","duration_ms":200000,
            "album":{"id":"al1","name":"Album","images":[{"url":"https://img/640","height":640,"width":640}]}}""")
        val m = mirrorFromSdk(snap(pos = 30_000), PlayerState(currentTrack = prev, contextUri = "spotify:playlist:p", hasContext = true),
                              audible = true)
        assertSame(prev, m.currentTrack)
        assertEquals("https://img/640", m.currentTrack?.artUrl)
        assertEquals(30_000L, m.progressMs)
        assertTrue(m.isPlaying)
        assertFalse(m.hasContext)
        assertEquals("spotify:playlist:p", m.contextUri)
    }

    @Test
    fun `a new uri gets a display-only track with NO art, never the previous art`() {
        val prev = track("""{"id":"other","name":"Old","uri":"$other",
            "album":{"id":"x","name":"Old album","images":[{"url":"https://img/old","height":640,"width":640}]}}""")
        val m = mirrorFromSdk(snap(), PlayerState(currentTrack = prev))
        val t = m.currentTrack!!
        assertEquals(target, t.uri)
        assertEquals("target", t.id)
        assertEquals("Song", t.name)
        assertEquals("", t.artUrl)
        assertEquals("al1", t.album?.id)
        assertEquals("Album", t.album?.name)
        assertEquals("Artist One · Artist Two", t.allArtists)
        assertEquals("a1", t.primaryArtistId)
        assertFalse(t.isLocal)
        assertFalse(t.isEpisode)
        assertEquals(200_000L, t.durationMs)
    }

    @Test
    fun `a known catalog copy of the uri is used`() {
        val known = track("""{"id":"target","name":"Song","uri":"$target",
            "album":{"id":"al1","name":"Album","images":[{"url":"https://img/k","height":640,"width":640}]}}""")
        val m = mirrorFromSdk(snap(), PlayerState(), known = known)
        assertSame(known, m.currentTrack)
    }

    @Test
    fun `a known copy of ANOTHER uri is ignored`() {
        val known = track("""{"id":"other","name":"X","uri":"$other"}""")
        val m = mirrorFromSdk(snap(), PlayerState(), known = known)
        assertEquals(target, m.currentTrack?.uri)
    }

    @Test
    fun `paused maps to not playing, and the position does not run`() {
        val m = mirrorFromSdk(snap(paused = true, pos = 10_000, at = 1_000), PlayerState(isPlaying = true), nowElapsedMs = 5_000)
        assertFalse(m.isPlaying)
        assertEquals(10_000L, m.progressMs)
    }

    @Test
    fun `playing runs the position forward to now, within the duration`() {
        assertEquals(14_000L, mirrorFromSdk(snap(pos = 10_000, at = 1_000), PlayerState(), nowElapsedMs = 5_000, audible = true).progressMs)
        assertEquals(200_000L, mirrorFromSdk(snap(pos = 199_000, at = 1_000), PlayerState(), nowElapsedMs = 9_000, audible = true).progressMs)
    }

    // ── never playing without audio (review 2026-10-04 evening) ────────────

    @Test
    fun `a single non-paused report never turns the mirror playing, and its position does not run`() {
        val m = mirrorFromSdk(snap(pos = 10_000, at = 1_000), PlayerState(isPlaying = false), nowElapsedMs = 5_000)
        assertFalse(m.isPlaying)
        assertEquals(10_000L, m.progressMs)
    }

    @Test
    fun `a mirror that was already playing stays playing while not paused`() {
        val m = mirrorFromSdk(snap(pos = 10_000, at = 1_000), PlayerState(isPlaying = true), nowElapsedMs = 2_000)
        assertTrue(m.isPlaying)
        assertEquals(11_000L, m.progressMs)
        assertFalse(mirrorFromSdk(snap(paused = true), PlayerState(isPlaying = true), audible = true).isPlaying)
    }

    @Test
    fun `the gate confirms an advancing stream on the second report`() {
        val gate = SdkPlayingGate()
        assertFalse(gate.offer(snap(pos = 0, at = 1_000)))
        assertTrue(gate.offer(snap(pos = 3_000, at = 4_000)))
        // Confirmed for the uri: a seek on the same uri keeps it.
        assertTrue(gate.offer(snap(pos = 90_000, at = 4_200)))
    }

    @Test
    fun `a stalled isPaused false stream never confirms, through the gate and the mirror`() {
        val gate = SdkPlayingGate()
        var state = PlayerState(isPlaying = false)
        for (i in 0 until 10) {
            val s = snap(pos = 12_000, at = 1_000L + i * 3_000L)
            state = mirrorFromSdk(s, state, nowElapsedMs = s.atElapsedMs + 500, audible = gate.offer(s))
            assertFalse(state.isPlaying, "report $i")
            assertEquals(12_000L, state.progressMs)
        }
    }

    @Test
    fun `a pause, a null track or another uri resets the gate`() {
        val gate = SdkPlayingGate()
        gate.offer(snap(pos = 0, at = 1_000))
        assertTrue(gate.offer(snap(pos = 1_000, at = 2_000)))
        assertFalse(gate.offer(snap(paused = true, pos = 1_000, at = 2_500)))
        assertFalse(gate.offer(snap(pos = 1_000, at = 3_000)))
        assertTrue(gate.offer(snap(pos = 2_000, at = 4_000)))
        assertFalse(gate.offer(snap(uri = other, pos = 0, at = 4_500)))
        assertFalse(gate.offer(snap(uri = null, pos = 0, at = 5_000)))
        assertFalse(gate.offer(snap(uri = other, pos = 0, at = 5_500)))
        assertTrue(gate.offer(snap(uri = other, pos = 1_000, at = 6_500)))
        gate.reset()
        assertFalse(gate.offer(snap(uri = other, pos = 2_000, at = 7_500)))
    }

    @Test
    fun `a forward jump right after the first report is a seek, not audio`() {
        val gate = SdkPlayingGate()
        assertFalse(gate.offer(snap(pos = 0, at = 1_000)))
        assertFalse(gate.offer(snap(pos = 60_000, at = 1_600)))
        assertTrue(gate.offer(snap(pos = 61_000, at = 2_600)))
    }

    @Test
    fun `no track in the snapshot keeps the previous track, not playing`() {
        val prev = track("""{"id":"other","name":"Old","uri":"$other"}""")
        val m = mirrorFromSdk(snap(uri = null), PlayerState(isPlaying = true, currentTrack = prev, progressMs = 42))
        assertSame(prev, m.currentTrack)
        assertFalse(m.isPlaying)
        assertEquals(42L, m.progressMs)
    }

    @Test
    fun `shuffle and repeat come from the snapshot when present`() {
        val prev = PlayerState(shuffleEnabled = false, repeatState = "off")
        val m = mirrorFromSdk(snap(shuffle = true, repeat = 2), prev)
        assertTrue(m.shuffleEnabled)
        assertEquals("track", m.repeatState)
        val kept = mirrorFromSdk(snap(), PlayerState(shuffleEnabled = true, repeatState = "context"))
        assertTrue(kept.shuffleEnabled)
        assertEquals("context", kept.repeatState)
    }

    @Test
    fun `a zero SDK duration falls back to the track's`() {
        val prev = track("""{"id":"target","name":"Song","uri":"$target","duration_ms":123000}""")
        assertEquals(123_000L, mirrorFromSdk(snap(duration = 0), PlayerState(currentTrack = prev)).durationMs)
    }

    @Test
    fun `an episode carries its show and no artists`() {
        val ep = "spotify:episode:e1"
        val m = mirrorFromSdk(
            snap(uri = ep, episode = true, artists = listOf("spotify:show:s1" to "The Show")),
            PlayerState(),
        )
        val t = m.currentTrack!!
        assertTrue(t.isEpisode)
        assertTrue(t.artists.isNullOrEmpty())
        assertNull(t.album)
        assertEquals("The Show", t.show?.name)
        assertEquals("s1", t.show?.id)
        assertEquals("The Show", t.primaryArtist)
    }

    @Test
    fun `blank artist names are dropped`() {
        val m = mirrorFromSdk(snap(artists = listOf(null to null, "spotify:artist:a" to " ", "spotify:artist:b" to "B")), PlayerState())
        assertEquals("B", m.currentTrack?.allArtists)
    }

    @Test
    fun `the device and sleep timer survive the mirror`() {
        val prev = PlayerState(sleepTimerMinutes = 5, sleepTimerTotalMinutes = 10)
        val m = mirrorFromSdk(snap(), prev)
        assertEquals(5, m.sleepTimerMinutes)
        assertEquals(10, m.sleepTimerTotalMinutes)
    }

    // ── sdkSkipAllowed (next / previous on the SDK source) ──────────────────

    @Test
    fun `a playlist context allows the local app's own skip`() {
        assertTrue(sdkSkipAllowed("spotify:playlist:37i9dQZF1DX", loneUriFlag = false))
    }

    @Test
    fun `album, artist, show, collection and station contexts allow it`() {
        listOf(
            "spotify:album:al1",
            "spotify:artist:a1",
            "spotify:show:s1",
            "spotify:user:1230128430:collection",
            "spotify:station:track:t1",
        ).forEach { assertTrue(sdkSkipAllowed(it, loneUriFlag = false), it) }
    }

    @Test
    fun `no context reported is a lone uri`() {
        assertFalse(sdkSkipAllowed(null, loneUriFlag = false))
    }

    @Test
    fun `a blank context uri is a lone uri`() {
        assertFalse(sdkSkipAllowed("", loneUriFlag = false))
        assertFalse(sdkSkipAllowed("   ", loneUriFlag = false))
    }

    @Test
    fun `a single track or episode as the context is a lone uri`() {
        assertFalse(sdkSkipAllowed("spotify:track:6hHc7Pks7wtBIW8Z6A0iFq", loneUriFlag = false))
        assertFalse(sdkSkipAllowed("spotify:episode:e1", loneUriFlag = false))
    }

    @Test
    fun `the lone-uri flag wins over a real context the events have not replaced yet`() {
        assertFalse(sdkSkipAllowed("spotify:playlist:p1", loneUriFlag = true))
        assertFalse(sdkSkipAllowed(null, loneUriFlag = true))
    }

    // ── connectDropoutNoteShown (the Queue screen's line) ───────────────────

    @Test
    fun `the dropout line shows on the SDK source while the local app plays`() {
        assertTrue(connectDropoutNoteShown(MirrorSource.SDK, snap(paused = false)))
    }

    @Test
    fun `no dropout line while the local app is paused - Connect merely idle`() {
        assertFalse(connectDropoutNoteShown(MirrorSource.SDK, snap(paused = true)))
    }

    @Test
    fun `no dropout line on the Web API source, with no local state, or with no item`() {
        assertFalse(connectDropoutNoteShown(MirrorSource.WEB_API, snap(paused = false)))
        assertFalse(connectDropoutNoteShown(MirrorSource.SDK, null))
        assertFalse(connectDropoutNoteShown(MirrorSource.SDK, snap(uri = null, paused = false)))
    }

    @Test
    fun `the latch keeps the line through a pause on the SDK source only`() {
        assertTrue(connectDropoutNoteShown(MirrorSource.SDK, snap(paused = true), latched = true))
        assertTrue(connectDropoutNoteShown(MirrorSource.SDK, null, latched = true))
        assertFalse(connectDropoutNoteShown(MirrorSource.WEB_API, snap(paused = false), latched = true))
    }

    // ── displayTrackFrom on a LOCAL file (audit 2026-10-04 W11) ─────────────

    @Test
    fun `a local uri builds a local item with no addressable ids`() {
        val uri = "spotify:local:A:B:T:215"
        val t = displayTrackFrom(
            snap(uri = uri, artists = listOf(null to "Local Artist")).copy(albumUri = null),
            uri,
        )
        assertTrue(t.isLocal)
        assertTrue(t.isLocalItem)
        assertNull(t.shareUrl)
        assertNull(t.likeTargetId)
        assertNull(t.albumContextUri())
        assertNull(t.primaryArtistId)
        assertEquals("Local Artist", t.allArtists)
    }

    @Test
    fun `ids are read only behind their prefix`() {
        val t = displayTrackFrom(snap(uri = "spotify:track:t1"), "spotify:track:t1")
        assertEquals("t1", t.id)
        assertEquals("al1", t.album?.id)
        assertEquals("a1", t.primaryArtistId)
        assertFalse(t.isLocalItem)
    }
}
