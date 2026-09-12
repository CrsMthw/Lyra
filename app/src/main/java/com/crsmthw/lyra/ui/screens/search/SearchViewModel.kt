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

/**
 * Which result type the Search screen is showing. Each tab is its own vertical list with its own
 * scroll position and its own paging cursor — results used to be one list of stacked sections, and
 * paging tracks pushed the Albums section further out of reach with every page.
 */
enum class SearchTab { TRACKS, ALBUMS, ARTISTS }

/** The `type` value a per-type page request sends for this tab. */
private val SearchTab.apiType: String
    get() = when (this) {
        SearchTab.TRACKS  -> "track"
        SearchTab.ALBUMS  -> "album"
        SearchTab.ARTISTS -> "artist"
    }

/**
 * One tab's paging cursor and flags. The three are fully independent: a page in flight for one type
 * neither blocks nor is blocked by the others, and a type that runs out parks only its own tab.
 */
data class TabPaging(
    /** Offset the NEXT page of this type is requested at. Only ever advanced on success. */
    val nextOffset   : Int     = SEARCH_PAGE_SIZE,
    /** This type still has a `next`, so its list can still grow. */
    val canLoadMore  : Boolean = false,
    /** A page of this type is in flight *underneath* the live list — it never swaps it out. */
    val isLoadingMore: Boolean = false,
    /**
     * The last page of this type failed, so its paging is parked. Renders as an inline retry row at
     * the end of that tab's list — never as [SearchUiState.error], which is a full-screen state
     * that would replace the results. Mutually exclusive with [isLoadingMore], and always paired
     * with `canLoadMore = false`.
     */
    val pagingFailed : Boolean = false,
)

data class SearchUiState(
    val query    : String                   = "",
    val results  : SearchResponse?          = null,
    /** Result type on screen. Not persisted across process death — a fresh search starts on Tracks. */
    val tab      : SearchTab                = SearchTab.TRACKS,
    val paging   : Map<SearchTab, TabPaging> = emptyMap(),
    val isLoading: Boolean                  = false,
    val error    : String?                  = null,
) {
    /** Paging state for one tab; an absent entry means "nothing fetched yet", not "exhausted". */
    fun pagingFor(tab: SearchTab): TabPaging = paging[tab] ?: TabPaging()
}

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
     *
     * A **tab switch is deliberately not an identity change**: a page already in flight for type X
     * still belongs in X's list even if the user has moved on to another tab, so [loadMore] keys
     * its request by type and writes back by type, never by "whatever tab is showing now".
     */
    private var searchEpoch = 0

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

    /**
     * Switches the visible result type. Purely a view change — it starts no request and cancels
     * none, so anything already in flight lands in the tab it was fetched for.
     */
    fun selectTab(tab: SearchTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
    }

    fun onQueryChange(q: String) {
        _query.value = q
        _state.update { it.copy(query = q) }
        // A blank field ends the current search outright: the debounce filters blanks out, so
        // nothing else would ever land to clear a spinner or drop a stale page.
        if (q.isBlank()) {
            searchEpoch++
            resultsQuery = ""
            _state.update {
                it.copy(results = null, tab = SearchTab.TRACKS, paging = emptyMap(),
                        isLoading = false, error = null)
            }
        }
    }

    private fun doSearch(query: String) {
        val epoch = ++searchEpoch
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, paging = emptyMap(), error = null) }
            // The first page is ONE call for all three types at offset 0, so every tab is filled in
            // a single round trip and switching between them is instant. Only pages after this one
            // are per-type.
            repository.search(query, offset = 0).fold(
                onSuccess = { results ->
                    if (epoch != searchEpoch) return@fold
                    resultsQuery = query
                    _state.update {
                        it.copy(
                            results   = results,
                            isLoading = false,
                            // REPLACES the map rather than merging into it: a `pagingFailed` or
                            // `isLoadingMore` left behind by the previous query (whose in-flight
                            // response the epoch guard dropped without clearing its flag) would
                            // otherwise survive into these results and park that tab's paging
                            // before the user ever reached its bottom.
                            paging    = SearchTab.entries.associateWith { tab -> results.firstPage(tab) },
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
     * Appends the next page of ONE type to that type's list — the lazy-load-on-scroll path.
     *
     * Deliberately touches **only** that tab's [TabPaging]: the screen renders `isLoading` and
     * `error` as full-screen states that *replace* the lists, so writing either here would yank the
     * results out from under a scrolling user. A page that fails (including the endpoint refusing
     * an offset past its ceiling) ends that tab's paging and raises its `pagingFailed`, which the
     * screen shows as an inline retry row — [retryLoadMore] is the ONLY thing that re-arms
     * `canLoadMore`, so the automatic scroll trigger cannot refire on its own.
     *
     * [tab] is the type being paged, passed in by the list that asked for it — NOT read from
     * `state.tab`. The user can switch tabs while this is in flight and the page still lands where
     * it belongs.
     */
    fun loadMore(tab: SearchTab) {
        val current = _state.value
        if (current.isLoading || current.results == null) return
        val paging = current.pagingFor(tab)
        if (paging.isLoadingMore || !paging.canLoadMore) return
        val query  = resultsQuery.takeIf { it.isNotBlank() } ?: return
        val epoch  = searchEpoch
        val offset = paging.nextOffset
        if (offset > MAX_SEARCH_OFFSET) {
            _state.update { it.withPaging(tab) { p -> p.copy(canLoadMore = false) } }
            return
        }
        viewModelScope.launch {
            _state.update { it.withPaging(tab) { p -> p.copy(isLoadingMore = true) } }
            repository.search(query, type = tab.apiType, offset = offset).fold(
                onSuccess = { page ->
                    if (epoch != searchEpoch) return@fold
                    _state.update { s ->
                        // Guard on this TYPE's own offset: the epoch alone can't tell a duplicate
                        // in-flight page for the same query+type from the one this response belongs
                        // to. Everything is computed inside the update so a page landing for
                        // another type in the same frame can't be clobbered.
                        val before = s.results
                        if (before == null || offset != s.pagingFor(tab).nextOffset) return@update s
                        val merged     = before.appendPage(tab, page)
                        val nextOffset = offset + SEARCH_PAGE_SIZE
                        s.copy(results = merged).withPaging(tab) {
                            it.copy(
                                nextOffset    = nextOffset,
                                isLoadingMore = false,
                                // A page that added nothing new to THIS type would leave its scroll
                                // trigger satisfied forever, so treat "nothing new" as its end.
                                canLoadMore   = merged.bucket(tab)?.next != null &&
                                                merged.count(tab) > before.count(tab) &&
                                                nextOffset <= MAX_SEARCH_OFFSET,
                            )
                        }
                    }
                },
                onFailure = {
                    if (epoch != searchEpoch) return@fold
                    _state.update {
                        it.withPaging(tab) { p ->
                            p.copy(isLoadingMore = false, canLoadMore = false, pagingFailed = true)
                        }
                    }
                },
            )
        }
    }

    /**
     * Re-arms paging for ONE tab after a failed page — that list's inline retry row. `nextOffset`
     * is only advanced on success, so this re-requests the SAME offset with no arithmetic. The tab
     * comes from the footer that was tapped, so a tab switch between render and tap can't misroute
     * the re-arm.
     *
     * Synchronous on purpose: [loadMore] reads `_state.value` and flips `isLoadingMore` inline, so
     * there is no frame where `canLoadMore` is true while `isLoadingMore` is false for the screen's
     * scroll trigger to fire a duplicate page into.
     */
    fun retryLoadMore(tab: SearchTab) {
        _state.update { it.withPaging(tab) { p -> p.copy(canLoadMore = true, pagingFailed = false) } }
        loadMore(tab)
    }

    fun clearQuery() {
        searchEpoch++
        resultsQuery = ""
        _query.value = ""
        _state.update { SearchUiState() }
    }
}

// ── Per-tab paging helpers ──────────────────────────────────────────────────────

private fun SearchUiState.withPaging(tab: SearchTab, block: (TabPaging) -> TabPaging) =
    copy(paging = paging + (tab to block(pagingFor(tab))))

/** The response bucket a tab renders. */
private fun SearchResponse.bucket(tab: SearchTab): Paged<*>? = when (tab) {
    SearchTab.TRACKS  -> tracks
    SearchTab.ALBUMS  -> albums
    SearchTab.ARTISTS -> artists
}

private fun SearchResponse.count(tab: SearchTab) = bucket(tab)?.items?.size ?: 0

/**
 * Paging state for one tab straight after the all-types first page.
 *
 * The "first page must be non-empty" rule is applied **per type**: an empty-but-`next` bucket would
 * arm paging behind that tab's "No results" message, which composes no list — so its scroll trigger
 * could never fire to page past it.
 */
private fun SearchResponse.firstPage(tab: SearchTab) = TabPaging(
    nextOffset  = SEARCH_PAGE_SIZE,
    canLoadMore = bucket(tab)?.next != null && count(tab) > 0,
)

// ── Page merging ────────────────────────────────────────────────────────────────
//
// Offset paging over a relevance-ranked result set DOES return repeats, and a duplicate key throws
// in a `LazyColumn` — so each append de-dupes against the ids already on screen.

/**
 * Merges a ONE-TYPE page into the bucket it belongs to and leaves the other two untouched.
 *
 * That last part is load-bearing: a `type=track` response carries null `albums`/`artists`, and
 * [appendItems]' "a missing bucket means this type is exhausted" rule — correct for the all-types
 * first page — would null out the other two tabs' `next` and silently end their paging.
 */
private fun SearchResponse.appendPage(tab: SearchTab, page: SearchResponse) = when (tab) {
    SearchTab.TRACKS  -> copy(tracks  = tracks?.appendItems(page.tracks) { it.id }   ?: page.tracks)
    SearchTab.ALBUMS  -> copy(albums  = albums?.appendItems(page.albums) { it.id }   ?: page.albums)
    SearchTab.ARTISTS -> copy(artists = artists?.appendItems(page.artists) { it.id } ?: page.artists)
}

private fun <T> Paged<T>.appendItems(page: Paged<T>?, id: (T) -> String): Paged<T> {
    // No bucket for the type we asked for means the API has nothing further for it: drop its `next`.
    if (page == null) return copy(next = null)
    val seen = items.mapTo(HashSet(items.size)) { id(it) }
    return copy(
        items  = items + page.items.filterNot { id(it) in seen },
        offset = page.offset,
        next   = page.next,
    )
}

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
