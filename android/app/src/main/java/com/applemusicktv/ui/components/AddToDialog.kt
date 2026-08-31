package com.applemusicktv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.PlaylistDto
import com.applemusicktv.ui.viewmodel.PlayerViewModel

/**
 * The single "Add to…" sheet used everywhere a song can be added — long-press menus and the Now
 * Playing ··· menu. First row is **Library**, then the user's editable playlists (Apple's own order).
 * Tapping one adds the song and dismisses. Reused so every surface stays identical.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddToDialog(playerVm: PlayerViewModel, song: Song, onDismiss: () -> Unit) {
    var playlists by remember { mutableStateOf<List<PlaylistDto>?>(null) }
    LaunchedEffect(Unit) { playlists = playerVm.editablePlaylists() }
    Dialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        Column(
            Modifier.width(360.dp).heightIn(max = 480.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Add to…", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))
            Spacer(Modifier.height(4.dp))
            // Library always first.
            AddRow(Glyph.PLUS, "Library", { playerVm.addToLibrary(song); onDismiss() },
                Modifier.focusRequester(firstFocus))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)).padding(vertical = 2.dp))
            val pls = playlists
            when {
                pls == null -> Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                pls.isEmpty() -> Text("No editable playlists", color = Color(0xFF888888), fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(pls) { pl ->
                        AddRow(Glyph.QUEUE, pl.name, { playerVm.addToPlaylist(pl.id, pl.name, song); onDismiss() })
                    }
                }
            }
        }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { firstFocus.requestFocus() } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddRow(icon: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(icon, size = 17.dp, color = Color(0xFFB0B0B4))
            }
            Text(label, fontSize = 15.sp, color = Color.White, maxLines = 1)
        }
    }
}
