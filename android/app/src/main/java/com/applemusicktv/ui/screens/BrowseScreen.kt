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
import androidx.compose.ui.draw.clip
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

/** A Browse shelf — albums/playlists OR a music-video row (mirrors Apple's Browse page). */
data class BrowseShelf(
    val title: String,
    val albums: List<com.applemusicktv.data.model.Album> = emptyList(),
    val videos: List<com.applemusicktv.data.model.Song> = emptyList(),
    /** Apple editorial room behind this shelf — when set the row ends with a "More" see-all card. */
    val roomId: String? = null,
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
                            BrowseShelf(s.title, s.albums.map(repo::albumFromDto), s.videos.map(repo::songFromDto), s.roomId)
                        },
                    )
                }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it.message) }
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
    /** Opens a shelf's full editorial room (the trailing "More" card). */
    onSeeAll: (roomId: String) -> Unit = {},
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

    if (state.shelves.isEmpty()) {
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
        items(state.shelves, key = { it.title }) { shelf ->
            if (shelf.videos.isNotEmpty()) BrowseVideoRow(shelf.title, shelf.videos, playerVm)
            else BrowseRow(shelf.title, shelf.albums, onAlbumClick, onPlaylistClick, playerVm,
                shelf.roomId, onSeeAll)
        }
    }
}

/** Music-video shelf: wide 16:9 thumbnails; tapping plays the shelf into the fullscreen player. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowseVideoRow(title: String, videos: List<com.applemusicktv.data.model.Song>, playerVm: PlayerViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(videos.size) { idx ->
                val v = videos[idx]
                Surface(
                    onClick = { playerVm.playAlbum(videos, idx) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                ) {
                    Column(Modifier.width(230.dp)) {
                        Box(Modifier.width(230.dp).height(129.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1A1A))) {
                            if (v.artworkUrl != null)
                                AsyncImage(model = v.artworkUrl(480), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFA233B)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                Text("MV", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(v.title, fontSize = 12.5.sp, color = Color(0xFFF2F2F5), maxLines = 1, fontWeight = FontWeight.SemiBold)
                        Text(v.artistName, fontSize = 10.5.sp, color = Color(0xFF8A8A8E), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(
    title: String,
    albums: List<com.applemusicktv.data.model.Album>,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    playerVm: PlayerViewModel? = null,
    roomId: String? = null,
    onSeeAll: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text       = title,
            fontSize   = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White,
            modifier   = Modifier.padding(start = 48.dp, bottom = 14.dp),
        )
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                val isStation = album.id.startsWith("ra.")
                val isSong = album.type == "songs"
                AlbumCard(album = album, size = 130, onClick = {
                    when {
                        isStation -> playerVm?.playStation(album.id, album.artworkUrl(600))
                        isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                        isSong -> playerVm?.playSong(album)
                        else -> onAlbumClick(album.id)
                    }
                })
            }
            // "More" — opens the shelf's full editorial room (e.g. Daily Top 100 → all 100 country
            // lists). Only shown when Apple actually gave this shelf a room.
            if (roomId != null) {
                item(key = "more-$roomId") { SeeAllCard(size = 130) { onSeeAll(roomId) } }
            }
        }
    }
}

/** The trailing "More →" card at the end of a shelf: a chevron over a subtle tile. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeeAllCard(size: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.width(size.dp)) {
        androidx.tv.material3.Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.08f),
            glow = androidx.tv.material3.CardDefaults.glow(
                focusedGlow = androidx.tv.material3.Glow(Color.White.copy(alpha = 0.22f), 18.dp)),
            border = androidx.tv.material3.CardDefaults.border(
                focusedBorder = androidx.tv.material3.Border(
                    border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.55f)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))),
            colors = androidx.tv.material3.CardDefaults.colors(
                containerColor = Color(0xFF1C1C1E), focusedContainerColor = Color(0xFF2A2A2E)),
            shape = androidx.tv.material3.CardDefaults.shape(
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.applemusicktv.ui.components.Icon(
                    com.applemusicktv.ui.components.Glyph.NEXT, size = 26.dp, color = Color.White)
            }
        }
        Text("More", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF2F2F5),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
}
