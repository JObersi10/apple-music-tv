package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.components.AmCard
import com.applemusicktv.ui.components.SectionHeader
import com.applemusicktv.ui.theme.AmTokens
import com.applemusicktv.ui.viewmodel.HomeSection
import com.applemusicktv.ui.viewmodel.HomeViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

/**
 * v2 UI-overhaul — Phase 2 flagship: Home rebuilt on [AmCard] / [SectionHeader] against [AmTokens],
 * to match Apple Music's own shelf grammar. **Selected only when the New-UI flag is on** (Dev →
 * Interface → New UI); flag off keeps [HomeScreen] untouched, so this is fully revertible.
 *
 * Same data, same [HomeViewModel], same click routing as [HomeScreen] — this only changes how it
 * looks. Ambient wash / parallax / shared-element motion are later phases; this is cards + shelves.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreenV2(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    onCategoryClick: (String) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()

    if (state.isLoading && state.sections.isEmpty()) {
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
    if (state.sections.isEmpty()) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            Text("No content — set your Music User Token", color = Color(0xFF555555), fontSize = 14.sp)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(AmTokens.Color.Background),
        contentPadding = PaddingValues(top = 28.dp, bottom = 102.dp),
        verticalArrangement = Arrangement.spacedBy(AmTokens.Space.shelfGap),
    ) {
        items(state.sections, key = { it.title }) { section ->
            ShelfV2(section, playerVm, onAlbumClick, onPlaylistClick, onCategoryClick)
        }
    }
}

@Composable
private fun ShelfV2(
    section: HomeSection,
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    onCategoryClick: (String) -> Unit,
) {
    val open: (Album) -> Unit = { album ->
        val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
        val isStation = album.id.startsWith("ra.")
        val isCategory = album.id.startsWith("ac-") || album.id.startsWith("c-") || album.id.startsWith("mr-")
        when {
            isCategory -> onCategoryClick(album.id)
            isStation  -> playerVm.playStation(album.id, album.artworkUrl(600))
            isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
            else       -> onAlbumClick(album.id)
        }
    }
    // picks = big square lockups; gradient = wide cards; everything else = standard shelf.
    val width = when (section.style) { "picks" -> 200; "gradient" -> 250; else -> 156 }
    val aspect = if (section.style == "gradient") 1.6f else 1f

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(section.title, modifier = Modifier.padding(start = 24.dp, end = 24.dp))
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 0.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(AmTokens.Space.md),
        ) {
            items(section.albums, key = { it.id }) { album ->
                AmCard(
                    title = album.title,
                    subtitle = album.artistName.ifBlank { null },
                    artworkUrl = album.artworkUrl(width * 2),
                    width = width,
                    aspectRatio = aspect,
                    onClick = { open(album) },
                    onLongClick = {
                        if (album.id.startsWith("pl.") || album.id.startsWith("p.")) playerVm.shufflePlayPlaylist(album.id)
                    },
                )
            }
        }
    }
}
