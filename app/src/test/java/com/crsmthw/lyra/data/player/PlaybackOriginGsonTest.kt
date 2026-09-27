package com.crsmthw.lyra.data.player

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The origin store's on-disk shape, parsed from JSON STRINGS with a plain `Gson()` — the
 * `Unsafe`-allocation hazards only show through deserialization (CLAUDE.md → Unit tests).
 */
class PlaybackOriginGsonTest {

    private val gson = Gson()

    private fun parse(json: String): PlaybackOrigin? =
        gson.fromJson(json, PlaybackOriginRecord::class.java)?.toOrigin()

    /** JSON → origin → JSON → origin must be a fixed point. */
    private fun roundTrip(json: String): PlaybackOrigin? {
        val first = parse(json)
        val again = first?.let { parse(gson.toJson(it.toRecord())) }
        assertEquals(first, again)
        return first
    }

    @Test
    fun `context origin round-trips`() {
        assertEquals(
            PlaybackOrigin.Context("spotify:playlist:p1"),
            roundTrip("""{"kind":"context","contextUri":"spotify:playlist:p1"}"""),
        )
    }

    @Test
    fun `a stored collection context reads back as Liked`() {
        assertEquals(
            PlaybackOrigin.Liked,
            roundTrip("""{"kind":"context","contextUri":"spotify:user:cris:collection"}"""),
        )
    }

    @Test
    fun `liked origin round-trips`() {
        assertEquals(PlaybackOrigin.Liked, roundTrip("""{"kind":"liked"}"""))
    }

    @Test
    fun `uris origin round-trips`() {
        assertEquals(
            PlaybackOrigin.Uris(listOf("spotify:track:1", "spotify:track:2")),
            roundTrip("""{"kind":"uris","uris":["spotify:track:1","spotify:track:2"]}"""),
        )
    }

    @Test
    fun `a null slot inside uris is dropped`() {
        assertEquals(
            PlaybackOrigin.Uris(listOf("spotify:track:1", "spotify:track:2")),
            roundTrip("""{"kind":"uris","uris":["spotify:track:1",null,"spotify:track:2",""]}"""),
        )
    }

    @Test
    fun `a stored uris list longer than 750 reads back capped`() {
        val many = (0 until 800).joinToString(",") { "\"spotify:track:$it\"" }
        val origin = roundTrip("""{"kind":"uris","uris":[$many]}""") as PlaybackOrigin.Uris
        assertEquals(750, origin.uris.size)
    }

    @Test
    fun `unreadable records are no origin`() {
        assertNull(parse("""{}"""))
        assertNull(parse("""{"kind":null}"""))
        assertNull(parse("""{"kind":"something-new"}"""))
        assertNull(parse("""{"kind":"context"}"""))
        assertNull(parse("""{"kind":"context","contextUri":""}"""))
        assertNull(parse("""{"kind":"uris"}"""))
        assertNull(parse("""{"kind":"uris","uris":null}"""))
        assertNull(parse("""{"kind":"uris","uris":[null]}"""))
    }

    @Test
    fun `unknown fields from a newer build are ignored`() {
        assertEquals(PlaybackOrigin.Liked, parse("""{"kind":"liked","future":42}"""))
    }
}
