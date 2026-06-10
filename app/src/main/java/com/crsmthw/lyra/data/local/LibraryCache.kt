package com.crsmthw.lyra.data.local

import android.content.Context
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.remote.model.SpotifyUser
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * Bumps whenever the playlist *set* changes via [prependPlaylist] / [removePlaylist] — e.g. a
     * playlist created from the song menu or the player while the Library screen is already in
     * memory. LibraryViewModel collects this to re-sync its in-memory list from the cache. It is
     * deliberately NOT bumped by [save] (which `loadLibrary`/`refreshLibrary` call), so a normal
     * library refresh doesn't trigger a redundant re-sync.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    /**
     * Emits a playlist id whenever its cached track list is surgically changed via
     * [appendToPlaylistTrackList] / [removeFromPlaylistTrackList] — i.e. add/remove from any screen,
     * including the full player and pop-out add-to-playlist sheets. LibraryViewModel collects this
     * to live-refresh that playlist when it's the one currently open, so the change shows without a
     * manual pull-to-refresh. Deliberately NOT emitted by [saveTrackList] (which loading/pagination
     * call), so the Library's own loading doesn't loop.
     */
    private val _trackListChanges = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val trackListChanges: SharedFlow<String> = _trackListChanges

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

    /**
     * Surgically appends [track] to a playlist's cached track list (mirrors [prependToLikedSongs],
     * but appends because Spotify adds to the END of a playlist). No-op when:
     *  - there's no cached list for the playlist (it'll be fetched fresh on open anyway),
     *  - the track is already present, or
     *  - the cache holds only a partial page (`tracks.size < knownTotal`) — appending to a paged
     *    prefix would break the offset-based pagination contract (the cache must stay a contiguous
     *    prefix of the real list), so we leave it and let the new track load on the next page.
     * Keeps the existing snapshot id so a snapshot match still serves the cache on open; a later
     * full library refresh sees the real new snapshot and reconciles by re-fetching.
     */
    fun appendToPlaylistTrackList(playlistId: String, knownTotal: Int, track: SpotifyTrack) {
        synchronized(lock) {
            val current  = loadLocked() ?: return
            val existing = current.trackLists[playlistId] ?: return
            if (existing.tracks.any { it.id == track.id }) return
            if (existing.tracks.size < knownTotal) return
            saveLocked(current.copy(
                trackLists = current.trackLists + (playlistId to
                    CachedTrackList(existing.snapshotId, existing.tracks + track)),
            ))
            _trackListChanges.tryEmit(playlistId)
        }
    }

    /** Removes a track from a playlist's cached track list if present (keeps the snapshot id). */
    fun removeFromPlaylistTrackList(playlistId: String, trackUri: String) {
        synchronized(lock) {
            val current  = loadLocked() ?: return
            val existing = current.trackLists[playlistId] ?: return
            if (existing.tracks.none { it.uri == trackUri }) return
            saveLocked(current.copy(
                trackLists = current.trackLists + (playlistId to
                    CachedTrackList(existing.snapshotId, existing.tracks.filterNot { it.uri == trackUri })),
            ))
            _trackListChanges.tryEmit(playlistId)
        }
    }

    fun prependPlaylist(playlist: SpotifyPlaylist) {
        synchronized(lock) {
            val current = loadLocked() ?: return
            if (current.playlists.any { it.id == playlist.id }) return
            saveLocked(current.copy(playlists = listOf(playlist) + current.playlists))
            _revision.value++
        }
    }

    fun removePlaylist(playlistId: String) {
        synchronized(lock) {
            val current = loadLocked() ?: return
            saveLocked(current.copy(
                playlists  = current.playlists.filterNot { it.id == playlistId },
                trackLists = current.trackLists - playlistId,
            ))
            _revision.value++
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
