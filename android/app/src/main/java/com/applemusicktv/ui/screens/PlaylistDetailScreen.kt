package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.viewmodel.PlaylistDetailViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    playlistName: String,
    artworkUrl: String? = null,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: PlaylistDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(playlistId) { vm.load(playlistId, artworkUrl) }

    var sort by remember { mutableStateOf(PlaylistSort.DEFAULT) }
    var descending by remember { mutableStateOf(false) }
    val sortedTracks = remember(state.tracks, sort, descending) {
        val base = when (sort) {
            PlaylistSort.DEFAULT -> state.tracks
            PlaylistSort.TITLE   -> state.tracks.sortedBy { it.title.lowercase() }
            PlaylistSort.ARTIST  -> state.tracks.sortedBy { it.artistName.lowercase() }
            PlaylistSort.ALBUM   -> state.tracks.sortedBy { it.albumName.lowercase() }
        }
        // Direction applies to real sort fields; DEFAULT reversed = reverse order.
        if (descending) base.reversed() else base
    }

    Row(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        // Left panel — artwork + info only
        Column(
            modifier = Modifier.width(300.dp).fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color(0xFF0A0A0A)))),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(0.dp))
                    .background(Color(0xFF2A1A3E)),
            ) {
                if (state.artworkUrl != null) {
                    AsyncImage(
                        model = state.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(playlistName, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                if (state.curatorName.isNotEmpty())
                    Text(state.curatorName, fontSize = 13.sp, color = Color(0xFF888888))
                if (state.tracks.isNotEmpty())
                    Text("${state.tracks.size} songs", fontSize = 12.sp, color = Color(0xFF666666))
            }
        }

        // Right panel — Play/Shuffle header + track list
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                state.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No tracks", color = Color(0xFF555555), fontSize = 14.sp)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    // Sticky Play/Shuffle header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFF0A0A0A))
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                onClick = { playerVm.playAlbum(sortedTracks, 0) },
                                shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFFFA233B), focusedContainerColor = Color(0xFFCC1A2E)),
                            ) {
                                Box(Modifier.padding(horizontal = 28.dp, vertical = 11.dp)) {
                                    Text("▶  Play", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Surface(
                                onClick = { playerVm.playAlbum(sortedTracks.shuffled(), 0) },
                                shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2A2A2A), focusedContainerColor = Color(0xFF3A3A3A)),
                            ) {
                                Box(Modifier.padding(horizontal = 28.dp, vertical = 11.dp)) {
                                    Text("⇄  Shuffle", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    // Sort bar
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Sort", fontSize = 11.sp, color = Color(0xFF666666))
                            for (opt in PlaylistSort.entries) {
                                val selected = opt == sort
                                Surface(
                                    onClick = { if (sort == opt) descending = !descending else { sort = opt; descending = false } },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = if (selected) Color(0xFF2E2E30) else Color.Transparent,
                                        focusedContainerColor = Color(0xFF3A3A3C),
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                                ) {
                                    Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                                        Text(
                                            opt.label + if (selected) (if (descending) "  ↓" else "  ↑") else "",
                                            fontSize = 11.sp,
                                            color = if (selected) Color.White else Color(0xFF888888),
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    trackItems(sortedTracks, playerVm)
                }
            }
        }
    }
}

private enum class PlaylistSort(val label: String) {
    DEFAULT("Default"), TITLE("Title"), ARTIST("Artist"), ALBUM("Album")
}

private fun LazyListScope.trackItems(tracks: List<Song>, playerVm: PlayerViewModel) {
    items(tracks.size) { idx ->
        val song = tracks[idx]
        var menuSongState by remember { mutableStateOf<Song?>(null) }
        @OptIn(ExperimentalTvMaterial3Api::class)
        Surface(
            onClick     = { playerVm.playAlbum(tracks, idx) },
            onLongClick = { menuSongState = song },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color(0xFF1C1C1E),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${idx + 1}", fontSize = 12.sp, color = Color(0xFF555555), modifier = Modifier.width(24.dp))
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A2A2A))) {
                    if (song.artworkUrl != null)
                        AsyncImage(model = song.artworkUrl(80), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f)) {
                    Text(song.title, fontSize = 13.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(song.artistName, fontSize = 11.sp, color = Color(0xFF666666), maxLines = 1)
                }
                Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFF555555))
            }
        }
        menuSongState?.let { s ->
            PlaylistTrackContextMenu(
                song        = s,
                onDismiss   = { menuSongState = null },
                onPlayNext  = { playerVm.playNext(s); menuSongState = null },
                onAddToQueue = { playerVm.addToQueue(s); menuSongState = null },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistTrackContextMenu(song: Song, onDismiss: () -> Unit, onPlayNext: () -> Unit, onAddToQueue: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(500); runCatching { firstFocus.requestFocus() } }
        Column(
            Modifier.width(320.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(song.title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(12.dp))
            PlaylistContextItem("Play Next", onPlayNext, Modifier.focusRequester(firstFocus))
            PlaylistContextItem("Add to Queue", onAddToQueue)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistContextItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2E2E30)),
    ) {
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
    }
}
