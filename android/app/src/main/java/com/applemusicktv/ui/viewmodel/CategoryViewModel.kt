package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.network.HomeSection
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryUiState(
    val isLoading:   Boolean = true,
    val title:       String = "",
    val description: String? = null,
    val artworkUrl:  String? = null,
    val sections:    List<HomeSection> = emptyList(),
    val error:       String? = null,
)

/** An Apple editorial "multiroom" category page — a title, a blurb, and several playlist/album shelves. */
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repo: MusicRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    // Route id is prefixed with the page flavour: "ac-<id>" apple-curator, "c-<id>" curator,
    // "mr-<id>" editorial multiroom.
    private val rawId = savedState.get<String>("categoryId") ?: ""
    private val isApple = rawId.startsWith("ac-")
    private val isMultiRoom = rawId.startsWith("mr-")
    private val realId = rawId.removePrefix("ac-").removePrefix("c-").removePrefix("mr-")
    private val _state = MutableStateFlow(CategoryUiState())
    val state: StateFlow<CategoryUiState> = _state

    init { if (realId.isNotEmpty()) load() }

    private fun load() = viewModelScope.launch {
        (if (isMultiRoom) repo.getMultiRoom(realId) else repo.getCurator(realId, isApple))
            .onSuccess { d ->
                _state.value = CategoryUiState(
                    isLoading = false, title = d.title, description = d.description,
                    artworkUrl = d.artworkUrl, sections = d.sections,
                )
            }
            .onFailure { _state.value = CategoryUiState(isLoading = false, error = it.message) }
    }
}
