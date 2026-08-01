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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import coil.compose.AsyncImage
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(playerVm: PlayerViewModel, onAlbumClick: (String) -> Unit = {}, onArtistClick: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val vm: SearchViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val recents by vm.recentSearches.collectAsState()
    val focusRequester = remember { FocusRequester() }
    // Keyboard only opens once the user explicitly selects the search box —
    // otherwise entering the tab auto-focuses the field and pops the IME.
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }

    Column(modifier = modifier.fillMaxSize().padding(48.dp)) {
        Text("Search", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.height(20.dp))

        if (editing) {
            Box(
                modifier = Modifier.fillMaxWidth(0.6f).height(52.dp)
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = state.query, onValueChange = vm::onQueryChange,
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                    cursorBrush = SolidColor(Color(0xFFFA233B)), singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) Text("Artists, albums, songs…", color = Color(0xFF555555), fontSize = 18.sp)
                        inner()
                    }
                )
            }
        } else {
            Surface(
                onClick = { editing = true },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF1C1C1E), focusedContainerColor = Color(0xFF2A2A2C),
                ),
                modifier = Modifier.fillMaxWidth(0.6f).height(52.dp),
            ) {
                Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        state.query.ifEmpty { "Search — press to type…" },
                        color = if (state.query.isEmpty()) Color(0xFF777777) else Color.White,
                        fontSize = 18.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFA233B))
            }
            state.results != null && (state.results!!.songs.isNotEmpty() || state.results!!.albums.isNotEmpty() || state.results!!.artists.isNotEmpty()) -> {
                val results = state.results!!
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (results.artists.isNotEmpty()) {
                        item {
                            Text("Artists", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    // Songs were fetched and mapped all along but never rendered.
                    if (results.songs.isNotEmpty()) {
                        item {
                            Text("Songs", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                        }
                        itemsIndexed(results.songs, key = { _, s -> s.id }) { idx, song ->
                            Surface(
                                onClick = { playerVm.playAlbum(results.songs, idx) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Color(0xFF2A2A2A),
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(
                                        model = song.artworkUrl(120),
                                        contentDescription = song.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, fontSize = 15.sp, color = Color.White, maxLines = 1)
                                        Text(song.artistName, fontSize = 12.sp, color = Color(0xFF999999), maxLines = 1)
                                    }
                                    Text(song.durationFormatted, fontSize = 12.sp, color = Color(0xFF777777))
                                }
                            }
                        }
                    }
                    if (results.albums.isNotEmpty()) {
                        item {
                            Text("Albums", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.height(400.dp),
                            ) {
                                items(results.albums, key = { it.id }) { album ->
                                    AlbumCard(album = album, size = 160, onClick = { onAlbumClick(album.id) })
                                }
                            }
                        }
                    }
                }
            }
            state.query.length < 2 -> {
                // Genre browsing when not searching
                if (state.genres.isNotEmpty() || recents.isNotEmpty()) {
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
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        item {
                            Text("Browse by Genre", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(state.genres, key = { it.id }) { genre ->
                                    val isSelected = genre.id == state.selectedGenreId
                                    Surface(
                                        onClick = { vm.selectGenre(genre.id) },
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = if (isSelected) Color(0xFFFA233B) else Color(0xFF2A2A2A),
                                            focusedContainerColor = if (isSelected) Color(0xFFE01F33) else Color(0xFF3A3A3A),
                                        ),
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                    ) {
                                        Text(genre.name, fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                    }
                                }
                            }
                        }
                        if (state.genreLoading) {
                            item { Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator(color = Color(0xFFFA233B)) } }
                        }
                        state.genreContent?.sections?.forEach { section ->
                            item {
                                Text(section.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    items(section.albums, key = { it.id }) { item ->
                                        val isPlaylist = item.id.startsWith("pl.") || item.id.startsWith("p.")
                                        com.applemusicktv.ui.components.AlbumCard(
                                            album = com.applemusicktv.data.model.Album(
                                                id = item.id, title = item.title, artistName = item.artistName,
                                                artworkUrl = item.artworkUrl, artworkBgColor = item.artworkBgColor,
                                            ),
                                            size = 140,
                                            onClick = { onAlbumClick(item.id) },
                                        )
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
