package com.applemusicktv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.applemusicktv.data.repository.CategoryGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.HomeSection
import androidx.compose.foundation.shape.RoundedCornerShape
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GenreChip(val id: String, val name: String)

data class BrowseUiState(
    val isLoading: Boolean           = true,
    val error:     String?           = null,
    val sections:  List<HomeSection> = emptyList(),
    val genres:    List<GenreChip>   = emptyList(),
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
                        sections  = resp.sections.map { s ->
                            HomeSection(title = s.title, albums = s.albums.map(repo::albumFromDto))
                        },
                    )
                }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it.message) }
            repo.getGenres().onSuccess { gs ->
                _state.value = _state.value.copy(genres = gs.map { GenreChip(it.id, it.name) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    onGenreClick: (id: String, name: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val vm: BrowseViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    if (state.isLoading) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFFA233B))
        }
        return
    }

    if (state.error != null) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not connect to server", color = Color(0xFFFF453A), fontSize = 16.sp)
                Text(state.error ?: "", color = Color(0xFF555555), fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Surface(onClick = vm::load, colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFFFA233B))) {
                    Text("Retry", color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                }
            }
        }
        return
    }

    if (state.sections.isEmpty()) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            Text("No content available", color = Color(0xFF555555), fontSize = 14.sp)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 102.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items(state.sections, key = { it.title }) { section ->
            BrowseRow(section, onAlbumClick, onPlaylistClick, playerVm)
        }
        if (state.genres.isNotEmpty()) {
            item(key = "__genres__") { GenreChipsRow(state.genres, onGenreClick) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenreChipsRow(genres: List<GenreChip>, onGenreClick: (id: String, name: String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Genres", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(genres, key = { it.id }) { g ->
                Surface(
                    onClick = { onGenreClick(g.id, g.name) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1C1C1E), focusedContainerColor = Color(0xFFFA233B)),
                ) {
                    Text(g.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(
    section: HomeSection,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    playerVm: PlayerViewModel? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text       = section.title,
            fontSize   = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White,
            modifier   = Modifier.padding(start = 48.dp, bottom = 14.dp),
        )
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(section.albums, key = { it.id }) { album ->
                val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                val isSong = album.type == "songs"
                AlbumCard(album = album, size = 130, onClick = {
                    when {
                        isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                        isSong -> playerVm?.playSong(album)
                        else -> onAlbumClick(album.id)
                    }
                })
            }
        }
    }
}
