package com.applemusicktv.ui.screens

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
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
private enum class MvFocus { INFO, ARTIST, SCRUB, SUBS, AUDIO, PIP }
private enum class MvPicker { NONE, SUBS, AUDIO }

@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
fun MusicVideoScreen(
    vm: MusicVideoViewModel,
    onMinimize: () -> Unit,   // PiP: shrink to a corner, stay in the app
    onExit: () -> Unit,       // Back once the chrome is gone: leave the screen (video keeps playing)
    onFocusUp: () -> Unit,    // Up from the top button row → move focus to the nav bar
    onArtistClick: (String) -> Unit = {},  // open the artist page from the video player
    focusRequester: FocusRequester,  // owned by AppShell so the nav bar's Down can target it
) {
    val state by vm.state.collectAsState()
    val cues by vm.cues.collectAsState()
    val context = LocalContext.current

    var controls by remember { mutableStateOf(true) }
    var focus by remember { mutableStateOf(MvFocus.SCRUB) }
    var picker by remember { mutableStateOf(MvPicker.NONE) }
    var pickCursor by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    // Scrub cursor: L/R move a ghost position, OK commits the seek — no live scrubbing,
    // matching the Now Playing progress bar. null = not scrubbing.
    var scrub by remember { mutableStateOf<Long?>(null) }
    var poke by remember { mutableIntStateOf(0) }

    // Auto-hide chrome ~5s after the last interaction — but never while paused or a picker
    // is open (Apple keeps the bar up then). Any key press re-arms via `poke`.
    LaunchedEffect(poke, picker, showInfo, state.playing) {
        if (picker != MvPicker.NONE || showInfo || !state.playing) { controls = true; return@LaunchedEffect }
        controls = true
        delay(3000)
        controls = false
    }

    // Grab focus when the screen appears so the D-pad drives the video, not anything behind it.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun poke() { poke++; controls = true }

    val subs = state.subtitles
    val auds = state.audioTracks

    // NOTE: the video surface itself is owned by AppShell (a single persistent PlayerView that
    // resizes between fullscreen and PiP) — this composable is only the controls overlay, so it
    // must stay transparent. Handing the surface between two views was throwing
    // MediaCodecVideoRenderer errors ("Can't play this video") after a PiP round-trip.
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Remote transport keys work regardless of where focus sits — same as songs.
                when (ev.key) {
                    Key.MediaNext, Key.MediaFastForward -> { vm.next(); poke(); return@onKeyEvent true }
                    Key.MediaPrevious, Key.MediaRewind -> { vm.prev(); poke(); return@onKeyEvent true }
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
                    // Back peels the overlay first (Apple TV behaviour): info panel, then the
                    // chrome, and only exits the player once nothing is on screen.
                    Key.Back -> when {
                        showInfo -> { showInfo = false; true }
                        controls -> { controls = false; true }
                        else -> { onExit(); true }
                    }
                    Key.DirectionUp -> {
                        poke(); scrub = null
                        // At the top button row already → don't consume, let focus escape up
                        // to the top nav bar (so you can switch tabs from fullscreen video).
                        if (focus in setOf(MvFocus.ARTIST, MvFocus.SUBS, MvFocus.AUDIO, MvFocus.PIP)) { onFocusUp(); true }
                        else { focus = if (focus == MvFocus.INFO) MvFocus.SCRUB else firstButton(state.artistId != null, subs, auds); true }
                    }
                    Key.DirectionDown -> { poke(); scrub = null; focus = if (focus == MvFocus.SCRUB) MvFocus.INFO else MvFocus.SCRUB; true }
                    Key.DirectionLeft -> {
                        poke()
                        when (focus) {
                            MvFocus.SCRUB -> scrub = ((scrub ?: state.positionMs) - 10_000).coerceIn(0, state.durationMs)
                            MvFocus.INFO -> {}
                            else -> focus = prevButton(focus, state.artistId != null, subs, auds)
                        }; true
                    }
                    Key.DirectionRight -> {
                        poke()
                        when (focus) {
                            MvFocus.SCRUB -> scrub = ((scrub ?: state.positionMs) + 10_000).coerceIn(0, state.durationMs)
                            MvFocus.INFO -> {}
                            else -> focus = nextButton(focus, state.artistId != null, subs, auds)
                        }; true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        poke()
                        when (focus) {
                            MvFocus.INFO -> showInfo = !showInfo
                            MvFocus.ARTIST -> state.artistId?.let { onArtistClick(it) }
                            MvFocus.SCRUB -> { val s = scrub; if (s != null) { vm.seekTo(s); scrub = null } else vm.togglePlayPause() }
                            MvFocus.SUBS -> if (subs.isNotEmpty()) { picker = MvPicker.SUBS; pickCursor = subs.indexOfFirst { it.index == state.subtitleIndex }.coerceAtLeast(0) }
                            MvFocus.AUDIO -> if (auds.isNotEmpty()) { picker = MvPicker.AUDIO; pickCursor = auds.indexOfFirst { it.index == state.audioIndex }.coerceAtLeast(0) }
                            MvFocus.PIP -> onMinimize()
                        }; true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Loading spinner sits down at the playback bar (where play/pause lives), not dead-centre —
        // a brief prefetch flash mid-screen was distracting.
        if (state.loading) CircularProgressIndicator(
            color = Color(0xFFFA233B), strokeWidth = 3.dp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 52.dp).size(26.dp),
        )

        if (state.error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't play this video", color = Color(0xFFFF453A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(state.error ?: "", color = Color(0xFF888888), fontSize = 12.sp)
            }
        }

        // ── Captions ─────────────────────────────────────────────────────────
        // Rendered as Compose text in the controls layer. A native SubtitleView sits under
        // the secure media-overlay video surface (z-order) and never shows; Compose here is
        // the same layer as the visible controls, so captions land on top. Lifts above the
        // transport bar when the chrome is up.
        val captionText = cues.mapNotNull { it.text?.toString()?.trim()?.ifBlank { null } }.joinToString("\n")
        if (captionText.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (controls) 190.dp else 56.dp, start = 48.dp, end = 48.dp),
            ) {
                Text(
                    captionText,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
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
                val shownPos = scrub ?: state.positionMs
                val frac = (shownPos.toFloat() / dur).coerceIn(0f, 1f)
                val scrubbing = scrub != null
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 48.dp, vertical = 30.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            val artistFocused = focus == MvFocus.ARTIST
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(if (artistFocused) Color(0x33FFFFFF) else Color.Transparent)
                                    .padding(horizontal = if (artistFocused) 8.dp else 0.dp, vertical = if (artistFocused) 3.dp else 0.dp),
                            ) {
                                Text(state.artist, color = if (artistFocused) Color.White else Color(0xCCFFFFFF),
                                    fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
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
                            Text(fmt(shownPos), color = if (scrubbing) Color(0xFFFA233B) else Color.White, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            PlayPauseGlyph(playing = state.playing)
                        }
                        Text("-" + fmt(dur - shownPos), color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                    Spacer(Modifier.height(12.dp))
                    // Info button — the only handle we have for title/artist while a video plays.
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (focus == MvFocus.INFO) Color(0x33FFFFFF) else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Info", color = if (focus == MvFocus.INFO) Color.White else Color(0xB3FFFFFF),
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Info panel — slides up from the bottom with metadata + the "cool" technical read-out.
        androidx.compose.animation.AnimatedVisibility(
            visible = showInfo,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        ) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color(0xF2000000), 1f to Color(0xF2000000)))) {
                Column(Modifier.padding(horizontal = 48.dp, vertical = 32.dp)) {
                    Text(state.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(state.artist, color = Color(0xCCFFFFFF), fontSize = 16.sp)
                    if (!state.info.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(state.info!!, color = Color(0xCCFFFFFF), fontSize = 15.sp, lineHeight = 22.sp)
                    }
                }
            }
        }

        // ── Subtitle / audio picker ──────────────────────────────────────────
        if (picker != MvPicker.NONE) {
            val opts = if (picker == MvPicker.SUBS) subs else auds
            val selIndex = if (picker == MvPicker.SUBS) state.subtitleIndex else state.audioIndex
            Box(
                // Anchored just above the CC/audio button row (bottom-right), not floating mid-screen.
                Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 176.dp)
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

private fun firstButton(hasArtist: Boolean, subs: List<SubtitleOption>, auds: List<SubtitleOption>): MvFocus =
    if (hasArtist) MvFocus.ARTIST else if (subs.isNotEmpty()) MvFocus.SUBS else if (auds.isNotEmpty()) MvFocus.AUDIO else MvFocus.PIP

private fun buttonOrder(hasArtist: Boolean, subs: List<SubtitleOption>, auds: List<SubtitleOption>): List<MvFocus> =
    buildList { if (hasArtist) add(MvFocus.ARTIST); if (subs.isNotEmpty()) add(MvFocus.SUBS); if (auds.isNotEmpty()) add(MvFocus.AUDIO); add(MvFocus.PIP) }

private fun nextButton(cur: MvFocus, hasArtist: Boolean, subs: List<SubtitleOption>, auds: List<SubtitleOption>): MvFocus {
    val o = buttonOrder(hasArtist, subs, auds); val i = o.indexOf(cur)
    return if (i in 0 until o.lastIndex) o[i + 1] else cur
}
private fun prevButton(cur: MvFocus, hasArtist: Boolean, subs: List<SubtitleOption>, auds: List<SubtitleOption>): MvFocus {
    val o = buttonOrder(hasArtist, subs, auds); val i = o.indexOf(cur)
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

/** Drawn (not emoji) transport glyph. Inverted per request: a play triangle while playing,
 *  pause bars while paused. */
@androidx.compose.runtime.Composable
private fun PlayPauseGlyph(playing: Boolean) {
    Canvas(Modifier.size(13.dp)) {
        if (playing) {
            val path = Path().apply {
                moveTo(size.width * 0.1f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width * 0.1f, size.height)
                close()
            }
            drawPath(path, Color.White)
        } else {
            val bw = size.width * 0.3f
            drawRect(Color.White, topLeft = Offset(size.width * 0.12f, 0f), size = Size(bw, size.height))
            drawRect(Color.White, topLeft = Offset(size.width * 0.58f, 0f), size = Size(bw, size.height))
        }
    }
}

@androidx.compose.runtime.Composable
private fun InfoStat(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = Color(0xFF8A8A8A), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

private fun codecName(mime: String?): String = when {
    mime == null -> "—"
    mime.contains("hevc") || mime.contains("hvc") -> "HEVC"
    mime.contains("avc") -> "H.264"
    mime.contains("mp4a") || mime.contains("aac") -> "AAC"
    mime.contains("ac3") -> "Dolby"
    else -> mime.substringAfterLast('/').uppercase()
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
