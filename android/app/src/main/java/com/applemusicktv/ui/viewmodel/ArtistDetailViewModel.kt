package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.SimilarArtistDto
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistDetailUiState(
    val isLoading:      Boolean = true,
    val name:           String = "",
    val artworkUrl:     String? = null,
    val bio:            String? = null,
    val genres:         List<String> = emptyList(),
    val topSongs:       List<Song> = emptyList(),
    val latestRelease:  Album? = null,
    val albums:         List<Album> = emptyList(),
    val featuredAlbums: List<Album> = emptyList(),
    val similarArtists: List<SimilarArtistDto> = emptyList(),
    val error:          String? = null,
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val repo: MusicRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val artistId = savedState.get<String>("artistId") ?: ""
    private val _state = MutableStateFlow(ArtistDetailUiState())
    val state: StateFlow<ArtistDetailUiState> = _state

    init { if (artistId.isNotEmpty()) load() }

    private fun load() = viewModelScope.launch {
        repo.getArtistFull(artistId)
            .onSuccess { d ->
                _state.value = ArtistDetailUiState(
                    isLoading      = false,
                    name           = d.name,
                    artworkUrl     = d.artworkUrl,
                    bio            = d.editorialNotes,
                    genres         = d.genreNames,
                    topSongs       = d.topSongs.map(repo::songFromDto),
                    latestRelease  = d.latestRelease?.let(repo::albumFromDto),
                    albums         = d.albums.map(repo::albumFromDto),
                    featuredAlbums = d.featuredAlbums.map(repo::albumFromDto),
                    similarArtists = d.similarArtists,
                )
            }
            .onFailure { _state.value = ArtistDetailUiState(isLoading = false, error = it.message) }
    }
}
