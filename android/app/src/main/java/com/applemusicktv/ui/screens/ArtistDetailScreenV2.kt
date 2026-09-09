package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.AmCard
import com.applemusicktv.ui.components.SectionHeader
import com.applemusicktv.ui.theme.AmTokens
import com.applemusicktv.ui.viewmodel.ArtistDetailViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

/**
 * v2 UI-overhaul — Artist page on the shared [AmCard]/[SectionHeader]/[AmTokens] kit so it matches
 * [HomeScreenV2] / [BrowseScreenV2]. **Selected only when the New-UI flag is on**; flag off keeps the
 * original [ArtistDetailScreen]. Same [ArtistDetailViewModel] and click routing — look only.
 *
 * Apple's artist layout: full-bleed hero with the name + Play/Shuffle overlaid, then shelves (top
 * songs grid, albums, featured, similar-artist circles) in the same grammar as every other page.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ArtistDetailScreenV2(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ArtistDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(AmTokens.Color.Background), Alignment.Center) {
            CircularProgressIndicator(color = AmTokens.Color.Accent)
        }
        return
    }
    if (state.error != null) {
        Box(modifier.fillMaxSize().background(AmTokens.Color.Background), Alignment.Center) {
            Text("Error: ${state.error}", color = Color(0xFFFF3B30), fontSize = 14.sp)
        }
        return
    }

    var showAbout by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(AmTokens.Color.Background)) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        // Full-bleed hero with name + genres + Play/Shuffle overlaid at the bottom.
        item {
            // Hero: tall enough to feel full-bleed but sized so the whole block (art + name + pills)
            // fits the viewport — otherwise focusing the pills scrolls the art off the top. A SQUARE
            // source centre-cropped keeps the face in frame (a wide crop showed only the forehead).
            Box(Modifier.fillMaxWidth().height(430.dp)) {
                if (state.artworkUrl != null) {
                    AsyncImage(
                        model = state.artworkUrl?.replace("{w}", "1400")?.replace("{h}", "1400")?.replace("{f}", "jpg"),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0x22000000), Color(0xCC000000), AmTokens.Color.Background)),
                ))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 18.dp)) {
                    Text(state.name, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                    if (state.genres.isNotEmpty())
                        Text(state.genres.joinToString(" · "), fontSize = 12.sp, color = Color(0xFFCCCCCC),
                            modifier = Modifier.padding(top = 2.dp))
                    if (state.topSongs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HeroPill(com.applemusicktv.ui.components.Glyph.PLAY, "Play", AmTokens.Color.Accent) { playerVm.playAlbum(state.topSongs, 0) }
                            HeroPill(com.applemusicktv.ui.components.Glyph.SHUFFLE, "Shuffle", Color(0x33FFFFFF)) { playerVm.playAlbum(state.topSongs.shuffled(), 0) }
                            HeroPill(com.applemusicktv.ui.components.Glyph.RADIO, "Station", Color(0x33FFFFFF)) { vm.playStation { songs -> playerVm.playAlbum(songs, 0) } }
                        }
                    }
                }
            }
        }

        if (state.topSongs.isNotEmpty()) {
            item {
                com.applemusicktv.ui.components.SongGridRow(
                    title = "Top Songs",
                    items = state.topSongs,
                    title2 = { it.title },
                    subtitle = { it.artistName },
                    artUrl = { it.artworkUrl(96) },
                    onClick = { idx -> playerVm.playAlbum(state.topSongs, idx) },
                )
            }
        }

        if (state.musicVideos.isNotEmpty()) {
            item { HeaderV2("Music Videos") }
            item {
                LazyRow(contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 0.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.musicVideos.size) { i ->
                        val v = state.musicVideos[i]
                        Surface(
                            onClick = { playerVm.playAlbum(state.musicVideos, i) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                        ) {
                            Column(Modifier.width(250.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(AmTokens.Color.Surface)) {
                                    if (v.artworkUrl != null) AsyncImage(model = v.artworkUrl(600), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Text(v.title, fontSize = AmTokens.Type.TitleSize, color = AmTokens.Color.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }

        state.latestRelease?.let { latest ->
            item { HeaderV2("Latest Release") }
            item {
                Box(Modifier.padding(start = 24.dp, top = 2.dp)) {
                    AmCard(title = latest.title, subtitle = latest.artistName.ifBlank { null },
                        artworkUrl = latest.artworkUrl(360), width = 170, onClick = { onAlbumClick(latest.id) })
                }
            }
        }

        if (state.albums.isNotEmpty()) {
            item { HeaderV2("Albums") }
            item { AlbumRowV2(state.albums, onAlbumClick) }
        }
        if (state.featuredAlbums.isNotEmpty()) {
            item { HeaderV2("Featured") }
            item { AlbumRowV2(state.featuredAlbums, onAlbumClick) }
        }

        // About sits at the bottom (Apple order), just above Similar Artists.
        val aboutBio = (state.fullBio ?: state.bio)?.let(::stripHtml)
        val hasFacts = state.origin != null || state.born != null || state.genres.isNotEmpty()
        if (aboutBio != null || hasFacts) {
            item { HeaderV2("About ${state.name}") }
            item {
                Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (aboutBio != null) {
                        // Tap to open the full bio in a scrollable overlay (it's truncated to 8 lines here).
                        Surface(
                            onClick = { showAbout = true },
                            modifier = Modifier.weight(1f).heightIn(min = 130.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(AmTokens.Radius.Card)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = AmTokens.Color.SurfaceGlass, focusedContainerColor = AmTokens.Color.SurfaceGlassHi),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text(aboutBio, fontSize = 13.sp, color = AmTokens.Color.TextSecondary, lineHeight = 20.sp, maxLines = 8,
                                    overflow = TextOverflow.Ellipsis)
                                Text("Read more", fontSize = 11.sp, color = AmTokens.Color.Accent,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    if (hasFacts) {
                        Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            state.origin?.let { AboutFact("FROM", it) }
                            state.born?.let { AboutFact("BORN", it) }
                            if (state.genres.isNotEmpty()) AboutFact("GENRE", state.genres.joinToString(", "))
                        }
                    }
                }
            }
        }

        if (state.similarArtists.isNotEmpty()) {
            item { HeaderV2("Similar Artists") }
            item {
                LazyRow(contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 24.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.similarArtists, key = { it.id }) { a ->
                        // Circle-only focus (scale the image + white ring, no boxy dark halo). Name sits
                        // below the circle in a wide column, 2 lines, centred — so it never clips mid-word.
                        Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                onClick = { onArtistClick(a.id) },
                                shape = ClickableSurfaceDefaults.shape(CircleShape),
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = androidx.tv.material3.Border(
                                        androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)), shape = CircleShape)),
                            ) {
                                Box(Modifier.size(100.dp).clip(CircleShape).background(AmTokens.Color.Surface)) {
                                    if (a.artworkUrl != null) AsyncImage(
                                        model = a.artworkUrl.replace("{w}", "220").replace("{h}", "220").replace("{f}", "jpg"),
                                        contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(a.name, fontSize = AmTokens.Type.SubtitleSize, color = AmTokens.Color.TextPrimary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }

        // Full-bio scrollable overlay (opened by tapping the About card).
        if (showAbout) {
            val fullBio = (state.fullBio ?: state.bio)?.let(::stripHtml)
            androidx.activity.compose.BackHandler(enabled = true) { showAbout = false }
            Box(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.72f).fillMaxHeight(0.8f)
                        .clip(RoundedCornerShape(20.dp)).background(AmTokens.Color.SurfaceGlassHi).padding(32.dp),
                ) {
                    Text("About ${state.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(fullBio ?: "", fontSize = 15.sp, color = AmTokens.Color.TextSecondary, lineHeight = 24.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = { showAbout = false },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = AmTokens.Color.Accent, focusedContainerColor = AmTokens.Color.Accent),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    ) {
                        Text("Close", color = Color.White, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderV2(title: String) {
    SectionHeader(title, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp))
}

/** Apple's artistBio ships with HTML (<i>, <b>, <br>, entities). Strip tags + decode common
 *  entities so the About card shows clean prose instead of a literal "<i>". */
private fun stripHtml(s: String): String =
    s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
        .trim()

/** One labelled fact in the artist About column (FROM / BORN / GENRE). */
@Composable
private fun AboutFact(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = AmTokens.Color.LabelUppercase, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        Text(value, fontSize = 14.sp, color = AmTokens.Color.TextPrimary, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroPill(glyph: com.applemusicktv.ui.components.Glyph, label: String, bg: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = bg, focusedContainerColor = if (bg == AmTokens.Color.Accent) Color(0xFFFF3B54) else Color(0x55FFFFFF)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            com.applemusicktv.ui.components.Icon(glyph, size = 14.dp, color = Color.White)
            Text(label, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AlbumRowV2(albums: List<Album>, onAlbumClick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 0.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(AmTokens.Space.md)) {
        items(albums, key = { it.id }) { album ->
            AmCard(title = album.title, subtitle = album.artistName.ifBlank { null },
                artworkUrl = album.artworkUrl(312), width = 156, onClick = { onAlbumClick(album.id) })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRowV2(n: Int, song: Song, onPlay: () -> Unit, onQueue: () -> Unit) {
    Surface(
        onClick = onPlay,
        onLongClick = onQueue,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = AmTokens.Color.Surface),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("$n", fontSize = 13.sp, color = AmTokens.Color.TextSecondary, modifier = Modifier.width(26.dp))
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(AmTokens.Color.Surface)) {
                if (song.artworkUrl != null) AsyncImage(model = song.artworkUrl(88), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 14.sp, color = AmTokens.Color.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(song.albumName, fontSize = 11.sp, color = AmTokens.Color.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(song.durationFormatted, fontSize = 11.sp, color = AmTokens.Color.TextSecondary)
        }
    }
}
