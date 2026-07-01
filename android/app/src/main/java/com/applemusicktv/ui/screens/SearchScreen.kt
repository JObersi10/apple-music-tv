package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.SearchViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(playerVm: PlayerViewModel, onAlbumClick: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val vm: SearchViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
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
            state.results != null && state.results!!.albums.isNotEmpty() -> {
                Text("Albums", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, modifier = Modifier.padding(bottom = 14.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement   = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.results!!.albums, key = { it.id }) { album ->
                        AlbumCard(album = album, size = 160, onClick = { onAlbumClick(album.id) })
                    }
                }
            }
            state.query.length < 2 -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Start typing to search Apple Music", color = Color(0xFF555555), fontSize = 16.sp)
            }
        }
    }
}
