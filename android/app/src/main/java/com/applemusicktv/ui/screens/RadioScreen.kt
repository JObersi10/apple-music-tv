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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.datasource.RadioStation
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.ui.viewmodel.RadioViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RadioScreen(playerVm: PlayerViewModel, modifier: Modifier = Modifier) {
    val vm: RadioViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var addDraft by remember { mutableStateOf("") }
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(adding) { if (adding) runCatching { addFocus.requestFocus() } }

    Column(modifier.fillMaxSize().background(Color(0xFF0A0A0A)).padding(horizontal = 48.dp, vertical = 28.dp)) {
        // Title + search on one line
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Radio", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.width(24.dp))
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
        }

        Spacer(Modifier.height(12.dp))

        // Country chips: Popular + detected/added + "+"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelChip("Popular", state.activeCountry == "") { vm.selectPopular() }
            state.countries.forEach { c ->
                SelChip(c, state.activeCountry == c) { vm.selectCountry(c) }
            }
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
                        keyboardActions = KeyboardActions(onDone = {
                            vm.addCountry(addDraft); addDraft = ""; adding = false
                        }),
                        modifier = Modifier.width(120.dp).focusRequester(addFocus),
                        decorationBox = { inner ->
                            if (addDraft.isEmpty()) Text("Country name…", color = Color(0xFF555555), fontSize = 13.sp)
                            inner()
                        },
                    )
                }
            } else {
                Chip("＋") { adding = true }
            }
        }

        state.correctionNote?.let {
            Spacer(Modifier.height(6.dp))
            Text("Showing $it", fontSize = 11.sp, color = Color(0xFFFA233B))
        }

        Spacer(Modifier.height(12.dp))

        if (state.loading && state.stations.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Color(0xFFFA233B)) }
        } else if (state.stations.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No stations found", color = Color(0xFF666666), fontSize = 14.sp) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.stations, key = { it.id }) { st ->
                    StationRow(st) { playerVm.playInternetRadio(st.name, st.streamUrl, st.tags.ifBlank { st.country }) }
                }
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
            Text("▶", fontSize = 13.sp, color = Color(0xFFFA233B))
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
