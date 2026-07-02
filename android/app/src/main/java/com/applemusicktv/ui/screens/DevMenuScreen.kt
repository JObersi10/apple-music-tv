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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.applemusicktv.ui.viewmodel.DevMenuViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DevMenuScreen(playerVm: PlayerViewModel, modifier: Modifier = Modifier) {
    val vm: DevMenuViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    var pcIpDraft by remember(state.pcServerIp) { mutableStateOf(state.pcServerIp) }

    Row(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        Column(
            modifier = Modifier.width(400.dp).fillMaxHeight()
                .background(Color(0xFF111111)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Dev", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)

            // Web server box
            if (state.webServerUrl.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF0D1F0D), RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("PHONE WEB SERVER", fontSize = 9.sp, color = Color(0xFF6BCB77),
                        fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(state.webServerUrl, fontSize = 15.sp, color = Color.White,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text("Open on phone → paste Music-User-Token", fontSize = 11.sp, color = Color(0xFF557755))
                }
            }

            Spacer(Modifier.height(4.dp))
            StatusChip("Server reachable", state.serverOk)
            StatusChip("Bearer token", state.hasBearer, if (!state.hasBearer) "Refresh to scrape" else null)
            StatusChip("Music-User-Token", state.hasMUT, state.mutSetAt?.let { "Set $it" } ?: if (!state.hasMUT) "Set via phone web server" else null)
            StatusChip(
                label = if (state.standaloneMode) "Standalone mode" else "Proxy mode",
                ok = !state.standaloneMode,
                sub = if (state.standaloneMode) "Direct Apple API — stream unavailable" else "PC server routing traffic",
            )
            if (state.lyricsOffsetMs != 0L) {
                StatusChip("Lyrics offset", true, "${state.lyricsOffsetMs}ms — adjust at ${state.webServerUrl}")
            }

            Spacer(Modifier.weight(1f))
            ActionBtn("Re-check Server", Color(0xFF1A2A1A)) { vm.recheckServer(playerVm) }
            ActionBtn("Refresh Status", Color(0xFF2A2A2A)) { vm.refresh() }

            // PC Server field — bottom of left panel
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("PC SERVER", fontSize = 9.sp, color = Color(0xFF888888),
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Enter laptop IP to route traffic through it", fontSize = 10.sp, color = Color(0xFF555555))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.weight(1f)
                            .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        BasicTextField(
                            value = pcIpDraft,
                            onValueChange = { pcIpDraft = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            cursorBrush = SolidColor(Color(0xFFFA233B)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.setPcServerIp(pcIpDraft) }),
                            decorationBox = { inner ->
                                if (pcIpDraft.isEmpty()) {
                                    Text("192.168.x.x", fontSize = 13.sp, color = Color(0xFF444444), fontFamily = FontFamily.Monospace)
                                }
                                inner()
                            },
                        )
                    }
                    ActionBtn("Set", Color(0xFF2A2A2A), small = true) { vm.setPcServerIp(pcIpDraft) }
                    if (pcIpDraft.isNotEmpty()) {
                        ActionBtn("Clear", Color(0xFF3A1A1A), small = true) { pcIpDraft = ""; vm.setPcServerIp("") }
                    }
                }
                if (state.pcServerIp.isNotEmpty()) {
                    Text("Active: ${state.pcServerIp}:3000", fontSize = 10.sp, color = Color(0xFF6BCB77), fontFamily = FontFamily.Monospace)
                } else {
                    Text("Not set — using default ${com.applemusicktv.BuildConfig.PROXY_BASE_URL}", fontSize = 9.sp, color = Color(0xFF444444), fontFamily = FontFamily.Monospace)
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Logs", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                ActionBtn("Clear", Color(0xFF2A2A2A), small = true) { vm.clearLogs() }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp)).padding(12.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.logs.reversed()) { log ->
                    Text(log.message, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = when (log.level) {
                            "ERROR" -> Color(0xFFFF6B6B); "WARN" -> Color(0xFFFFD93D)
                            "OK"    -> Color(0xFF6BCB77); else  -> Color(0xFFAAAAAA)
                        }, lineHeight = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).background(if (ok) Color(0xFF6BCB77) else Color(0xFFFF6B6B), RoundedCornerShape(50)))
        Column {
            Text(label, fontSize = 13.sp, color = Color(0xFFCCCCCC))
            if (sub != null) Text(sub, fontSize = 10.sp, color = Color(0xFF777777))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionBtn(label: String, color: Color, small: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = color, focusedContainerColor = color.copy(alpha = 0.7f)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier.height(if (small) 32.dp else 40.dp)) {
        Box(Modifier.padding(horizontal = if (small) 12.dp else 18.dp), Alignment.Center) {
            Text(label, fontSize = if (small) 11.sp else 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}
