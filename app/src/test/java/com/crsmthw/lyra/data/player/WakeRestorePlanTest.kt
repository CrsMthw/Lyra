package com.crsmthw.lyra.data.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakeRestorePlanTest {

    private val t1 = "spotify:track:1"
    private val t2 = "spotify:track:2"
    private val t3 = "spotify:track:3"
    private val ep = "spotify:episode:e1"
    private val playlist = "spotify:playlist:p1"
    private val collection = "spotify:user:cris:collection"
    private val liked = listOf(t1, t2, t3)

    private fun plan(
        uri: String = t2,
        ctx: String? = null,
        origin: PlaybackOrigin? = null,
        likedUris: List<String>? = liked,
        isEpisode: Boolean = false,
    ) = planWakeRestore(uri, ctx, origin, likedUris, isEpisode)

    // ── Rule 1: a non-collection mirror context ──────────────────────────────

    @Test
    fun `non-collection mirror context restores as that context`() {
        assertEquals(WakeRestoreBody.Context(playlist), plan(ctx = playlist))
    }

    @Test
    fun `mirror context wins over a Liked or Uris origin`() {
        assertEquals(WakeRestoreBody.Context(playlist), plan(ctx = playlist, origin = PlaybackOrigin.Liked))
        assertEquals(
            WakeRestoreBody.Context(playlist),
            plan(ctx = playlist, origin = PlaybackOrigin.Uris(listOf(t1, t2))),
        )
    }

    @Test
    fun `blank mirror context is ignored`() {
        assertEquals(WakeRestoreBody.Uris(listOf(t2, t3)), plan(ctx = "  "))
    }

    @Test
    fun `an episode never restores into a show context`() {
        assertEquals(
            WakeRestoreBody.Uris(listOf(ep)),
            plan(uri = ep, ctx = "spotify:show:s1", isEpisode = true, likedUris = null),
        )
    }

    @Test
    fun `an episode inside a playlist context keeps the context`() {
        assertEquals(WakeRestoreBody.Context(playlist), plan(uri = ep, ctx = playlist, isEpisode = true))
    }

    // ── Rule 2: Liked (collection context or Liked origin) ───────────────────

    @Test
    fun `collection mirror context restores as the liked window from the current uri`() {
        assertEquals(WakeRestoreBody.Uris(listOf(t2, t3)), plan(ctx = collection))
    }

    @Test
    fun `spotify collection form is also a collection`() {
        assertEquals(WakeRestoreBody.Uris(listOf(t2, t3)), plan(ctx = "spotify:collection:tracks"))
    }

    @Test
    fun `Liked origin restores as the liked window`() {
        assertEquals(WakeRestoreBody.Uris(listOf(t2, t3)), plan(origin = PlaybackOrigin.Liked))
    }

    @Test
    fun `liked window is capped at 750`() {
        val many = (0 until 2_000).map { "spotify:track:$it" }
        val body = plan(uri = "spotify:track:100", origin = PlaybackOrigin.Liked, likedUris = many)
        body as WakeRestoreBody.Uris
        assertEquals(750, body.uris.size)
        assertEquals("spotify:track:100", body.uris.first())
        assertEquals("spotify:track:849", body.uris.last())
    }

    @Test
    fun `Liked origin with the uri missing from the liked cache falls back to the single item`() {
        assertEquals(
            WakeRestoreBody.Uris(listOf("spotify:track:9")),
            plan(uri = "spotify:track:9", origin = PlaybackOrigin.Liked),
        )
    }

    @Test
    fun `collection context with no liked cache falls back to the single item`() {
        assertEquals(WakeRestoreBody.Uris(listOf(t2)), plan(ctx = collection, likedUris = null))
    }

    // ── Rule 3: a Uris origin ────────────────────────────────────────────────

    @Test
    fun `Uris origin holding the uri restores from the uri onward`() {
        val origin = PlaybackOrigin.Uris(listOf("spotify:track:a", t2, "spotify:track:b"))
        assertEquals(WakeRestoreBody.Uris(listOf(t2, "spotify:track:b")), plan(origin = origin))
    }

    @Test
    fun `Uris origin window is capped at 750`() {
        val many = (0 until 900).map { "spotify:track:$it" }
        val body = plan(uri = "spotify:track:0", origin = PlaybackOrigin.Uris(many), likedUris = null)
        body as WakeRestoreBody.Uris
        assertEquals(750, body.uris.size)
    }

    @Test
    fun `Uris origin without the uri is the single item even when the uri is liked`() {
        val origin = PlaybackOrigin.Uris(listOf("spotify:track:x"))
        assertEquals(WakeRestoreBody.Uris(listOf(t2)), plan(origin = origin))
    }

    @Test
    fun `episode Uris origin restores the episode feed from the episode`() {
        val origin = PlaybackOrigin.Uris(listOf("spotify:episode:e0", ep, "spotify:episode:e2"))
        assertEquals(
            WakeRestoreBody.Uris(listOf(ep, "spotify:episode:e2")),
            plan(uri = ep, origin = origin, isEpisode = true),
        )
    }

    // ── Rule 4: no origin, uri in the liked cache ────────────────────────────

    @Test
    fun `no origin and the uri liked restores as the liked window`() {
        assertEquals(WakeRestoreBody.Uris(listOf(t1, t2, t3)), plan(uri = t1, origin = null))
    }

    @Test
    fun `a Context origin is better information than the liked cache`() {
        assertEquals(WakeRestoreBody.Uris(listOf(t2)), plan(origin = PlaybackOrigin.Context(playlist)))
    }

    // ── Rule 5: the single item ──────────────────────────────────────────────

    @Test
    fun `nothing known is the single item`() {
        assertEquals(WakeRestoreBody.Uris(listOf(t2)), plan(likedUris = null))
    }

    @Test
    fun `an episode with no origin is the single episode even if the liked list somehow holds it`() {
        assertEquals(
            WakeRestoreBody.Uris(listOf(ep)),
            plan(uri = ep, isEpisode = true, likedUris = listOf(ep)),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    fun `windowFrom returns null for an absent uri and starts at the first occurrence`() {
        assertNull(windowFrom(listOf(t1), t2))
        assertEquals(listOf(t2, t1, t2), windowFrom(listOf(t1, t2, t1, t2), t2))
    }

    @Test
    fun `forContext maps the collection to Liked`() {
        assertEquals(PlaybackOrigin.Liked, PlaybackOrigin.forContext(collection))
        assertEquals(PlaybackOrigin.Context(playlist), PlaybackOrigin.forContext(playlist))
    }

    @Test
    fun `forUris caps at 750 and drops blanks`() {
        val origin = PlaybackOrigin.forUris(listOf("", t1) + (0 until 800).map { "spotify:track:n$it" })
        origin as PlaybackOrigin.Uris
        assertEquals(750, origin.uris.size)
        assertEquals(t1, origin.uris.first())
    }
}
