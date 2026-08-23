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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.applemusicktv.ui.viewmodel.MusicVideoViewModel
import com.applemusicktv.ui.viewmodel.SubtitleOption
import com.applemusicktv.ui.viewmodel.qualityLabel
import kotlinx.coroutines.delay

/** One focusable control in the video player. */
private enum class MvTarget { ARTIST, AUDIO, QUALITY, QUEUE, PREV, PLAYPAUSE, NEXT, SCRUB, INFO }

@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
fun MusicVideoScreen(
    vm: MusicVideoViewModel,
    onExit: () -> Unit,       // Back once the chrome is gone: leave the screen (video keeps playing)
    onFocusUp: () -> Unit,    // Up from the top row → move focus to the nav bar
    onArtistClick: (String) -> Unit = {},
    showOnScreenControls: Boolean = false,  // Google TV remotes: draw prev/play/next on screen
    queue: List<com.applemusicktv.data.model.Song> = emptyList(),
    queueIndex: Int = 0,
    onPickQueueItem: (Int) -> Unit = {},
    focusRequester: FocusRequester,
) {
    val state by vm.state.collectAsState()
    val cues by vm.cues.collectAsState()
    val showQueue by vm.showQueue.collectAsState()
    var queueCursor by remember(showQueue) { mutableIntStateOf(queueIndex.coerceAtLeast(0)) }

    var controls by remember { mutableStateOf(true) }
    var focus by remember { mutableStateOf(MvTarget.SCRUB) }
    var audioPicker by remember { mutableStateOf(false) }
    var pickCursor by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var poke by remember { mutableIntStateOf(0) }

    val auds = state.audioTracks

    // The rows of the control layout, top→bottom. Empty rows are skipped by up/down.
    val rows: List<List<MvTarget>> = remember(state.artistId, auds, showOnScreenControls) {
        buildList {
            add(buildList {
                if (state.artistId != null) add(MvTarget.ARTIST)
                if (auds.isNotEmpty()) add(MvTarget.AUDIO)
                add(MvTarget.QUALITY)
                add(MvTarget.QUEUE)
            })
            if (showOnScreenControls) add(listOf(MvTarget.PREV, MvTarget.PLAYPAUSE, MvTarget.NEXT))
            add(listOf(MvTarget.SCRUB))
            add(listOf(MvTarget.INFO))
        }
    }
    fun rowOf(t: MvTarget) = rows.indexOfFirst { it.contains(t) }
    fun moveRow(delta: Int): Boolean {
        val r = rowOf(focus); val nr = r + delta
        if (nr < 0) { onFocusUp(); return true }
        if (nr >= rows.size) return true
        val col = rows[r].indexOf(focus).coerceIn(0, rows[nr].lastIndex)
        focus = rows[nr][col]; return true
    }
    fun moveCol(delta: Int) {
        val r = rowOf(focus); val c = rows[r].indexOf(focus) + delta
        if (c in rows[r].indices) focus = rows[r][c]
    }

    LaunchedEffect(poke, audioPicker, showInfo, state.playing) {
        if (audioPicker || showInfo || !state.playing) { controls = true; return@LaunchedEffect }
        controls = true
        delay(3000)
        controls = false
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    fun poke() { poke++; controls = true }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.MediaNext, Key.MediaFastForward -> { vm.next(); poke(); return@onKeyEvent true }
                    Key.MediaPrevious, Key.MediaRewind -> { vm.prev(); poke(); return@onKeyEvent true }
                    Key.MediaPlayPause -> { vm.togglePlayPause(); poke(); return@onKeyEvent true }
                    else -> {}
                }
                if (showQueue) {
                    when (ev.key) {
                        Key.Back, Key.Menu -> { vm.hideQueue(); poke(); true }
                        Key.DirectionUp -> { if (queueCursor > 0) queueCursor--; true }
                        Key.DirectionDown -> { if (queueCursor < queue.size - 1) queueCursor++; true }
                        Key.DirectionCenter, Key.Enter -> { onPickQueueItem(queueCursor); vm.hideQueue(); poke(); true }
                        else -> true
                    }
                } else if (ev.key == Key.Menu) { queueCursor = queueIndex.coerceAtLeast(0); vm.toggleQueue(); true }
                else if (audioPicker) {
                    when (ev.key) {
                        Key.Back -> { audioPicker = false; poke(); true }
                        Key.DirectionUp -> { if (pickCursor > 0) pickCursor--; true }
                        Key.DirectionDown -> { if (pickCursor < auds.size - 1) pickCursor++; true }
                        Key.DirectionCenter, Key.Enter -> {
                            auds.getOrNull(pickCursor)?.let { vm.setAudio(it.index) }
                            audioPicker = false; poke(); true
                        }
                        else -> true
                    }
                } else when (ev.key) {
                    Key.Back -> when {
                        showInfo -> { showInfo = false; true }
                        controls -> { controls = false; true }
                        else -> { onExit(); true }
                    }
                    Key.DirectionUp -> { poke(); scrub = null; moveRow(-1) }
                    Key.DirectionDown -> { poke(); scrub = null; moveRow(1) }
                    Key.DirectionLeft -> {
                        poke()
                        if (focus == MvTarget.SCRUB) scrub = ((scrub ?: state.positionMs) - 10_000).coerceIn(0, state.durationMs)
                        else moveCol(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        poke()
                        if (focus == MvTarget.SCRUB) scrub = ((scrub ?: state.positionMs) + 10_000).coerceIn(0, state.durationMs)
                        else moveCol(1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        poke()
                        when (focus) {
                            MvTarget.INFO -> showInfo = !showInfo
                            MvTarget.ARTIST -> state.artistId?.let { onArtistClick(it) }
                            MvTarget.AUDIO -> if (auds.isNotEmpty()) { audioPicker = true; pickCursor = auds.indexOfFirst { it.index == state.audioIndex }.coerceAtLeast(0) }
                            MvTarget.QUALITY -> vm.cycleQuality()
                            MvTarget.QUEUE -> { queueCursor = queueIndex.coerceAtLeast(0); vm.toggleQueue() }
                            MvTarget.PREV -> vm.prev()
                            MvTarget.PLAYPAUSE -> vm.togglePlayPause()
                            MvTarget.NEXT -> vm.next()
                            MvTarget.SCRUB -> { val s = scrub; if (s != null) { vm.seekTo(s); scrub = null } else vm.togglePlayPause() }
                        }; true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
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

        // ── Captions (WebVTT only; muxed CEA-608 in secure video can't be extracted) ──
        val captionText = cues.mapNotNull { it.text?.toString()?.trim()?.ifBlank { null } }.joinToString("\n")
        if (captionText.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (controls) 190.dp else 56.dp, start = 48.dp, end = 48.dp),
            ) {
                Text(captionText, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }

        // ── On-screen media controls (Google TV) — centred, a bit below middle ──
        if (showOnScreenControls) {
            AnimatedVisibility(
                visible = controls && !state.loading && state.error == null,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(BiasAlignment(0f, 0.25f)),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundControl("⏮", focus == MvTarget.PREV)
                    RoundControl(if (state.playing) "⏸" else "▶", focus == MvTarget.PLAYPAUSE, big = true)
                    RoundControl("⏭", focus == MvTarget.NEXT)
                }
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
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 48.dp, vertical = 30.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            val artistFocused = focus == MvTarget.ARTIST
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(if (artistFocused) Color(0x33FFFFFF) else Color.Transparent)
                                    .padding(horizontal = if (artistFocused) 8.dp else 0.dp, vertical = if (artistFocused) 3.dp else 0.dp),
                            ) {
                                Text(state.artist, color = if (artistFocused) Color.White else Color(0xCCFFFFFF), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(state.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        }
                        if (auds.isNotEmpty()) { OverlayIcon("A", focus == MvTarget.AUDIO, false); Spacer(Modifier.width(14.dp)) }
                        // Quality button — cycles 480/720/1080/4K, remembered for all videos.
                        OverlayPill(qualityLabel(state.qualityHeight), focus == MvTarget.QUALITY)
                        Spacer(Modifier.width(14.dp))
                        OverlayIcon("≡", focus == MvTarget.QUEUE, false)
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier.fillMaxWidth().height(if (focus == MvTarget.SCRUB) 6.dp else 4.dp)
                            .clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF)),
                    ) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                            .background(if (focus == MvTarget.SCRUB) Color.White else Color(0xE6FFFFFF)))
                    }
                    Spacer(Modifier.height(8.dp))
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
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (focus == MvTarget.INFO) Color(0x33FFFFFF) else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Info", color = if (focus == MvTarget.INFO) Color.White else Color(0xB3FFFFFF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Info panel
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

        // ── Up-Next queue panel (right side, Apple TV style) ─────────────────
        if (showQueue) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(360.dp)
                    .background(Color(0xF21A1A1C)).padding(vertical = 24.dp),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text("UP NEXT", color = Color(0xFF9A9A9A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 12.dp))
                    }
                    itemsIndexed(queue) { i, song ->
                        val sel = i == queueCursor
                        val nowPlaying = i == queueIndex
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Color(0x33FFFFFF) else Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (song.isMusicVideo)
                                Box(Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFFA233B)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                    Text("MV", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = if (nowPlaying) Color(0xFFFA233B) else Color.White, fontSize = 14.sp,
                                    fontWeight = if (nowPlaying) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                                Text(song.artistName, color = Color(0xFF888888), fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // ── Audio picker ─────────────────────────────────────────────────────
        if (audioPicker) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 176.dp)
                    .width(240.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF2222222)).padding(vertical = 10.dp),
            ) {
                Column {
                    Text("AUDIO", color = Color(0xFF9A9A9A), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                    auds.forEachIndexed { i, opt ->
                        Row(
                            Modifier.fillMaxWidth().background(if (i == pickCursor) Color(0x22FFFFFF) else Color.Transparent).padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(opt.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (opt.index == state.audioIndex) Text("✓", color = Color.White, fontSize = 15.sp)
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
        Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(if (focused) Color.White else Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (focused) Color.Black else if (active) Color.White else Color(0xCCFFFFFF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** A pill button (used for the quality label). */
@androidx.compose.runtime.Composable
private fun OverlayPill(label: String, focused: Boolean) {
    Box(
        Modifier.height(42.dp).clip(RoundedCornerShape(50)).background(if (focused) Color.White else Color(0x33FFFFFF)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** A large round on-screen control (Google TV prev/play/next). */
@androidx.compose.runtime.Composable
private fun RoundControl(glyph: String, focused: Boolean, big: Boolean = false) {
    val sz = if (big) 66.dp else 52.dp
    Box(
        Modifier.size(sz).clip(RoundedCornerShape(50)).background(if (focused) Color.White else Color(0x55000000)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (focused) Color.Black else Color.White, fontSize = if (big) 26.sp else 20.sp)
    }
}

/** Drawn (not emoji) transport glyph in the scrub row: play triangle while playing, pause bars while paused. */
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

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
