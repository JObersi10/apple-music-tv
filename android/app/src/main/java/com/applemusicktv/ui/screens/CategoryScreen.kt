package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Text
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.CategoryViewModel

/** Editorial category page (Apple "multiroom") — a title, a blurb, and several playlist/album shelves. */
@Composable
fun CategoryScreen(
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val vm: CategoryViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    Box(modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFA233B))
            }
            state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Couldn't load this category.", color = Color(0xFF888888), fontSize = 15.sp)
            }
            // contentPadding (not Modifier.padding) so a focused card's scaled border and
            // glow can bleed into the margin instead of being clipped at the edges.
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Column(Modifier.padding(start = 8.dp)) {
                        // Editorial hero — a wide banner with a scrim, title overlaid bottom-left.
                        state.artworkUrl?.takeIf { it.isNotBlank() }?.let { art ->
                            Box(
                                Modifier.fillMaxWidth().height(220.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                            ) {
                                AsyncImage(
                                    model = art, contentDescription = state.title,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                                Box(
                                    Modifier.fillMaxSize().background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent, 0.55f to Color(0x66000000), 1f to Color(0xEE000000),
                                        ),
                                    ),
                                )
                                Text(
                                    state.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
                                )
                            }
                            state.description?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(12.dp))
                                Text(it, fontSize = 13.sp, color = Color(0xFFAAAAAA), lineHeight = 19.sp,
                                    modifier = Modifier.fillMaxWidth(0.8f))
                            }
                        } ?: run {
                            // No hero image — plain title (e.g. multirooms have none server-side).
                            Text(state.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            state.description?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(it, fontSize = 13.sp, color = Color(0xFFAAAAAA), lineHeight = 19.sp,
                                    modifier = Modifier.fillMaxWidth(0.75f))
                            }
                        }
                    }
                }
                items(state.sections, key = { it.title }) { section ->
                    Column {
                        // A single-shelf room (a "More" see-all page) names its shelf after the room,
                        // so the heading would print twice — show it once.
                        if (!section.title.equals(state.title, ignoreCase = true)) {
                            Text(section.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                color = Color.White, modifier = Modifier.padding(bottom = 10.dp, start = 8.dp))
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            // Room on all sides so the focus glow/scale never clips.
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            items(section.albums, key = { it.id }) { dto ->
                                val isPlaylist = dto.id.startsWith("pl.") || dto.id.startsWith("p.")
                                AlbumCard(
                                    album = Album(
                                        id = dto.id, title = dto.title, artistName = dto.artistName,
                                        artworkUrl = dto.artworkUrl, artworkBgColor = dto.artworkBgColor,
                                    ),
                                    size = 150,
                                    onClick = {
                                        if (isPlaylist) onPlaylistClick(dto.id, dto.title, (dto.artworkUrl ?: "").replace("{w}", "500").replace("{h}", "500").replace("{f}", "jpg"))
                                        else onAlbumClick(dto.id)
                                    },
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
