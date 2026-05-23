package com.crsmthw.lyra.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.remote.model.SearchResponse
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SearchUiState(
    val query     : String         = "",
    val results   : SearchResponse? = null,
    val isLoading : Boolean        = false,
    val error     : String?        = null,
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val repository: SpotifyRepository) : ViewModel() {

    private val _query   = MutableStateFlow("")
    private val _state   = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _state

    init {
        // Debounce search input: wait 400 ms after last keystroke before hitting API
        _query
            .debounce(400L)
            .filter { it.isNotBlank() }
            .distinctUntilChanged()
            .onEach { doSearch(it) }
            .launchIn(viewModelScope)
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
        SearchViewModel(container.spotifyRepository) as T
}
