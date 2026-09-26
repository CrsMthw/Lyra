package com.crsmthw.lyra.ui.cassette

import com.crsmthw.lyra.data.remote.model.SpotifyAlbumFull
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cassette overlay's pure rules. Models are PARSED from JSON with a plain `Gson()` (the
 * project rule): the null hazards the mapping guards against only exist through deserialization.
 */
class CassetteRulesTest {

    private val gson = Gson()
    private fun track(json: String): SpotifyTrack = gson.fromJson(json, SpotifyTrack::class.java)
    private fun album(json: String): SpotifyAlbumFull = gson.fromJson(json, SpotifyAlbumFull::class.java)

    // ── Eligibility ──────────────────────────────────────────────────────────

    private val on = CassetteSettings(enabled = true)

    private fun eligible(
        settings: CassetteSettings = on,
        isPlaying: Boolean = true,
        waking: Boolean = false,
        visualizerEnabled: Boolean = false,
        lyricsShowing: Boolean = false,
        docked: Boolean = false,
        overlayOpen: Boolean = false,
        hasTrack: Boolean = true,
        windowFocused: Boolean = true,
        touchExploring: Boolean = false,
    ) = cassetteEligible(settings, isPlaying, waking, visualizerEnabled, lyricsShowing, docked, overlayOpen,
        hasTrack, windowFocused, touchExploring)

    @Test fun `eligible when every condition holds`() = assertTrue(eligible())
    @Test fun `setting off is never eligible`() = assertFalse(eligible(settings = CassetteSettings()))
    @Test fun `paused music is not eligible`() = assertFalse(eligible(isPlaying = false))
    @Test fun `the wake spinner is not eligible even though the state reads playing`() =
        assertFalse(eligible(isPlaying = true, waking = true))
    @Test fun `eligible once the spinner clears with the music playing`() =
        assertTrue(eligible(isPlaying = true, waking = false))
    @Test fun `the visualizer suppresses it`() = assertFalse(eligible(visualizerEnabled = true))
    @Test fun `showing lyrics suppresses it`() = assertFalse(eligible(lyricsShowing = true))
    @Test fun `never on the docked pane`() = assertFalse(eligible(docked = true))
    @Test fun `an open menu or sheet suppresses it`() = assertFalse(eligible(overlayOpen = true))
    @Test fun `nothing playing is not eligible`() = assertFalse(eligible(hasTrack = false))
    @Test fun `an unfocused window (split-screen, the other app in use) is not eligible`() =
        assertFalse(eligible(windowFocused = false))
    @Test fun `never while TalkBack touch exploration is on`() = assertFalse(eligible(touchExploring = true))
    @Test fun `touch exploration wins even when everything else holds`() =
        assertFalse(eligible(settings = on.copy(keepScreenOn = true), windowFocused = true, touchExploring = true))

    @Test
    fun `the other settings do not affect eligibility`() {
        assertTrue(eligible(settings = on.copy(keepScreenOn = true, dimAfterDelay = true, showExitHint = false,
            colorSource = CassetteColorSource.CUSTOM)))
    }

    // ── Label mapping ────────────────────────────────────────────────────────

    private val trackJson = """
        {
          "id": "t1", "name": "Anti-Hero", "uri": "spotify:track:t1", "type": "track",
          "artists": [{"id": "a1", "name": "Taylor Swift"}, {"id": "a2", "name": "Bleachers"}],
          "album": {"id": "al1", "name": "Midnights", "release_date": "2022-10-21"}
        }
    """.trimIndent()

    @Test
    fun `a track maps title, all artists, album and year`() {
        val meta = CassetteAlbumMeta(copyright = "© 2022 Taylor Swift", label = "Republic Records")
        val l = cassetteLabelFor(track(trackJson), meta)
        assertEquals("Anti-Hero", l.title)
        assertEquals("Taylor Swift · Bleachers", l.artist)
        assertEquals("Midnights", l.album)
        assertEquals("2022", l.year)
        assertEquals("© 2022 Taylor Swift", l.copyright)
        assertEquals("Republic Records", l.recordLabel)
    }

    @Test
    fun `no meta leaves the fine print empty`() {
        val l = cassetteLabelFor(track(trackJson), null)
        assertNull(l.copyright)
        assertNull(l.recordLabel)
    }

    @Test
    fun `an episode puts the show in the artist slot and has no album or year`() {
        val json = """
            {
              "id": "e1", "name": "Episode 12", "uri": "spotify:episode:e1", "type": "episode",
              "show": {"id": "s1", "name": "The Daily"}
            }
        """.trimIndent()
        val l = cassetteLabelFor(track(json), null)
        assertEquals("Episode 12", l.title)
        assertEquals("The Daily", l.artist)
        assertNull(l.album)
        assertNull(l.year)
    }

    @Test
    fun `an episode without a show has an empty artist line`() {
        val l = cassetteLabelFor(track("""{"id": "e2", "name": "Ep", "uri": "spotify:episode:e2"}"""), null)
        assertEquals("", l.artist)
    }

    @Test
    fun `a null album gives no album and no year`() {
        val json = """{"id": "t2", "name": "Song", "uri": "spotify:track:t2", "artists": [{"id": "a", "name": "X"}], "album": null}"""
        val l = cassetteLabelFor(track(json), null)
        assertNull(l.album)
        assertNull(l.year)
        assertEquals("X", l.artist)
    }

    @Test
    fun `the year prints only as four digits`() {
        fun yearOf(date: String?): String? {
            val d = if (date == null) "null" else "\"$date\""
            val json = """{"id": "t", "name": "S", "uri": "spotify:track:t", "album": {"id": "al", "name": "A", "release_date": $d}}"""
            return cassetteLabelFor(track(json), null).year
        }
        assertEquals("2019", yearOf("2019-05-01"))
        assertEquals("1987", yearOf("1987"))
        assertNull(yearOf(""))
        assertNull(yearOf("19"))
        assertNull(yearOf(null))
        assertNull(yearOf("0000-00-00".replace("0", "x")))
    }

    @Test
    fun `a null track name becomes an empty title instead of crashing`() {
        val l = cassetteLabelFor(track("""{"id": "t", "name": null, "uri": "spotify:track:t"}"""), null)
        assertEquals("", l.title)
    }

    @Test
    fun `a blank album name is no album`() {
        val json = """{"id": "t", "name": "S", "uri": "spotify:track:t", "album": {"id": "al", "name": "  "}}"""
        assertNull(cassetteLabelFor(track(json), null).album)
    }

    // ── Album lookup id ──────────────────────────────────────────────────────

    @Test
    fun `album id is the track's album id`() = assertEquals("al1", cassetteAlbumIdFor(track(trackJson)))

    @Test
    fun `episodes, local files and id-less albums never look up`() {
        assertNull(cassetteAlbumIdFor(track("""{"id": "e", "name": "E", "uri": "spotify:episode:e"}""")))
        assertNull(cassetteAlbumIdFor(track(
            """{"id": null, "name": "L", "uri": "spotify:local:x", "is_local": true, "album": {"id": "al", "name": "A"}}""")))
        assertNull(cassetteAlbumIdFor(track(
            """{"id": null, "name": "L", "uri": "spotify:local:x", "is_local": true, "album": {"id": null, "name": "A"}}""")))
        assertNull(cassetteAlbumIdFor(track("""{"id": "t", "name": "S", "uri": "spotify:track:t", "album": null}""")))
        assertNull(cassetteAlbumIdFor(track("""{"id": "t", "name": "S", "uri": "spotify:track:t", "album": {"id": "", "name": "A"}}""")))
    }

    // ── Album meta ───────────────────────────────────────────────────────────

    @Test
    fun `the C line wins over P and null slots are skipped`() {
        val a = album("""
            {"id": "al", "name": "A", "label": "Big Machine",
             "copyrights": [null, {"type": "P", "text": "℗ 2019 Big Machine"}, {"type": "C", "text": "© 2019 Big Machine"}]}
        """.trimIndent())
        val m = cassetteAlbumMetaFrom(a)
        assertEquals("© 2019 Big Machine", m.copyright)
        assertEquals("Big Machine", m.label)
    }

    @Test
    fun `without a C line the first line is used`() {
        val a = album("""{"id": "al", "name": "A", "copyrights": [{"type": "P", "text": "℗ 2001 X"}]}""")
        assertEquals("℗ 2001 X", cassetteAlbumMetaFrom(a).copyright)
    }

    @Test
    fun `blank copyright and blank label are null`() {
        val a = album("""{"id": "al", "name": "A", "label": "  ", "copyrights": [{"type": "C", "text": " "}]}""")
        val m = cassetteAlbumMetaFrom(a)
        assertNull(m.copyright)
        assertNull(m.label)
    }

    @Test
    fun `a blank C line falls back to the first non-blank entry`() {
        val a = album("""{"id": "al", "name": "A", "copyrights": [{"type": "P", "text": "℗ X"}, {"type": "C", "text": ""}]}""")
        assertEquals("℗ X", cassetteAlbumMetaFrom(a).copyright)
    }

    @Test
    fun `null text and type inside an entry, and missing copyrights, do not crash`() {
        assertNull(cassetteAlbumMetaFrom(album("""{"id": "al", "name": "A", "copyrights": [{"type": null, "text": null}]}""")).copyright)
        assertNull(cassetteAlbumMetaFrom(album("""{"id": "al", "name": "A"}""")).copyright)
        assertNull(cassetteAlbumMetaFrom(album("""{"id": "al", "name": "A", "copyrights": null, "label": null}""")).label)
    }
}
