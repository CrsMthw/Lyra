package com.crsmthw.lyra.ui.screens.show

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.auth.SpotifyAuthManager
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.remote.model.ShowPage
import com.crsmthw.lyra.data.remote.model.SpotifyEpisode
import com.crsmthw.lyra.data.remote.model.SpotifyShow
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One episodes page. The endpoint caps `limit` at 50. */
private const val EPISODE_PAGE_SIZE = 50

/**
 * The legacy magic market value `getArtistAlbums` has always sent. Only used as a ONE-SHOT retry —
 * see [ShowDetailViewModel.fetchEpisodePage].
 */
private const val EPISODE_MARKET_FALLBACK = "from_token"

private const val TAG = "ShowDetailVM"

data class ShowDetailUiState(
    val show          : SpotifyShow?         = null,
    val episodes      : List<SpotifyEpisode> = emptyList(),
    val isLoading     : Boolean              = true,
    val isLoadingMore : Boolean              = false,
    val canLoadMore   : Boolean              = false,
    val error         : String?              = null,
    /** null = still resolving; the heart is disabled only for that first moment. */
    val isFollowed    : Boolean?             = null,
    /**
     * The stored grant does NOT carry `user-read-playback-position`, so no episode can arrive with
     * a resume point and every row must read as untouched. True for any session authorized before
     * that scope was requested (a refresh never widens a grant); the screen turns it into one
     * quiet line above the episodes rather than silently showing nothing.
     */
    val resumeScopeMissing: Boolean          = false,
)

class ShowDetailViewModel(
    private val repository  : SpotifyRepository,
    private val libraryCache: LibraryCache,
    authManager             : SpotifyAuthManager,
    private val showId      : String,
) : ViewModel() {

    // The grant is read ONCE, here: it can only change through a re-authorization, which leaves
    // this screen entirely (Auth → Library), so the next show opened gets a new ViewModel and with
    // it the new answer. Nothing to observe.
    private val _state = MutableStateFlow(
        ShowDetailUiState(
            resumeScopeMissing =
                !authManager.hasScope(SpotifyAuthManager.SCOPE_READ_PLAYBACK_POSITION),
        )
    )
    val uiState: StateFlow<ShowDetailUiState> = _state

    private val showUri = "spotify:show:$showId"

    /**
     * The API offset the next episodes page starts at — `ShowPage.rawCount`, the count of RAW slots
     * received, never `episodes.size`. They are genuinely different numbers: an episode that is
     * unavailable or removed in the user's market arrives as a `null` slot in the items array and
     * is dropped on the way in (see [ShowPage]), so paging by the rendered size would re-request
     * those slots and duplicate every row after them at each page boundary.
     */
    private var nextOffset = 0

    init {
        load()
        viewModelScope.launch {
            // Seed from the cached followed-shows list first (kept current by the Shows filter's
            // full fetch and by this screen's own toggle) so the heart is usable even when the
            // status call fails. Mirrors ArtistDetailViewModel exactly.
            val cachedFollowed = withContext(Dispatchers.IO) {
                libraryCache.load()?.followedShows?.any { it.id == showId }
            }
            if (cachedFollowed == true) {
                _state.update { it.copy(isFollowed = true) }
                return@launch
            }
            repository.isInLibrary(showUri).fold(
                onSuccess = { followed -> _state.update { it.copy(isFollowed = followed) } },
                onFailure = { e ->
                    // Never leave the heart stuck disabled (isFollowed == null): assume not
                    // followed — the first tap then PUTs, which is correct either way.
                    Log.w(TAG, "me/library/contains failed for $showUri", e)
                    _state.update { it.copy(isFollowed = false) }
                },
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.getShow(showId).fold(
                onSuccess = { show ->
                    // `GET shows/{id}` embeds the first page of episodes, so the common case
                    // paints from ONE round trip. Only fall through to the dedicated episodes
                    // endpoint when that embedded page is absent or empty — which is also where
                    // the market retry lives. An embedded page whose slots are ALL null counts as
                    // empty here, so such a show falls through to the retry instead of painting a
                    // permanently empty list.
                    val embeddedPage = show.episodes
                    val embedded     = embeddedPage?.items.orEmpty()
                    if (embeddedPage != null && embedded.isNotEmpty()) {
                        nextOffset = embeddedPage.rawCount
                        _state.update { it.copy(
                            show        = show,
                            episodes    = embedded,
                            isLoading   = false,
                            canLoadMore = nextOffset < totalEpisodes(show, embeddedPage),
                        ) }
                    } else {
                        _state.update { it.copy(show = show) }
                        fetchEpisodePage(0).fold(
                            onSuccess = { page ->
                                val items = page.items.orEmpty()
                                nextOffset = page.rawCount
                                _state.update { it.copy(
                                    episodes    = items,
                                    isLoading   = false,
                                    // Gate on SLOTS, not rendered rows: a first page that was all
                                    // unavailable still has more pages behind it.
                                    canLoadMore = page.rawCount > 0 && nextOffset < totalEpisodes(show, page),
                                ) }
                            },
                            onFailure = { e ->
                                // The show itself loaded, so this is not a blocking error: the
                                // hero still renders and the list shows its empty state.
                                Log.w(TAG, "episodes page 0 failed for $showId", e)
                                _state.update { it.copy(isLoading = false) }
                            },
                        )
                    }
                },
                onFailure = { e -> _state.update { it.copy(error = e.message, isLoading = false) } },
            )
        }
    }

    fun loadMoreEpisodes() {
        val s = _state.value
        if (!s.canLoadMore || s.isLoadingMore || s.isLoading) return
        val offset = nextOffset
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            fetchEpisodePage(offset).fold(
                onSuccess = { page ->
                    val items = page.items.orEmpty()
                    nextOffset = offset + page.rawCount
                    _state.update { cur ->
                        // De-dupe by id: a page fetched at an offset the server has since shifted
                        // can repeat a row, and a duplicate LazyColumn key is a hard crash.
                        val seen   = cur.episodes.mapNotNullTo(HashSet<String>()) { it.id }
                        val fresh  = items.filter { ep -> ep.id?.let(seen::add) == true }
                        cur.copy(
                            episodes      = cur.episodes + fresh,
                            isLoadingMore = false,
                            // Paging ends on a page with no SLOTS at all — not on one that added no
                            // rows. A mid-feed page can be entirely nulls (all unavailable in this
                            // market) or entirely duplicates, and stopping there would strand the
                            // rest of the feed. `nextOffset` advanced past those slots, so the next
                            // request is a genuinely new page and the `total` bound still ends it.
                            canLoadMore   = page.rawCount > 0 &&
                                            nextOffset < totalEpisodes(cur.show, page),
                        )
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "episodes page at offset $offset failed for $showId", e)
                    // Park paging rather than retry in a loop; a re-open re-arms it.
                    _state.update { it.copy(isLoadingMore = false, canLoadMore = false) }
                },
            )
        }
    }

    /**
     * One episodes page, with the documented one-shot `market=from_token` retry.
     *
     * "If neither market nor user country are provided, the content is considered unavailable for
     * the client" — so a 200 with zero items is ambiguous. The FIRST page therefore always gets
     * its one retry, including (especially) when the response reports `total: 0` or omits `total`
     * entirely: that is exactly what "considered unavailable" looks like, so gating page 0 on a
     * non-zero total would skip the one case the retry exists for. Later pages are gated on
     * `offset < total`, because an EXHAUSTED page (offset == total) answers empty perfectly
     * legitimately and retrying it would double every last page's traffic.
     *
     * "Empty" here is [ShowPage.items] — the NULL-FREE view. A page of nothing but `null` slots is
     * the same unavailable-in-this-market symptom wearing a different shape, so it counts as empty
     * and gets the retry; only [ShowPage.rawCount] (used by the callers for paging) still sees the
     * slots.
     *
     * The retry's own failure is swallowed and the original 2xx page returned, so a market the
     * modern API rejects with a 400 can never turn a good empty page into an error.
     *
     * This lives here rather than in the repository so every repository method stays 1:1 with one
     * request — which is also what keeps the TEMPORARY spike's "(no market)" leg honest.
     */
    private suspend fun fetchEpisodePage(offset: Int): Result<ShowPage<SpotifyEpisode>> {
        val result = repository.getShowEpisodes(showId, limit = EPISODE_PAGE_SIZE, offset = offset)
        val page   = result.getOrNull() ?: return result
        if (!page.items.isNullOrEmpty()) return result
        if (offset > 0 && offset >= (page.total ?: 0)) return result
        val retried = repository.getShowEpisodes(
            showId, limit = EPISODE_PAGE_SIZE, offset = offset, market = EPISODE_MARKET_FALLBACK,
        ).getOrNull()
        return if (retried != null && !retried.items.isNullOrEmpty()) Result.success(retried)
               else result
    }

    /**
     * Optimistic follow/unfollow through the unified `me/library` with the show uri — the same
     * path albums and artists take, since PUT/DELETE `me/shows` are deprecated. Keeps the Library
     * Shows filter in sync surgically.
     */
    fun toggleFollowed() {
        val followed = _state.value.isFollowed ?: return
        _state.update { it.copy(isFollowed = !followed) }
        viewModelScope.launch {
            val result = if (followed) repository.removeFromLibrary(showUri)
                         else repository.saveToLibrary(showUri)
            result.fold(
                onSuccess = {
                    withContext(Dispatchers.IO) {
                        if (followed) {
                            libraryCache.removeFollowedShow(showId)
                        } else {
                            // Cache the show WITHOUT its embedded episodes page: the cache file is
                            // read whole on every library paint, and 50 episode objects per show
                            // would bloat it for data the Shows list never renders.
                            _state.value.show?.let { libraryCache.addFollowedShow(it.copy(episodes = null)) }
                        }
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "follow toggle failed for $showUri", e)
                    _state.update { it.copy(isFollowed = followed) }   // revert
                },
            )
        }
    }
}

/**
 * The show's episode count. `total_episodes` on the show object is authoritative; a page's own
 * `total` is the fallback for a show object that omitted it. 0 when neither answers, which reads
 * as "nothing more to page" — safe, because paging also stops on an empty page.
 */
private fun totalEpisodes(show: SpotifyShow?, page: ShowPage<SpotifyEpisode>?): Int =
    show?.totalEpisodes ?: page?.total ?: 0

class ShowDetailViewModelFactory(
    private val container: AppContainer,
    private val showId   : String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ShowDetailViewModel(
            container.spotifyRepository,
            container.libraryCache,
            container.authManager,
            showId,
        ) as T
}
