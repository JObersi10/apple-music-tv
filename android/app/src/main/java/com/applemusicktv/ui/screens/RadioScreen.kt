package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.datasource.RadioStation
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.theme.AmTokens
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.ui.viewmodel.RadioViewModel

/**
 * Radio tab. Top = Apple Music Radio (grouping 168577: live stations, shows, DJ mixes, broadcasters,
 * genre stations) rendered as horizontal shelves. Bottom = our own internet-radio directory
 * (radio-browser.info), the "Local Radio" section the user asked to sit under Apple's content.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RadioScreen(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    onCuratorClick: (id: String) -> Unit = {},
    onArtistClick: (id: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm: RadioViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var addDraft by remember { mutableStateOf("") }
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(adding) { if (adding) runCatching { addFocus.requestFocus() } }

    LazyColumn(
        // No horizontal padding here — each shelf owns start=24/end=0 so the first card sits at the
        // margin and the row bleeds off the right edge, exactly like Home/New (fixes the cutoff).
        modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // No page title — the top nav bar already labels this "Radio".
        // ── Apple Music Radio shelves ───────────────────────────────────────────
        if (state.appleLoading && state.appleSections.isEmpty()) {
            item { com.applemusicktv.ui.components.ShelfSkeleton(rows = 5) }
        }
        items(state.appleSections, key = { "ap-" + it.title }) { section ->
            AppleShelf(section, playerVm, onAlbumClick, onPlaylistClick, onCuratorClick, onArtistClick)
        }

        // ── Local Radio (internet-radio directory) ──────────────────────────────
        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text("Local Radio", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Text("Free internet stations from around the world", fontSize = 11.sp, color = Color(0xFF777777),
                    modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                // Search
                Row(
                    Modifier.width(360.dp).height(38.dp).background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = state.query, onValueChange = vm::onQueryChange, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Color(0xFFFA233B)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (state.query.isEmpty()) Text("Search stations…", color = Color(0xFF555555), fontSize = 14.sp)
                            inner()
                        },
                    )
                    Chip("Go") { vm.search() }
                }
                Spacer(Modifier.height(12.dp))
                // Country chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelChip("Popular", state.activeCountry == "") { vm.selectPopular() }
                    state.countries.forEach { c -> SelChip(c, state.activeCountry == c) { vm.selectCountry(c) } }
                    if (adding) {
                        Row(
                            Modifier.height(34.dp).background(Color(0xFF1C1C1E), RoundedCornerShape(50)).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                value = addDraft, onValueChange = { addDraft = it }, singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                                cursorBrush = SolidColor(Color(0xFFFA233B)),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { vm.addCountry(addDraft); addDraft = ""; adding = false }),
                                modifier = Modifier.width(120.dp).focusRequester(addFocus),
                                decorationBox = { inner ->
                                    if (addDraft.isEmpty()) Text("Country name…", color = Color(0xFF555555), fontSize = 13.sp)
                                    inner()
                                },
                            )
                        }
                    } else Chip("＋") { adding = true }
                }
                state.correctionNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Showing $it", fontSize = 11.sp, color = Color(0xFFFA233B))
                }
            }
        }
        if (state.loading && state.stations.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator(color = Color(0xFFFA233B)) } }
        } else if (state.stations.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().height(80.dp), Alignment.Center) { Text("No stations found", color = Color(0xFF666666), fontSize = 14.sp) } }
        } else {
            items(state.stations, key = { "ir-" + it.id }) { st ->
                StationRow(st) { playerVm.playInternetRadio(st.name, st.streamUrl, st.tags.ifBlank { st.country }, st.faviconUrl) }
            }
        }
    }
}

/** One Apple radio shelf — album/station cards, or 16:9 video cards for video shelves. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppleShelf(
    section: com.applemusicktv.data.network.HomeSection,
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    onCuratorClick: (id: String) -> Unit,
    onArtistClick: (id: String) -> Unit,
) {
    // Same shelf grammar as Home/New (SectionHeader + AmCard 156 + start-24/end-0 contentPadding so
    // the first card sits at the title margin and the row bleeds off the right edge, uncut).
    Column(Modifier.fillMaxWidth()) {
        com.applemusicktv.ui.components.SectionHeader(section.title, modifier = Modifier.padding(start = 24.dp, end = 24.dp))
        if (section.videos.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 0.dp, bottom = 6.dp),
            ) {
                items(section.videos.size) { idx ->
                    val v = section.videos[idx]
                    val art = (v.artworkUrl ?: "").replace("{w}", "480").replace("{h}", "270").replace("{f}", "jpg")
                    Surface(
                        onClick = { playerVm.playVideos(section.videos, idx) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                    ) {
                        Column(Modifier.width(230.dp)) {
                            Box(Modifier.width(230.dp).height(129.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1A1A))) {
                                if (v.artworkUrl != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(v.title, fontSize = 12.5.sp, color = Color(0xFFF2F2F5), maxLines = 1, fontWeight = FontWeight.SemiBold)
                            Text(v.artistName, fontSize = 10.5.sp, color = Color(0xFF8A8A8E), maxLines = 1)
                        }
                    }
                }
            }
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(AmTokens.Space.md),
            contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 0.dp, bottom = 6.dp),
        ) {
            items(section.albums, key = { it.id }) { dto ->
                val isPlaylist = dto.id.startsWith("pl.") || dto.id.startsWith("p.")
                val isCurator = dto.id.startsWith("ac-") || dto.id.startsWith("c-") || dto.id.startsWith("mr-")
                val isStation = dto.id.startsWith("ra.")
                val isArtist = dto.type == "artists" || dto.id.startsWith("r.")
                val isSong = dto.type == "songs"
                com.applemusicktv.ui.components.AmCard(
                    title = dto.title,
                    subtitle = dto.artistName.ifBlank { null },
                    artworkUrl = dto.artworkUrl?.let { (it).replace("{w}", "312").replace("{h}", "312").replace("{f}", "jpg") },
                    width = 156,
                    onClick = {
                        when {
                            isCurator  -> onCuratorClick(dto.id)
                            isStation  -> playerVm.playStation(dto.id, (dto.artworkUrl ?: "").replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg"))
                            isArtist   -> onArtistClick(dto.id)
                            isSong     -> playerVm.playSong(Album(id = dto.id, title = dto.title, artistName = dto.artistName, artworkUrl = dto.artworkUrl, artworkBgColor = dto.artworkBgColor, type = "songs"))
                            isPlaylist -> onPlaylistClick(dto.id, dto.title, (dto.artworkUrl ?: "").replace("{w}", "500").replace("{h}", "500").replace("{f}", "jpg"))
                            else       -> onAlbumClick(dto.id)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StationRow(st: RadioStation, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF141416), focusedContainerColor = Color(0xFF232325)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.005f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(38.dp).background(Color(0xFF2A2A2C), RoundedCornerShape(6.dp)), Alignment.Center) {
                if (st.faviconUrl != null) AsyncImage(model = st.faviconUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                else Text("📻", fontSize = 17.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(st.name, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    st.country.uppercase().takeIf { it.isNotBlank() },
                    st.tags.takeIf { it.isNotBlank() }?.split(",")?.firstOrNull()?.trim(),
                    st.bitrate.takeIf { it > 0 }?.let { "${it}kbps" },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, fontSize = 10.sp, color = Color(0xFF777777), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.PLAY, size = 13.dp, color = Color(0xFFFA233B))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2A2A2C), focusedContainerColor = Color(0xFFFA233B)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(label, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFFFA233B) else Color(0xFF2A2A2C),
            focusedContainerColor = if (selected) Color(0xFFE01F33) else Color(0xFF3A3A3C),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(label, fontSize = 12.sp, color = Color.White, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
