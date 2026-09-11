package com.crsmthw.lyra.data.remote.model

import com.google.gson.annotations.SerializedName

// TEMPORARY — podcast API spike, remove after go/no-go
//
// Minimal Gson models for the Tier-3 podcast API spike. They live in their OWN file rather than
// appended to SpotifyModels.kt so that a NO-GO verdict is a single-file delete. ProGuard already
// keeps `com.crsmthw.lyra.data.remote.model.**`, so no new keep rule is needed.
//
// Every field is nullable with a default: the spike must report "200 but empty" rather than blow
// up on a shape we haven't seen before.

/**
 * A podcast show. Fields limited to what a future show-detail screen would need.
 *
 * `publisher` is marked **deprecated** on the show object by the February-2026 API — it is read
 * here only so the spike can report whether it still arrives; no UI should depend on it.
 */
data class SpotifyShow(
    val id            : String?              = null,
    val name          : String?              = null,
    val publisher     : String?              = null,   // deprecated by Spotify (Feb 2026)
    val description   : String?              = null,
    val images        : List<SpotifyImage>?  = null,
    val uri           : String?              = null,
    @SerializedName("total_episodes")
    val totalEpisodes : Int?                 = null,
    /** Only present on `GET shows/{id}`, which embeds the first page of episodes. */
    val episodes      : ShowPage<SpotifyEpisode>? = null,
)

data class SpotifyEpisode(
    val id          : String?             = null,
    val name        : String?             = null,
    @SerializedName("duration_ms")
    val durationMs  : Long?               = null,
    @SerializedName("release_date")
    val releaseDate : String?             = null,
    val uri         : String?             = null,
    val images      : List<SpotifyImage>? = null,
)

/**
 * A null-tolerant paged wrapper. The shared [Paged] declares `items`/`total`/`limit`/`offset` as
 * non-null, which is fine for endpoints we already trust; the spike deliberately tolerates a
 * missing key so it can distinguish "no items" from "unparseable".
 */
data class ShowPage<T>(
    val items : List<T>? = null,
    val total : Int?     = null,
    val next  : String?  = null,
)

/** `GET me/shows` — items are `{ added_at, show }` wrappers. */
data class SavedShowsResponse(
    val items : List<SavedShowItem>? = null,
    val total : Int?                 = null,
    val next  : String?              = null,
)

data class SavedShowItem(
    @SerializedName("added_at") val addedAt : String?      = null,
    val show                                : SpotifyShow? = null,
)

/** `GET search?type=show` — only the `shows` bucket is parsed. */
data class ShowSearchResponse(
    val shows: ShowPage<SpotifyShow>? = null,
)
