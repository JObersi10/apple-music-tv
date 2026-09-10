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
import com.applemusicktv.ui.components.Glyph
import com.applemusicktv.ui.components.Icon
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
    var addToSong by remember { mutableStateOf<Song?>(null) }
    // Per-row focus requesters → closing the context menu returns focus to the long-pressed row.
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    var refocusId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refocusId) {
        val id = refocusId ?: return@LaunchedEffect
        kotlinx.coroutines.delay(60)
        runCatching { rowFocus[id]?.requestFocus() }
        refocusId = null
    }

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
                    PillButton(Glyph.PLAY, "Play", Color(0xFFFA233B), Color(0xFFFF3B54)) {
                        playerVm.playAlbum(state.tracks)
                    }
                    PillButton(Glyph.SHUFFLE, "Shuffle", Color(0xFF2A2A2C), Color(0xFF3A3A3C)) {
                        playerVm.playAlbum(state.tracks.shuffled(), shuffle = true)
                    }
                }
            }

            itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track       = track,
                    index       = index + 1,
                    onClick     = { playerVm.playAlbum(state.tracks, index) },
                    onLongClick = { menuSong = track },
                    focusRequester = rowFocus.getOrPut(track.id) { FocusRequester() },
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
        val dismissMenu = { refocusId = menuSong?.id; menuSong = null }
        val goArtist = s.artistId ?: album.artistId ?: state.tracks.firstOrNull()?.artistId
        val goAlbum  = s.albumId ?: album.id
        com.applemusicktv.ui.components.AmContextMenu(
            title = s.title,
            subtitle = s.artistName,
            artworkUrl = s.artworkUrl,
            onDismiss = dismissMenu,
            actions = buildList {
                add(com.applemusicktv.ui.components.AmMenuAction("Play Next", com.applemusicktv.ui.components.Glyph.PLAY_NEXT) { playerVm.playNext(s); dismissMenu() })
                add(com.applemusicktv.ui.components.AmMenuAction("Add to Queue", com.applemusicktv.ui.components.Glyph.QUEUE_ADD) { playerVm.addToQueue(s); dismissMenu() })
                add(com.applemusicktv.ui.components.AmMenuAction("Add to…", com.applemusicktv.ui.components.Glyph.ADD_TO) { addToSong = s; dismissMenu() })
                add(com.applemusicktv.ui.components.AmMenuAction("Create Station", com.applemusicktv.ui.components.Glyph.RADIO) { playerVm.createSongStation(s); dismissMenu() })
                if (goArtist != null) add(com.applemusicktv.ui.components.AmMenuAction("Go to Artist", com.applemusicktv.ui.components.Glyph.ARTIST) { onArtistClick(goArtist); dismissMenu() })
                if (goAlbum  != null) add(com.applemusicktv.ui.components.AmMenuAction("Go to Album", com.applemusicktv.ui.components.Glyph.ALBUM) { onAlbumClick(goAlbum); dismissMenu() })
            },
        )
    }

    addToSong?.let { s ->
        com.applemusicktv.ui.components.AddToDialog(playerVm, s, onDismiss = { addToSong = null })
    }

    } // outer Box
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AlbumContextItem(icon: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(icon, size = 17.dp, color = Color(0xFFB0B0B4))
            }
            Text(label, fontSize = 15.sp, color = Color.White)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackRow(track: Song, index: Int, onClick: () -> Unit, onLongClick: () -> Unit = {}, focusRequester: FocusRequester? = null) {
    Surface(
        onClick     = onClick,
        onLongClick = onLongClick,
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).fillMaxWidth(),
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
