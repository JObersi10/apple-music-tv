package com.applemusicktv.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(playerVm: PlayerViewModel, onAlbumClick: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val vm: HomeViewModel = hiltViewModel()
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (state.topPicks.isNotEmpty()) item {
            ContentRow("Top Picks", state.topPicks, cardSize = 120, onAlbumClick = onAlbumClick)
        }
        if (state.madeForYou.isNotEmpty()) item {
            ContentRow("Made For You", state.madeForYou, cardSize = 150, onAlbumClick = onAlbumClick)
        }
        if (state.recentlyAdded.isNotEmpty()) item {
            ContentRow("Recently Added", state.recentlyAdded, cardSize = 120, onAlbumClick = onAlbumClick)
        }
        if (state.newReleases.isNotEmpty()) item {
            ContentRow("New Releases", state.newReleases, cardSize = 130, onAlbumClick = onAlbumClick)
        }
    }
}

@Composable
private fun ContentRow(title: String, albums: List<Album>, cardSize: Int, onAlbumClick: (String) -> Unit) {
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
                AlbumCard(album = album, size = cardSize, onClick = { onAlbumClick(album.id) })
            }
        }
    }
}
