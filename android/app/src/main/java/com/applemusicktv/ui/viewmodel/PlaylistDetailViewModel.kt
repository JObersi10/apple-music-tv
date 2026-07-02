package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailState(
    val loading: Boolean = false,
    val tracks: List<Song> = emptyList(),
    val artworkUrl: String? = null,
    val curatorName: String = "",
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repo: MusicRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailState())
    val state: StateFlow<PlaylistDetailState> = _state

    private var loadedId: String? = null

    fun load(playlistId: String, initialArtworkUrl: String? = null) {
        // Allow reloading only if it's a different playlist.
        if (loadedId == playlistId && (_state.value.loading || _state.value.tracks.isNotEmpty())) return
        loadedId = playlistId
        viewModelScope.launch {
            _state.value = PlaylistDetailState(loading = true, artworkUrl = initialArtworkUrl)
            repo.getPlaylistTracks(playlistId).onSuccess { songs ->
                _state.value = PlaylistDetailState(
                    tracks     = songs,
                    artworkUrl = initialArtworkUrl ?: songs.firstOrNull()?.artworkUrl,
                )
            }.onFailure {
                _state.value = PlaylistDetailState(artworkUrl = initialArtworkUrl)
            }
        }
    }
}
