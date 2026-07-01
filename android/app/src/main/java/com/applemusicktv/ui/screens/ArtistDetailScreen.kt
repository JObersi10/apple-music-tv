package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.ArtistDetailViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ArtistDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(Color(0xFF080808)), Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFFA233B))
        }
        return
    }
    if (state.error != null) {
        Box(modifier.fillMaxSize().background(Color(0xFF080808)), Alignment.Center) {
            Text("Error: ${state.error}", color = Color(0xFFFF3B30), fontSize = 14.sp)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Color(0xFF080808)),
        contentPadding = PaddingValues(bottom = 60.dp),
    ) {
        // Hero header
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                if (state.artworkUrl != null) {
                    AsyncImage(
                        model = state.artworkUrl?.replace("{w}", "1200")?.replace("{h}", "1200")?.replace("{f}", "jpg"),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0x33000000), Color(0xFF080808))),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(48.dp)) {
                    Text(state.name, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                    if (state.genres.isNotEmpty())
                        Text(state.genres.joinToString(" • "), fontSize = 13.sp, color = Color(0xFFBBBBBB))
                }
            }
        }

        if (state.bio != null) {
            item {
                Text(
                    state.bio!!,
                    fontSize = 13.sp, color = Color(0xFF999999), lineHeight = 20.sp, maxLines = 3,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            }
        }

        // Top songs
        if (state.topSongs.isNotEmpty()) {
            item { SectionTitle("Top Songs") }
            item {
                Row(Modifier.padding(start = 48.dp, end = 48.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("▶  Play") { playerVm.playAlbum(state.topSongs, 0) }
                    PillButton("⇄  Shuffle") { playerVm.playAlbum(state.topSongs.shuffled(), 0) }
                }
            }
            itemsIndexedTopSongs(state.topSongs, playerVm)
        }

        state.latestRelease?.let { latest ->
            item { SectionTitle("Latest Release") }
            item {
                Box(Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) {
                    AlbumCard(album = latest, size = 180, onClick = { onAlbumClick(latest.id) })
                }
            }
        }

        if (state.albums.isNotEmpty()) {
            item { SectionTitle("Albums") }
            item { AlbumRow(state.albums, onAlbumClick) }
        }
        if (state.featuredAlbums.isNotEmpty()) {
            item { SectionTitle("Featured") }
            item { AlbumRow(state.featuredAlbums, onAlbumClick) }
        }

        if (state.similarArtists.isNotEmpty()) {
            item { SectionTitle("Similar Artists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(state.similarArtists, key = { it.id }) { a ->
                        Surface(
                            onClick = { onArtistClick(a.id) },
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF1C1C1E)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                        ) {
                            Column(Modifier.width(120.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF2A2A2A))) {
                                    if (a.artworkUrl != null) AsyncImage(
                                        model = a.artworkUrl.replace("{w}", "200").replace("{h}", "200").replace("{f}", "jpg"),
                                        contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(a.name, fontSize = 12.sp, color = Color.White, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
        modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 12.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFFFA233B), focusedContainerColor = Color(0xFFCC1A2E)),
    ) {
        Box(Modifier.padding(horizontal = 26.dp, vertical = 10.dp)) {
            Text(label, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AlbumRow(albums: List<Album>, onAlbumClick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(album = album, size = 150, onClick = { onAlbumClick(album.id) })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedTopSongs(
    songs: List<Song>,
    playerVm: PlayerViewModel,
) {
    items(songs.size) { idx ->
        val song = songs[idx]
        Surface(
            onClick = { playerVm.playAlbum(songs, idx) },
            onLongClick = { playerVm.addToQueue(song) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 1.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF1C1C1E)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${idx + 1}", fontSize = 13.sp, color = Color(0xFF666666), modifier = Modifier.width(28.dp))
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A))) {
                    if (song.artworkUrl != null) AsyncImage(model = song.artworkUrl(88), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f)) {
                    Text(song.title, fontSize = 14.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(song.albumName, fontSize = 11.sp, color = Color(0xFF666666), maxLines = 1)
                }
                Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFF555555))
            }
        }
    }
}
