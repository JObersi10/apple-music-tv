package com.applemusicktv.ui.screens

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay

/** Where the D-pad focus sits in the transport row. */
private enum class MvFocus { SCRUB, SUBS, PIP }

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

    var controls by remember { mutableStateOf(true) }
    var focus by remember { mutableStateOf(MvFocus.SCRUB) }
    var showSubs by remember { mutableStateOf(false) }
    var subCursor by remember { mutableIntStateOf(0) }
    // Bump on every interaction to re-arm the auto-hide timer.
    var poke by remember { mutableIntStateOf(0) }

    // Auto-hide the chrome ~5s after the last interaction (never while the picker is open).
    LaunchedEffect(poke, showSubs, state.playing) {
        if (showSubs || !state.playing) return@LaunchedEffect
        controls = true
        delay(5000)
        controls = false
    }

    val focusReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusReq.requestFocus() } }

    fun poke() { poke++; controls = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusReq)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Subtitle picker owns keys while open.
                if (showSubs) {
                    val n = state.subtitles.size
                    when (ev.key) {
                        Key.Back -> { showSubs = false; poke(); true }
                        Key.DirectionUp -> { if (subCursor > 0) subCursor--; true }
                        Key.DirectionDown -> { if (subCursor < n - 1) subCursor++; true }
                        Key.DirectionCenter, Key.Enter -> {
                            state.subtitles.getOrNull(subCursor)?.let { vm.setSubtitle(it.index) }
                            showSubs = false; poke(); true
                        }
                        else -> true
                    }
                } else when (ev.key) {
                    Key.Back -> { onBack(); true }
                    Key.MediaPlayPause -> { vm.togglePlayPause(); poke(); true }
                    Key.DirectionUp -> { poke(); focus = if (state.subtitles.isNotEmpty()) MvFocus.SUBS else MvFocus.PIP; true }
                    Key.DirectionDown -> { poke(); focus = MvFocus.SCRUB; true }
                    Key.DirectionLeft, Key.MediaRewind -> {
                        poke()
                        when (focus) {
                            MvFocus.SCRUB -> vm.seekBy(-10_000)
                            MvFocus.PIP -> if (state.subtitles.isNotEmpty()) focus = MvFocus.SUBS
                            else -> {}
                        }; true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        poke()
                        when (focus) {
                            MvFocus.SCRUB -> vm.seekBy(10_000)
                            MvFocus.SUBS -> if (state.subtitles.isNotEmpty()) focus = MvFocus.PIP
                            else -> {}
                        }; true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        poke()
                        when (focus) {
                            MvFocus.SCRUB -> vm.togglePlayPause()
                            MvFocus.SUBS -> {
                                if (state.subtitles.isNotEmpty()) {
                                    subCursor = state.subtitles.indexOfFirst { it.index == state.subtitleIndex }.coerceAtLeast(0)
                                    showSubs = true
                                }
                            }
                            MvFocus.PIP -> (context as? com.applemusicktv.MainActivity)?.enterPip()
                        }; true
                    }
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
                        useController = false            // our overlay is the only chrome
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = true
                        subtitleView?.setApplyEmbeddedStyles(true)
                        player = p
                    }
                },
                update = { it.player = p },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (state.loading) CircularProgressIndicator(color = Color(0xFFFA233B))

        if (state.error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't play this video", color = Color(0xFFFF453A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(state.error ?: "", color = Color(0xFF888888), fontSize = 12.sp)
            }
        }

        // ── Transport overlay ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = controls && !state.loading && state.error == null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))),
            ) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 40.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        // Subtitles button (only when the video actually carries captions).
                        if (state.subtitles.isNotEmpty()) {
                            OverlayIcon("CC", focused = focus == MvFocus.SUBS, active = state.subtitleIndex >= 0)
                            Spacer(Modifier.width(14.dp))
                        }
                        OverlayIcon("⧉", focused = focus == MvFocus.PIP, active = false)
                    }
                    Spacer(Modifier.height(14.dp))
                    // Progress bar
                    val dur = state.durationMs.coerceAtLeast(1)
                    val frac = (state.positionMs.toFloat() / dur).coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxWidth().height(if (focus == MvFocus.SCRUB) 6.dp else 4.dp)
                            .clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF)),
                    ) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                            .background(if (focus == MvFocus.SCRUB) Color.White else Color(0xE6FFFFFF)))
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth()) {
                        Text(fmt(state.positionMs), color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                        Text("-" + fmt(dur - state.positionMs), color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }

        // ── Subtitle picker ──────────────────────────────────────────────────
        if (showSubs) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 60.dp, bottom = 120.dp)
                    .width(240.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF2222222)).padding(vertical = 10.dp),
            ) {
                Column {
                    Text("SUBTITLES", color = Color(0xFF9A9A9A), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                    state.subtitles.forEachIndexed { i, opt ->
                        val on = i == subCursor
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (on) Color(0x22FFFFFF) else Color.Transparent)
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(opt.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (opt.index == state.subtitleIndex) Text("✓", color = Color.White, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A small circular transport button styled like the reference: filled when focused. */
@androidx.compose.runtime.Composable
private fun OverlayIcon(glyph: String, focused: Boolean, active: Boolean) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (focused) Color.Black else if (active) Color.White else Color(0xCCFFFFFF),
            fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
