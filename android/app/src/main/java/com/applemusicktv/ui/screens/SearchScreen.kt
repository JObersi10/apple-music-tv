package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import coil.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.SearchViewModel
import com.applemusicktv.data.repository.Curator

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(playerVm: PlayerViewModel, onAlbumClick: (String) -> Unit = {}, onArtistClick: (String) -> Unit = {}, onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> }, onCuratorClick: (id: String, kind: String) -> Unit = { _, _ -> }, modifier: Modifier = Modifier) {
    val vm: SearchViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val recents by vm.recentSearches.collectAsState()
    val focusRequester = remember { FocusRequester() }
    // Keyboard only opens once the user explicitly selects the search box —
    // otherwise entering the tab auto-focuses the field and pops the IME.
    var editing by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<com.applemusicktv.data.model.Song?>(null) }
    // Same guard the other screens use: the OK release that ends a long-press would
    // otherwise land on the first menu item the moment it takes focus.
    var clickBlocked by remember { mutableStateOf(false) }
    /** Set once the text field has really taken focus — see onFocusChanged below. */
    var hasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        // Only grab focus when the user opened the field. Re-running this after every
        // query change is what kept popping the keyboard back up while typing.
        if (editing) runCatching { focusRequester.requestFocus() } else hasFocused = false
    }

    val ctx = LocalContext.current
    val view = LocalView.current
    /** Compose's hide() is a no-op on Fire TV; go through the platform IMM as well. */
    fun closeEditor() {
        editing = false
        runCatching {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    // Back closes the editor instead of leaving the tab with the IME still up.
    androidx.activity.compose.BackHandler(enabled = editing) { closeEditor() }

    Column(modifier = modifier.fillMaxSize().padding(48.dp)) {
        Text("Search", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.height(10.dp))

        if (editing) {
            Row(
                modifier = Modifier.fillMaxWidth(0.5f).height(40.dp)
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = state.query, onValueChange = vm::onQueryChange,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color(0xFFFA233B)), singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { closeEditor() },
                        onDone = { closeEditor() },
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester)
                        // Only close once it has actually held focus: onFocusChanged
                        // fires with isFocused=false on mount, before the
                        // FocusRequester runs, which closed the editor instantly.
                        .onFocusChanged {
                            if (it.isFocused) hasFocused = true
                            else if (hasFocused && editing) editing = false
                        },
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) Text("Artists, albums, songs…", color = Color(0xFF555555), fontSize = 15.sp)
                        inner()
                    }
                )
                if (state.query.isNotEmpty()) {
                    Surface(
                        onClick = { vm.onQueryChange(""); runCatching { focusRequester.requestFocus() } },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF3A3A3C)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    ) {
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.CLOSE, size = 13.dp, color = Color(0xFFAAAAAA))
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(0.5f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = { editing = true },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1C1C1E), focusedContainerColor = Color(0xFF2A2A2C),
                    ),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            state.query.ifEmpty { "Search — press to type…" },
                            color = if (state.query.isEmpty()) Color(0xFF777777) else Color.White,
                            fontSize = 15.sp,
                        )
                    }
                }
                if (state.query.isNotEmpty()) {
                    Surface(
                        onClick = { vm.onQueryChange("") },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1C1C1E), focusedContainerColor = Color(0xFF3A3A3C)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.CLOSE, size = 14.dp, color = Color(0xFFAAAAAA))
                        }
                    }
                }
            }
        }

        val ms = menuSong
        if (ms != null) {
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(ms) {
                kotlinx.coroutines.delay(800)
                clickBlocked = false
                runCatching { firstFocus.requestFocus() }
            }
            androidx.compose.ui.window.Dialog(onDismissRequest = { menuSong = null }) {
                Column(
                    Modifier.width(320.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E)).padding(vertical = 4.dp),
                ) {
                    Text(ms.title, fontSize = 13.sp, color = Color(0xFF999999), fontWeight = FontWeight.Medium,
                        maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    SearchContextItem("▶", "Play Next", { if (!clickBlocked) { playerVm.playNext(ms); menuSong = null } }, Modifier.focusRequester(firstFocus))
                    SearchContextItem("+", "Add to Queue", { if (!clickBlocked) { playerVm.addToQueue(ms); menuSong = null } })
                    if (ms.artistId != null) SearchContextItem("♪", "Go to Artist", { if (!clickBlocked) { onArtistClick(ms.artistId); menuSong = null } })
                    if (ms.albumId != null) SearchContextItem("◉", "Go to Album", { if (!clickBlocked) { onAlbumClick(ms.albumId); menuSong = null } })
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFA233B))
            }
            state.results != null && (state.results!!.songs.isNotEmpty() || state.results!!.albums.isNotEmpty() || state.results!!.artists.isNotEmpty() || state.results!!.playlists.isNotEmpty() || state.results!!.curators.isNotEmpty()) -> {
                val results = state.results!!
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    // Top Results — the strongest hit of each kind up front, the way Apple Music leads
                    // its search page. Editorial playlists first (what the user missed), then artist,
                    // album and the #1 song, so the best match is one focus move away.
                    item {
                        Text("Top Results", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                        // contentPadding (not Modifier.padding) so a focused, scaled card can scroll
                        // fully into view and its halo isn't sliced at the row edges.
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp)) {
                            // Editorial category first — it's the strongest match for terms like
                            // "formula 1" or "tomorrowland", and the one users can't find otherwise.
                            items(results.curators.take(1), key = { "top-cu-${it.id}" }) { cur ->
                                CuratorCard(cur, size = 150, onClick = { onCuratorClick(cur.id, cur.kind) })
                            }
                            items(results.playlists.take(2), key = { "top-pl-${it.id}" }) { pl ->
                                AlbumCard(album = pl, size = 150, onClick = { onPlaylistClick(pl.id, pl.title, pl.artworkUrl(500) ?: "") })
                            }
                            results.artists.firstOrNull()?.let { artist ->
                                item(key = "top-ar-${artist.id}") {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
                                        Surface(
                                            onClick = { onArtistClick(artist.id) },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                                            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2A2A2A), focusedContainerColor = Color(0xFF3A3A3A)),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                                            modifier = Modifier.size(120.dp),
                                        ) {
                                            Box(Modifier.fillMaxSize()) {
                                                if (artist.artworkUrl != null) AsyncImage(
                                                    model = artist.artworkUrl.replace("{w}", "240").replace("{h}", "240").replace("{f}", "jpg"),
                                                    contentDescription = artist.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(artist.name, fontSize = 12.sp, color = Color.White, maxLines = 1)
                                        Text("Artist", fontSize = 10.sp, color = Color(0xFF999999))
                                    }
                                }
                            }
                            items(results.albums.take(2), key = { "top-al-${it.id}" }) { album ->
                                val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                                AlbumCard(album = album, size = 150, onClick = {
                                    if (isPlaylist) onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "") else onAlbumClick(album.id)
                                })
                            }
                        }
                    }
                    if (results.curators.isNotEmpty()) {
                        item {
                            Text("Categories", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                                items(results.curators, key = { it.id }) { cur ->
                                    CuratorCard(cur, onClick = { onCuratorClick(cur.id, cur.kind) })
                                }
                            }
                        }
                    }
                    if (results.playlists.isNotEmpty()) {
                        item {
                            Text("Playlists", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                                items(results.playlists, key = { it.id }) { pl ->
                                    AlbumCard(album = pl, size = 130, onClick = { onPlaylistClick(pl.id, pl.title, pl.artworkUrl(500) ?: "") })
                                }
                            }
                        }
                    }
                    if (results.artists.isNotEmpty()) {
                        item {
                            Text("Artists", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                                items(results.artists, key = { it.id }) { artist ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(96.dp),
                                    ) {
                                        Surface(
                                            onClick = { onArtistClick(artist.id) },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                                            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2A2A2A), focusedContainerColor = Color(0xFF3A3A3A)),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                                            modifier = Modifier.size(80.dp),
                                        ) {
                                            Box(Modifier.fillMaxSize()) {
                                                if (artist.artworkUrl != null) {
                                                    coil.compose.AsyncImage(
                                                        model = artist.artworkUrl.replace("{w}", "160").replace("{h}", "160").replace("{f}", "jpg"),
                                                        contentDescription = artist.name,
                                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(artist.name, fontSize = 11.sp, color = Color.White, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                    // Two columns of compact rows — a TV is wide, one column wasted it.
                    if (results.songs.isNotEmpty()) {
                        item {
                            Text("Songs", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        val rows = results.songs.chunked(2)
                        itemsIndexed(rows, key = { _, r -> r.first().id }) { rowIdx, row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                row.forEachIndexed { colIdx, song ->
                                    val flatIdx = rowIdx * 2 + colIdx
                                    Surface(
                                        onClick = { playerVm.playAlbum(results.songs, flatIdx) },
                                        onLongClick = { menuSong = song; clickBlocked = true },
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = Color.Transparent,
                                            focusedContainerColor = Color(0xFF2A2A2A),
                                        ),
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            AsyncImage(
                                                model = song.artworkUrl(100),
                                                contentDescription = song.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(4.dp)),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(song.title, fontSize = 13.sp, color = Color.White, maxLines = 1)
                                                Text(
                                                    listOf(song.artistName, song.albumName)
                                                        .filter { it.isNotBlank() }
                                                        .joinToString(" — "),
                                                    fontSize = 11.sp, color = Color(0xFF999999), maxLines = 1,
                                                )
                                            }
                                            Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFF777777))
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (results.albums.isNotEmpty()) {
                        item {
                            Text("Albums", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(7),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(300.dp),
                            ) {
                                items(results.albums, key = { it.id }) { album ->
                                    val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                                    AlbumCard(album = album, size = 110, onClick = {
                                        if (isPlaylist) onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                                        else onAlbumClick(album.id)
                                    })
                                }
                            }
                        }
                    }
                }
            }
            state.query.length < 2 -> {
                // Category tiles + recents when not searching
                if (state.categories.isNotEmpty() || recents.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        // Recent searches first — retyping on a remote is the slow part.
                        if (recents.isNotEmpty()) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Recent", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Spacer(Modifier.width(12.dp))
                                    Surface(
                                        onClick = { vm.clearRecents() },
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = Color(0xFF1E1E1E),
                                            focusedContainerColor = Color(0xFF3A3A3A),
                                        ),
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                    ) {
                                        Text("Clear", fontSize = 12.sp, color = Color(0xFFBBBBBB),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                                    items(recents, key = { it }) { term ->
                                        Surface(
                                            onClick = { vm.runRecent(term) },
                                            onLongClick = { vm.removeRecent(term) },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = Color(0xFF2A2A2A),
                                                focusedContainerColor = Color(0xFF3A3A3A),
                                            ),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                        ) {
                                            Text(term, fontSize = 13.sp, color = Color.White,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
                        // Genre / mood / decade tiles — Apple's "Browse by Genre" grid.
                        state.categories.forEach { group ->
                            item {
                                Text(group.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                                // vertical contentPadding so the focused tile's border/glow isn't
                                // sliced at the row's top/bottom edge.
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                                    items(group.items, key = { it.id }) { cur ->
                                        CategoryTile(cur.name, cur.artworkUrl) { onCuratorClick(cur.id, cur.kind) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("Start typing to search Apple Music", color = Color(0xFF555555), fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchContextItem(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2E2E30)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(icon, fontSize = 16.sp, color = Color(0xFF888888), modifier = Modifier.width(22.dp))
            Text(label, fontSize = 15.sp, color = Color.White)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CuratorCard(cur: Curator, size: Int = 130, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size.dp)) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E1E20), focusedContainerColor = Color(0xFF2E2E30)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            modifier = Modifier.size(size.dp),
        ) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (cur.artworkUrl != null) AsyncImage(
                    model = cur.artworkUrl, contentDescription = cur.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                ) else com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.MUSIC_NOTE, size = 40.dp, color = Color(0xFF666666))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(cur.name, fontSize = 12.sp, color = Color.White, maxLines = 2)
        Text("Category", fontSize = 10.sp, color = Color(0xFF999999))
    }
}

/** Colourful category tile (genre/mood/decade) — editorial art with label on a scrim. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryTile(name: String, artworkUrl: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1C1C1E), focusedContainerColor = Color(0xFF1C1C1E)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(12.dp)),
        ),
        modifier = Modifier.width(180.dp).height(110.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (artworkUrl != null) AsyncImage(
                model = artworkUrl, contentDescription = name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x22000000), 1f to Color(0xCC000000)),
            ))
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}
