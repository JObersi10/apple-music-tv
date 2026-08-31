package com.applemusicktv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlbumCard(album: Album, size: Int = 130, onClick: () -> Unit, onLongClick: () -> Unit = {}, motionEnabled: Boolean = true, modifier: Modifier = Modifier) {
    // Motion artwork (Playlists Made for You) plays while the card is focused — see MotionArtwork
    // for why it isn't five decoders at once.
    var focused by remember { mutableStateOf(false) }
    // Motion art plays ONLY for cards that ship a preloaded loop (the "Playlists Made for You" shelf).
    // Lazily fetching + decoding a motion video on EVERY focused card kept a HEVC decoder running
    // continuously as you browsed — it saturated this Fire TV and made the whole app janky. One
    // focused decoder on the one animated shelf is what the app shipped smooth with.
    val motionUrl = if (motionEnabled) album.motionUrl else null
    Card(
        onClick  = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(size.dp)
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        // Apple-style: soft scale + a gentle white halo on focus — no hard red border.
        scale = CardDefaults.scale(focusedScale = 1.08f, pressedScale = 0.96f),
        glow  = CardDefaults.glow(
            focusedGlow = Glow(Color.White.copy(alpha = 0.22f), 18.dp)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.55f)),
                shape  = RoundedCornerShape(12.dp),
            )
        ),
        colors = CardDefaults.colors(
            containerColor        = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (album.artworkUrl != null) {
                    AsyncImage(
                        model              = album.artworkUrl(size * 2),
                        contentDescription = album.title,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize(),
                    )
                    motionUrl?.let { mu ->
                        MotionArtwork(mu, play = focused, modifier = Modifier.fillMaxSize())
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(album.color)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Glyph.MUSIC_NOTE, size = (size * 0.32f).dp, color = Color.White.copy(alpha = 0.2f))
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text(
                    text  = album.title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF2F2F5),
                    letterSpacing = (-0.1).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text  = album.artistName,
                    fontSize = 10.5.sp,
                    color = Color(0xFF8A8A8E),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
