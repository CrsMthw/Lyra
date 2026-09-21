package com.crsmthw.lyra.data.local

import android.content.Context
import com.crsmthw.lyra.data.remote.model.PlaylistTracksMeta
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyShow
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.remote.model.SpotifyUser
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * One playlist's (or Liked Songs') cached track list.
 *
 * [tracks] are the FILTERED rows — `mapNotNull { resolvedTrack }` + `isPlayable != false` — so the
 * list size is NOT a position in the endpoint's own numbering. [rawOffset] is: it is the API
 * `offset` the next page must be fetched at, counting every item the endpoint returned, including
 * the ones dropped client-side. Seeding pagination from `tracks.size` instead re-fetches one row
 * per dropped item at the page boundary and renders it twice (see CACHING.md, "Pagination offset
 * must use the RAW page size").
 *
 * Nullable on purpose, and new fields here must be too: Gson allocates via `Unsafe` and bypasses
 * the Kotlin constructor, so a field absent from a cache file written by an older build lands as
 * `null` whatever its declared type (see [LibraryCacheData]). `null` therefore means "legacy entry,
 * boundary unknown" — callers fall back to `tracks.size` and guard the next page against
 * duplicates. Only the playlist paths maintain it; Liked Songs tracks its raw offset elsewhere
 * (`LibraryViewModel.likedSongsOffset`, the background fetcher's own loop variable).
 */
data class CachedTrackList(
    val snapshotId : String,
    val tracks     : List<SpotifyTrack>,
    val rawOffset  : Int? = null,
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

/**
 * One surgical edit to a playlist's cached track list, as announced by
 * [LibraryCache.trackListChanges]. It carries the MUTATION, not just the playlist id, so a listener
 * can apply exactly what changed to whatever rows it is showing instead of diffing its list against
 * the cache's — the two can legitimately differ by rows this edit had nothing to do with, and a diff
 * books that gap onto the edit (the 128→124 hero dip, 2026-09-12).
 *
 * [removedUris] is the set the caller ASKED to remove, never what the cache managed to drop:
 * deriving it from cache contents would reintroduce exactly that dependence. Spotify's remove-by-uri
 * drops every occurrence, so a listener filters by uri membership and lets the row count say how
 * many actually went. [added] is the single appended track ([LibraryCache.appendToPlaylistTrackList]
 * appends one at a time, and only when the cache holds the whole list).
 */
data class TrackListChange(
    val playlistId  : String,
    val removedUris : Set<String>   = emptySet(),
    val added       : SpotifyTrack? = null,
)

data class LibraryCacheData(
    val playlists         : List<SpotifyPlaylist>          = emptyList(),
    // Dead since the featured-playlists endpoint stopped returning editorial content (removed
    // 2026-07); kept so Gson still reads cache files written by older versions. Never written.
    val featuredPlaylists : List<SpotifyPlaylist>          = emptyList(),
    val likedSongCount    : Int                            = 0,
    val user              : SpotifyUser?                   = null,
    val trackLists        : Map<String, CachedTrackList>   = emptyMap(),
    // Nullable: absent in caches written before these existed (Gson Unsafe alloc — see above).
    val forYou            : ForYouCacheData?               = null,
    val savedAlbums       : List<SpotifyAlbum>?            = null,
    val followedArtists   : List<SpotifyArtist>?           = null,
    val followedShows     : List<SpotifyShow>?             = null,
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
     * Emits a [TrackListChange] whenever a playlist's cached track list is surgically changed via
     * [appendToPlaylistTrackList] / [removeFromPlaylistTrackList] — i.e. add/remove from any screen,
     * including the full player and pop-out add-to-playlist sheets. LibraryViewModel collects this
     * to live-refresh that playlist when it's the one currently open, so the change shows without a
     * manual pull-to-refresh. Deliberately NOT emitted by [saveTrackList] (which loading/pagination
     * call), so the Library's own loading doesn't loop.
     *
     * The payload is the edit itself, not just the id: a listener applies those uris to its own rows
     * rather than diffing them against the cached list, so the two drifting apart (a page the UI has
     * that the cache never got) can no longer delete a rendered row. See [TrackListChange].
     */
    private val _trackListChanges = MutableSharedFlow<TrackListChange>(extraBufferCapacity = 8)
    val trackListChanges: SharedFlow<TrackListChange> = _trackListChanges

    /**
     * Emits a playlist id whenever that playlist was **mutated in-app** — a track added or removed
     * from any screen — *whether or not* its cached list could be patched. [trackListChanges] is
     * about the cached ROWS and stays silent when the patch is skipped (nothing cached, a partial
     * page, the track already present); this one is about the playlist having CHANGED SIZE
     * server-side, which is true either way.
     *
     * `LibraryViewModel` answers it with one authoritative `limit=1` re-read of the server total —
     * the single source of truth for every "N songs" the app shows. Local ±deltas are only the
     * interim value the rows already reflect; they are never the last word.
     */
    private val _playlistMutations = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val playlistMutations: SharedFlow<String> = _playlistMutations

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Wholesale replace. Prefer a patching writer — [saveLibraryMeta], [saveCollections],
     * [saveForYou], [saveTrackList]: a `load()` … `save()` pair spans TWO lock acquisitions, so any
     * surgical edit landing between them (a page append, an add/remove) is silently reverted to
     * whatever the read saw.
     */
    fun save(data: LibraryCacheData) = synchronized(lock) { saveLocked(data) }

    fun load(): LibraryCacheData? = synchronized(lock) { loadLocked() }

    /**
     * Patches ONLY the library metadata — the playlist list, the liked-song count and the user —
     * inside ONE lock acquisition, leaving track lists, the For-you band and the Albums/Artists/
     * Shows lists exactly as they are on disk.
     *
     * This is what `loadLibrary`/`refreshLibrary` must use. Reading the cache and saving a rebuilt
     * [LibraryCacheData] back was two separate lock acquisitions, so a `saveTrackList` page append
     * or a surgical add/remove that landed in between was reverted to the stale map — the UI then
     * held rows the cache did not, which is the drift [TrackListChange] exists to survive. It also
     * preserves the legacy `featuredPlaylists` field, which the rebuilt-object form dropped on
     * every refresh.
     */
    fun saveLibraryMeta(
        playlists      : List<SpotifyPlaylist>,
        likedSongCount : Int,
        user           : SpotifyUser?,
    ) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            saveLocked(current.copy(
                playlists      = playlists,
                likedSongCount = likedSongCount,
                user           = user,
            ))
        }
    }

    /** Persists just the For-you band (jump-back-in + top tracks), leaving the rest untouched. */
    fun saveForYou(forYou: ForYouCacheData) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            saveLocked(current.copy(forYou = forYou))
        }
    }

    /**
     * Persists the Albums/Artists/Shows filter content, leaving the rest untouched.
     *
     * A `null` argument means "leave that list exactly as it is", and each of the three is
     * independent: a paginated sweep that died part-way must not overwrite a COMPLETE cached list
     * with its truncated prefix, and a leg that was never fetched this session must not blank one
     * (`LibraryViewModel.loadCollections` passes null for every leg it didn't complete).
     */
    fun saveCollections(
        savedAlbums     : List<SpotifyAlbum>?  = null,
        followedArtists : List<SpotifyArtist>? = null,
        followedShows   : List<SpotifyShow>?   = null,
    ) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            saveLocked(current.copy(
                savedAlbums     = savedAlbums     ?: current.savedAlbums,
                followedArtists = followedArtists ?: current.followedArtists,
                followedShows   = followedShows   ?: current.followedShows,
            ))
        }
    }

    // ── Surgical collection updates (album save / artist follow toggles on the detail screens).
    //    Ping `revision` so LibraryViewModel re-syncs its Albums/Artists filter lists.

    fun addSavedAlbum(album: SpotifyAlbum) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            if (current.savedAlbums.orEmpty().any { it.id == album.id }) return
            saveLocked(current.copy(savedAlbums = listOf(album) + current.savedAlbums.orEmpty()))
            _revision.value++
        }
    }

    fun removeSavedAlbum(albumId: String) {
        synchronized(lock) {
            val current = loadLocked() ?: return
            if (current.savedAlbums.orEmpty().none { it.id == albumId }) return
            saveLocked(current.copy(savedAlbums = current.savedAlbums.orEmpty().filterNot { it.id == albumId }))
            _revision.value++
        }
    }

    fun addFollowedArtist(artist: SpotifyArtist) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            if (current.followedArtists.orEmpty().any { it.id == artist.id }) return
            saveLocked(current.copy(followedArtists = listOf(artist) + current.followedArtists.orEmpty()))
            _revision.value++
        }
    }

    fun removeFollowedArtist(artistId: String) {
        synchronized(lock) {
            val current = loadLocked() ?: return
            if (current.followedArtists.orEmpty().none { it.id == artistId }) return
            saveLocked(current.copy(followedArtists = current.followedArtists.orEmpty().filterNot { it.id == artistId }))
            _revision.value++
        }
    }

    fun addFollowedShow(show: SpotifyShow) {
        val id = show.id ?: return
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            if (current.followedShows.orEmpty().any { it.id == id }) return
            saveLocked(current.copy(followedShows = listOf(show) + current.followedShows.orEmpty()))
            _revision.value++
        }
    }

    fun removeFollowedShow(showId: String) {
        synchronized(lock) {
            val current = loadLocked() ?: return
            if (current.followedShows.orEmpty().none { it.id == showId }) return
            saveLocked(current.copy(followedShows = current.followedShows.orEmpty().filterNot { it.id == showId }))
            _revision.value++
        }
    }

    /**
     * Persists a track list. [rawOffset] is the API offset the next page starts at — see
     * [CachedTrackList]. **Every playlist caller must pass it**; omitting it stores `null`, which
     * costs the next open its de-duplication guard. The Liked-Songs callers omit it deliberately.
     */
    fun saveTrackList(
        playlistId : String,
        snapshotId : String,
        tracks     : List<SpotifyTrack>,
        rawOffset  : Int? = null,
    ) {
        synchronized(lock) {
            val current = loadLocked() ?: LibraryCacheData()
            saveLocked(current.copy(
                trackLists = current.trackLists + (playlistId to CachedTrackList(snapshotId, tracks, rawOffset)),
            ))
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

    /**
     * The result of [appendToLikedSongs]: the tracks that were genuinely new (not already held, by
     * id, in list order) and the row count after the write.
     */
    data class LikedSongsAppend(val added: List<SpotifyTrack>, val rowCount: Int)

    /**
     * Atomically appends a fetched page to the Liked Songs list under ONE lock — the shared
     * `LikedSongsIndexer`'s writer (2026-09-21). Reads the CURRENT list inside the lock, so a prepend
     * (a like, the iPod's reconcile) landing between the caller's earlier read and this write is
     * kept, not reverted — the `load()` … `saveTrackList(cached + page)` pair the foreground
     * service used to do had exactly that window. De-duplicates by id (heals an overlap when the
     * seed offset started below the true raw position). Purely additive: `snapshotId` and
     * `likedSongCount` are left alone — a backfill has no authority over the total (it was already
     * counted), and writing a stale total would revert a concurrent like's increment.
     * Returns null when there is no liked list to append to.
     */
    fun appendToLikedSongs(tracks: List<SpotifyTrack>): LikedSongsAppend? {
        synchronized(lock) {
            val current  = loadLocked() ?: return null
            val existing = current.trackLists[LIKED_SONGS_KEY] ?: return null
            val held     = existing.tracks.mapTo(HashSet()) { it.id }
            val added    = tracks.filter { it.id !in held }.distinctBy { it.id }
            saveLocked(current.copy(
                trackLists = current.trackLists + (LIKED_SONGS_KEY to
                    CachedTrackList(existing.snapshotId, existing.tracks + added)),
            ))
            return LikedSongsAppend(added, existing.tracks.size + added.size)
        }
    }

    /**
     * Atomically prepends [tracks] (newest first) to the Liked Songs list under ONE lock — the iPod's
     * reconcile writer (2026-09-21), the counterpart of [appendToLikedSongs]: a page the indexer
     * appended between the caller's read and this write survives. De-duplicates by id, keeping the
     * FIRST occurrence (so a re-like already held moves nowhere and the fresh copy is dropped).
     * [total] is the server's count: it becomes the `snapshotId` AND `likedSongCount`, as the
     * single-track [prependToLikedSongs] does. Returns the merged list as written.
     */
    fun prependToLikedSongs(tracks: List<SpotifyTrack>, total: Int): List<SpotifyTrack> {
        synchronized(lock) {
            val current  = loadLocked() ?: LibraryCacheData()
            val existing = current.trackLists[LIKED_SONGS_KEY]?.tracks ?: emptyList()
            val merged   = (tracks + existing).distinctBy { it.id }
            saveLocked(current.copy(
                likedSongCount = total,
                trackLists     = current.trackLists + (LIKED_SONGS_KEY to
                    CachedTrackList(total.toString(), merged)),
            ))
            return merged
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
     * Announces an in-app add/remove on [playlistId] — see [playlistMutations]. Both surgical track
     * methods call it themselves; call it directly only for a successful mutation whose cached-list
     * patch was skipped before it could (e.g. an add whose full track object wasn't available).
     */
    fun notePlaylistMutated(playlistId: String) {
        _playlistMutations.tryEmit(playlistId)
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
        // Outside the lock and BEFORE every early return: the playlist grew server-side even when
        // the cached list can't take the new row, and the count reconcile must hear about it.
        notePlaylistMutated(playlistId)
        synchronized(lock) {
            val current  = loadLocked() ?: return
            val existing = current.trackLists[playlistId] ?: return
            if (existing.tracks.any { it.id == track.id }) return
            if (existing.tracks.size < knownTotal) return
            val newTracks = existing.tracks + track
            saveLocked(current.copy(
                // +1 raw item as well: the guard above means the cache holds the whole list, so the
                // boundary sits at the end and the new row moves it by exactly one.
                trackLists = current.trackLists + (playlistId to
                    CachedTrackList(existing.snapshotId, newTracks, existing.rawOffset?.plus(1))),
                // Keep the My Playlists metadata count in lockstep with the cached list. The guard
                // above guarantees the cache holds the full list, so newTracks.size IS the new
                // authoritative total — an absolute value, never a ±1 delta (which would compound any
                // pre-existing drift). Fixes the left-pane count lagging the header after an in-app add.
                playlists  = current.playlists.withTrackCount(playlistId, newTracks.size),
            ))
            // Carries the appended track, and only from HERE — past the completeness guard above.
            // A partially-paged cache stays silent on purpose: the open list is a prefix, so an
            // "appended" row would land hundreds of rows before its real position and the next page
            // would render it a second time. It simply loads with that page instead.
            _trackListChanges.tryEmit(TrackListChange(playlistId, added = track))
            _revision.value++                        // refresh the My Playlists list count
        }
    }

    /** Removes a track from a playlist's cached track list if present (keeps the snapshot id). */
    fun removeFromPlaylistTrackList(playlistId: String, trackUri: String) =
        removeFromPlaylistTrackList(playlistId, listOf(trackUri))

    /**
     * Batch form of [removeFromPlaylistTrackList], for the Library's multi-select removal: the whole
     * set is dropped in ONE cache load/save and ONE [trackListChanges] emission. Not just a
     * convenience — per-uri calls would re-read and re-write the whole cache file N times, and
     * [trackListChanges] is a `tryEmit` on a bounded buffer, so a large selection would start
     * dropping notifications.
     */
    fun removeFromPlaylistTrackList(playlistId: String, trackUris: Collection<String>) {
        // Outside the lock and BEFORE every early return (see appendToPlaylistTrackList): the
        // removal already happened server-side even for a playlist with no cached track list.
        notePlaylistMutated(playlistId)
        synchronized(lock) {
            val current  = loadLocked() ?: return
            val existing = current.trackLists[playlistId] ?: return
            val removing  = trackUris.toSet()
            val newTracks = existing.tracks.filterNot { it.uri in removing }
            // The count delta, not `removing.size` — a uri can appear more than once in a playlist
            // (and Spotify's remove-by-uri drops every occurrence), and one that isn't cached at all
            // must not decrement anything.
            val removed   = existing.tracks.size - newTracks.size
            if (removed == 0) return
            // Mirror the metadata count to the cache only when the cache held the full list; for a
            // partial cache its size isn't the total, so best-effort decrement. Either way this is
            // the INTERIM value — the [playlistMutations] reconcile overwrites it with the server's
            // total a moment later. (Note the full-list test is `cached rows >= metadata`: rows the
            // client filters out — unplayable / local / episode — make the cached list legitimately
            // SHORTER than the total, which is why its size must not be treated as one.)
            val metaNow  = current.playlists.firstOrNull { it.id == playlistId }?.trackCount ?: 0
            val newTotal = if (existing.tracks.size >= metaNow) newTracks.size
                           else (metaNow - removed).coerceAtLeast(0)
            // The removed rows are real API items, so the boundary moves back by the same count —
            // floored at the rows we still hold, since an offset below them would re-fetch rows that
            // are already on screen. Left null for a legacy entry: it stays "unknown", it does not
            // become a guess.
            val newRawOffset = existing.rawOffset?.let { (it - removed).coerceAtLeast(newTracks.size) }
            saveLocked(current.copy(
                trackLists = current.trackLists + (playlistId to
                    CachedTrackList(existing.snapshotId, newTracks, newRawOffset)),
                playlists  = current.playlists.withTrackCount(playlistId, newTotal),
            ))
            // The REQUESTED set, not `existing.tracks.filter { … }`: the listener applies it to its
            // own rows, and deriving the payload from cache contents would make it depend on the
            // cache and the UI holding the same rows — the very assumption this signal drops.
            _trackListChanges.tryEmit(TrackListChange(playlistId, removedUris = removing))
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
