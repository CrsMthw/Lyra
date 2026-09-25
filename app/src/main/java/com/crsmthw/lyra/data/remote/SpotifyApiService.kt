package com.crsmthw.lyra.data.remote

import com.crsmthw.lyra.data.remote.model.*
import retrofit2.http.*

/**
 * The item types the client can render, for `me/player`'s `additional_types`.
 *
 * **Not optional.** The parameter's default is `track` ALONE: without opting in to `episode`,
 * `GET me/player` answers 200 with `item: null` the whole time a podcast is playing, which
 * `PlayerStateManager` reads as "nothing playing" — a blank player over audible playback. Spotify
 * documents the parameter as existing purely so pre-podcast clients keep their old behaviour, and
 * warns it "might be deprecated in the future", i.e. episodes eventually arrive regardless.
 *
 * `me/player/queue` takes NO query parameters and already returns `TrackObject | EpisodeObject`,
 * so it needs nothing (what filtered episodes out there was our own uri filter, not the API).
 * `playlists/{id}/items` also accepts this parameter and is deliberately NOT opted in: playlist
 * track lists stay track-only for now, since their cached rows feed mosaics, play-queue uri lists
 * and counts.
 */
const val PLAYER_ADDITIONAL_TYPES = "track,episode"

interface SpotifyApiService {

    // ── User ─────────────────────────────────────────────────────────────────
    @GET("me")
    suspend fun getCurrentUser(): SpotifyUser

    // ── Library ──────────────────────────────────────────────────────────────
    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Query("limit")  limit : Int = 50,
        @Query("offset") offset: Int = 0,
    ): UserPlaylistsResponse

    @GET("me/tracks")
    suspend fun getLikedSongs(
        @Query("limit")  limit : Int = 50,
        @Query("offset") offset: Int = 0,
    ): SavedTracksResponse

    @PUT("me/library")
    suspend fun saveTracks(@Query("uris") uris: String)

    @DELETE("me/library")
    suspend fun removeTracks(@Query("uris") uris: String)

    @GET("me/library/contains")
    suspend fun checkSavedTracks(@Query("uris") uris: String): List<Boolean>

    @GET("me/albums")
    suspend fun getSavedAlbums(
        @Query("limit")  limit : Int = 50,
        @Query("offset") offset: Int = 0,
    ): SavedAlbumsResponse

    // The READ side of follows survived Feb 2026 (needs the user-follow-read scope — without it
    // this 403s and the Artists filter looks empty). The WRITE side (PUT/DELETE me/following +
    // me/following/contains) was removed for dev-mode apps: follow/unfollow/status go through
    // the unified me/library endpoints above with artist uris + the user-follow-* scopes.
    @GET("me/following")
    suspend fun getFollowedArtists(
        @Query("type")  type : String  = "artist",
        @Query("limit") limit: Int     = 50,
        @Query("after") after: String? = null,   // cursor: id of the last artist of the prev page
    ): FollowedArtistsResponse

    // ── Playlists ────────────────────────────────────────────────────────────
    @GET("playlists/{id}")
    suspend fun getPlaylistFull(@Path("id") id: String): SpotifyPlaylistFull

    @GET("playlists/{id}/items")
    suspend fun getPlaylistTracks(
        @Path("id")      id    : String,
        @Query("limit")  limit : Int = 50,
        @Query("offset") offset: Int = 0,
    ): PlaylistTracksResponse

    @POST("me/playlists")
    suspend fun createPlaylist(@Body body: CreatePlaylistRequest): SpotifyPlaylist

    // Spotify has no "delete playlist" — you unfollow your own playlist, which removes it.
    @DELETE("playlists/{id}/followers")
    suspend fun unfollowPlaylist(@Path("id") id: String)

    @POST("playlists/{id}/items")
    suspend fun addTracksToPlaylist(
        @Path("id") id  : String,
        @Body       body: AddTracksRequest,
    ): SnapshotIdResponse

    @HTTP(method = "DELETE", path = "playlists/{id}/items", hasBody = true)
    suspend fun removeItemsFromPlaylist(
        @Path("id") id  : String,
        @Body       body: RemoveItemsRequest,
    ): SnapshotIdResponse

    @PUT("playlists/{id}/items")
    suspend fun reorderPlaylistItems(
        @Path("id") id  : String,
        @Body       body: ReorderItemsRequest,
    ): SnapshotIdResponse

    // 200 with an empty body — no return type, same as unfollowPlaylist.
    @PUT("playlists/{id}")
    suspend fun updatePlaylistDetails(
        @Path("id") id  : String,
        @Body       body: UpdatePlaylistDetailsRequest,
    )

    // ── Player ───────────────────────────────────────────────────────────────
    @GET("me/player")
    suspend fun getPlayerState(
        @Query("additional_types") additionalTypes: String = PLAYER_ADDITIONAL_TYPES,
    ): PlayerStateResponse?

    /**
     * `device_id` targets a device that only has to be LISTED by `me/player/devices`, not active —
     * what the App Remote wake path needs while a freshly started Spotify has registered but not
     * yet become the active device (docs/PLAYER.md → Playback 404 Fallback). Retrofit omits a null
     * query, so every existing caller's request is unchanged.
     */
    @PUT("me/player/play")
    suspend fun resumePlayback(@Query("device_id") deviceId: String? = null)

    @PUT("me/player/play")
    suspend fun play(
        @Body              body    : PlayRequest,
        @Query("device_id") deviceId: String? = null,
    )

    @PUT("me/player/pause")
    suspend fun pause()

    @POST("me/player/next")
    suspend fun skipNext()

    @POST("me/player/previous")
    suspend fun skipPrevious()

    @PUT("me/player/seek")
    suspend fun seek(@Query("position_ms") positionMs: Long)

    @PUT("me/player/shuffle")
    suspend fun setShuffle(@Query("state") state: Boolean)

    @PUT("me/player/repeat")
    suspend fun setRepeat(@Query("state") state: String)

    @PUT("me/player/volume")
    suspend fun setVolume(@Query("volume_percent") volume: Int)

    @GET("me/player/queue")
    suspend fun getQueue(): QueueResponse?

    @POST("me/player/queue")
    suspend fun addToQueue(@Query("uri") uri: String)

    @GET("me/player/recently-played")
    suspend fun getRecentlyPlayed(@Query("limit") limit: Int = 50): RecentlyPlayedResponse

    // ── Personalisation ──────────────────────────────────────────────────────
    @GET("me/top/tracks")
    suspend fun getTopTracks(
        @Query("time_range") timeRange: String = "short_term",   // short_term | medium_term | long_term
        @Query("limit")      limit    : Int    = 20,
        @Query("offset")     offset   : Int    = 0,
    ): Paged<SpotifyTrack>

    @GET("me/top/artists")
    suspend fun getTopArtists(
        @Query("time_range") timeRange: String = "short_term",
        @Query("limit")      limit    : Int    = 20,
        @Query("offset")     offset   : Int    = 0,
    ): Paged<SpotifyArtist>

    @GET("me/player/devices")
    suspend fun getAvailableDevices(): DevicesResponse?

    @PUT("me/player")
    suspend fun transferPlayback(@Body request: TransferPlaybackRequest)

    // ── Albums ───────────────────────────────────────────────────────────────
    @GET("albums/{id}")
    suspend fun getAlbum(
        @Path("id")     id    : String,
        @Query("limit") limit : Int = 50,
    ): SpotifyAlbumFull

    // ── Artists ──────────────────────────────────────────────────────────────
    @GET("artists/{id}")
    suspend fun getArtist(@Path("id") id: String): SpotifyArtistFull

    @GET("artists/{id}/albums")
    suspend fun getArtistAlbums(
        @Path("id")                                       id            : String,
        @Query(value = "include_groups", encoded = true)  includeGroups : String = "album,single,compilation",
        @Query("market")                                  market        : String = "from_token",
        @Query("limit")                                   limit         : Int    = 10,
        @Query("offset")                                  offset        : Int    = 0,
    ): Paged<SpotifyAlbum>

    // ── Search ───────────────────────────────────────────────────────────────
    @GET("search")
    suspend fun search(
        @Query("q")      query : String,
        @Query("type")   type  : String,
        @Query("limit")  limit : Int,
        @Query("offset") offset: Int = 0,
    ): SearchResponse

    // ── Token refresh (hits accounts endpoint, not api) ──────────────────────
    // Note: handled by TokenManager via OkHttp directly (not Retrofit)

    // ── Podcast shows ────────────────────────────────────────────────────────
    // All three reads are on the Feb-2026 "still available" list, and the on-device spike proved
    // each answers 2xx with the token Lyra ships (no `user-read-playback-position`). The WRITE
    // side (PUT/DELETE me/shows) is deprecated/removed — following a show goes through the
    // unified me/library endpoints above with a show uri, exactly like albums and artists.
    //
    // `market` is optional on both show reads and is deliberately NOT sent by default: the spike
    // came back with a full episode list without it. It stays a parameter only so a caller can
    // make the documented one-shot `from_token` retry when a page answers empty (see
    // docs/SPOTIFY.md → Podcast shows, "Market caveat").
    @GET("me/shows")
    suspend fun getSavedShows(
        @Query("limit")  limit : Int = 50,
        @Query("offset") offset: Int = 0,
    ): SavedShowsResponse

    @GET("shows/{id}")
    suspend fun getShow(
        @Path("id")      id     : String,
        @Query("market") market : String? = null,
    ): SpotifyShow

    @GET("shows/{id}/episodes")
    suspend fun getShowEpisodes(
        @Path("id")      id     : String,
        @Query("limit")  limit  : Int     = 50,
        @Query("offset") offset : Int     = 0,
        @Query("market") market : String? = null,
    ): ShowPage<SpotifyEpisode>
}
