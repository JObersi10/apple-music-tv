package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
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

    var menuSongState by remember { mutableStateOf<Song?>(null) }
    var lastDismissMs by remember { mutableStateOf(0L) }
    val dismissMenu: () -> Unit = {
        lastDismissMs = System.currentTimeMillis()
        menuSongState = null
    }
    menuSongState?.let { s ->
        LaunchedEffect(s.id) {
            if (s.artistId == null || s.albumId == null) {
                val (aId, alId) = playerVm.lookupSongIds(s.id)
                if (aId != null || alId != null)
                    menuSongState = s.copy(artistId = aId ?: s.artistId, albumId = alId ?: s.albumId)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left panel — artwork + info, same familiar layout as an album (album cover
        // is a touch bigger; here the "year" slot becomes the song count and the
        // artist line becomes the playlist maker).
        Column(
            modifier = Modifier.width(300.dp).fillMaxHeight().padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A1A3E)),
            ) {
                if (state.artworkUrl != null) {
                    AsyncImage(
                        model = state.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.motionUrl?.let { MotionCover(url = it, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.height(20.dp))
            Text(playlistName, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold,
                lineHeight = 26.sp, maxLines = 2)
            if (state.curatorName.isNotEmpty())
                Text(state.curatorName, fontSize = 15.sp, color = Color(0xFFFA233B), modifier = Modifier.padding(top = 4.dp))
            if (state.tracks.isNotEmpty())
                Text("${state.tracks.size} songs", fontSize = 12.sp, color = Color(0xFF555555), modifier = Modifier.padding(top = 4.dp))
        }

        // Right panel — Play/Shuffle header + track list
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when {
                state.loading && state.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                state.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No tracks", color = Color(0xFF555555), fontSize = 14.sp)
                }
                else -> {
                    LazyColumn(
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
                                onClick = { playerVm.playAlbum(sortedTracks.shuffled(), 0, shuffle = true) },
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
                    trackItems(sortedTracks, playerVm) { song ->
                        val now = System.currentTimeMillis()
                        if (menuSongState == null && now - lastDismissMs > 600) menuSongState = song
                    }
                }
                }  // end else block
            }
        }
    } // Row

    // Fullscreen context menu overlay — no Dialog API, no focus/dismiss races
    menuSongState?.let { s ->
        Box(
            Modifier.fillMaxSize()
                .background(Color(0x88000000))
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.key == Key.Escape)) {
                        dismissMenu(); true
                    } else false
                },
            contentAlignment = Alignment.Center,
        ) {
            val firstFocus = remember { FocusRequester() }
            var clickBlocked by remember(s.id) { mutableStateOf(true) }
            LaunchedEffect(s.id) {
                kotlinx.coroutines.delay(800)
                clickBlocked = false
                runCatching { firstFocus.requestFocus() }
            }
            Column(
                Modifier.width(320.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1C1E))
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(s.title, fontSize = 13.sp, color = Color(0xFF999999), fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                HorizontalDivider(color = Color(0xFF2E2E30), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 8.dp))
                PlaylistContextItem("▶", "Play Next",    { if (!clickBlocked) { playerVm.playNext(s);    dismissMenu() } }, Modifier.focusRequester(firstFocus))
                PlaylistContextItem("+", "Add to Queue", { if (!clickBlocked) { playerVm.addToQueue(s); dismissMenu() } })
                HorizontalDivider(color = Color(0xFF2E2E30), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 8.dp))
                s.artistId?.let { aid -> PlaylistContextItem("♪", "Go to Artist", onClick = { if (!clickBlocked) { onArtistClick(aid); dismissMenu() } }) }
                s.albumId?.let  { alid -> PlaylistContextItem("◉", "Go to Album",  onClick = { if (!clickBlocked) { onAlbumClick(alid);  dismissMenu() } }) }
            }
        }
    }

    } // outer Box
}

private enum class PlaylistSort(val label: String) {
    DEFAULT("Default"), TITLE("Title"), ARTIST("Artist"), ALBUM("Album")
}

private fun LazyListScope.trackItems(tracks: List<Song>, playerVm: PlayerViewModel, onLongPress: (Song) -> Unit = {}) {
    items(tracks.size) { idx ->
        val song = tracks[idx]
        @OptIn(ExperimentalTvMaterial3Api::class)
        Surface(
            onClick     = { playerVm.playAlbum(tracks, idx) },
            onLongClick = { onLongPress(song) },
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
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistContextItem(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2E2E30)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(icon, fontSize = 16.sp, color = Color(0xFF888888), modifier = Modifier.width(22.dp))
            Text(label, fontSize = 15.sp, color = Color.White)
        }
    }
}
