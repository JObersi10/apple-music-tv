package com.applemusicktv.ui.components

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingBar(playerVm: PlayerViewModel, modifier: Modifier = Modifier) {
    val vm = playerVm
    val state by vm.state.collectAsState()
    val song = state.currentSong ?: return

    Surface(
        modifier = modifier.fillMaxWidth().height(74.dp),
        colors   = SurfaceDefaults.colors(containerColor = Color(0xFF0F0F0F)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF3A1A1A)),
            ) {
                if (song.artworkUrl != null) {
                    AsyncImage(
                        model = song.artworkUrl(96),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(song.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Text(song.artistName, fontSize = 11.sp, color = Color(0xFF666666), modifier = Modifier.padding(top = 2.dp))
            }

            Column(modifier = Modifier.width(220.dp)) {
                val progress = if (state.song?.durationMs ?: 0L > 0)
                    (state.progressMs.toFloat() / (state.song?.durationMs ?: 1L)).coerceIn(0f, 1f)
                else 0f

                Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF2A2A2A))) {
                    Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFFFA233B)))
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMs(state.progressMs), fontSize = 9.sp, color = Color(0xFF555555))
                    Text(song.durationFormatted, fontSize = 9.sp, color = Color(0xFF555555))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TransportBtn("⏮", onClick = vm::prev)
                TransportBtn(if (state.isPlaying) "⏸" else "▶", onClick = vm::togglePlayPause, isPrimary = true)
                TransportBtn("⏭", onClick = vm::next)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TransportBtn(icon: String, onClick: () -> Unit, isPrimary: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(if (isPrimary) 40.dp else 34.dp),
        shape    = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors   = ClickableSurfaceDefaults.colors(
            containerColor        = if (isPrimary) Color(0xFFFA233B) else Color.Transparent,
            focusedContainerColor = if (isPrimary) Color(0xFFE01F33) else Color(0xFF2A2A2A),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        glow  = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color(0xFFFA233B).copy(alpha = 0.4f), 10.dp)),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(icon, fontSize = if (isPrimary) 14.sp else 12.sp, color = Color.White)
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
