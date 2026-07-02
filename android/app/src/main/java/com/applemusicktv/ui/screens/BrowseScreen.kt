package com.applemusicktv.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.data.repository.SearchResults
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowseUiState(
    val isLoading:     Boolean     = true,
    val error:         String?     = null,
    val topPicks:      List<Album> = emptyList(),
    val madeForYou:    List<Album> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val newReleases:   List<Album> = emptyList(),
)

@HiltViewModel
class BrowseViewModel @Inject constructor(private val repo: MusicRepository) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = BrowseUiState(isLoading = true)
            try {
                val topD    = async { repo.search("top hits 2024", 10) }
                val madeD   = async { repo.search("chill playlist", 8) }
                val recentD = async { repo.search("new album 2025", 10) }
                val newD    = async { repo.search("new release 2025", 8) }
                _state.value = BrowseUiState(
                    isLoading     = false,
                    topPicks      = topD.await().getOrDefault(empty()).albums,
                    madeForYou    = madeD.await().getOrDefault(empty()).albums,
                    recentlyAdded = recentD.await().getOrDefault(empty()).albums,
                    newReleases   = newD.await().getOrDefault(empty()).albums,
                )
            } catch (e: Exception) {
                _state.value = BrowseUiState(isLoading = false, error = e.message)
            }
        }
    }

    private fun empty() = SearchResults()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(playerVm: PlayerViewModel, onAlbumClick: (String) -> Unit = {}, modifier: Modifier = Modifier) {
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
                Text("Could not connect", color = Color(0xFFFF453A), fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Surface(onClick = vm::load, colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFFFA233B))) {
                    Text("Retry", color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (state.topPicks.isNotEmpty()) item {
            BrowseRow("Top Picks", state.topPicks, onAlbumClick)
        }
        if (state.madeForYou.isNotEmpty()) item {
            BrowseRow("Made For You", state.madeForYou, onAlbumClick)
        }
        if (state.recentlyAdded.isNotEmpty()) item {
            BrowseRow("Recently Added", state.recentlyAdded, onAlbumClick)
        }
        if (state.newReleases.isNotEmpty()) item {
            BrowseRow("New Releases", state.newReleases, onAlbumClick)
        }
    }
}

@Composable
private fun BrowseRow(title: String, albums: List<Album>, onAlbumClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text       = title,
            fontSize   = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White,
            modifier   = Modifier.padding(start = 48.dp, bottom = 14.dp),
        )
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album = album, size = 130, onClick = { onAlbumClick(album.id) })
            }
        }
    }
}
