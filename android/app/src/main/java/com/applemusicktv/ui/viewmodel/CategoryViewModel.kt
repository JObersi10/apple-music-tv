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
    val sections:    List<HomeSection> = emptyList(),
    val error:       String? = null,
)

/** An Apple editorial "multiroom" category page — a title, a blurb, and several playlist/album shelves. */
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repo: MusicRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val categoryId = savedState.get<String>("categoryId") ?: ""
    private val _state = MutableStateFlow(CategoryUiState())
    val state: StateFlow<CategoryUiState> = _state

    init { if (categoryId.isNotEmpty()) load() }

    private fun load() = viewModelScope.launch {
        repo.getMultiRoom(categoryId)
            .onSuccess { d ->
                _state.value = CategoryUiState(
                    isLoading = false, title = d.title, description = d.description, sections = d.sections,
                )
            }
            .onFailure { _state.value = CategoryUiState(isLoading = false, error = it.message) }
    }
}
