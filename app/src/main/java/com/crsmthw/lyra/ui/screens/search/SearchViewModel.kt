package com.crsmthw.lyra.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.local.RecentSearch
import com.crsmthw.lyra.data.remote.model.SearchResponse
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.components.TrackActionsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Most recent tapped results kept on the Search screen. */
private const val MAX_RECENT_SEARCHES = 5

data class SearchUiState(
    val query     : String         = "",
    val results   : SearchResponse? = null,
    val isLoading : Boolean        = false,
    val error     : String?        = null,
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

    init {
        // Debounce search input: wait 400 ms after last keystroke before hitting API
        _query
            .debounce(400L)
            .filter { it.isNotBlank() }
            .distinctUntilChanged()
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
        viewModelScope.launch { withContext(Dispatchers.IO) { libraryCache.saveRecentSearches(updated) } }
    }

    fun onQueryChange(q: String) {
        _query.value = q
        _state.update { it.copy(query = q) }
        if (q.isBlank()) _state.update { it.copy(results = null) }
    }

    private fun doSearch(query: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.search(query).fold(
                onSuccess = { results ->
                    _state.update { it.copy(results = results, isLoading = false) }
                },
                onFailure = { e ->
                    _state.update { it.copy(error = e.message, isLoading = false) }
                },
            )
        }
    }

    fun clearQuery() {
        _query.value = ""
        _state.update { SearchUiState() }
    }
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

fun SpotifyPlaylist.toRecentSearch() =
    RecentSearch("playlist", id, uri, name, owner?.displayName ?: "Playlist", thumbnailUrl.ifBlank { null })
