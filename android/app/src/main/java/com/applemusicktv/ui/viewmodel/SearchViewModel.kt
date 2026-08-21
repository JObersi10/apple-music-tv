package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.network.GenreDto
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.data.repository.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query:          String         = "",
    val isLoading:      Boolean        = false,
    val results:        SearchResults? = null,
    val error:          String?        = null,
    val genres:         List<GenreDto> = emptyList(),
    val selectedGenreId: String?       = null,
    val genreContent:   com.applemusicktv.data.network.HomeResponse? = null,
    val genreLoading:   Boolean        = false,
    val categories:     List<com.applemusicktv.data.repository.CategoryGroup> = emptyList(),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val history: com.applemusicktv.data.SearchHistoryPreferences,
) : ViewModel() {

    val recentSearches: StateFlow<List<String>> = history.recent

    private val _state    = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            repo.getCategories().onSuccess { cats -> _state.update { it.copy(categories = cats) } }
        }
        viewModelScope.launch {
            queryFlow
                .debounce(400)
                .filter { it.length >= 2 }
                .distinctUntilChanged()
                .collectLatest { term ->
                    _state.update { it.copy(isLoading = true, error = null) }
                    repo.search(term)
                        .onSuccess  { r ->
                            // Only remember terms that actually found something —
                            // half-typed words would otherwise fill the list.
                            if (r.songs.isNotEmpty() || r.albums.isNotEmpty() || r.artists.isNotEmpty()) {
                                history.add(term)
                            }
                            _state.update { it.copy(isLoading = false, results = r) }
                        }
                        .onFailure  { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
                }
        }
    }

    fun onQueryChange(q: String) {
        // Below the 2-char search threshold, drop the old results too — otherwise
        // clearing the box left the previous song list on screen.
        val tooShort = q.length < 2
        _state.update {
            it.copy(
                query = q, selectedGenreId = null, genreContent = null,
                results = if (tooShort) null else it.results,
                isLoading = if (tooShort) false else it.isLoading,
                error = if (tooShort) null else it.error,
            )
        }
        queryFlow.value = q
    }
    fun runRecent(term: String) { onQueryChange(term) }
    fun removeRecent(term: String) = history.remove(term)
    fun clearRecents() = history.clear()

    fun clearSearch() { _state.value = SearchUiState(categories = _state.value.categories); queryFlow.value = "" }
    fun selectGenre(id: String) {
        _state.update { it.copy(selectedGenreId = id, genreLoading = true, genreContent = null) }
        viewModelScope.launch {
            repo.getGenreContent(id).onSuccess { r -> _state.update { it.copy(genreContent = r, genreLoading = false) } }
                .onFailure { _state.update { it.copy(genreLoading = false) } }
        }
    }
}
