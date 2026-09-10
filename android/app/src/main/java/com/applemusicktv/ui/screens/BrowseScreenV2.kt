package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.AmCard
import com.applemusicktv.ui.components.SectionHeader
import com.applemusicktv.ui.components.SpotlightHeroCard
import com.applemusicktv.ui.theme.AmTokens
import com.applemusicktv.ui.viewmodel.PlayerViewModel

/**
 * v2 UI-overhaul — Browse ("New") rebuilt on [AmCard]/[SectionHeader] against [AmTokens], the
 * companion to [HomeScreenV2]. **Selected only when the New-UI flag is on** (Dev → Interface → New
 * UI); flag off keeps [BrowseScreen] untouched, so this is fully revertible.
 *
 * Same [BrowseViewModel], same shelves, same click routing as [BrowseScreen] — only the look changes.
 * Spotlight (the real Apple Featured shelf) and music-video rows keep their bespoke cards; normal
 * shelves move to the shared card grammar. Card rows start at the title margin and bleed off the
 * right edge (seamless), matching the tuned old-UI layout.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreenV2(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    onCuratorClick: (id: String) -> Unit = {},
    onSeeAll: (roomId: String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm: BrowseViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    var menuItem by remember { mutableStateOf<Album?>(null) }
    val onLong: (Album) -> Unit = { menuItem = it }

    if (state.isLoading && state.shelves.isEmpty()) {
        Box(modifier.fillMaxSize()) { com.applemusicktv.ui.components.ShelfSkeleton() }
        return
    }
    if (state.error != null) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not connect to server", color = Color(0xFFFF453A), fontSize = 16.sp)
                Text(state.error ?: "", color = Color(0xFF555555), fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Surface(onClick = vm::load, colors = ClickableSurfaceDefaults.colors(containerColor = AmTokens.Color.Accent)) {
                    Text("Retry", color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                }
            }
        }
        return
    }
    if (state.shelves.isEmpty()) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            Text("No content available", color = Color(0xFF555555), fontSize = 14.sp)
        }
        return
    }

    Box(modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AmTokens.Color.Background),
        contentPadding = PaddingValues(top = 28.dp, bottom = 102.dp),
        verticalArrangement = Arrangement.spacedBy(AmTokens.Space.shelfGap),
    ) {
        items(state.shelves, key = { it.title }) { shelf ->
            when {
                shelf.style == "spotlight" && shelf.albums.isNotEmpty() ->
                    SpotlightRowV2(shelf.title, shelf.albums, onAlbumClick, onPlaylistClick, onCuratorClick, playerVm, onLong)
                shelf.videos.isNotEmpty() -> VideoRowV2(shelf.title, shelf.videos, playerVm, onLong)
                shelf.albums.isNotEmpty() && shelf.albums.first().type == "songs" ->
                    com.applemusicktv.ui.components.SongGridRow(
                        title = shelf.title,
                        items = shelf.albums,
                        title2 = { it.title },
                        subtitle = { it.artistName },
                        artUrl = { it.artworkUrl(96) },
                        onClick = { idx -> playerVm.playSong(shelf.albums[idx]) },
                        onLongClick = { idx -> shelf.albums.getOrNull(idx)?.let(onLong) },
                    )
                else -> ShelfRowV2(shelf, playerVm, onAlbumClick, onPlaylistClick, onCuratorClick, onSeeAll, onLong)
            }
        }
    }
        menuItem?.let { mi ->
            com.applemusicktv.ui.components.CardContextMenu(
                item = mi, playerVm = playerVm, onArtist = onArtistClick, onAlbum = onAlbumClick,
                onDismiss = { menuItem = null }, showGoToArtist = false,
            )
        }
    }
}

@Composable
private fun ShelfRowV2(
    shelf: BrowseShelf,
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    onCuratorClick: (id: String) -> Unit,
    onSeeAll: (roomId: String) -> Unit,
    onLong: (Album) -> Unit,
) {
    val open: (Album) -> Unit = { album ->
        val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
        val isStation = album.id.startsWith("ra.")
        val isCurator = album.id.startsWith("ac-") || album.id.startsWith("c-") || album.id.startsWith("mr-")
        val isSong = album.type == "songs"
        when {
            isCurator -> onCuratorClick(album.id)
            isStation -> playerVm.playStation(album.id, album.artworkUrl(600))
            isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
            isSong -> playerVm.playSong(album)
            else -> onAlbumClick(album.id)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(shelf.title, modifier = Modifier.padding(start = 24.dp, end = 24.dp), onSeeAll = shelf.roomId?.let { { onSeeAll(it) } })
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 0.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(AmTokens.Space.md),
        ) {
            items(shelf.albums, key = { it.id }) { album ->
                AmCard(
                    title = album.title,
                    subtitle = album.artistName.ifBlank { null },
                    artworkUrl = album.artworkUrl(312),
                    width = 156,
                    onClick = { open(album) },
                    onLongClick = { onLong(album) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SpotlightRowV2(
    title: String,
    albums: List<Album>,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    onCuratorClick: (id: String) -> Unit,
    playerVm: PlayerViewModel,
    onLong: (Album) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, modifier = Modifier.padding(start = 24.dp, end = 24.dp))
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 0.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(albums, key = { it.id }, contentType = { "spotlight" }) { album ->
                val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                val isStation = album.id.startsWith("ra.")
                val isCurator = album.id.startsWith("ac-") || album.id.startsWith("c-") || album.id.startsWith("mr-")
                val isSong = album.type == "songs"
                SpotlightHeroCard(album = album, width = 360, onLongClick = { onLong(album) }, onClick = {
                    when {
                        isCurator -> onCuratorClick(album.id)
                        isStation -> playerVm.playStation(album.id, album.artworkUrl(600))
                        isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                        isSong -> playerVm.playSong(album)
                        else -> onAlbumClick(album.id)
                    }
                })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoRowV2(title: String, videos: List<Song>, playerVm: PlayerViewModel, onLong: (Album) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, modifier = Modifier.padding(start = 24.dp, end = 24.dp))
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 0.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(videos.size) { idx ->
                val v = videos[idx]
                Surface(
                    onClick = { playerVm.playAlbum(videos, idx) },
                    onLongClick = { onLong(Album(id = v.id, title = v.title, artistName = v.artistName, artistId = v.artistId, artworkUrl = v.artworkUrl, type = "music-videos")) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                ) {
                    Column(Modifier.width(230.dp)) {
                        Box(Modifier.width(230.dp).height(129.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1A1A))) {
                            if (v.artworkUrl != null)
                                AsyncImage(model = v.artworkUrl(480), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFA233B)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                Text("MV", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(v.title, fontSize = 12.5.sp, color = Color(0xFFF2F2F5), maxLines = 1, fontWeight = FontWeight.SemiBold)
                        Text(v.artistName, fontSize = 10.5.sp, color = Color(0xFF8A8A8E), maxLines = 1)
                    }
                }
            }
        }
    }
}
