package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.data.repository.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query:     String         = "",
    val isLoading: Boolean        = false,
    val results:   SearchResults? = null,
    val error:     String?        = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val repo: MusicRepository) : ViewModel() {

    private val _state    = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(400)
                .filter { it.length >= 2 }
                .distinctUntilChanged()
                .collectLatest { term ->
                    _state.update { it.copy(isLoading = true, error = null) }
                    repo.search(term)
                        .onSuccess  { r -> _state.update { it.copy(isLoading = false, results = r) } }
                        .onFailure  { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
                }
        }
    }

    fun onQueryChange(q: String) { _state.update { it.copy(query = q) }; queryFlow.value = q }
    fun clearSearch() { _state.value = SearchUiState(); queryFlow.value = "" }
}
