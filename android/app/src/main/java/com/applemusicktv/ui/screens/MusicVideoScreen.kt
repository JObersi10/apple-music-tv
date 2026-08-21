package com.applemusicktv.ui.screens

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.applemusicktv.ui.viewmodel.MusicVideoViewModel

@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
fun MusicVideoScreen(
    mvId: String,
    title: String,
    artist: String,
    onBack: () -> Unit,
    vm: MusicVideoViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(mvId) { vm.load(mvId, title, artist) }
    DisposableEffect(Unit) { onDispose { vm.release() } }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.Back -> { onBack(); true }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { vm.togglePlayPause(); true }
                    Key.DirectionRight, Key.MediaFastForward -> { vm.seekBy(10_000); true }
                    Key.DirectionLeft, Key.MediaRewind -> { vm.seekBy(-10_000); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val p = vm.player
        if (p != null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = true
                        controllerShowTimeoutMs = 3000       // bar fades after 3s idle
                        controllerAutoShow = false           // don't force it open on load
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setKeepScreenOn(true)                // don't dim/sleep during video
                        subtitleView?.setApplyEmbeddedStyles(true)
                        player = p
                        hideController()
                    }
                },
                update = { it.player = p },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (state.loading) {
            CircularProgressIndicator(color = Color(0xFFFA233B))
        }

        if (state.error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't play this video", color = Color(0xFFFF453A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(state.error ?: "", color = Color(0xFF888888), fontSize = 12.sp)
            }
        }
    }
}
