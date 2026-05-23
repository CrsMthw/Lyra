package com.crsmthw.lyra.data.remote

import com.crsmthw.lyra.data.remote.model.*
import retrofit2.Response
import retrofit2.http.*

interface SpotifyApiService {

    // ── User ─────────────────────────────────────────────────────────────────
    @GET("me")
    suspend fun getCurrentUser(): Response<SpotifyUser>

    // ── Library ──────────────────────────────────────────────────────────────
    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Query("limit")  limit : Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<UserPlaylistsResponse>

    @GET("me/tracks")
    suspend fun getLikedSongs(
        @Query("limit")  limit : Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<SavedTracksResponse>

    @PUT("me/library")
    suspend fun saveTracks(@Query("uris") uris: String): Response<Unit>

    @DELETE("me/library")
    suspend fun removeTracks(@Query("uris") uris: String): Response<Unit>

    @GET("me/library/contains")
    suspend fun checkSavedTracks(@Query("uris") uris: String): Response<List<Boolean>>

    // ── Playlists ────────────────────────────────────────────────────────────
    @GET("playlists/{id}")
    suspend fun getPlaylistFull(@Path("id") id: String): Response<SpotifyPlaylistFull>

    @GET("playlists/{id}/items")
    suspend fun getPlaylistTracks(
        @Path("id")      id    : String,
        @Query("limit")  limit : Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<PlaylistTracksResponse>

    // ── Player ───────────────────────────────────────────────────────────────
    @GET("me/player")
    suspend fun getPlayerState(): Response<PlayerStateResponse>

    @PUT("me/player/play")
    suspend fun resumePlayback(): Response<Unit>

    @PUT("me/player/play")
    suspend fun play(@Body body: PlayRequest): Response<Unit>

    @PUT("me/player/pause")
    suspend fun pause(): Response<Unit>

    @POST("me/player/next")
    suspend fun skipNext(): Response<Unit>

    @POST("me/player/previous")
    suspend fun skipPrevious(): Response<Unit>

    @PUT("me/player/seek")
    suspend fun seek(@Query("position_ms") positionMs: Long): Response<Unit>

    @PUT("me/player/shuffle")
    suspend fun setShuffle(@Query("state") state: Boolean): Response<Unit>

    @PUT("me/player/repeat")
    suspend fun setRepeat(@Query("state") state: String): Response<Unit>

    @PUT("me/player/volume")
    suspend fun setVolume(@Query("volume_percent") volume: Int): Response<Unit>

    // ── Search ───────────────────────────────────────────────────────────────
    @GET("search")
    suspend fun search(
        @Query("q")     query: String,
        @Query("type")  type : String,
        @Query("limit") limit: Int,
    ): Response<SearchResponse>

    // ── Browse / Featured ────────────────────────────────────────────────────
    @GET("browse/featured-playlists")
    suspend fun getFeaturedPlaylists(
        @Query("limit") limit: Int = 10,
    ): Response<FeaturedPlaylistsResponse>

    // ── Token refresh (hits accounts endpoint, not api) ──────────────────────
    // Note: handled by TokenManager via OkHttp directly (not Retrofit)
}

