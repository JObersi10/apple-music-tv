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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // Foundation combinedClickable (not a tv Card) so a TOUCH TAP / long-press works; stays focusable
    // so D-pad nav + the focus scale/halo are preserved.
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "albumCardScale")
    Column(
        modifier = modifier.width(size.dp)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (focused) Modifier.border(
                    BorderStroke(1.5.dp, Color.White.copy(alpha = 0.55f)),
                    RoundedCornerShape(12.dp),
                ) else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
