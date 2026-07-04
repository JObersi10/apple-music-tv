package com.applemusicktv.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Artist
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.PlaylistDto
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.LibraryViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.ui.viewmodel.SortField

private enum class LibrarySection(val label: String) {
    Playlists("Playlists"), Albums("Albums"), Artists("Artists"), Songs("Songs"),
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String?) -> Unit = { _, _, _ -> },
    onArtistClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableStateOf(LibrarySection.Playlists) }
    val state by vm.state.collectAsState()

    Row(modifier = modifier.fillMaxSize()) {
        // Sidebar
        LazyColumn(
            modifier = Modifier.width(190.dp).fillMaxHeight().background(Color(0xFF0D0D0D)),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(LibrarySection.entries) { s ->
                SidebarItem(s.label, s == section) { section = s }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val hasAnyData = state.playlists.isNotEmpty() || state.albums.isNotEmpty() ||
                state.artists.isNotEmpty() || state.songs.isNotEmpty()
            when {
                !state.hasMut -> NoMutPlaceholder()
                // Only show the full-screen spinner on a truly empty first load.
                // If cached/previous data exists, keep showing it while refreshing
                // so focus stays in the content and doesn't jump to the top bar.
                state.isLoading && !hasAnyData -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Error: ${state.error}", color = Color(0xFFFF3B30), fontSize = 14.sp)
                }
                else -> Column(Modifier.fillMaxSize()) {
                    SortBar(section, state.sort.field, state.sort.dir.name) { vm.setSort(it) }
                    Box(Modifier.weight(1f)) {
                        when (section) {
                            LibrarySection.Playlists -> {
                                val pinnedIds by vm.pinnedIds.collectAsState()
                                PlaylistGrid(
                                    playlists  = vm.sortedPlaylists(),
                                    pinnedIds  = pinnedIds,
                                    onOpen     = { onPlaylistClick(it.id, it.name, it.artworkUrl(500)) },
                                    onPlay     = { vm.playPlaylist(it.id, playerVm) },
                                    onTogglePin = { vm.togglePin(it.id) },
                                )
                            }
                            LibrarySection.Albums  -> AlbumGrid(vm.sortedAlbums(), onAlbumClick)
                            LibrarySection.Artists -> ArtistList(vm.sortedArtists(), onArtistClick)
                            LibrarySection.Songs   -> SongList(vm.sortedSongs(), playerVm, onArtistClick)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SortBar(
    section: LibrarySection,
    currentField: SortField,
    currentDir: String,
    onSort: (SortField) -> Unit,
) {
    val fields = when (section) {
        LibrarySection.Playlists -> listOf(SortField.DEFAULT to "Playlist Order", SortField.NAME to "Name")
        LibrarySection.Albums    -> listOf(SortField.DEFAULT to "Date Added", SortField.NAME to "Name", SortField.ARTIST to "Artist")
        LibrarySection.Artists   -> listOf(SortField.DEFAULT to "Date Added", SortField.NAME to "Name")
        LibrarySection.Songs     -> listOf(SortField.DEFAULT to "Date Added", SortField.NAME to "Title", SortField.ARTIST to "Artist")
    }
    val dirArrow = if (currentDir == "ASC") " ↑" else " ↓"
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0A0A)).padding(horizontal = 32.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sort:", fontSize = 11.sp, color = Color(0xFF555555))
        fields.forEach { (field, label) ->
            val isActive = currentField == field
            Surface(
                onClick = { onSort(field) },
                shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor        = if (isActive) Color(0xFFFA233B) else Color(0xFF1E1E1E),
                    focusedContainerColor = if (isActive) Color(0xFFE01F33) else Color(0xFF2A2A2A),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            ) {
                Text(
                    text = label + if (isActive) dirArrow else "",
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistGrid(
    playlists: List<PlaylistDto>,
    pinnedIds: Set<String>,
    onOpen: (PlaylistDto) -> Unit,
    onPlay: (PlaylistDto) -> Unit,
    onTogglePin: (PlaylistDto) -> Unit,
) {
    if (playlists.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No playlists", color = Color(0xFF555555)) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 32.dp, end = 48.dp, top = 20.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            PlaylistCard(
                playlist    = playlist,
                pinned      = playlist.id in pinnedIds,
                onOpen      = { onOpen(playlist) },
                onPlay      = { onPlay(playlist) },
                onTogglePin = { onTogglePin(playlist) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistCard(
    playlist: PlaylistDto,
    pinned: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Surface(
        onClick      = onOpen,
        onLongClick  = onTogglePin,
        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1A1A1A), focusedContainerColor = Color(0xFF252525)),
        scale  = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(Color(0xFF2A1A2E)),
            ) {
                if (playlist.artworkUrl != null) {
                    AsyncImage(
                        model = playlist.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("♪", fontSize = 32.sp, color = Color(0xFF444444))
                    }
                }
                if (pinned) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    ) {
                        Text("📌", fontSize = 10.sp)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, fontSize = 12.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium)
                    if (playlist.curatorName.isNotEmpty())
                        Text(playlist.curatorName, fontSize = 10.sp, color = Color(0xFF666666), maxLines = 1)
                }
                Surface(
                    onClick = onPlay,
                    shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor        = Color(0xFF2A2A2A),
                        focusedContainerColor = Color(0xFFFA233B),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("▶", fontSize = 9.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<Album>, onAlbumClick: (String) -> Unit) {
    if (albums.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No albums", color = Color(0xFF555555)) }; return }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 32.dp, end = 48.dp, top = 20.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(album = album, size = 150, onClick = { onAlbumClick(album.id) })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistList(artists: List<Artist>, onArtistClick: (String) -> Unit) {
    if (artists.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No artists", color = Color(0xFF555555)) }; return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(artists, key = { it.id }) { artist ->
            Surface(onClick = { onArtistClick(artist.id) }, modifier = Modifier.fillMaxWidth(), shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF1C1C1E)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color(0xFF2A2A2A))) {
                        if (artist.artworkUrl != null) AsyncImage(model = artist.artworkUrl(88), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(artist.name, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongList(songs: List<Song>, playerVm: PlayerViewModel, onArtistClick: (String) -> Unit = {}) {
    if (songs.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No songs", color = Color(0xFF555555)) }; return }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(songs.size) { idx ->
            val song = songs[idx]
            Surface(
                onClick = { playerVm.playAlbum(songs, idx) },
                onLongClick = { menuSong = song },
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF1C1C1E)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A2A2A))) {
                        if (song.artworkUrl != null) AsyncImage(model = song.artworkUrl(80), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(song.title, fontSize = 13.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium)
                        Text(song.artistName, fontSize = 11.sp, color = Color(0xFF666666), maxLines = 1)
                    }
                    Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFF555555))
                }
            }
        }
    }
    menuSong?.let { s ->
        SongContextMenu(
            song = s,
            onDismiss = { menuSong = null },
            onPlayNext = { playerVm.playNext(s); menuSong = null },
            onAddToQueue = { playerVm.addToQueue(s); menuSong = null },
            onGoToArtist = s.artistId?.let { id -> { onArtistClick(id); menuSong = null } },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongContextMenu(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onGoToArtist: (() -> Unit)? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        // Delay focus to prevent the OK key-up from the long-press triggering the first item
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(500); runCatching { firstFocus.requestFocus() } }
        Column(
            Modifier.width(320.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(song.title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(12.dp))
            ContextMenuItem("Play Next", onPlayNext, Modifier.focusRequester(firstFocus))
            ContextMenuItem("Add to Queue", onAddToQueue)
            if (onGoToArtist != null) ContextMenuItem("Go to Artist", onGoToArtist)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContextMenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2E2E30)),
    ) {
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

@Composable
private fun NoMutPlaceholder() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("No Music User Token", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Open Dev tab → use web server on phone to add token", color = Color(0xFF666666), fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val bg     by animateColorAsState(if (isSelected) Color(0xFF1A1A1A) else Color.Transparent, tween(150))
    val accent by animateColorAsState(if (isSelected) Color(0xFFFA233B) else Color.Transparent, tween(150))
    val text   by animateColorAsState(if (isSelected) Color.White else Color(0xFF999999), tween(150))
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = bg, focusedContainerColor = Color(0xFF252525))) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(2.dp).height(20.dp).background(accent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
            Text(label, fontSize = 13.sp, color = text, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.padding(start = 14.dp))
        }
    }
}
