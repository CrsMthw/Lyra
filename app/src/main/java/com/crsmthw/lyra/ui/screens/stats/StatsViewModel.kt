package com.crsmthw.lyra.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.components.TrackActionsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The three ranges `GET /me/top/{type}` supports. */
enum class StatsTimeRange(val apiValue: String) {
    SHORT("short_term"),     // ~4 weeks
    MEDIUM("medium_term"),   // ~6 months
    LONG("long_term"),       // all time
}

data class StatsRangeData(
    val topArtists : List<SpotifyArtist> = emptyList(),
    val topTracks  : List<SpotifyTrack>  = emptyList(),
)

data class StatsUiState(
    val range     : StatsTimeRange = StatsTimeRange.SHORT,
    val data      : Map<StatsTimeRange, StatsRangeData> = emptyMap(),
    val isLoading : Boolean = false,
    val error     : String? = null,
) {
    val current: StatsRangeData get() = data[range] ?: StatsRangeData()
    /** The visible range has no data yet and a load is running → show the loader. */
    val showLoading: Boolean get() = isLoading && range !in data
}

/**
 * "Wrapped-lite": top artists + tracks per time range from `/me/top/{type}`. Each range is
 * fetched once per screen lifetime and kept in [StatsUiState.data]; switching ranges is instant
 * after the first visit.
 */
class StatsViewModel(
    private val repository : SpotifyRepository,
    libraryCache           : LibraryCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState

    /** Backs the song touch-and-hold menu on top-track rows. */
    val trackActions = TrackActionsController(repository, libraryCache, viewModelScope)

    private val inFlight = mutableSetOf<StatsTimeRange>()

    init { load(StatsTimeRange.SHORT) }

    fun setRange(range: StatsTimeRange) {
        _uiState.update { it.copy(range = range) }
        load(range)
    }

    private fun load(range: StatsTimeRange) {
        if (range in _uiState.value.data || range in inFlight) return
        inFlight += range
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val artists = repository.getTopArtists(range.apiValue, limit = 10).getOrNull()?.items
            repository.getTopTracks(range.apiValue, limit = 20).fold(
                onSuccess = { page ->
                    _uiState.update { s -> s.copy(
                        data      = s.data + (range to StatsRangeData(artists.orEmpty(), page.items)),
                        isLoading = false,
                    ) }
                },
                onFailure = { e ->
                    if (artists != null) {
                        // Artists arrived, tracks didn't — show what we have rather than an error.
                        _uiState.update { s -> s.copy(
                            data      = s.data + (range to StatsRangeData(artists, emptyList())),
                            isLoading = false,
                        ) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = e.message) }
                    }
                },
            )
            inFlight -= range
        }
    }

    /** Retries the visible range after a failure. */
    fun retry() = load(_uiState.value.range)
}

class StatsViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        StatsViewModel(
            container.spotifyRepository,
            container.libraryCache,
        ) as T
}
