package com.crsmthw.lyra.data.remote.model

import com.google.gson.annotations.SerializedName

// Gson models for the podcast surface (`me/shows`, `shows/{id}` and `shows/{id}/episodes`).
// `search?type=show` needs no wrapper of its own — its hits are podcast shows in the ordinary
// search envelope, so they arrive as `SearchResponse.shows`.
//
// These live in their own file rather than in SpotifyModels.kt purely so the podcast feature stays
// greppable; ProGuard already keeps `com.crsmthw.lyra.data.remote.model.**`, so no new keep rule is
// needed — including for the [SpotifyShow] list the library cache persists.
//
// EVERY field is nullable with a default — and so is every list ELEMENT. That is not defensive
// padding: Gson allocates via `Unsafe` and bypasses the Kotlin constructor, so a key the payload
// omits lands as `null` whatever the declared type, and a `null` inside a JSON array lands as a
// `null` element inside a `List<NonNull>` (the same hazard documented on [SpotifyPlaylist] and
// [com.crsmthw.lyra.data.local.LibraryCacheData]). The podcast payloads omit plenty — `publisher`
// is already gone, episode `images` can be absent, `me/shows` can answer 200 with no `items`, and
// an episode unavailable in the user's market arrives as a bare `null` in the items array.

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
 * [resumePoint] is REQUESTED: `user-read-playback-position` is in
 * `SpotifyAuthManager.SCOPES` (since 2026-09-15), and it is what drives the show screen's Played
 * state and "N left" progress. It is still null in two ordinary cases, and the UI must treat both
 * as "no progress known": the stored token PREDATES the scope (a refresh never widens a grant, so
 * a session authorized earlier only gains it on a reconnect — the show screen says so in one line
 * when `SpotifyAuthManager.hasScope` is false), or the user has never started this episode.
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
 * A paged wrapper for the podcast endpoints, separate from the shared [Paged] only because its
 * `total` is nullable: "200 with no items" is a real, documented outcome for episode lists (the
 * market caveat — see docs/SPOTIFY.md → Podcast shows) and must stay distinguishable from a parse
 * failure, and a `total` of 0 is part of that signal.
 *
 * The null-tolerance of the items themselves is the same rule as [Paged], and podcasts are where it
 * was found: Spotify returns a `null` SLOT for an episode that is unavailable or removed in the
 * user's market (`"items": [null, {…}]`), in the embedded `shows/{id}` page and in
 * `shows/{id}/episodes` alike. That crashed the show screen at the first `it.uri` / `it.id`
 * (2026-09-13). Read [items] — null-free — and page by [rawCount], which counts every slot.
 */
data class ShowPage<T : Any>(
    @SerializedName("items") val rawItems: List<T?>? = null,
    val total : Int?    = null,
    val next  : String? = null,
) {
    /** The page's episodes with the unavailable slots dropped. Null only when the key was absent. */
    val items: List<T>? get() = rawItems?.filterNotNull()

    /** Slots the endpoint returned, nulls included — what `offset` advances by. */
    val rawCount: Int get() = rawItems?.size ?: 0
}

/** `GET me/shows` — items are `{ added_at, show }` wrappers; the page itself can arrive itemless. */
data class SavedShowsResponse(
    @SerializedName("items") val rawItems: List<SavedShowItem?>? = null,
    val total : Int?    = null,
    val next  : String? = null,
) {
    val items   : List<SavedShowItem>? get() = rawItems?.filterNotNull()
    val rawCount: Int                  get() = rawItems?.size ?: 0
}

data class SavedShowItem(
    @SerializedName("added_at") val addedAt : String?      = null,
    val show                                : SpotifyShow? = null,
)
