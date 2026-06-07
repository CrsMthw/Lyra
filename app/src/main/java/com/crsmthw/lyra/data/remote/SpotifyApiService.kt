package com.crsmthw.lyra.data.remote

import com.crsmthw.lyra.data.remote.model.*
import retrofit2.http.*

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

    // ── Player ───────────────────────────────────────────────────────────────
    @GET("me/player")
    suspend fun getPlayerState(): PlayerStateResponse?

    @PUT("me/player/play")
    suspend fun resumePlayback()

    @PUT("me/player/play")
    suspend fun play(@Body body: PlayRequest)

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
        @Query("q")     query: String,
        @Query("type")  type : String,
        @Query("limit") limit: Int,
    ): SearchResponse

    // ── Browse / Featured ────────────────────────────────────────────────────
    @GET("browse/featured-playlists")
    suspend fun getFeaturedPlaylists(
        @Query("limit") limit: Int = 10,
    ): FeaturedPlaylistsResponse

    // ── Token refresh (hits accounts endpoint, not api) ──────────────────────
    // Note: handled by TokenManager via OkHttp directly (not Retrofit)
}
