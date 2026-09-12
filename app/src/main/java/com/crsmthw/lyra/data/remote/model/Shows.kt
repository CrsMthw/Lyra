package com.crsmthw.lyra.data.remote.model

import com.google.gson.annotations.SerializedName

// Gson models for the podcast surface (`me/shows`, `shows/{id}`, `shows/{id}/episodes`, and
// `search?type=show`). They live in their own file rather than in SpotifyModels.kt purely so the
// podcast feature stays greppable; ProGuard already keeps `com.crsmthw.lyra.data.remote.model.**`,
// so no new keep rule is needed — including for the [SpotifyShow] list the library cache persists.
//
// EVERY field is nullable with a default. That is not defensive padding: Gson allocates via
// `Unsafe` and bypasses the Kotlin constructor, so a key the payload omits lands as `null`
// whatever the declared type (the same hazard documented on [SpotifyPlaylist] and
// [com.crsmthw.lyra.data.local.LibraryCacheData]). The podcast payloads omit plenty — `publisher`
// is already gone, episode `images` can be absent, and `me/shows` can answer 200 with no `items`.

/**
 * A podcast show — the simplified object from `me/shows` / `search?type=show`, and the full object
 * from `GET shows/{id}` (which additionally embeds the first page of [episodes]).
 *
 * **Do not build UI on [publisher].** February 2026 deprecated it and the on-device spike confirmed
 * it is absent from live responses; the field survives only so the spike can keep reporting that.
 * A show's subtitle is its episode count, never its publisher.
 */
data class SpotifyShow(
    val id              : String?             = null,
    val name            : String?             = null,
    val description     : String?             = null,
    @SerializedName("html_description")
    val htmlDescription : String?             = null,
    val images          : List<SpotifyImage>? = null,
    val uri             : String?             = null,
    @SerializedName("total_episodes")
    val totalEpisodes   : Int?                = null,
    val explicit        : Boolean?            = null,
    @SerializedName("media_type")
    val mediaType       : String?             = null,
    /** Deprecated by Spotify (Feb 2026) and absent in practice — never read by UI. */
    val publisher       : String?             = null,
    /** Only present on `GET shows/{id}`, which embeds the first page of episodes. */
    val episodes        : ShowPage<SpotifyEpisode>? = null,
) {
    val artUrl      : String  get() = images?.firstOrNull()?.url.orEmpty()
    val thumbnailUrl: String  get() = images?.lastOrNull()?.url.orEmpty()
    /** The uri the unified `me/library` follow/unfollow/contains calls take. */
    val libraryUri  : String? get() = uri ?: id?.let { "spotify:show:$it" }
}

/**
 * One podcast episode. [show] is the embedded parent-show summary, which `shows/{id}/episodes`
 * does NOT send (the caller already knows the show) but `me/player` and `me/player/queue` DO —
 * it is what gives an episode a subtitle and a fallback image when it is the now-playing item.
 *
 * [resumePoint] needs the `user-read-playback-position` scope, which Lyra does not request, so it
 * arrives null; it is modelled only so the field parses if that scope is ever added.
 */
data class SpotifyEpisode(
    val id                  : String?             = null,
    val name                : String?             = null,
    val description         : String?             = null,
    val images              : List<SpotifyImage>? = null,
    val uri                 : String?             = null,
    @SerializedName("duration_ms")
    val durationMs          : Long?               = null,
    @SerializedName("release_date")
    val releaseDate         : String?             = null,
    @SerializedName("release_date_precision")
    val releaseDatePrecision: String?             = null,
    val explicit            : Boolean?            = null,
    @SerializedName("resume_point")
    val resumePoint         : EpisodeResumePoint? = null,
    val show                : SpotifyShow?        = null,
) {
    val artUrl      : String get() = images?.firstOrNull()?.url ?: show?.artUrl.orEmpty()
    val thumbnailUrl: String get() = images?.lastOrNull()?.url ?: show?.thumbnailUrl.orEmpty()
}

data class EpisodeResumePoint(
    @SerializedName("fully_played")       val fullyPlayed     : Boolean? = null,
    @SerializedName("resume_position_ms") val resumePositionMs: Long?    = null,
)

/**
 * A null-tolerant paged wrapper for the podcast endpoints.
 *
 * Deliberately NOT the shared [Paged], which declares `items`/`total`/`limit`/`offset` non-null.
 * That is safe for endpoints we already trust; it is not safe here, because "200 with no items" is
 * a real, documented outcome for episode lists (the market caveat — see docs/SPOTIFY.md → Podcast
 * shows) and must stay distinguishable from a parse failure.
 */
data class ShowPage<T>(
    val items : List<T>? = null,
    val total : Int?     = null,
    val next  : String?  = null,
)

/** `GET me/shows` — items are `{ added_at, show }` wrappers; the page itself can arrive itemless. */
data class SavedShowsResponse(
    val items : List<SavedShowItem>? = null,
    val total : Int?                 = null,
    val next  : String?              = null,
)

data class SavedShowItem(
    @SerializedName("added_at") val addedAt : String?      = null,
    val show                                : SpotifyShow? = null,
)

/**
 * `GET search?type=show` — only the `shows` bucket is parsed.
 *
 * Separate from [SearchResponse] because the show type is requested on its own call; folding
 * `shows` into [SearchResponse] is a Search-screen follow-up, not a model change here.
 */
data class ShowSearchResponse(
    val shows: ShowPage<SpotifyShow>? = null,
)
