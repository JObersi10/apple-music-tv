package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One shelf of a genre page — albums/playlists OR music videos (never both). */
data class GenreSection(val title: String, val albums: List<Album>, val videos: List<Song>)

data class GenreUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val sections: List<GenreSection> = emptyList(),
)

@HiltViewModel
class GenreViewModel @Inject constructor(private val repo: MusicRepository) : ViewModel() {
    private val _state = MutableStateFlow(GenreUiState())
    val state: StateFlow<GenreUiState> = _state
    private var loadedId: String? = null

    fun load(id: String) {
        if (id == loadedId) return
        loadedId = id
        viewModelScope.launch {
            _state.value = GenreUiState(isLoading = true)
            repo.getGenreContent(id)
                .onSuccess { resp ->
                    _state.value = GenreUiState(
                        isLoading = false,
                        sections = resp.sections.map { s ->
                            GenreSection(
                                title = s.title,
                                albums = s.albums.map(repo::albumFromDto),
                                videos = s.videos.map(repo::songFromDto),
                            )
                        }.filter { it.albums.isNotEmpty() || it.videos.isNotEmpty() },
                    )
                }
                .onFailure { _state.value = GenreUiState(isLoading = false, error = it.message) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GenreScreen(
    genreId: String,
    genreName: String,
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val vm: GenreViewModel = hiltViewModel()
    LaunchedEffect(genreId) { vm.load(genreId) }
    val state by vm.state.collectAsState()

    Column(modifier.fillMaxSize()) {
        Text(genreName, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.padding(start = 48.dp, top = 20.dp, bottom = 8.dp))

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Color(0xFFFA233B)) }
            state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Could not load genre", color = Color(0xFFFF453A), fontSize = 14.sp)
            }
            state.sections.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No content available", color = Color(0xFF555555), fontSize = 14.sp)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(top = 8.dp, bottom = 102.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                items(state.sections, key = { it.title }) { section ->
                    if (section.videos.isNotEmpty()) VideoRow(section, playerVm)
                    else AlbumRow(section, onAlbumClick, onPlaylistClick)
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    section: GenreSection,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(section.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(section.albums, key = { it.id }) { album ->
                val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                AlbumCard(album = album, size = 130, onClick = {
                    if (isPlaylist) onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                    else onAlbumClick(album.id)
                })
            }
        }
    }
}

/** Music-video shelf: wide 16:9 thumbnails. Tapping plays the whole shelf as a mixed
 *  queue — playAlbum routes each video into the fullscreen video player. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoRow(section: GenreSection, playerVm: PlayerViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Text(section.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(section.videos.size) { idx ->
                val v = section.videos[idx]
                Surface(
                    onClick = { playerVm.playAlbum(section.videos, idx) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                ) {
                    Column(Modifier.width(240.dp)) {
                        Box(Modifier.width(240.dp).height(135.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2A2A))) {
                            if (v.artworkUrl != null)
                                AsyncImage(model = v.artworkUrl(500), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFA233B)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                Text("MV", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(v.title, fontSize = 13.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium)
                        Text(v.artistName, fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1)
                    }
                }
            }
        }
    }
}
