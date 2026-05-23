package com.crsmthw.lyra.data.local

import android.content.Context
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.remote.model.SpotifyUser
import com.google.gson.Gson
import java.io.File

data class CachedTrackList(
    val snapshotId : String,
    val tracks     : List<SpotifyTrack>,
)

data class LibraryCacheData(
    val playlists         : List<SpotifyPlaylist>          = emptyList(),
    val featuredPlaylists : List<SpotifyPlaylist>          = emptyList(),
    val likedSongCount    : Int                            = 0,
    val user              : SpotifyUser?                   = null,
    val trackLists        : Map<String, CachedTrackList>   = emptyMap(),
)

class LibraryCache(context: Context) {

    private val file = File(context.filesDir, "library_cache.json")
    private val gson = Gson()

    fun save(data: LibraryCacheData) {
        runCatching { file.writeText(gson.toJson(data)) }
    }

    fun load(): LibraryCacheData? = runCatching {
        gson.fromJson(file.readText(), LibraryCacheData::class.java)
    }.getOrNull()

    fun saveTrackList(playlistId: String, snapshotId: String, tracks: List<SpotifyTrack>) {
        runCatching {
            val current = load() ?: LibraryCacheData()
            save(current.copy(trackLists = current.trackLists + (playlistId to CachedTrackList(snapshotId, tracks))))
        }
    }

    fun loadTrackList(playlistId: String): CachedTrackList? = load()?.trackLists?.get(playlistId)

    fun clear() { file.delete() }

    val sizeBytes: Long get() = if (file.exists()) file.length() else 0L
}
