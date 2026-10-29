package com.applemusicktv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A Browse shelf — albums/playlists OR a music-video row (mirrors Apple's Browse page). */
data class BrowseShelf(
    val title: String,
    val albums: List<com.applemusicktv.data.model.Album> = emptyList(),
    val videos: List<com.applemusicktv.data.model.Song> = emptyList(),
    /** Apple editorial room behind this shelf — when set the row ends with a "More" see-all card. */
    val roomId: String? = null,
    /** Presentation hint: "spotlight" (big landscape editorial cards) | null (normal square shelf). */
    val style: String? = null,
)

data class BrowseUiState(
    val isLoading: Boolean           = true,
    val error:     String?           = null,
    val shelves:   List<BrowseShelf> = emptyList(),
)

@HiltViewModel
class BrowseViewModel @Inject constructor(private val repo: MusicRepository) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = BrowseUiState(isLoading = true)
            repo.getBrowse()
                .onSuccess { resp ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        shelves = resp.sections.map { s ->
                            BrowseShelf(s.title, s.albums.map(repo::albumFromDto), s.videos.map(repo::songFromDto), s.roomId, s.style)
                        },
                    )
                }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it.message) }
        }
    }
}
