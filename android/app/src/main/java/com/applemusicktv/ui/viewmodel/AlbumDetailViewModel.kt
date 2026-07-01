package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val isLoading:     Boolean     = true,
    val album:         Album?      = null,
    val tracks:        List<Song>  = emptyList(),
    val relatedAlbums: List<Album> = emptyList(),
    val error:         String?     = null,
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repo: MusicRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val albumId = savedState.get<String>("albumId") ?: ""
    private val _state  = MutableStateFlow(AlbumDetailUiState())
    val state: StateFlow<AlbumDetailUiState> = _state

    init { if (albumId.isNotEmpty()) load() }

    private fun load() {
        viewModelScope.launch {
            try {
                val albumD   = async { repo.getAlbum(albumId) }
                val tracksD  = async { repo.getAlbumTracks(albumId) }
                val relatedD = async { repo.getRelatedAlbums(albumId) }
                _state.value = AlbumDetailUiState(
                    isLoading     = false,
                    album         = albumD.await().getOrNull(),
                    tracks        = tracksD.await().getOrDefault(emptyList()),
                    relatedAlbums = relatedD.await().getOrDefault(emptyList()),
                )
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
