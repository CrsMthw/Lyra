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
    private val lock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    fun save(data: LibraryCacheData) = synchronized(lock) { saveLocked(data) }

    fun load(): LibraryCacheData? = synchronized(lock) { loadLocked() }

    fun saveTrackList(playlistId: String, snapshotId: String, tracks: List<SpotifyTrack>) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            saveLocked(current.copy(trackLists = current.trackLists + (playlistId to CachedTrackList(snapshotId, tracks))))
        }
    }

    fun loadTrackList(playlistId: String): CachedTrackList? =
        synchronized(lock) { loadLocked()?.trackLists?.get(playlistId) }

    fun prependToLikedSongs(track: SpotifyTrack) {
        synchronized(lock) {
            val current  = loadLocked() ?: return
            val existing = current.trackLists[LIKED_SONGS_KEY] ?: return
            if (existing.tracks.any { it.id == track.id }) return   // already present — no-op
            val newTotal = (existing.snapshotId.toIntOrNull() ?: existing.tracks.size) + 1
            saveLocked(current.copy(
                likedSongCount = newTotal,
                trackLists     = current.trackLists + (LIKED_SONGS_KEY to
                    CachedTrackList(newTotal.toString(), listOf(track) + existing.tracks)),
            ))
        }
    }

    fun removeFromLikedSongs(trackId: String) {
        synchronized(lock) {
            val current   = loadLocked() ?: return
            val existing  = current.trackLists[LIKED_SONGS_KEY] ?: return
            val newTracks = existing.tracks.filter { it.id != trackId }
            val newTotal  = ((existing.snapshotId.toIntOrNull() ?: existing.tracks.size) - 1).coerceAtLeast(0)
            saveLocked(current.copy(
                likedSongCount = newTotal,
                trackLists     = current.trackLists + (LIKED_SONGS_KEY to
                    CachedTrackList(newTotal.toString(), newTracks)),
            ))
        }
    }

    fun prependPlaylist(playlist: SpotifyPlaylist) {
        synchronized(lock) {
            val current = loadLocked() ?: return
            if (current.playlists.any { it.id == playlist.id }) return
            saveLocked(current.copy(playlists = listOf(playlist) + current.playlists))
        }
    }

    fun clear() = synchronized(lock) { file.delete() }

    val sizeBytes: Long get() = if (file.exists()) file.length() else 0L

    // ── Unlocked helpers — only call while holding `lock` ─────────────────────

    private fun loadLocked(): LibraryCacheData? =
        runCatching { gson.fromJson(file.readText(), LibraryCacheData::class.java) }.getOrNull()

    private fun saveLocked(data: LibraryCacheData) =
        runCatching { file.writeText(gson.toJson(data)) }

    companion object {
        const val LIKED_SONGS_KEY = "liked_songs"
    }
}
