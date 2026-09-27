package com.crsmthw.lyra.data.player

/**
 * Where what is playing CAME FROM — remembered so the play button can rebuild the queue after
 * Spotify has died (docs/PLAYER.md → Playback 404 Fallback). Pure Kotlin, no Android imports.
 *
 * Written every time Lyra issues a play, and whenever the player poll sees the context uri change
 * (so playback started inside the Spotify app is remembered too). Persisted as a
 * [PlaybackOriginRecord] by `PlaybackOriginStore`.
 */
sealed interface PlaybackOrigin {
    /** A playlist / album / artist / show context — Spotify itself holds the order. */
    data class Context(val contextUri: String) : PlaybackOrigin

    /**
     * Liked Songs. Nothing is stored: the `collection` context REJECTS an `offset`
     * ("Can't have offset for context type: COLLECTION"), so a restore always rebuilds a `uris`
     * window from the liked cache, starting at the current track.
     */
    data object Liked : PlaybackOrigin

    /** A bare `uris` list — search results, top tracks, an episode feed. At most [URI_CAP] entries. */
    data class Uris(val uris: List<String>) : PlaybackOrigin

    fun toRecord(): PlaybackOriginRecord = when (this) {
        is Context -> PlaybackOriginRecord(kind = KIND_CONTEXT, contextUri = contextUri)
        Liked      -> PlaybackOriginRecord(kind = KIND_LIKED)
        is Uris    -> PlaybackOriginRecord(kind = KIND_URIS, uris = uris)
    }

    companion object {
        /**
         * The largest `uris` body `me/player/play` takes — above ~800 it answers 413. The same cap
         * as `playFromLikedSongs` and the show screen's episode queue.
         */
        const val URI_CAP = 750

        internal const val KIND_CONTEXT = "context"
        internal const val KIND_LIKED   = "liked"
        internal const val KIND_URIS    = "uris"

        /**
         * The origin of a play of [contextUri]: the `collection` context (Liked Songs,
         * `spotify:user:<id>:collection`) maps to [Liked], anything else to [Context].
         */
        fun forContext(contextUri: String): PlaybackOrigin =
            if (isCollectionContext(contextUri)) Liked else Context(contextUri)

        /** A [Uris] origin, capped at [URI_CAP] and with blank entries dropped. */
        fun forUris(uris: List<String>): PlaybackOrigin =
            Uris(uris.filter { it.isNotBlank() }.take(URI_CAP))
    }
}

/**
 * True for the Liked Songs context in either form Spotify reports it:
 * `spotify:user:<id>:collection` or `spotify:collection…`.
 */
fun isCollectionContext(uri: String?): Boolean =
    uri != null && (uri.endsWith(":collection") || uri.startsWith("spotify:collection"))

/**
 * The on-disk shape of a [PlaybackOrigin]. Gson allocates via `Unsafe` (defaults never run), so
 * EVERY field is nullable and so is every list element (CLAUDE.md → Coding Conventions); an
 * unreadable or unknown record reads back as "no origin", never a crash. Kept by R8
 * (`proguard-rules.pro`).
 */
data class PlaybackOriginRecord(
    val kind      : String?        = null,
    val contextUri: String?        = null,
    val uris      : List<String?>? = null,
) {
    fun toOrigin(): PlaybackOrigin? = when (kind) {
        PlaybackOrigin.KIND_CONTEXT -> contextUri?.takeIf { it.isNotBlank() }?.let(PlaybackOrigin::forContext)
        PlaybackOrigin.KIND_LIKED   -> PlaybackOrigin.Liked
        PlaybackOrigin.KIND_URIS    -> uris?.filterNotNull()?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }?.let(PlaybackOrigin::forUris)
        else                        -> null
    }
}
