package com.applemusicktv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album

/** Parse Apple's bare-hex `artworkBgColor` ("1a2b3c") into an opaque Compose color. */
private fun bgColor(hex: String?, fallback: Long = 0xFF1A1A1E): Color {
    if (hex.isNullOrBlank()) return Color(fallback)
    return runCatching { Color(("FF" + hex.removePrefix("#").take(6)).toLong(16)) }.getOrDefault(Color(fallback))
}

/** The small uppercase label above a spotlight card, derived from the item type (Apple's own). */
private fun spotlightLabel(album: Album): String = when {
    album.tagline?.isNotBlank() == true -> album.tagline!!.uppercase()
    album.type == "playlists" -> "UPDATED PLAYLIST"
    album.type == "stations" || album.id.startsWith("ra.") -> "NEW RADIO SHOW"
    album.type == "songs" -> "NEW SONG"
    album.type == "music-videos" -> "NEW VIDEO"
    else -> "NEW ALBUM"
}

/**
 * Big landscape editorial card for the Browse "New" spotlight row. Layout matches apple.com: the
 * label / title / subtitle sit ABOVE the image; the wide art (its own aspect ratio) sits below with
 * the short editorial blurb captioned bottom-left over a soft scrim.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SpotlightHeroCard(album: Album, width: Int = 360, onClick: () -> Unit) {
    val tint = bgColor(album.artworkBgColor)
    Card(
        onClick = onClick,
        modifier = Modifier.width(width.dp),
        scale = CardDefaults.scale(focusedScale = 1.05f, pressedScale = 0.97f),
        glow = CardDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.28f), 22.dp)),
        border = CardDefaults.border(focusedBorder = Border(
            border = BorderStroke(2.5.dp, Color.White.copy(alpha = 0.85f)),
            shape = RoundedCornerShape(9.dp))),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        shape = CardDefaults.shape(RoundedCornerShape(9.dp)),
    ) {
        Column(Modifier.width(width.dp).padding(horizontal = 2.dp)) {
            // Text block ABOVE the image.
            Text(spotlightLabel(album), color = Color(0xFF9A9AA0), fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(album.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.artistName.isNotBlank()) {
                Text(album.artistName, color = Color(0xFF8A8A8E), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            Spacer(Modifier.height(9.dp))
            // Wide art below, at its own ratio. Source is already native-ratio, so Crop centers with
            // no letterbox padding. A permanent hairline frame (bigger + brighter on focus) gives the
            // cards edges instead of soft-rounded blobs.
            Box(Modifier.fillMaxWidth().aspectRatio(1.86f)
                .clip(RoundedCornerShape(8.dp))
                .background(tint)
                .border(1.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))) {
                val art = album.wideArtworkUrl ?: album.artworkUrl(width * 2)
                if (art != null) {
                    AsyncImage(model = art, contentDescription = album.title,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                album.editorialNotes?.takeIf { it.isNotBlank() }?.let { note ->
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
                    Text(note, color = Color.White.copy(alpha = 0.95f), fontSize = 10.5.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
            }
        }
    }
}

/**
 * Big gradient card for Home's personalized rows ("Top Picks for You", "Playlists Made for You").
 * Square artwork fills the card; a color-tinted gradient scrim carries a bold title at the bottom.
 * Focused cards on the "Playlists Made for You" shelf play their motion loop.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GradientCard(album: Album, width: Int = 250, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    val tint = bgColor(album.artworkBgColor)
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(width.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        scale = CardDefaults.scale(focusedScale = 1.06f, pressedScale = 0.97f),
        glow = CardDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.28f), 22.dp)),
        border = CardDefaults.border(focusedBorder = Border(
            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(16.dp))),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(15.dp)).background(tint)) {
            if (album.artworkUrl != null) {
                AsyncImage(model = album.artworkUrl(width * 2), contentDescription = album.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                album.motionUrl?.let { mu -> MotionArtwork(mu, play = focused, modifier = Modifier.fillMaxSize()) }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0.4f to Color.Transparent,
                1f to tint.copy(alpha = 0.92f),
            )))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                Text(album.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (album.artistName.isNotBlank()) {
                    Text(album.artistName, color = Color.White.copy(alpha = 0.78f), fontSize = 11.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
