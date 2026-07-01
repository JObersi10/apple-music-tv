package com.applemusicktv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun AlbumCard(album: Album, size: Int = 130, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick  = onClick,
        modifier = modifier.width(size.dp),
        scale = CardDefaults.scale(focusedScale = 1.10f, pressedScale = 0.96f),
        glow  = CardDefaults.glow(
            focusedGlow = Glow(Color(0xFFFA233B).copy(alpha = 0.5f), 14.dp)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color(0xFFFA233B)),
                shape  = RoundedCornerShape(8.dp),
            )
        ),
        colors = CardDefaults.colors(
            containerColor        = Color(0xFF161616),
            focusedContainerColor = Color(0xFF1E1E1E),
        ),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (album.artworkUrl != null) {
                    AsyncImage(
                        model              = album.artworkUrl(size * 2),
                        contentDescription = album.title,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(album.color)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("♪", fontSize = (size * 0.28f).sp, color = Color.White.copy(alpha = 0.2f))
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text  = album.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE5E5E7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text  = album.artistName,
                    fontSize = 10.sp,
                    color = Color(0xFF666666),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
