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
import androidx.compose.ui.BiasAlignment
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
import com.applemusicktv.ui.viewmodel.SubtitleOption
import kotlinx.coroutines.delay

/** Where the D-pad focus sits in the transport row. */
private enum class MvFocus { SCRUB, SUBS, AUDIO, PIP }
private enum class MvPicker { NONE, SUBS, AUDIO }

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
    var picker by remember { mutableStateOf(MvPicker.NONE) }
    var pickCursor by remember { mutableIntStateOf(0) }
    var poke by remember { mutableIntStateOf(0) }

    // Auto-hide chrome ~5s after the last interaction — but never while paused or a picker
    // is open (Apple keeps the bar up then). Any key press re-arms via `poke`.
    LaunchedEffect(poke, picker, state.playing) {
        if (picker != MvPicker.NONE || !state.playing) { controls = true; return@LaunchedEffect }
        controls = true
        delay(5000)
        controls = false
    }

    val focusReq = remember { FocusRequester() }
    // Re-assert focus whenever the player is (re)built for a queue item, so keys keep working.
    LaunchedEffect(vm.player) { runCatching { focusReq.requestFocus() } }

    fun poke() { poke++; controls = true }

    val subs = state.subtitles
    val auds = state.audioTracks

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusReq)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Remote transport keys work regardless of where focus sits — same as songs.
                when (ev.key) {
                    Key.MediaNext -> { vm.next(); poke(); return@onKeyEvent true }
                    Key.MediaPrevious -> { vm.prev(); poke(); return@onKeyEvent true }
                    Key.MediaPlayPause -> { vm.togglePlayPause(); poke(); return@onKeyEvent true }
                    else -> {}
                }
                if (picker != MvPicker.NONE) {
                    val opts: List<SubtitleOption> = if (picker == MvPicker.SUBS) subs else auds
                    when (ev.key) {
                        Key.Back -> { picker = MvPicker.NONE; poke(); true }
                        Key.DirectionUp -> { if (pickCursor > 0) pickCursor--; true }
                        Key.DirectionDown -> { if (pickCursor < opts.size - 1) pickCursor++; true }
                        Key.DirectionCenter, Key.Enter -> {
                            opts.getOrNull(pickCursor)?.let {
                                if (picker == MvPicker.SUBS) vm.setSubtitle(it.index) else vm.setAudio(it.index)
                            }
                            picker = MvPicker.NONE; poke(); true
                        }
                        else -> true
                    }
                } else when (ev.key) {
                    Key.Back -> { onBack(); true }
                    Key.DirectionUp -> { poke(); focus = firstButton(subs, auds); true }
                    Key.DirectionDown -> { poke(); focus = MvFocus.SCRUB; true }
                    Key.DirectionLeft, Key.MediaRewind -> {
                        poke()
                        when (focus) {
                            MvFocus.SCRUB -> vm.seekBy(-10_000)
                            else -> focus = prevButton(focus, subs, auds)
                        }; true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        poke()
                        when (focus) {
                            MvFocus.SCRUB -> vm.seekBy(10_000)
                            else -> focus = nextButton(focus, subs, auds)
                        }; true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        poke()
                        when (focus) {
                            MvFocus.SCRUB -> vm.togglePlayPause()
                            MvFocus.SUBS -> if (subs.isNotEmpty()) { picker = MvPicker.SUBS; pickCursor = subs.indexOfFirst { it.index == state.subtitleIndex }.coerceAtLeast(0) }
                            MvFocus.AUDIO -> if (auds.isNotEmpty()) { picker = MvPicker.AUDIO; pickCursor = auds.indexOfFirst { it.index == state.audioIndex }.coerceAtLeast(0) }
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
                        useController = false
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
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000)))) {
                val dur = state.durationMs.coerceAtLeast(1)
                val frac = (state.positionMs.toFloat() / dur).coerceIn(0f, 1f)
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 48.dp, vertical = 30.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(state.artist, color = Color(0xCCFFFFFF), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(state.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        }
                        if (subs.isNotEmpty()) { OverlayIcon("CC", focus == MvFocus.SUBS, state.subtitleIndex >= 0); Spacer(Modifier.width(14.dp)) }
                        if (auds.isNotEmpty()) { OverlayIcon("A", focus == MvFocus.AUDIO, false); Spacer(Modifier.width(14.dp)) }
                        OverlayIcon("⧉", focus == MvFocus.PIP, false)
                    }
                    Spacer(Modifier.height(16.dp))
                    // Progress bar
                    Box(
                        Modifier.fillMaxWidth().height(if (focus == MvFocus.SCRUB) 6.dp else 4.dp)
                            .clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF)),
                    ) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                            .background(if (focus == MvFocus.SCRUB) Color.White else Color(0xE6FFFFFF)))
                    }
                    Spacer(Modifier.height(8.dp))
                    // Elapsed + inline play/pause glyph, parked under the playhead; remaining at the end.
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.align(BiasAlignment(horizontalBias = frac * 2f - 1f, verticalBias = 0f)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(fmt(state.positionMs), color = Color.White, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.playing) "⏸" else "▶", color = Color.White, fontSize = 13.sp)
                        }
                        Text("-" + fmt(dur - state.positionMs), color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }

        // ── Subtitle / audio picker ──────────────────────────────────────────
        if (picker != MvPicker.NONE) {
            val opts = if (picker == MvPicker.SUBS) subs else auds
            val selIndex = if (picker == MvPicker.SUBS) state.subtitleIndex else state.audioIndex
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 60.dp, bottom = 130.dp)
                    .width(240.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF2222222)).padding(vertical = 10.dp),
            ) {
                Column {
                    Text(if (picker == MvPicker.SUBS) "SUBTITLES" else "AUDIO", color = Color(0xFF9A9A9A), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                    opts.forEachIndexed { i, opt ->
                        Row(
                            Modifier.fillMaxWidth().background(if (i == pickCursor) Color(0x22FFFFFF) else Color.Transparent)
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(opt.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (opt.index == selIndex) Text("✓", color = Color.White, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun firstButton(subs: List<SubtitleOption>, auds: List<SubtitleOption>): MvFocus =
    if (subs.isNotEmpty()) MvFocus.SUBS else if (auds.isNotEmpty()) MvFocus.AUDIO else MvFocus.PIP

private fun buttonOrder(subs: List<SubtitleOption>, auds: List<SubtitleOption>): List<MvFocus> =
    buildList { if (subs.isNotEmpty()) add(MvFocus.SUBS); if (auds.isNotEmpty()) add(MvFocus.AUDIO); add(MvFocus.PIP) }

private fun nextButton(cur: MvFocus, subs: List<SubtitleOption>, auds: List<SubtitleOption>): MvFocus {
    val o = buttonOrder(subs, auds); val i = o.indexOf(cur)
    return if (i in 0 until o.lastIndex) o[i + 1] else cur
}
private fun prevButton(cur: MvFocus, subs: List<SubtitleOption>, auds: List<SubtitleOption>): MvFocus {
    val o = buttonOrder(subs, auds); val i = o.indexOf(cur)
    return if (i > 0) o[i - 1] else MvFocus.SCRUB
}

/** A small circular transport button styled like the reference: filled when focused. */
@androidx.compose.runtime.Composable
private fun OverlayIcon(glyph: String, focused: Boolean, active: Boolean) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(if (focused) Color.White else Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (focused) Color.Black else if (active) Color.White else Color(0xCCFFFFFF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
