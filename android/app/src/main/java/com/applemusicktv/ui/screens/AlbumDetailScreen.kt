package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    modifier: Modifier = Modifier,
) {
    val vm: AlbumDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    if (state.isLoading) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFFA233B))
        }
        return
    }

    val album = state.album ?: return

    Row(modifier = modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
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
            }
            Spacer(Modifier.height(20.dp))
            Text(album.title, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(album.artistName, fontSize = 15.sp, color = Color(0xFFFA233B),
                modifier = Modifier.padding(top = 4.dp))
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
                        onClick = { playerVm.playAlbum(state.tracks.shuffled()) },
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
                    track   = track,
                    index   = index + 1,
                    onClick = { playerVm.playAlbum(state.tracks, index) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackRow(track: Song, index: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
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
