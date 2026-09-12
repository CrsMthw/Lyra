package com.crsmthw.lyra.ui.screens.show

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
)

class ShowDetailViewModel(
    private val repository  : SpotifyRepository,
    private val libraryCache: LibraryCache,
    private val showId      : String,
) : ViewModel() {

    private val _state = MutableStateFlow(ShowDetailUiState())
    val uiState: StateFlow<ShowDetailUiState> = _state

    private val showUri = "spotify:show:$showId"

    /**
     * The API offset the next episodes page starts at — the count of RAW items received, not
     * `episodes.size`. They happen to be equal today (nothing is filtered out of an episode page,
     * unlike track lists, which drop unplayable rows), but keeping them separate means a future
     * filter can't silently re-fetch and duplicate a row at each page boundary.
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
                    // the market retry lives.
                    val embedded = show.episodes?.items.orEmpty()
                    if (embedded.isNotEmpty()) {
                        nextOffset = embedded.size
                        _state.update { it.copy(
                            show        = show,
                            episodes    = embedded,
                            isLoading   = false,
                            canLoadMore = nextOffset < totalEpisodes(show, show.episodes),
                        ) }
                    } else {
                        _state.update { it.copy(show = show) }
                        fetchEpisodePage(0).fold(
                            onSuccess = { page ->
                                val items = page.items.orEmpty()
                                nextOffset = items.size
                                _state.update { it.copy(
                                    episodes    = items,
                                    isLoading   = false,
                                    canLoadMore = items.isNotEmpty() && nextOffset < totalEpisodes(show, page),
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
                    nextOffset = offset + items.size
                    _state.update { cur ->
                        // De-dupe by id: a page fetched at an offset the server has since shifted
                        // can repeat a row, and a duplicate LazyColumn key is a hard crash.
                        val seen   = cur.episodes.mapNotNullTo(HashSet<String>()) { it.id }
                        val fresh  = items.filter { ep -> ep.id?.let(seen::add) == true }
                        cur.copy(
                            episodes      = cur.episodes + fresh,
                            isLoadingMore = false,
                            // A page that added nothing new ends paging, however the totals read.
                            canLoadMore   = fresh.isNotEmpty() &&
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
     * the client" — so a 200 with zero items is ambiguous. The retry is gated on
     * `offset < total`, NOT merely on "empty with a non-zero total": an EXHAUSTED page (offset ==
     * total) answers empty perfectly legitimately, and retrying it would double every last page's
     * traffic. The retry's own failure is swallowed and the original 2xx page returned, so a
     * market the modern API rejects with a 400 can never turn a good empty page into an error.
     *
     * This lives here rather than in the repository so every repository method stays 1:1 with one
     * request — which is also what keeps the TEMPORARY spike's "(no market)" leg honest.
     */
    private suspend fun fetchEpisodePage(offset: Int): Result<ShowPage<SpotifyEpisode>> {
        val result = repository.getShowEpisodes(showId, limit = EPISODE_PAGE_SIZE, offset = offset)
        val page   = result.getOrNull() ?: return result
        if (!page.items.isNullOrEmpty()) return result
        if (offset >= (page.total ?: 0)) return result
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
        ShowDetailViewModel(container.spotifyRepository, container.libraryCache, showId) as T
}
