package com.crsmthw.lyra.data.local

import android.content.Context
import com.crsmthw.lyra.data.remote.model.PlaylistTracksMeta
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
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

/**
 * One tapped Search result, persisted for the Search screen's "Recent" section. Stores just enough
 * to re-render the row and re-navigate: [type] is "track" | "album" | "artist" | "playlist";
 * [uri] is only meaningful for tracks (to play) and playlists; albums/artists navigate by [id].
 */
data class RecentSearch(
    val type     : String,
    val id       : String,
    val uri      : String,
    val name     : String,
    val subtitle : String,
    val imageUrl : String?,
)

/**
 * One "Jump back in" tile: a de-duped recently-played context. [type] is
 * "playlist" | "liked" | "album" | "artist"; [id] navigates (playlists resolve against the
 * library list, albums/artists push their detail route), [uri] plays.
 */
data class JumpBackInItem(
    val type     : String,
    val id       : String,
    val uri      : String,
    val title    : String,
    val subtitle : String? = null,
    val artUrl   : String? = null,
)

/**
 * Cached "For you" band. All fields nullable — Gson allocates via Unsafe and bypasses Kotlin
 * defaults, so absent fields in caches written before this existed arrive null regardless of
 * declared defaults (same hazard as the sparse-owner crash; see SpotifyPlaylist).
 */
data class ForYouCacheData(
    val jumpBackIn : List<JumpBackInItem>? = null,
    val topTracks  : List<SpotifyTrack>?   = null,
)

data class LibraryCacheData(
    val playlists         : List<SpotifyPlaylist>          = emptyList(),
    val featuredPlaylists : List<SpotifyPlaylist>          = emptyList(),
    val likedSongCount    : Int                            = 0,
    val user              : SpotifyUser?                   = null,
    val trackLists        : Map<String, CachedTrackList>   = emptyMap(),
    // Nullable: absent in caches written before these existed (Gson Unsafe alloc — see above).
    val forYou            : ForYouCacheData?               = null,
    val savedAlbums       : List<SpotifyAlbum>?            = null,
    val followedArtists   : List<SpotifyArtist>?           = null,
)

class LibraryCache(context: Context) {

    private val file = File(context.filesDir, "library_cache.json")
    private val recentsFile = File(context.filesDir, "recent_searches.json")
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

    /** Persists just the For-you band (jump-back-in + top tracks), leaving the rest untouched. */
    fun saveForYou(forYou: ForYouCacheData) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            saveLocked(current.copy(forYou = forYou))
        }
    }

    /** Persists the Albums/Artists filter content, leaving the rest untouched. */
    fun saveCollections(savedAlbums: List<SpotifyAlbum>, followedArtists: List<SpotifyArtist>) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            saveLocked(current.copy(savedAlbums = savedAlbums, followedArtists = followedArtists))
        }
    }

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
            val newTracks = existing.tracks + track
            saveLocked(current.copy(
                trackLists = current.trackLists + (playlistId to CachedTrackList(existing.snapshotId, newTracks)),
                // Keep the My Playlists metadata count in lockstep with the cached list. The guard
                // above guarantees the cache holds the full list, so newTracks.size IS the new
                // authoritative total — an absolute value, never a ±1 delta (which would compound any
                // pre-existing drift). Fixes the left-pane count lagging the header after an in-app add.
                playlists  = current.playlists.withTrackCount(playlistId, newTracks.size),
            ))
            _trackListChanges.tryEmit(playlistId)   // refresh the open playlist's track list
            _revision.value++                        // refresh the My Playlists list count
        }
    }

    /** Removes a track from a playlist's cached track list if present (keeps the snapshot id). */
    fun removeFromPlaylistTrackList(playlistId: String, trackUri: String) {
        synchronized(lock) {
            val current  = loadLocked() ?: return
            val existing = current.trackLists[playlistId] ?: return
            if (existing.tracks.none { it.uri == trackUri }) return
            val newTracks = existing.tracks.filterNot { it.uri == trackUri }
            // Mirror the metadata count to the cache only when the cache held the full list; for a
            // partial cache its size isn't the total, so best-effort decrement and let the per-open
            // reconcile settle it.
            val metaNow  = current.playlists.firstOrNull { it.id == playlistId }?.trackCount ?: 0
            val newTotal = if (existing.tracks.size >= metaNow) newTracks.size else (metaNow - 1).coerceAtLeast(0)
            saveLocked(current.copy(
                trackLists = current.trackLists + (playlistId to CachedTrackList(existing.snapshotId, newTracks)),
                playlists  = current.playlists.withTrackCount(playlistId, newTotal),
            ))
            _trackListChanges.tryEmit(playlistId)
            _revision.value++
        }
    }

    /**
     * Sets a playlist's cached metadata track count to an absolute [total] — used by the per-open
     * reconcile to heal drift or pick up an external change. Pings the revision so the My Playlists
     * list re-syncs. No-op when the playlist isn't cached or the count is already [total].
     */
    fun setPlaylistTrackCount(playlistId: String, total: Int) {
        synchronized(lock) {
            val current = loadLocked() ?: return
            val pl = current.playlists.firstOrNull { it.id == playlistId } ?: return
            if (pl.trackCount == total) return
            saveLocked(current.copy(playlists = current.playlists.withTrackCount(playlistId, total)))
            _revision.value++
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

    // ── Recent searches (Search screen) ─────────────────────────────────────────
    // Stored in their own file so they're independent of the main library cache and its refresh /
    // revision machinery. The list is small (capped by the caller), so a plain JSON array suffices.

    fun loadRecentSearches(): List<RecentSearch> = synchronized(lock) {
        runCatching {
            if (!recentsFile.exists()) return@runCatching emptyList<RecentSearch>()
            gson.fromJson(recentsFile.readText(), Array<RecentSearch>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    fun saveRecentSearches(list: List<RecentSearch>) = synchronized(lock) {
        runCatching { recentsFile.writeText(gson.toJson(list)) }
    }

    val sizeBytes: Long get() = if (file.exists()) file.length() else 0L

    // ── Unlocked helpers — only call while holding `lock` ─────────────────────

    private fun loadLocked(): LibraryCacheData? =
        runCatching { gson.fromJson(file.readText(), LibraryCacheData::class.java) }.getOrNull()

    private fun saveLocked(data: LibraryCacheData) =
        runCatching { file.writeText(gson.toJson(data)) }

    /** Returns a copy of the list with [playlistId]'s metadata track count set to [total]. */
    private fun List<SpotifyPlaylist>.withTrackCount(playlistId: String, total: Int): List<SpotifyPlaylist> =
        map {
            if (it.id == playlistId)
                it.copy(tracksMeta = (it.tracksMeta ?: PlaylistTracksMeta(0, null)).copy(total = total))
            else it
        }

    companion object {
        const val LIKED_SONGS_KEY = "liked_songs"
    }
}
