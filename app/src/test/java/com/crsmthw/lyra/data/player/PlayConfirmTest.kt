package com.crsmthw.lyra.data.player

import com.crsmthw.lyra.data.remote.model.PlayerStateResponse
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The play confirm (2026-10-04). Player responses are parsed from JSON strings, as the API sends them. */
class PlayConfirmTest {

    private val gson = Gson()
    private val target = "spotify:track:target"
    private val prior = "spotify:track:prior"
    private val other = "spotify:track:other"

    private fun obs(
        status: Int = 200,
        item: String? = target,
        linkedFrom: String? = null,
        playing: Boolean = true,
        device: String? = "phone",
    ) = PollObservation(status, item, linkedFrom, playing, device)

    private fun response(json: String): PlayerStateResponse =
        gson.fromJson(json, PlayerStateResponse::class.java)

    // ── toObservation ───────────────────────────────────────────────────────

    @Test
    fun `a null response is the 204 observation`() {
        val o = (null as PlayerStateResponse?).toObservation()
        assertEquals(204, o.status)
        assertNull(o.itemUri)
        assertEquals(false, o.isPlaying)
        assertNull(o.deviceId)
    }

    @Test
    fun `the 9_1_88 empty player parses to an item-less 200`() {
        val o = response(
            """{"is_playing":false,"progress_ms":0,"item":null,"shuffle_state":false,"repeat_state":"off",
               "currently_playing_type":"unknown","device":{"id":"38a477","name":"Cris's Z Fold8",
               "type":"Smartphone","is_active":true}}""",
        ).toObservation()
        assertEquals(PollObservation(200, null, null, false, "38a477"), o)
        assertEquals(ConfirmVerdict.EMPTY, confirmVerdict(o, target, prior))
    }

    @Test
    fun `linked_from is parsed so a relinked track confirms`() {
        val o = response(
            """{"is_playing":true,"progress_ms":10,"shuffle_state":false,"repeat_state":"off",
               "item":{"id":"copy","name":"Song","uri":"spotify:track:copy",
                       "linked_from":{"uri":"$target"}},
               "device":{"id":"d","name":"n","type":"Smartphone","is_active":true}}""",
        ).toObservation()
        assertEquals("spotify:track:copy", o.itemUri)
        assertEquals(target, o.linkedFromUri)
        assertEquals(ConfirmVerdict.CONFIRMED, confirmVerdict(o, target, prior))
    }

    @Test
    fun `a missing device and a missing linked_from read as null`() {
        val o = response(
            """{"is_playing":true,"progress_ms":0,"shuffle_state":false,"repeat_state":"off",
               "item":{"id":"t","name":"Song","uri":"$target"}}""",
        ).toObservation()
        assertNull(o.deviceId)
        assertNull(o.linkedFromUri)
    }

    // ── confirmVerdict ──────────────────────────────────────────────────────

    @Test
    fun `the target playing confirms`() {
        assertEquals(ConfirmVerdict.CONFIRMED, confirmVerdict(obs(), target, prior))
    }

    @Test
    fun `the target paused is not a confirmation`() {
        assertEquals(ConfirmVerdict.OTHER_ITEM, confirmVerdict(obs(playing = false), target, prior))
    }

    @Test
    fun `the previous item still playing is OTHER_ITEM`() {
        assertEquals(ConfirmVerdict.OTHER_ITEM, confirmVerdict(obs(item = prior), target, prior))
    }

    @Test
    fun `a 204 is NO_DEVICE`() {
        assertEquals(ConfirmVerdict.NO_DEVICE, confirmVerdict(obs(status = 204, item = null, playing = false), target, prior))
    }

    @Test
    fun `no item but playing is not EMPTY`() {
        // An ad, or a podcast before additional_types — not the stopped shape.
        assertEquals(ConfirmVerdict.OTHER_ITEM, confirmVerdict(obs(item = null, playing = true), target, prior))
    }

    @Test
    fun `after a skip any other item playing confirms`() {
        assertEquals(ConfirmVerdict.CONFIRMED, confirmVerdict(obs(item = other), target, prior, step = 1))
        assertEquals(ConfirmVerdict.CONFIRMED, confirmVerdict(obs(item = other), target, prior, step = -1))
    }

    @Test
    fun `after a skip the prior item is not a confirmation`() {
        assertEquals(ConfirmVerdict.OTHER_ITEM, confirmVerdict(obs(item = prior), target, prior, step = 1))
        assertEquals(ConfirmVerdict.EMPTY, confirmVerdict(obs(item = null, playing = false), target, prior, step = 1))
    }

    // ── fallbackBodies ──────────────────────────────────────────────────────

    private val album = "spotify:album:a1"
    private val collection = "spotify:user:cris:collection"

    @Test
    fun `Liked origin falls back to the collection then the album`() {
        assertEquals(
            listOf(PlayBody.Context(collection, null), PlayBody.Context(album, null)),
            fallbackBodies(PlaybackOrigin.Liked, album, collection, isEpisode = false),
        )
    }

    @Test
    fun `Liked origin without a user id falls back to the album only`() {
        assertEquals(listOf(PlayBody.Context(album, null)), fallbackBodies(PlaybackOrigin.Liked, album, null, false))
    }

    @Test
    fun `Liked origin with neither is empty`() {
        assertTrue(fallbackBodies(PlaybackOrigin.Liked, null, null, false).isEmpty())
        assertTrue(fallbackBodies(PlaybackOrigin.Liked, " ", "", false).isEmpty())
    }

    @Test
    fun `a uris origin falls back to the album and never the collection`() {
        assertEquals(
            listOf(PlayBody.Context(album, null)),
            fallbackBodies(PlaybackOrigin.Uris(listOf(target)), album, collection, false),
        )
        assertTrue(fallbackBodies(PlaybackOrigin.Uris(listOf(target)), null, collection, false).isEmpty())
    }

    @Test
    fun `a context origin and no origin fall back to the album`() {
        assertEquals(
            listOf(PlayBody.Context(album, null)),
            fallbackBodies(PlaybackOrigin.Context("spotify:playlist:p"), album, collection, false),
        )
        assertEquals(listOf(PlayBody.Context(album, null)), fallbackBodies(null, album, collection, false))
    }

    @Test
    fun `an episode has no fallback`() {
        assertTrue(fallbackBodies(PlaybackOrigin.Liked, album, collection, isEpisode = true).isEmpty())
        assertTrue(fallbackBodies(PlaybackOrigin.Uris(listOf("spotify:episode:e")), album, null, true).isEmpty())
    }

    // ── Review 2026-10-04: the context, the wake confirm, another item playing ─────────

    @Test
    fun `the reported context is parsed`() {
        val o = response(
            """{"is_playing":true,"progress_ms":10,"shuffle_state":false,"repeat_state":"off",
               "context":{"type":"album","uri":"spotify:album:a"},
               "item":{"id":"target","name":"Song","uri":"$target"},
               "device":{"id":"d","name":"n","type":"Smartphone","is_active":true}}""",
        ).toObservation()
        assertEquals("spotify:album:a", o.contextUri)
        assertNull(response("""{"is_playing":false,"progress_ms":0,"shuffle_state":false,"repeat_state":"off"}""")
            .toObservation().contextUri)
    }

    @Test
    fun `a context matches itself and the two collection forms match each other`() {
        val album = obs().copy(contextUri = "spotify:album:a")
        assertTrue(reportsContext(album, "spotify:album:a"))
        assertFalse(reportsContext(album, "spotify:album:b"))
        assertFalse(reportsContext(obs(), "spotify:album:a"))
        val coll = obs().copy(contextUri = "spotify:collection:tracks")
        assertTrue(reportsContext(coll, "spotify:user:u:collection"))
        assertFalse(reportsContext(coll, "spotify:album:a"))
    }

    @Test
    fun `the wake confirm needs the body's own context, or a streak`() {
        val sdkOnly = obs()
        // No body since the SDK play: one CONFIRMED poll is the SDK's own report.
        assertTrue(wakeConfirmReached(sdkOnly, bodySent = false, sentContextUri = null, confirmedStreak = 1))
        // A uris body: two in a row (the SDK's audio before the body could otherwise confirm it).
        assertFalse(wakeConfirmReached(sdkOnly, bodySent = true, sentContextUri = null, confirmedStreak = 1))
        // Two polls can both be the SDK's own audio before 9.1.88 empties the player (~1.8 s): not enough.
        assertFalse(wakeConfirmReached(sdkOnly, bodySent = true, sentContextUri = null, confirmedStreak = 2))
        assertTrue(wakeConfirmReached(sdkOnly, bodySent = true, sentContextUri = null, confirmedStreak = WAKE_CONFIRM_STREAK_NO_CONTEXT))
        // A context body: its context reported confirms at once; another context needs five.
        val coll = "spotify:user:u:collection"
        assertTrue(wakeConfirmReached(obs().copy(contextUri = coll), true, coll, 1))
        assertFalse(wakeConfirmReached(obs(), true, coll, 4))
        assertTrue(wakeConfirmReached(obs(), true, coll, WAKE_CONFIRM_STREAK_OTHER_CONTEXT))
    }

    @Test
    fun `another item playing is neither the target, its relinked copy nor the prior song`() {
        assertTrue(isOtherItemPlaying(obs(item = other), target, prior))
        assertFalse(isOtherItemPlaying(obs(item = target), target, prior))
        assertFalse(isOtherItemPlaying(obs(item = other, linkedFrom = target), target, prior))
        assertFalse(isOtherItemPlaying(obs(item = prior), target, prior))
        assertFalse(isOtherItemPlaying(obs(item = other, playing = false), target, prior))
        assertFalse(isOtherItemPlaying(obs(item = null, playing = false), target, prior))
        assertFalse(isOtherItemPlaying(obs(status = 204, item = null, playing = false), target, prior))
        assertTrue(isOtherItemPlaying(obs(item = other), target, priorUri = null))
    }
}
