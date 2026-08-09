package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.viewmodel.AlbumDetailViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    playerVm: PlayerViewModel,
    onBack: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm: AlbumDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    if (state.isLoading && state.tracks.isEmpty()) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFFA233B))
        }
        return
    }

    val album = state.album ?: return
    var menuSong by remember { mutableStateOf<Song?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        // Left: artwork + info only
        Column(modifier = Modifier.width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A2E))) {
                if (album.artworkUrl != null) {
                    AsyncImage(
                        model = album.artworkUrl(520),
                        contentDescription = album.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.motionUrl?.let { MotionCover(url = it, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.height(20.dp))
            Text(album.title, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val albumArtistId = album.artistId ?: state.tracks.firstOrNull()?.artistId
            if (albumArtistId != null) {
                Surface(
                    onClick = { onArtistClick(albumArtistId) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0x1AFA233B)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(album.artistName, fontSize = 15.sp, color = Color(0xFFFA233B),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            } else {
                Text(album.artistName, fontSize = 15.sp, color = Color(0xFFFA233B),
                    modifier = Modifier.padding(top = 4.dp))
            }
            album.releaseDate?.let {
                Text(it.take(4), fontSize = 12.sp, color = Color(0xFF555555), modifier = Modifier.padding(top = 4.dp))
            }
        }

        // Right: Play/Shuffle header + track list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Play & Shuffle pinned above tracks
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        onClick = { playerVm.playAlbum(state.tracks) },
                        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor        = Color(0xFFFA233B),
                            focusedContainerColor = Color(0xFFE01F33),
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    ) {
                        Box(Modifier.padding(horizontal = 28.dp, vertical = 11.dp)) {
                            Text("▶  Play", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                    Surface(
                        onClick = { playerVm.playAlbum(state.tracks.shuffled(), shuffle = true) },
                        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor        = Color(0xFF2A2A2A),
                            focusedContainerColor = Color(0xFF3A3A3A),
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    ) {
                        Box(Modifier.padding(horizontal = 28.dp, vertical = 11.dp)) {
                            Text("⇄  Shuffle", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }

            itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track       = track,
                    index       = index + 1,
                    onClick     = { playerVm.playAlbum(state.tracks, index) },
                    onLongClick = { menuSong = track },
                )
            }
        }
    } // Row

    // Fullscreen context menu overlay
    menuSong?.let { s ->
        LaunchedEffect(s.id) {
            if (s.artistId == null || s.albumId == null) {
                val (aId, alId) = playerVm.lookupSongIds(s.id)
                if (aId != null || alId != null)
                    menuSong = s.copy(artistId = aId ?: s.artistId, albumId = alId ?: s.albumId)
            }
        }
        val dismissMenu = { menuSong = null }
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
                AlbumContextItem("▶", "Play Next",    { if (!clickBlocked) { playerVm.playNext(s);    dismissMenu() } }, Modifier.focusRequester(firstFocus))
                AlbumContextItem("+", "Add to Queue", { if (!clickBlocked) { playerVm.addToQueue(s); dismissMenu() } })
                val goArtist = s.artistId ?: album.artistId ?: state.tracks.firstOrNull()?.artistId
                val goAlbum  = s.albumId ?: album.id
                HorizontalDivider(color = Color(0xFF2E2E30), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 8.dp))
                if (goArtist != null) AlbumContextItem("♪", "Go to Artist", onClick = { if (!clickBlocked) { onArtistClick(goArtist); dismissMenu() } })
                if (goAlbum  != null) AlbumContextItem("◉", "Go to Album",  onClick = { if (!clickBlocked) { onAlbumClick(goAlbum);   dismissMenu() } })
            }
        }
    }

    } // outer Box
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AlbumContextItem(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackRow(track: Song, index: Int, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Surface(
        onClick     = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape    = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors   = ClickableSurfaceDefaults.colors(
            containerColor        = Color.Transparent,
            focusedContainerColor = Color(0xFF1C1C1E),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("$index", fontSize = 13.sp, color = Color(0xFF555555), modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.artistName.isNotEmpty()) {
                    Text(track.artistName, fontSize = 11.sp, color = Color(0xFF666666), modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(track.durationFormatted, fontSize = 12.sp, color = Color(0xFF555555))
        }
    }
}
