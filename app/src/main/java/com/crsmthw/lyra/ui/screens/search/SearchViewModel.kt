package com.crsmthw.lyra.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.local.RecentSearch
import com.crsmthw.lyra.data.remote.model.Paged
import com.crsmthw.lyra.data.remote.model.SearchResponse
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SEARCH_PAGE_SIZE
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.components.TrackActionsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Most recent tapped results kept on the Search screen. */
private const val MAX_RECENT_SEARCHES = 10

/**
 * Highest `offset` the Search endpoint accepts. Paging stops here instead of letting the next call
 * come back 400 — though a refused page is harmless either way (see [SearchViewModel.loadMore]).
 */
private const val MAX_SEARCH_OFFSET = 1000 - SEARCH_PAGE_SIZE

data class SearchUiState(
    val query         : String          = "",
    val results       : SearchResponse? = null,
    val isLoading     : Boolean         = false,
    /** A page is in flight *underneath* the live results — it never swaps the list out. */
    val isLoadingMore : Boolean         = false,
    /** At least one type still has a `next`, so the list can still grow. */
    val canLoadMore   : Boolean         = false,
    /**
     * The last page request failed, so paging is parked. Renders as an inline retry row at the end
     * of the list — never as [error], which is a full-screen state that would replace the results.
     * Mutually exclusive with [isLoadingMore], and always paired with `canLoadMore = false`.
     */
    val pagingFailed  : Boolean         = false,
    val error         : String?         = null,
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository  : SpotifyRepository,
    private val libraryCache: LibraryCache,
) : ViewModel() {

    private val _query   = MutableStateFlow("")
    private val _state   = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _state

    /** Last few tapped results, newest first — shown while the query is blank. */
    private val _recentSearches = MutableStateFlow<List<RecentSearch>>(emptyList())
    val recentSearches: StateFlow<List<RecentSearch>> = _recentSearches

    /** Backs the song touch-and-hold menu for search result rows. */
    val trackActions = TrackActionsController(repository, libraryCache, viewModelScope)

    /**
     * Stale guard for every search response. Bumped whenever the search *identity* changes — a new
     * debounced query, a cleared field — so a response that was already in flight can never write
     * over newer state. Cancellation is not enough on its own: `SpotifyRepository.safeCall` wraps
     * the call in `runCatching`, which swallows the `CancellationException` and hands it back as an
     * ordinary `Result.failure`, so a cancelled call still reaches the `onFailure` branch.
     */
    private var searchEpoch = 0

    /** Offset the NEXT page is requested at. One offset covers all three types (see repository). */
    private var nextOffset = 0

    /**
     * Query the results currently on screen were fetched for — what [loadMore] pages. NOT
     * `state.query`, which is the live field text: that runs ahead of the 400 ms debounce, so a
     * scroll to the end during the debounce window would have appended a page of the *new* query's
     * results onto the old query's list.
     */
    private var resultsQuery = ""

    init {
        // Debounce search input: wait 400 ms after last keystroke before hitting API.
        // `distinctUntilChanged` sits BEFORE the blank filter on purpose: a cleared field then
        // consumes the distinct slot, so retyping the *same* query re-searches instead of being
        // dropped against a retained value that survived the clear. (Filtering first left a
        // cleared-then-retyped query with a filled bar over an empty background.) Narrow case that
        // survives: retyping inside the 400 ms window, where `debounce` swallows the blank itself.
        _query
            .debounce(400L)
            .distinctUntilChanged()
            .filter { it.isNotBlank() }
            .onEach { doSearch(it) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            _recentSearches.value = withContext(Dispatchers.IO) { libraryCache.loadRecentSearches() }
        }
    }

    /** Records a tapped result as a recent search: move-to-front, de-duped by id, capped, persisted. */
    fun addRecentSearch(item: RecentSearch) {
        val updated = (listOf(item) + _recentSearches.value.filterNot { it.id == item.id })
            .take(MAX_RECENT_SEARCHES)
        _recentSearches.value = updated
        persistRecentSearches(updated)
    }

    /** Drops one entry from the recent list — its trailing X. De-dupes by the same key as [addRecentSearch]. */
    fun removeRecentSearch(id: String) {
        val updated = _recentSearches.value.filterNot { it.id == id }
        if (updated.size == _recentSearches.value.size) return
        _recentSearches.value = updated
        persistRecentSearches(updated)
    }

    /** Empties the recent list — the "Clear all" affordance at the end of it. */
    fun clearRecentSearches() {
        if (_recentSearches.value.isEmpty()) return
        _recentSearches.value = emptyList()
        persistRecentSearches(emptyList())
    }

    /** Writes the list through to `recent_searches.json`; an empty list persists as an empty array. */
    private fun persistRecentSearches(list: List<RecentSearch>) {
        viewModelScope.launch { withContext(Dispatchers.IO) { libraryCache.saveRecentSearches(list) } }
    }

    fun onQueryChange(q: String) {
        _query.value = q
        _state.update { it.copy(query = q) }
        // A blank field ends the current search outright: the debounce filters blanks out, so
        // nothing else would ever land to clear a spinner or drop a stale page.
        if (q.isBlank()) {
            searchEpoch++
            nextOffset   = 0
            resultsQuery = ""
            _state.update {
                it.copy(results = null, isLoading = false, isLoadingMore = false,
                        canLoadMore = false, pagingFailed = false, error = null)
            }
        }
    }

    private fun doSearch(query: String) {
        val epoch = ++searchEpoch
        viewModelScope.launch {
            _state.update {
                it.copy(isLoading = true, isLoadingMore = false, canLoadMore = false,
                        pagingFailed = false, error = null)
            }
            repository.search(query, offset = 0).fold(
                onSuccess = { results ->
                    if (epoch != searchEpoch) return@fold
                    resultsQuery = query
                    nextOffset   = SEARCH_PAGE_SIZE
                    _state.update {
                        it.copy(
                            results      = results,
                            isLoading    = false,
                            pagingFailed = false,
                            // An all-empty first page is the end of the road whatever `next` says:
                            // the screen renders that as the "No results" state, which composes no
                            // LazyColumn — so the scroll trigger could never fire to page past it.
                            canLoadMore  = results.hasMore() && results.itemCount() > 0,
                        )
                    }
                },
                onFailure = { e ->
                    if (epoch != searchEpoch) return@fold
                    _state.update { it.copy(error = e.message, isLoading = false) }
                },
            )
        }
    }

    /**
     * Appends the next page to the live results — the lazy-load-on-scroll path.
     *
     * Deliberately touches **only** `isLoadingMore` / `canLoadMore` / `pagingFailed`: the screen's
     * `when` renders `isLoading` and `error` as full-screen states that *replace* the list, so
     * writing either here would yank the results out from under a scrolling user. A page that fails
     * (including the endpoint refusing an offset past its ceiling) ends paging and raises
     * `pagingFailed`, which the screen shows as an inline retry row — [retryLoadMore] is the ONLY
     * thing that re-arms `canLoadMore`, so the automatic scroll trigger cannot refire on its own.
     */
    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || !current.canLoadMore) return
        val results = current.results ?: return
        val query   = resultsQuery.takeIf { it.isNotBlank() } ?: return
        val epoch   = searchEpoch
        val offset  = nextOffset
        if (offset > MAX_SEARCH_OFFSET) {
            _state.update { it.copy(canLoadMore = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            repository.search(query, offset = offset).fold(
                onSuccess = { page ->
                    // Guard on the offset too: the epoch alone can't tell a duplicate in-flight
                    // page for the SAME query from the one this response belongs to.
                    if (epoch != searchEpoch || offset != nextOffset) return@fold
                    val merged = results.appendPage(page)
                    nextOffset = offset + SEARCH_PAGE_SIZE
                    _state.update {
                        it.copy(
                            results       = merged,
                            isLoadingMore = false,
                            // A page whose every item was a duplicate would leave the scroll
                            // trigger satisfied forever, so treat "nothing new" as the end.
                            canLoadMore   = merged.hasMore() &&
                                            merged.itemCount() > results.itemCount() &&
                                            nextOffset <= MAX_SEARCH_OFFSET,
                        )
                    }
                },
                onFailure = {
                    if (epoch != searchEpoch) return@fold
                    _state.update {
                        it.copy(isLoadingMore = false, canLoadMore = false, pagingFailed = true)
                    }
                },
            )
        }
    }

    /**
     * Re-arms paging after a failed page — the inline retry row's tap. `nextOffset` is only
     * advanced on success, so this re-requests the SAME offset with no arithmetic.
     *
     * Synchronous on purpose: [loadMore] reads `_state.value` and flips `isLoadingMore` inline, so
     * there is no frame where `canLoadMore` is true while `isLoadingMore` is false for the screen's
     * scroll trigger to fire a duplicate page into.
     */
    fun retryLoadMore() {
        _state.update { it.copy(canLoadMore = true, pagingFailed = false) }
        loadMore()
    }

    fun clearQuery() {
        searchEpoch++
        nextOffset   = 0
        resultsQuery = ""
        _query.value = ""
        _state.update { SearchUiState() }
    }
}

// ── Page merging ────────────────────────────────────────────────────────────────
//
// One `offset` fetches a page of every type at once, so a page is merged per type. Offset paging
// over a relevance-ranked result set DOES return repeats, and a duplicate key throws in a
// `LazyColumn` — so each append de-dupes against the ids already on screen.

private fun SearchResponse.appendPage(page: SearchResponse) = SearchResponse(
    tracks    = tracks?.appendItems(page.tracks)   { it.id } ?: page.tracks,
    albums    = albums?.appendItems(page.albums)   { it.id } ?: page.albums,
    artists   = artists?.appendItems(page.artists) { it.id } ?: page.artists,
    playlists = playlists,   // never requested — the search type is track,album,artist
)

private fun <T> Paged<T>.appendItems(page: Paged<T>?, id: (T) -> String): Paged<T> {
    // No page for this type means the API has nothing further for it: drop its `next`.
    if (page == null) return copy(next = null)
    val seen = items.mapTo(HashSet(items.size)) { id(it) }
    return copy(
        items  = items + page.items.filterNot { id(it) in seen },
        offset = page.offset,
        next   = page.next,
    )
}

/** True while any type still has a further page. */
private fun SearchResponse.hasMore() =
    tracks?.next != null || albums?.next != null || artists?.next != null

private fun SearchResponse.itemCount() =
    (tracks?.items?.size ?: 0) + (albums?.items?.size ?: 0) + (artists?.items?.size ?: 0)

class SearchViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SearchViewModel(container.spotifyRepository, container.libraryCache) as T
}

// ── Result → RecentSearch mappers ───────────────────────────────────────────────

fun SpotifyTrack.toRecentSearch() =
    RecentSearch("track", id, uri, name, allArtists, thumbnailUrl.ifBlank { null })

fun SpotifyAlbum.toRecentSearch() =
    RecentSearch(
        type     = "album",
        id       = id,
        uri      = "",
        name     = name,
        subtitle = artists?.joinToString(", ") { it.name }?.ifBlank { null } ?: "Album",
        imageUrl = images?.firstOrNull()?.url,
    )

fun SpotifyArtist.toRecentSearch() =
    RecentSearch("artist", id, "", name, "Artist", images?.firstOrNull()?.url)

fun SpotifyPlaylist.toRecentSearch() =
    RecentSearch("playlist", id, uri, name, owner?.displayName ?: "Playlist", thumbnailUrl.ifBlank { null })
