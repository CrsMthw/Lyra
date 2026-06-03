package com.crsmthw.lyra.data.remote

import com.crsmthw.lyra.data.remote.model.LrcLibResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface LrcLibApiService {
    @GET("api/get")
    suspend fun get(
        @Query("artist_name") artistName     : String,
        @Query("track_name")  trackName      : String,
        @Query("album_name")  albumName      : String,
        @Query("duration")    durationSeconds: Int,
    ): LrcLibResponse

    @GET("api/search")
    suspend fun search(
        @Query("q") query: String,
    ): List<LrcLibResponse>
}
