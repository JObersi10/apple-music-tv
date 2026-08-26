package com.applemusicktv.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.palette.graphics.Palette
import androidx.tv.material3.*
import androidx.tv.material3.Border
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.network.LyricWord
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import com.applemusicktv.ui.viewmodel.NowPlayingBackground
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.ui.components.Glyph
import com.applemusicktv.ui.viewmodel.RepeatMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DIAGNOSTIC: off = don't spin up the motion-artwork video decoder (CPU test). */
private const val MOTION_ENABLED = true

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerVm: PlayerViewModel,
    navVm: NavigationViewModel,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by playerVm.state.collectAsState()
    val song = state.currentSong

    val toggleCount by navVm.toggleQueue.collectAsState()
    val showQueue = toggleCount % 2 == 1

    val smoothProgress = rememberSmoothProgressMs(state.progressMs, state.isPlaying)

    DisposableEffect(Unit) {
        playerVm.nowPlayingVisible = true
        onDispose { playerVm.nowPlayingVisible = false }
    }

    val artistFocusHolder = remember { FocusRequester() }

    Box(modifier = modifier.fillMaxSize()) {
        // Screensaver state is declared here (not lower down) so the backdrop can react to it: when
        // idle mode kicks in and the user hasn't opted to keep the beat, the backdrop drops to plain
        // black regardless of the chosen mode.
        var screensaverOn by remember { mutableStateOf(false) }
        var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        // Auto-hide chrome (clock, controls, queue) after a short idle so a steady state is just
        // artwork + orbs. Only D-pad NAVIGATION brings it back — the media transport keys deliberately
        // don't, so play/pause/skip from across the room leaves the clean view alone.
        var chromeVisible by remember { mutableStateOf(true) }
        var lastNavMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(chromeVisible, lastNavMs) {
            if (!chromeVisible) return@LaunchedEffect
            while (isActive) {
                kotlinx.coroutines.delay(500)
                if (System.currentTimeMillis() - lastNavMs > CHROME_HIDE_MS) { chromeVisible = false; break }
            }
        }
        // Asymmetric transition: dropping INTO idle is a slow ~1.6 s eased settle (chrome fades, art
        // eases into place) so it reads as one deliberate move; coming BACK is a fast ~240 ms punch so
        // the controls snap to attention the instant you touch the D-pad — no waiting for a fade.
        val idle by animateFloatAsState(
            if (chromeVisible) 0f else 1f,
            animationSpec = if (chromeVisible)
                tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            else
                tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "idle",
        )
        val chromeAlpha = 1f - idle
        val backgroundMode = if (screensaverOn && !state.screensaverKeepBackground)
            NowPlayingBackground.BLACK else state.nowPlayingBackground
        DynamicBackground(artworkUrlTemplate = song?.artworkUrl, songKey = song?.id ?: "", beatAnalyzer = playerVm.beatAnalyzer, beatMultiplier = state.beatIntensity, mode = backgroundMode, playing = state.isPlaying, orbSpeed = state.orbSpeed, reduceMotion = state.reduceMotion, lowPower = state.lowPowerMode)

        if (song == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Nothing playing", color = Color(0xFF666666), fontSize = 18.sp)
            }
            return@Box
        }

        // System clock — top-left
        var clockText by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            while (isActive) {
                clockText = fmt.format(Date())
                kotlinx.coroutines.delay(1_000)
            }
        }
        val sleepLabel = when {
            state.sleepAfterSong -> "Sleep: end of song"
            state.sleepTimerEndsAt != null -> {
                val remaining = (state.sleepTimerEndsAt!! - System.currentTimeMillis()).coerceAtLeast(0)
                val mins = remaining / 60_000
                val secs = (remaining % 60_000) / 1_000
                "Sleep: ${mins}:${secs.toString().padStart(2, '0')}"
            }
            else -> null
        }
        if (state.showNowPlayingInfo) Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 10.dp)
                .graphicsLayer { alpha = chromeAlpha },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sleepLabel != null) {
                Text(sleepLabel, style = TextStyle(fontSize = 15.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
            }
            Text(clockText, style = TextStyle(fontSize = 15.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
        }

        val playFocus = remember { FocusRequester() }
        // When the chrome hides for idle, park focus back on the play/pause button (after the fade), so
        // the moment you wake it the centre control is the highlighted one — not wherever you'd left it.
        LaunchedEffect(chromeVisible) {
            if (!chromeVisible) {
                kotlinx.coroutines.delay(1700)
                if (!chromeVisible) runCatching { playFocus.requestFocus() }
            }
        }
        var fullScreenLyrics by remember { mutableStateOf(false) }
        // System Back exits full-screen lyrics (the 3-dots menu isn't reachable there).
        androidx.activity.compose.BackHandler(enabled = fullScreenLyrics) { fullScreenLyrics = false }

        // Ambient screensaver: after 10 min of no input while playing, drop to just the
        // drifting background + a small now-playing chip. Any key wakes it.
        // (screensaverOn / lastInteractionMs are declared above so the backdrop can read them.)
        LaunchedEffect(state.screensaverTimeoutMin, state.isPlaying) {
            if (state.screensaverTimeoutMin <= 0) { screensaverOn = false; return@LaunchedEffect }
            val thresholdMs = state.screensaverTimeoutMin * 60_000L
            while (isActive) {
                kotlinx.coroutines.delay(15_000)
                if (state.isPlaying && !screensaverOn &&
                    System.currentTimeMillis() - lastInteractionMs > thresholdMs) screensaverOn = true
            }
        }

        // Cross-dissolve between the player, full-screen lyrics and the screensaver so every
        // switch (and back) animates instead of hard-cutting.
        val npMode = when {
            screensaverOn -> 2
            fullScreenLyrics && state.lyrics.isNotEmpty() -> 1
            else -> 0
        }
        androidx.compose.animation.Crossfade(
            targetState = npMode,
            animationSpec = tween(450),
            modifier = Modifier.fillMaxSize(),
            label = "npMode",
        ) { mode -> when (mode) {
            2 -> AmbientScreensaver(song = song) { screensaverOn = false; lastInteractionMs = System.currentTimeMillis() }
            1 -> FullScreenLyrics(
                lyrics = state.lyrics,
                progressState = smoothProgress,
                offsetMs = state.avLyricsMs,
                song = song,
                isPlaying = state.isPlaying,
                onSeek = { ms -> playerVm.player.seekTo(ms) },
                onPrev = playerVm::prev,
                onPlayPause = playerVm::togglePlayPause,
                onNext = playerVm::next,
                lyricsScale = state.lyricsScale,
            )
            else -> {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 40.dp)
                .onPreviewKeyEvent { e ->
                    // Navigation only: media keys don't count as activity, so they neither wake the
                    // chrome nor postpone the screensaver.
                    if (e.type == KeyEventType.KeyDown && isNavKey(e.key)) {
                        val now = System.currentTimeMillis()
                        lastInteractionMs = now
                        lastNavMs = now
                        // First nav press just reveals the chrome — consume it so focus doesn't also
                        // jump while everything's still invisible.
                        if (!chromeVisible) { chromeVisible = true; return@onPreviewKeyEvent true }
                    }
                    false
                },
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            // Left — artwork + info + controls, grouped and vertically centred so idle (controls
            // hidden) doesn't leave an awkward void. Eases up slightly as it settles into idle.
            Column(
                modifier = Modifier.width(340.dp).fillMaxHeight().padding(vertical = 8.dp)
                    .graphicsLayer {
                        // On idle the controls + progress bar fade but still hold their layout space,
                        // which leaves the art sitting high. Ease the whole group DOWN a touch (and a
                        // hair larger) so the artwork + title + artist settle into the visual centre.
                        val s = 1f + 0.03f * idle
                        scaleX = s; scaleY = s
                        translationY = idle * 58f
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(256.dp)
                        .clip(RoundedCornerShape(if (state.artworkRounded) 18.dp else 0.dp))
                        .background(Color(0xFF1A1A2E)),
                ) {
                    // Cross-fade the cover instead of hard-swapping it on song change.
                    if (song.artworkUrl != null) {
                        androidx.compose.animation.Crossfade(
                            targetState = song.artworkUrl(600),
                            animationSpec = tween(1000),
                            label = "cover",
                        ) { url ->
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(url).crossfade(1000).build(),
                                contentDescription = song.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    // DIAGNOSTIC: motion artwork spins up a SECOND ExoPlayer/video
                    // decoder. Gated off to test whether it starves the audio decoder
                    // (standalone frame-drop chop) on the weak Fire TV.
                    // In PiP, drop the motion decoder entirely: it's a whole second ExoPlayer/video
                    // decoder, and holding it alive through the PiP transition is a memory spike that
                    // the Fire TV's low-memory killer answers by killing us. onDispose releases it.
                    @Suppress("ConstantConditionIf")
                    if (MOTION_ENABLED && state.motionUrl != null && !state.isInPip && !state.lowPowerMode) {
                        MotionCover(url = state.motionUrl!!, modifier = Modifier.fillMaxSize())
                    }
                }

                Spacer(Modifier.height(10.dp))

                var showOptionsMenu by remember { mutableStateOf(false) }
                var showSleepSubmenu by remember { mutableStateOf(false) }
                var showAddTo by remember { mutableStateOf(false) }
                if (showAddTo) com.applemusicktv.ui.components.AddToDialog(playerVm, song, onDismiss = { showAddTo = false })

                Box(Modifier.fillMaxWidth()) {
                    MarqueeText(
                        song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        letterSpacing = (-0.5).sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 38.dp),
                    )
                    // The ⋯ options button is chrome: it fades out with the controls on idle so the
                    // steady-state view is just artwork + text.
                    if (chromeAlpha > 0.02f) Surface(
                        onClick = { showOptionsMenu = true; showSleepSubmenu = false },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x1AFFFFFF), focusedContainerColor = Color(0x33FFFFFF)),
                        modifier = Modifier.align(Alignment.CenterEnd).size(32.dp).graphicsLayer { alpha = chromeAlpha },
                    ) { Box(Modifier.fillMaxSize(), Alignment.Center) {
                        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                            val r = size.minDimension * 0.07f
                            val cy = size.height / 2f
                            listOf(0.26f, 0.5f, 0.74f).forEach { fx ->
                                drawCircle(Color.White, radius = r, center = androidx.compose.ui.geometry.Offset(size.width * fx, cy))
                            }
                        }
                    } }
                }
                // No gap between title and artist — a fixed spacer here read as an abrupt
                // collapse against the idle chrome fade. Artist sits directly under the title.
                if (song.artistId != null) {
                    Surface(
                        onClick = { onArtistClick(song.artistId) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0x1AFFFFFF)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        modifier = Modifier.focusRequester(artistFocusHolder).fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        MarqueeText(song.artistName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFA233B),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                    }
                } else {
                    MarqueeText(song.artistName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFA233B),
                        modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))

                LaunchedEffect(song.id) {
                    try { playFocus.requestFocus() } catch (_: Exception) {}
                }
                Row(
                    modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportButton(TransportIcon.Prev, onClick = playerVm::prev,
                        modifier = Modifier.focusProperties { left = artistFocusHolder })
                    TransportButton(
                        if (state.isPlaying) TransportIcon.Pause else TransportIcon.Play,
                        loading = state.isLoading,
                        onClick = playerVm::togglePlayPause,
                        large = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    TransportButton(TransportIcon.Next, onClick = playerVm::next)
                    // Google TV and most non-Fire remotes have no Menu key, so the
                    // queue/lyrics toggle needs a control of its own. Fire TV keeps
                    // using Menu and doesn't need the extra button.
                    if (com.applemusicktv.util.TvDevice.needsOnScreenMenuToggle(LocalContext.current, playerVm.remoteOverride())) {
                        TransportButton(TransportIcon.Panel, onClick = navVm::toggleQueuePanel)
                    }
                }

                // ⋯ options dialog
                if (showOptionsMenu) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showOptionsMenu = false; showSleepSubmenu = false }) {
                        val menuFocus = remember { FocusRequester() }
                        LaunchedEffect(showSleepSubmenu) { kotlinx.coroutines.delay(100); runCatching { menuFocus.requestFocus() } }
                        Column(
                            Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (showSleepSubmenu) {
                                NpMenuItem("Back", Modifier.focusRequester(menuFocus), icon = Glyph.BACK) { showSleepSubmenu = false }
                                if (state.sleepTimerEndsAt != null || state.sleepAfterSong)
                                    NpMenuItem("Cancel Timer") { playerVm.cancelSleepTimer(); showOptionsMenu = false }
                                NpMenuItem("End of Song", checked = state.sleepAfterSong) { playerVm.setSleepAfterSong(); showOptionsMenu = false }
                                listOf(15, 30, 45, 60).forEach { min ->
                                    NpMenuItem("$min minutes") { playerVm.setSleepTimer(min); showOptionsMenu = false }
                                }
                            } else {
                                val timerLabel = when {
                                    state.sleepAfterSong -> "Sleep: End of Song"
                                    state.sleepTimerEndsAt != null -> {
                                        val m = ((state.sleepTimerEndsAt!! - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                                        "Sleep Timer (${m}m left)"
                                    }
                                    else -> "Sleep Timer"
                                }
                                val timerOn = state.sleepAfterSong || state.sleepTimerEndsAt != null
                                NpMenuItem(timerLabel, Modifier.focusRequester(menuFocus), icon = Glyph.MOON, checked = timerOn) { showSleepSubmenu = true }
                                // Crossfade + Beat Pulse live in Settings now — keep this menu short.
                                // Settings items leave the menu open so you can see the label
                                // flip and keep cycling. Only navigation and the sleep timer
                                // close it; Back dismisses.
                                NpMenuItem("Shuffle", icon = Glyph.SHUFFLE, checked = state.isShuffled) { playerVm.toggleShuffle() }
                                val repeatLabel = when (state.repeatMode) { RepeatMode.Off -> "Repeat: Off"; RepeatMode.All -> "Repeat: All"; RepeatMode.One -> "Repeat: One" }
                                NpMenuItem(repeatLabel, icon = if (state.repeatMode == RepeatMode.One) Glyph.REPEAT_ONE else Glyph.REPEAT, checked = state.repeatMode != RepeatMode.Off) { playerVm.toggleRepeat() }
                                if (state.lyrics.isNotEmpty()) NpMenuItem("Full-Screen Lyrics", icon = Glyph.LYRICS) { fullScreenLyrics = true; showOptionsMenu = false }
                                NpMenuItem("Add to…", icon = Glyph.ADD_TO) { showAddTo = true; showOptionsMenu = false }
                                NpMenuItem("Start Screensaver", icon = Glyph.STAR) { lastInteractionMs = System.currentTimeMillis(); screensaverOn = true; showOptionsMenu = false }
                                if (song.artistId != null) NpMenuItem("Go to Artist", icon = Glyph.ARTIST) { onArtistClick(song.artistId); showOptionsMenu = false }
                                if (song.albumId != null) NpMenuItem("Go to Album", icon = Glyph.ALBUM) { onAlbumClick(song.albumId); showOptionsMenu = false }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Column, not Box: PlayerProgressBar emits the bar AND a time row, which a Box would
                // overlap (the elapsed/duration text clipped over the bar).
                Column(Modifier.graphicsLayer { alpha = chromeAlpha }) {
                    PlayerProgressBar(
                        progressState = smoothProgress,
                        song = song,
                        playFocus = playFocus,
                        player = playerVm.player,
                    )
                }
            }

            // Right — lyrics or queue. Lyrics are content, not chrome, so they stay put on idle;
            // only the queue fades out with the rest of the controls.
            val rightIsLyrics = !showQueue && state.lyrics.isNotEmpty()
            // The "Lyrics • Menu = Queue" hint is a teaching aid, not permanent chrome. Show it only
            // while you're actually working with the panel (it has focus) — and only if the Now Playing
            // info setting is on — then fade it away. It still reserves its row so nothing jumps.
            var rightFocused by remember { mutableStateOf(false) }
            val hintAlpha by animateFloatAsState(
                if (state.showNowPlayingInfo && rightFocused) 1f else 0f, tween(250), label = "panelHint")
            Column(modifier = Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = if (rightIsLyrics) 1f else chromeAlpha }) {
                val label = when {
                    showQueue -> "Queue  •  Menu = Lyrics"
                    state.lyrics.isNotEmpty() -> "Lyrics  •  Menu = Queue"
                    else -> "Queue"
                }
                Text(
                    label,
                    fontSize = 10.sp,
                    color = Color(0x99FFFFFF),
                    modifier = Modifier.align(Alignment.End).padding(bottom = 6.dp).graphicsLayer { alpha = hintAlpha },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth().fillMaxHeight().onFocusChanged { rightFocused = it.hasFocus }) {
                    if (showQueue) {
                        QueuePanel(
                            queue = state.queue,
                            currentIndex = state.queueIndex,
                            userQueue = state.userQueue,
                            onSelect = { idx -> playerVm.playFromQueue(idx) },
                            onSelectUserQueue = { idx -> playerVm.playFromUserQueue(idx) },
                            onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                            leftFocus = playFocus,
                        )
                    } else if (state.lyrics.isNotEmpty()) {
                        LyricsPanel(
                            lyrics = state.lyrics,
                            progressState = smoothProgress,
                            offsetMs = state.avLyricsMs,
                            onSeek = { ms -> playerVm.player.seekTo(ms) },
                            playFocus = playFocus,
                            fontScale = state.lyricsScale,
                        )
                    } else {
                        QueuePanel(
                            queue = state.queue,
                            currentIndex = state.queueIndex,
                            userQueue = state.userQueue,
                            onSelect = { idx -> playerVm.playFromQueue(idx) },
                            onSelectUserQueue = { idx -> playerVm.playFromUserQueue(idx) },
                            onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                            leftFocus = playFocus,
                        )
                    }
                }
            }
        }
            }
        } }
        if (!screensaverOn) NextUpToast(
            progressState = smoothProgress,
            durationMs = song.durationMs,
            nextTitle = (state.userQueue.firstOrNull() ?: state.queue.getOrNull(state.queueIndex + 1))?.title,
            songKey = song.id,
        )
    }
}

/**
 * Small "Next: …" toast, bottom-right, that fades in ~15s before the track switches.
 * Reads the per-frame clock via derivedStateOf so only the boolean flip recomposes it.
 */
@Composable
private fun BoxScope.NextUpToast(progressState: androidx.compose.runtime.State<Long>, durationMs: Long, nextTitle: String?, songKey: String) {
    val rawShow by remember(durationMs, nextTitle) {
        androidx.compose.runtime.derivedStateOf {
            nextTitle != null && durationMs > 0L && (durationMs - progressState.value) in 1_000L..15_000L
        }
    }
    // Capture the "next" title ONCE, when the toast arms, and keep showing that. At a track boundary
    // the queue advances a few frames before the current song does, so the live next-title briefly
    // points one song too far ahead (the "next next"). Freezing the captured title ignores that
    // transient; a real track change flips songKey and resets us.
    var shownTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(songKey) { shownTitle = null }
    LaunchedEffect(rawShow, songKey) {
        if (!rawShow) { shownTitle = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        if (rawShow) shownTitle = nextTitle
    }
    val alpha by animateFloatAsState(if (shownTitle != null) 1f else 0f, tween(300), label = "nextToast")
    val title = shownTitle
    if (alpha <= 0.01f || title == null) return
    Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 30.dp, bottom = 52.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(Color(0xE6161616), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("NEXT", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFA233B), letterSpacing = 1.5.sp))
        Text(title, maxLines = 1, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
    }
}

/**
 * Ambient screensaver — the drifting background (rendered behind) plus a small
 * now-playing chip bottom-left. Grabs focus so any key wakes it back to the player.
 */
@Composable
private fun AmbientScreensaver(song: com.applemusicktv.data.model.Song, onWake: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .focusRequester(fr)
            .focusable()
            .onKeyEvent { onWake(); true },
    ) {
        // Heavy dim over the drifting background — colours + motion still faintly show.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)))
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (song.artworkUrl != null) {
                AsyncImage(
                    model = song.artworkUrl(160),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            Column {
                Text(song.title, maxLines = 1, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
                Text(song.artistName, maxLines = 1, style = TextStyle(fontSize = 14.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
            }
        }
    }
}

/**
 * Full-screen lyrics view — hides the split layout, shows only the karaoke lyrics with a
 * small now-playing chip bottom-left. Reached from the 3-dots menu; Back exits.
 */
@Composable
private fun FullScreenLyrics(
    lyrics: List<LyricLine>,
    progressState: androidx.compose.runtime.State<Long>,
    offsetMs: Long,
    song: com.applemusicktv.data.model.Song,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    lyricsScale: Float = 1f,
) {
    // Focus lands on the play button (not the top lyric line). Passing it to LyricsPanel
    // as playFocus also makes RIGHT jump here and the 7s idle auto-return work, so the
    // lyrics don't get stranded scrolled-away after you fiddle with the D-pad.
    val playFocus = remember { FocusRequester() }
    // Retry a few frames: a single requestFocus() on first composition fires before the play button
    // is attached, so it silently failed and focus fell through to the nav bar.
    LaunchedEffect(Unit) {
        repeat(8) {
            if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }

    // Transport controls fade out after 5s of no input; any key brings them back.
    var lastInputMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(lastInputMs) { kotlinx.coroutines.delay(5000); controlsVisible = false }
    val ctlAlpha by animateFloatAsState(if (controlsVisible) 1f else 0f, tween(400), label = "fsControls")

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            lastInputMs = System.currentTimeMillis(); controlsVisible = true; false
        },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 128.dp, top = 36.dp, bottom = 120.dp)) {
            LyricsPanel(
                lyrics = lyrics,
                progressState = progressState,
                offsetMs = offsetMs,
                onSeek = onSeek,
                playFocus = playFocus,
                fontScale = 1.3f * lyricsScale,
                autoReturnMs = 5_000L,
            )
        }
        // Now-playing chip, bottom-left — same placement as the screensaver.
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (song.artworkUrl != null) {
                AsyncImage(
                    model = song.artworkUrl(160),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            Column {
                Text(song.title, maxLines = 1, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
                Text(song.artistName, maxLines = 1, style = TextStyle(fontSize = 14.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
            }
        }
        // Transport controls, bottom-right (where "Back to exit" used to be). Fades on idle.
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 40.dp)
                .graphicsLayer { alpha = ctlAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransportButton(TransportIcon.Prev, onClick = onPrev)
            TransportButton(if (isPlaying) TransportIcon.Pause else TransportIcon.Play, onClick = onPlayPause, modifier = Modifier.focusRequester(playFocus))
            TransportButton(TransportIcon.Next, onClick = onNext)
        }
    }
}

/**
 * Interpolates smooth 60fps playback position between the ~200ms server
 * polling ticks, so word-by-word lyric animation doesn't stutter.
 */
@Composable
private fun rememberSmoothProgressMs(reportedMs: Long, isPlaying: Boolean): androidx.compose.runtime.State<Long> {
    val anchorRealMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anchorPosMs  = remember { mutableLongStateOf(reportedMs) }
    // Returned as State so callers can defer the read to leaf composables — only the
    // one thing that reads .value (the seek bar, the active word wipe) recomposes per
    // frame, instead of the whole Now Playing tree.
    val smoothMs = remember { mutableLongStateOf(reportedMs) }

    LaunchedEffect(reportedMs) {
        anchorPosMs.longValue  = reportedMs
        anchorRealMs.longValue = System.currentTimeMillis()
        smoothMs.longValue = reportedMs
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            withFrameMillis {
                smoothMs.longValue = anchorPosMs.longValue + (System.currentTimeMillis() - anchorRealMs.longValue)
            }
        }
    }

    return smoothMs
}

/**
 * Seek bar + time labels. Reads the per-frame progress State in its own scope so the
 * smooth clock recomposes only this small composable, not the whole Now Playing screen.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerProgressBar(
    progressState: androidx.compose.runtime.State<Long>,
    song: com.applemusicktv.data.model.Song,
    playFocus: FocusRequester,
    player: androidx.media3.common.Player,
) {
    val smoothProgressMs = progressState.value
    val duration = song.durationMs.takeIf { it > 0 } ?: 1L
    val progress = (smoothProgressMs.toFloat() / duration).coerceIn(0f, 1f)
    var seekBarFocused by remember { mutableStateOf(false) }
    var scrubMs by remember(song.id) { mutableLongStateOf(smoothProgressMs) }
    // Keep scrub cursor in sync with playback when not focused
    LaunchedEffect(smoothProgressMs, seekBarFocused) {
        if (!seekBarFocused) scrubMs = smoothProgressMs
    }
    val scrubProgress = (scrubMs.toFloat() / duration).coerceIn(0f, 1f)
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = { if (seekBarFocused) { player.seekTo(scrubMs); runCatching { playFocus.requestFocus() } } },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { seekBarFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft  -> { scrubMs = (scrubMs - 10_000).coerceAtLeast(0); true }
                        Key.DirectionRight -> { scrubMs = (scrubMs + 10_000).coerceAtMost(duration); true }
                        else -> false
                    }
                } else false
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(if (seekBarFocused) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x44FFFFFF))) {
                Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFFFA233B)))
                if (seekBarFocused) {
                    Box(modifier = Modifier.fillMaxWidth(scrubProgress).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(
            if (seekBarFocused) formatMs(scrubMs) else formatMs(smoothProgressMs),
            style = TextStyle(fontSize = 11.sp, color = if (seekBarFocused) Color.White else Color(0xFFAAAAAA), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
        )
        Text(song.durationFormatted, style = TextStyle(fontSize = 11.sp, color = Color(0xFFAAAAAA), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
    }
}

/** Looping, muted motion album-art video layered over the static cover. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun MotionCover(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ready by remember(url) { mutableStateOf(false) }

    val exo = remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_READY) ready = true
                }
            })
            prepare()
        }
    }

    // On resume from background the surface reattaches and briefly shows a green
    // YUV frame. Reset ready=false on pause so the shutter hides it until the
    // first decoded frame arrives.
    DisposableEffect(lifecycleOwner, exo) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { ready = false; exo.pause() }
                Lifecycle.Event.ON_RESUME -> {
                    exo.play()
                    // If already buffered, flip ready immediately; otherwise wait for listener
                    if (exo.playbackState == androidx.media3.common.Player.STATE_READY) ready = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exo.release()
        }
    }

    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(400), label = "motionFade")

    // A raw TextureView, NOT PlayerView. PlayerView renders into a SurfaceView, which is a
    // hole punched through the window — it does NOT honour Compose transforms and stretches
    // by its own surface size, which is what left the thin black edge / "shrinks in" look on
    // the static→motion handoff. A TextureView draws in the normal view layer, scales its
    // buffer to the exact view bounds (== the cover box), so the frame fills edge-to-edge.
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                exo.setVideoTextureView(this)
            }
        },
        update = { view -> exo.setVideoTextureView(view) },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

private fun Color.hsvHue(): Float {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val delta = max - min
    if (delta < 0.001f) return 0f
    val h = when (max) {
        r -> 60f * (((g - b) / delta).mod(6f))
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return if (h < 0f) h + 360f else h
}

private fun hueDist(a: Float, b: Float): Float {
    val d = kotlin.math.abs(a - b); return minOf(d, 360f - d)
}

/** Make a lighter/darker (and slightly de/re-saturated) shade of [c] — SAME hue. Used to fill orb
 *  slots from an album that only has one colour family, so we never invent a hue (blue/green) the
 *  artwork doesn't contain. step 0 = lighter, 1 = darker, 2 = lighter+, … */
private fun varyShade(c: Color, step: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
    val dir = if (step % 2 == 0) 1f else -1f
    val mag = 0.18f + 0.12f * (step / 2)
    hsv[2] = (hsv[2] + dir * mag).coerceIn(0.30f, 0.98f)
    hsv[1] = (hsv[1] * (1f - dir * 0.12f)).coerceIn(0.35f, 1f)   // lighter reads a touch less saturated
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// Pick up to n colors distinct in hue (>= minAngle apart). If the album doesn't HAVE that many
// separated accents, fill the rest with lighter/darker SHADES of the accents we found — so every orb
// colour still comes from the artwork (an orange cover gives orange shades, never a fake blue/green).
private fun spreadByHue(colors: List<Color>, n: Int, minAngle: Float = 20f): List<Color> {
    val result = mutableListOf<Color>()
    val chosen = mutableListOf<Pair<Float, Float>>()   // (hue, value)
    // Keep a swatch if it adds EITHER hue variety OR tonal variety. An album that is one
    // colour family (most are) still yields several real orbs — vibrant / muted / dark /
    // light variants of that family — instead of collapsing to one hue and then padding
    // with computed shades. This is what gives the Apple "oil painting" spread. We only
    // drop a swatch that is near-identical in BOTH hue and brightness to one already kept.
    for (c in colors) {
        val h = c.hsvHue()
        val v = maxOf(c.red, c.green, c.blue)
        if (chosen.all { (ch, cv) -> hueDist(h, ch) >= minAngle || kotlin.math.abs(v - cv) >= 0.16f }) {
            result.add(c); chosen.add(h to v)
            if (result.size == n) break
        }
    }
    if (result.size < n) {
        val src = result.toList().ifEmpty { colors.take(1) }.ifEmpty { listOf(Color(0xFF888888)) }
        var k = 0
        while (result.size < n) {
            result.add(varyShade(src[k % src.size], k / src.size)); k++
        }
    }
    return result
}

// Monochrome equivalent of spreadByHue: separate greys by BRIGHTNESS (hue is meaningless when
// every swatch is grey, so spreadByHue would collapse them all onto the first one).
private fun spreadByValue(colors: List<Color>, n: Int, minGap: Float = 0.13f): List<Color> {
    val result = mutableListOf<Color>()
    val chosen = mutableListOf<Float>()
    for (c in colors) {
        val v = maxOf(c.red, c.green, c.blue)
        if (chosen.all { kotlin.math.abs(v - it) >= minGap }) {
            result.add(c); chosen.add(v)
            if (result.size == n) break
        }
    }
    if (result.size < n) {
        for (c in colors) {
            if (c !in result) { result.add(c); if (result.size == n) break }
        }
    }
    return result
}

// Backdrop vibrancy. Raising SAT_* makes colors read as colors rather than tints;
// VALUE_CEILING is the safety rail that keeps them from turning pale and competing
// with the white lyrics on the right half of the screen. Don't push it past ~0.85.
private const val SAT_BOOST = 1.45f
private const val SAT_FLOOR = 0.55f
private const val VALUE_CEILING = 0.80f

/** Extracts a dark base color + a vibrant accent color from the artwork. */
@Composable
private fun rememberArtworkPalette(artworkUrl: String?): List<Color> {
    val context = LocalContext.current
    val fallback = listOf(Color(0xFF0A0A0A), Color(0xFF0D0D0D), Color(0xFF0A0A0A), Color(0xFF111111), Color(0xFF080808), Color(0xFF0D0D0D))
    var colors by remember(artworkUrl) { mutableStateOf(fallback) }
    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null) return@LaunchedEffect
        try {
            // Decode a SMALL bitmap for the palette — Palette downsamples internally anyway, so a
            // 1200² ARGB bitmap (~5.7 MB, kept in RAM with allowHardware off) was pure waste on a
            // memory-starved Fire TV. 256² is ~256 KB and gives an identical palette. Reuse Coil's
            // shared loader instead of spinning up a new ImageLoader per song.
            val loader = context.applicationContext.let { coil.Coil.imageLoader(it) }
            // Don't let the palette's small ARGB bitmap sit in Coil's memory cache — it's used once,
            // right here, then thrown away. (The displayed artwork is a separate, cached request.)
            val request = ImageRequest.Builder(context).data(artworkUrl).size(256).allowHardware(false)
                .memoryCachePolicy(coil.request.CachePolicy.DISABLED).build()
            val result = loader.execute(request)
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
            val p = Palette.from(bitmap).maximumColorCount(16).generate()
            val swatches = listOfNotNull(
                p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch,
                p.mutedSwatch, p.lightMutedSwatch, p.darkMutedSwatch, p.dominantSwatch,
            ).sortedByDescending { it.population }
            // Detect a grey / black-and-white cover from the ORIGINAL saturations, BEFORE the boost:
            // once every swatch is floored to SAT_FLOOR they all look colourful and the test can't
            // tell. A monochrome sleeve keeps its greys (and can go near-white), separated by
            // brightness instead of hue, so the orbs read as three shades of grey — not invented colour.
            val hsv = FloatArray(3)
            val maxSat = swatches.maxOfOrNull { android.graphics.Color.colorToHSV(it.rgb, hsv); hsv[1] } ?: 0f
            val monochrome = maxSat < 0.18f
            val picked = swatches.map { swatch ->
                android.graphics.Color.colorToHSV(swatch.rgb, hsv)
                if (monochrome) {
                    hsv[1] = 0f                                              // true grey, no invented hue
                    // Keep greys GREY — a near-white orb competes with the white lyrics. Cap well below
                    // white so monochrome art reads as smoky grey pools, not glowing white blobs.
                    hsv[2] = (0.30f + hsv[2] * 0.42f).coerceIn(0.30f, 0.68f)
                } else {
                    // Deep saturated colours read as colour; pastels read as light grey. Floor the
                    // saturation and cap the value so a pale swatch doesn't wash out the lyrics.
                    hsv[1] = (hsv[1] * SAT_BOOST).coerceIn(SAT_FLOOR, 1f)
                    hsv[2] = hsv[2].coerceAtMost(VALUE_CEILING)
                }
                Color(android.graphics.Color.HSVToColor(hsv))
            }.distinct()
            val dom = Color(p.getDominantColor(0xFF050505.toInt()))
            val domLum = 0.2126f * dom.red + 0.7152f * dom.green + 0.0722f * dom.blue

            colors = when {
                domLum < 0.06f && !monochrome -> fallback   // truly black colour art — nothing to extract
                picked.size >= 2 -> if (monochrome) spreadByValue(picked, 6) else spreadByHue(picked, 6)
                else -> {
                    val dark  = Color(dom.red * 0.4f, dom.green * 0.4f, dom.blue * 0.4f)
                    val light = Color((dom.red + 0.3f).coerceAtMost(1f), (dom.green + 0.3f).coerceAtMost(1f), (dom.blue + 0.3f).coerceAtMost(1f))
                    listOf(dom, light, dark, dom, light, dark)
                }
            }
        } catch (_: Exception) {}
    }
    return colors
}

/**
 * Full-screen backdrop: blurred album artwork (loaded tiny → upscaled) with
 * 4 radial color blobs drifting in quadrants, Screen-blended on top.
 * Beat energy pulses blob radius and alpha.
 */
@Composable
private fun DynamicBackground(artworkUrlTemplate: String?, songKey: String, beatAnalyzer: com.applemusicktv.media.BeatAnalyzer, beatMultiplier: Float = 1f, mode: NowPlayingBackground = NowPlayingBackground.DYNAMIC, playing: Boolean = true, orbSpeed: Float = 1f, reduceMotion: Boolean = false, lowPower: Boolean = false) {
    // BLACK: plain black, no blobs, no beat. Nothing else to compute.
    if (mode == NowPlayingBackground.BLACK) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val projector = mode == NowPlayingBackground.PROJECTOR

    // Intensity (Calm 0.55 … Crazy 3.5) is applied as a render AMPLITUDE below, NOT by multiplying the
    // level and clipping to 1 — that just pinned everything at max, so Strong and Crazy looked the same
    // and the vocal orb sat maxed. Levels stay 0..1; the multiplier drives how big/bright they swell.
    val amp = beatMultiplier
    // PERF: everything animated below is kept as State and read INSIDE drawBehind, never with `by` at
    // composable scope. Reading them here made DynamicBackground recompose ~60×/sec — rebuilding colour
    // lists and brushes every frame, which was the app's main GC-churn source. Read in the draw lambda,
    // only the draw phase re-runs each frame; the composable recomposes only on song/palette change.
    val rawEnergy by beatAnalyzer.energy.collectAsState()
    // Critically damped (dampingRatio 1.0), not the old 0.5: an underdamped spring overshoots and
    // rings after every hit, which read as the punch "bouncing all over the place". 1.0 gives a snappy
    // attack that settles cleanly with no wobble, so each beat lands once and decays.
    val energyState = animateFloatAsState(rawEnergy.coerceIn(0f, 1f), androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "beat")

    val rawBands by beatAnalyzer.bands.collectAsState()
    val bandSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
    val band0State = animateFloatAsState(rawBands.getOrElse(0) { 0f }.coerceIn(0f, 1f), bandSpring, label = "bass")
    val band1State = animateFloatAsState(rawBands.getOrElse(1) { 0f }.coerceIn(0f, 1f), bandSpring, label = "vocal")
    val band2State = animateFloatAsState(rawBands.getOrElse(2) { 0f }.coerceIn(0f, 1f), bandSpring, label = "treble")

    val paletteUrl = artworkUrlTemplate?.replace("{w}", "300")?.replace("{h}", "300")?.replace("{f}", "jpg")
    val palette = rememberArtworkPalette(paletteUrl)
    val animatedStates = palette.mapIndexed { i, c ->
        animateColorAsState(c, tween(1500), label = "blob$i")
    }

    // Orb speed scales the drift periods (faster speed → shorter tween).
    val sp = orbSpeed.coerceIn(0.4f, 2.0f)
    val infinite = rememberInfiniteTransition(label = "pool")
    val t1s = infinite.animateFloat(0f, 1f, infiniteRepeatable(tween((20_000 / sp).toInt(), easing = LinearEasing), AnimRepeatMode.Reverse), label = "t1")
    val t2s = infinite.animateFloat(0f, 1f, infiniteRepeatable(tween((27_000 / sp).toInt(), easing = LinearEasing), AnimRepeatMode.Reverse), label = "t2")
    val t3s = infinite.animateFloat(0f, 1f, infiniteRepeatable(tween((34_000 / sp).toInt(), easing = LinearEasing), AnimRepeatMode.Reverse), label = "t3")
    val t4s = infinite.animateFloat(0f, 1f, infiniteRepeatable(tween((15_000 / sp).toInt(), easing = LinearEasing), AnimRepeatMode.Reverse), label = "t4")

    // PERF + accessibility: freeze the perpetual drift while paused OR when Reduce Motion is on. We
    // snapshot the drift phase and read that in the draw instead of the live State, so the draw phase
    // isn't invalidated 60×/sec. Beat energy/bands settle to 0 on their own (and Reduce Motion zeroes
    // them below, so the orbs hold completely still).
    val moving = playing && !reduceMotion
    val frozen = remember { floatArrayOf(0f, 0f, 0f, 0f) }
    LaunchedEffect(moving) {
        if (!moving) { frozen[0] = t1s.value; frozen[1] = t2s.value; frozen[2] = t3s.value; frozen[3] = t4s.value }
    }

    // PROJECTOR uses TRUE black; a projector throws light on a wall, so the near-black #050505 lift
    // that stops a panel crushing shadows becomes a visible grey rectangle instead.
    Box(Modifier.fillMaxSize().background(if (projector) Color.Black else Color(0xFF050505))) {
        Box(Modifier.fillMaxSize().drawBehind {
            val w = size.width; val h = size.height
            // Deferred reads — this is the draw phase, so these re-run per frame without recomposing.
            // While paused we read the frozen snapshot (not the live State) so the draw stops updating.
            val t1 = if (moving) t1s.value else frozen[0]
            val t2 = if (moving) t2s.value else frozen[1]
            val t3 = if (moving) t3s.value else frozen[2]
            val t4 = if (moving) t4s.value else frozen[3]
            val n = animatedStates.size
            val motionAmp = if (reduceMotion) 0f else 1f   // Reduce Motion → orbs hold at base, no pulse

            if (projector) {
                // THREE ORBS, ONE PER BAND — bass (slow, low), vocal (centre-panned, mid), treble
                // (fast, high). Each rides an ellipse on its own animator+phase so the composition
                // never repeats, swells on its band, and glows from a palette colour.
                // Small drift toward the next accent so colour evolves but each orb keeps its identity.
                val orbColors = List(3) { i -> lerp(animatedStates[i % n].value, animatedStates[(i + 1) % n].value, 0.05f + 0.10f * floatArrayOf(t1, t2, t3)[i]) }
                val orbLevels = floatArrayOf(band0State.value * motionAmp, band1State.value * motionAmp, band2State.value * motionAmp)
                // Pushed right of the album art (which sits in the left column) so no orb hides behind it.
                val anchorX = floatArrayOf(0.52f, 0.62f, 0.72f)
                val anchorY = floatArrayOf(0.44f, 0.56f, 0.46f)
                val phase   = floatArrayOf(0f, 2.1f, 4.2f)
                val drift   = floatArrayOf(t1, t2, t3)
                val twoPi = (2.0 * Math.PI).toFloat()
                // Smaller orbs than the full-screen blobs — a projector glow is a light source, not a
                // wash. Wider drift so they still roam the frame at the smaller size.
                // Per-band character so the three read as three: bass is the big slow one, treble the
                // small snappy one, vocal in between. Each is DIM at rest and swells hard on its band,
                // so the beat is the difference you see — not a constant wash.
                val bandBaseR = floatArrayOf(minOf(w, h) * 0.26f, minOf(w, h) * 0.21f, minOf(w, h) * 0.16f)
                val sizeRide  = floatArrayOf(0.75f, 0.85f, 1.05f)   // treble punches biggest relative to size
                for (i in 0 until 3) {
                    val lvl = orbLevels[i]
                    // Gentler drift so the orbs roam slowly instead of swimming around the frame.
                    val cx = anchorX[i] * w + cos(drift[i] * twoPi + phase[i]) * 0.08f * w
                    val cy = anchorY[i] * h + sin(drift[i] * twoPi + phase[i]) * 0.045f * h
                    val c = Offset(cx, cy)
                    val col = orbColors[i]
                    // Halo alpha now RIDES the beat: dim at rest (won't wash the lyrics), bright on the
                    // hit. On true black under Screen blend that's exactly the expressive pulse we want.
                    // Intensity (amp) scales the SWELL, not the base: Calm barely moves, Crazy swings big
                    // and bright. Generous caps so higher tiers stay visibly punchier instead of clipping.
                    val r = bandBaseR[i] * (1f + (lvl * sizeRide[i] * amp).coerceAtMost(2.2f))
                    val haloA = (0.24f + lvl * 0.42f * amp).coerceAtMost(0.9f)
                    drawCircle(
                        brush = Brush.radialGradient(listOf(col.copy(alpha = haloA), col.copy(alpha = 0f)), center = c, radius = r),
                        radius = r, center = c, blendMode = BlendMode.Screen,
                    )
                    // Core: small, whitened (a real glow's hottest point desaturates), punches hard on
                    // the band — a tiny fraction of the area, so it can flare without lifting the black.
                    // Low Power skips the core (a second gradient per orb per frame) — the halo carries it.
                    if (!lowPower) {
                        val cr = r * 0.34f
                        val core = lerp(col, Color.White, 0.6f)
                        drawCircle(
                            brush = Brush.radialGradient(listOf(core.copy(alpha = (0.12f + lvl * 0.62f * amp).coerceAtMost(0.92f)), core.copy(alpha = 0f)), center = c, radius = cr),
                            radius = cr, center = c, blendMode = BlendMode.Screen,
                        )
                    }
                }
                // A LIGHT edge fade only — just enough to guarantee no lit rectangle at the frame edge.
                // The orbs are small and pulled inward, so they never reach the edge anyway; a heavy
                // vignette here was painting black back over the glow and killing it.
                val ev = h * 0.10f; val eh = w * 0.10f
                drawRect(Brush.verticalGradient(listOf(Color.Black, Color(0x00000000)), startY = 0f, endY = ev))
                drawRect(Brush.verticalGradient(listOf(Color(0x00000000), Color.Black), startY = h - ev, endY = h))
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color(0x00000000)), startX = 0f, endX = eh))
                drawRect(Brush.horizontalGradient(listOf(Color(0x00000000), Color.Black), startX = w - eh, endX = w))
                // Right-side darkening for lyrics readability (a gradient, not an edge). Stronger now —
                // a bright orb drifting under the lyric column was washing out the dim inactive lines.
                drawRect(Brush.horizontalGradient(listOf(Color(0x00000000), Color(0x9E000000)), startX = w * 0.30f, endX = w))
                return@drawBehind
            }

            // DYNAMIC — four drifting album-colour blobs. Intensity (amp) scales the beat swing;
            // Reduce Motion (motionAmp=0) holds them still.
            val energy = energyState.value * motionAmp
            val blobCount = if (lowPower) 2 else 4   // Low Power halves the blob count
            // Each blob is PINNED to its own distinct palette colour — no pair-crossfade. The old
            // lerp blended two colours per blob and, with big overlapping blobs under Screen, merged
            // the whole thing into one moving gradient wash. One colour per blob + smaller radius
            // (below) lets the separate colours read as distinct pools, like Apple's oil-painting
            // patches, instead of averaging into a single tint.
            val colors4 = List(4) { i -> animatedStates[i % n].value }
            val eAmp = (energy * amp).coerceIn(0f, 2.2f)
            // Per-corner band ride (projector-mode flavour): each corner breathes on its own
            // frequency band on top of the shared beat, so bass thumps two corners and treble
            // shimmers the other two — the palette pools now read the spectrum, not one pulse.
            val bandRide = floatArrayOf(
                band0State.value,   // top-left  → bass
                band2State.value,   // top-right → treble
                band1State.value,   // bot-left  → vocal/mid
                band0State.value,   // bot-right → bass
            )
            val beatScale = 1f + eAmp * 0.25f
            val beatAlpha = (0.60f + eAmp * 0.20f).coerceAtMost(0.95f)
            // Smaller than the old 0.62 so blobs overlap less and stay recognisably separate colours.
            val r = maxOf(w, h) * 0.42f * beatScale
            val nudge = eAmp * maxOf(w, h) * 0.02f
            val nudgeOffsets = listOf(
                Offset( nudge,  nudge * 0.5f),
                Offset(-nudge, -nudge * 0.7f),
                Offset( nudge * 0.6f, -nudge),
                Offset(-nudge * 0.4f,  nudge * 0.8f),
            )
            val centers = listOf(
                Offset(lerp(0.02f, 0.28f, t1) * w, lerp(0.05f, 0.32f, t2) * h),
                Offset(lerp(0.72f, 0.98f, t2) * w, lerp(0.05f, 0.35f, t3) * h),
                Offset(lerp(0.05f, 0.30f, t3) * w, lerp(0.68f, 0.95f, t1) * h),
                Offset(lerp(0.70f, 0.98f, t1) * w, lerp(0.65f, 0.95f, t3) * h),
            ).mapIndexed { i, c -> c + nudgeOffsets[i] }
            colors4.take(blobCount).forEachIndexed { i, color ->
                val ride = (bandRide[i] * amp).coerceIn(0f, 1.6f)
                val rr = r * (1f + ride * 0.30f)
                val aa = (beatAlpha + ride * 0.18f).coerceAtMost(0.98f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = aa), color.copy(alpha = 0f)),
                        center = centers[i], radius = rr,
                    ),
                    radius = rr, center = centers[i],
                    blendMode = BlendMode.Screen,
                )
            }
            // Center darkening
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x77000000), Color(0x00000000)),
                    center = Offset(w * 0.5f, h * 0.5f), radius = maxOf(w, h) * 0.55f,
                ),
                radius = maxOf(w, h) * 0.55f, center = Offset(w * 0.5f, h * 0.5f),
            )
            // Right-side darkening for lyrics readability
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color(0x00000000), Color(0x7A000000)),
                    startX = w * 0.35f, endX = w,
                ),
            )
            // Kept low: the flat veil mutes every hue equally, so readability comes
            // from the right-side gradient above instead.
            drawRect(Color(0x22000000))
        })
    }
}

/** Idle time (no D-pad navigation) before the Now Playing chrome auto-hides (then a 2 s settle). */
private const val CHROME_HIDE_MS = 7000L

/** D-pad navigation / select keys — the ones allowed to wake the auto-hidden chrome. Media transport
 *  keys are deliberately excluded so play/pause/skip don't bring the controls back. */
private fun isNavKey(key: Key): Boolean =
    key == Key.DirectionUp || key == Key.DirectionDown ||
        key == Key.DirectionLeft || key == Key.DirectionRight ||
        key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** How many lines of already-sung context stay above the active line after a scroll. */
private const val LYRIC_LEAD_LINES = 2
/** Scroll once the active line gets this close to the bottom of the viewport. */
private const val LYRIC_BOTTOM_MARGIN_PX = 40

private const val GAP_THRESHOLD_MS = 4000L
private const val LINE_END_GRACE_MS = 250L
private const val GAP_FADEOUT_MS    = 500L


@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LyricsPanel(
    lyrics: List<LyricLine>,
    progressState: androidx.compose.runtime.State<Long>,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    playFocus: FocusRequester? = null,
    fontScale: Float = 1f,
    autoReturnMs: Long = 7000L,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activeLineFocus = remember { FocusRequester() }

    // Structure (which line is active, gaps, auto-scroll) only needs coarse progress —
    // derivedStateOf quantises to 200ms so the list body recomposes ~5x/s, not 60x/s.
    // The active word wipe reads the raw per-frame State itself, staying smooth.
    val progressMs by remember(offsetMs) {
        androidx.compose.runtime.derivedStateOf { ((progressState.value + offsetMs) / 200L) * 200L }
    }
    val liveProgress = { progressState.value + offsetMs }

    val passedIndex = lyrics.indexOfLast { it.startMs <= progressMs }
    // Keep a line lit until its background vocals finish too — the lead line shouldn't
    // dim out while its (call-and-response) background part is still being sung.
    val activeIndex = if (passedIndex >= 0) {
        val ln = lyrics[passedIndex]
        val activeUntil = maxOf(ln.endMs, ln.background?.endMs ?: Long.MIN_VALUE) + LINE_END_GRACE_MS
        if (progressMs <= activeUntil) passedIndex else -1
    } else -1

    val scrollAnchor = passedIndex.coerceAtLeast(0)
    val firstLoad = remember { mutableStateOf(true) }

    // Track user-initiated scrolls so auto-scroll doesn't fight them. Auto-scroll
    // resumes the way Apple Music does: not on a timer, but when you bring the
    // current line back on screen — and then only at the next line change, so it
    // rejoins the flow instead of yanking mid-line.
    var userScrolled by remember { mutableStateOf(false) }
    // Also arm the auto-return when the lyric list simply HAS focus (you D-padded LEFT
    // into it) — not only after a scroll. Focus-in alone used to leave the timer disarmed,
    // so it never handed focus back to the play button.
    var listFocused by remember { mutableStateOf(false) }
    // After the lyrics have been focused/centred for a bit, hand focus back to the
    // play button so the D-pad isn't stranded in the lyric list.
    LaunchedEffect(userScrolled, listFocused) {
        if (playFocus == null || (!userScrolled && !listFocused)) return@LaunchedEffect
        kotlinx.coroutines.delay(autoReturnMs)
        if (!listState.isScrollInProgress) { runCatching { playFocus.requestFocus() }; userScrolled = false }
    }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) userScrolled = true
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(scrollAnchor, lyrics.size) {
        if (lyrics.isEmpty()) return@LaunchedEffect
        val target = (scrollAnchor - LYRIC_LEAD_LINES).coerceAtLeast(0)
        if (firstLoad.value) {
            listState.scrollToItem(target)
            firstLoad.value = false
            return@LaunchedEffect
        }
        if (userScrolled) {
            // Still browsing elsewhere — leave the list alone. Once the active line is
            // visible again this same effect fires on the next line change and takes over.
            val backInView = listState.layoutInfo.visibleItemsInfo.any { it.index == scrollAnchor }
            if (!backInView || listState.isScrollInProgress) return@LaunchedEffect
            userScrolled = false
            listState.animateScrollToItem(target)
            return@LaunchedEffect
        }

        // Centre the active line on every line change (Apple Music behaviour) — the
        // old "only scroll near the bottom" logic is what made it jump every few lines.
        val info = listState.layoutInfo
        val activeItem = info.visibleItemsInfo.firstOrNull { it.index == scrollAnchor }
        if (activeItem == null) {
            listState.animateScrollToItem(target)
        } else {
            val viewportH = info.viewportEndOffset - info.viewportStartOffset
            val itemCentre = activeItem.offset + activeItem.size / 2
            // Sit the active line high — around the album-art midline (~30% down), not centre.
            val delta = (itemCentre - viewportH * 0.30f)
            if (kotlin.math.abs(delta) > 8f) listState.animateScrollBy(delta)
        }
    }

    // When navigating back to this screen, scroll to the active line.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!firstLoad.value && lyrics.isNotEmpty()) {
                val target = (passedIndex - 3).coerceAtLeast(0)
                userScrolled = false
                listState.scrollToItem(target)
            }
        }
    }

    // No pinned/sticky line. It was tried for Get Lucky's held phrase, but
    // line-synced sources set endMs to the next line's startMs, so nearly every line
    // matched and one was permanently stuck at the top, fading in and out.
    Column(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).padding(end = 16.dp).nestedScroll(nestedScrollConnection)
            .onFocusChanged { listFocused = it.hasFocus }
            // Soft fade the top + bottom edges so lines dissolve in/out instead of popping.
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val h = size.height
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        (48f / h) to Color.Black,
                        ((h - 150f) / h).coerceIn(0f, 1f) to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .then(if (playFocus != null) Modifier.focusProperties {
                right = playFocus
                // LEFT from a lyric line always lands on play/pause too — user wants
                // either horizontal press out of the lyrics to go straight to transport.
                left = playFocus
                // Entering the list (e.g. LEFT from the play button) lands on the current
                // line, not whichever line happens to sit nearest the button.
                // Only steer focus to the active line if it is actually laid out right now.
                // Requesting focus on a detached FocusRequester leaves the window with no
                // focused view → input dispatch times out → ANR. Fall back to Default.
                enter = {
                    val activeVisible = activeIndex >= 0 &&
                        listState.layoutInfo.visibleItemsInfo.any { it.index == activeIndex }
                    if (activeVisible) activeLineFocus else androidx.compose.ui.focus.FocusRequester.Default
                }
            } else Modifier)
            .onPreviewKeyEvent { ev: androidx.compose.ui.input.key.KeyEvent ->
                // Block upward D-pad escape to top nav bar from lyrics
                ev.key == Key.DirectionUp && ev.type == KeyEventType.KeyDown &&
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            },
        contentPadding = PaddingValues(top = 32.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Key MUST be unique: two synced lines can share a startMs (a blank + first line both at 0ms
        // is common), and duplicate keys crash LazyColumn on the scroll-to-active remeasure. Fold in
        // the index — the list never reorders within a song, so index keeps keys stable and unique.
        items(lyrics.size, key = { "$it:${lyrics[it].startMs}" }) { idx ->
            val line = lyrics[idx]
            // One line lit at a time. Holding sustained lines lit was tried and removed:
            // line-synced sources set endMs to the next line's startMs, so "still within
            // its window" matched almost everything and the list scrolled and faded
            // wrongly on ordinary songs.
            // Unsynced (plain-text) lyrics come back with startMs = -1. Show them so
            // the panel isn't empty, but never highlighted and never seekable.
            val unsynced = line.startMs < 0
            // Sustained/held line stays lit in-flow while its own window still covers now (Get Lucky).
            val overlapActive = !unsynced && line.words.isNotEmpty() &&
                progressMs >= line.startMs && progressMs <= line.endMs &&
                (idx + 1 >= lyrics.size || line.endMs > lyrics[idx + 1].startMs)
            val isActive = (!unsynced && idx == activeIndex) || overlapActive
            val isPast = !unsynced && (idx < passedIndex || (idx == passedIndex && activeIndex == -1))
            // Only the active line reads the live per-frame clock (so only it recomposes
            // at 60fps). Inactive lines get a fixed value and never re-run on tick.
            val staticLineProgress = if (isPast) line.endMs else line.startMs - 1L
            val progressProvider: () -> Long = if (isActive) liveProgress else ({ staticLineProgress })

            val prevEnd = if (idx > 0) lyrics[idx - 1].endMs else 0L
            val gapMs = line.startMs - prevEnd
            val inGap = progressMs in prevEnd until line.startMs

            if (gapMs >= GAP_THRESHOLD_MS && inGap && idx == passedIndex + 1) {
                val dotsAlpha = if (line.startMs - progressMs < GAP_FADEOUT_MS)
                    ((line.startMs - progressMs).toFloat() / GAP_FADEOUT_MS).coerceIn(0f, 1f)
                else 1f
                MusicalDots(
                    fraction = ((progressMs - prevEnd).toFloat() / gapMs).coerceIn(0f, 1f),
                    outerAlpha = dotsAlpha,
                    // Inset to align with the lyric text (and clear the left edge so the
                    // first dot isn't clipped).
                    modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
                )
            }

            if (unsynced) {
                Text(
                    line.text,
                    style = TextStyle(fontSize = (22f * fontScale).sp, lineHeight = (28f * fontScale).sp, fontWeight = FontWeight.Medium, color = Color(0x66FFFFFF)),
                    modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                )
            } else {
                LyricLineRow(
                    line = line,
                    isActive = isActive,
                    isPast = isPast,
                    progress = progressProvider,
                    fontScale = fontScale,
                    focusRequester = if (isActive) activeLineFocus else null,
                    onSeek = { onSeek(line.startMs) },
                )
            }
        }
    }
    }
}

@Composable
private fun MusicalDots(fraction: Float, outerAlpha: Float = 1f, modifier: Modifier = Modifier) {
    // Each dot grows sequentially, then all shrink together.
    val dotStarts = floatArrayOf(0f, 0.24f, 0.48f)
    val dotDur    = 0.28f
    val shrinkStart = 0.82f

    Row(
        modifier = modifier.graphicsLayer { alpha = outerAlpha },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 3) {
            val growFrac   = ((fraction - dotStarts[i]) / dotDur).coerceIn(0f, 1f)
            val shrinkFrac = if (fraction >= shrinkStart)
                ((fraction - shrinkStart) / (1f - shrinkStart)).coerceIn(0f, 1f) else 0f
            val lit = if (fraction >= shrinkStart) 1f - shrinkFrac else growFrac
            val dotColor = lerp(Color(0xFF444444), Color.White, lit)
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer { val s = 0.55f + 0.65f * lit; scaleX = s; scaleY = s }
                    .clip(RoundedCornerShape(50))
                    .background(dotColor),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordWipeLine(
    words: List<LyricWord>,
    activeIdx: Int,
    progressMs: Long,
    live: Boolean = true,
    fontSize: androidx.compose.ui.unit.TextUnit = 26.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 32.sp,
    weight: FontWeight = FontWeight.Bold,
    sungColor: Color = Color.White,
    unsungColor: Color = Color(0xFF76767C),
) {
    FlowRow(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        words.forEachIndexed { i, word ->
            val dur = (word.endMs - word.startMs).coerceAtLeast(1L)
            val sung = i < activeIdx
            val isCurrent = i == activeIdx && live
            val frac = when {
                sung      -> 1f
                isCurrent -> ((progressMs - word.startMs).toFloat() / dur).coerceIn(0f, 1f)
                else      -> 0f
            }
            // Smooth sine swell — grows to a peak mid-word then eases back to original
            // size by the end. Sine (not a linear triangle) keeps it from reading as
            // shake; left-anchored so the first letter stays put. Slow/held words swell +
            // glow more; fast words stay flat.
            val slow  = (dur.coerceIn(300L, 1600L) - 300L) / 1300f
            val pulse = if (isCurrent) kotlin.math.sin(frac * Math.PI.toFloat()).coerceIn(0f, 1f) else 0f
            val grow  = 1f + 0.09f * slow * pulse
            val glowA = if (isCurrent && dur > 700L) 0.34f * pulse * slow else 0f
            WordWipe(
                text  = word.text + if (i < words.lastIndex) " " else "",
                frac  = frac, scale = grow, glowAlpha = glowA,
                fontSize = fontSize, lineHeight = lineHeight, weight = weight,
                sungColor = sungColor, unsungColor = unsungColor,
            )
        }
    }
}

@Composable
private fun WordWipe(
    text: String,
    frac: Float,
    scale: Float,
    glowAlpha: Float,
    fontSize: androidx.compose.ui.unit.TextUnit = 26.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 32.sp,
    weight: FontWeight = FontWeight.Bold,
    sungColor: Color = Color.White,
    unsungColor: Color = Color(0xFF76767C),
) {
    val f = frac.coerceIn(0f, 1f)
    // Soft left→right sweep: a feathered gradient edge instead of a hard clip line,
    // so the sung/unsung boundary reads as a smooth blur rather than a moving cut.
    val feather = 0.22f
    val lo = (f - feather).coerceIn(0f, 1f)
    val hi = (f + feather).coerceIn(0f, 1f)
    val brush: Brush = when {
        f <= 0f -> SolidColor(unsungColor)
        f >= 1f -> SolidColor(sungColor)
        else -> Brush.horizontalGradient(0f to sungColor, lo to sungColor, hi to unsungColor, 1f to unsungColor)
    }
    val glow = if (glowAlpha > 0f) Shadow(Color.White.copy(alpha = glowAlpha), blurRadius = 32f) else null
    Text(
        text,
        style = TextStyle(fontSize = fontSize, fontWeight = weight, lineHeight = lineHeight, letterSpacing = (-0.4).sp, brush = brush, shadow = glow),
        modifier = Modifier.graphicsLayer {
            scaleX = scale; scaleY = scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    progress: () -> Long,
    onSeek: () -> Unit,
    fontScale: Float = 1f,
    focusRequester: FocusRequester? = null,
) {
    // Reading the provider here subscribes only this row: the active row (live clock)
    // recomposes per frame; inactive rows read a constant and never re-run on tick.
    val progressMs = progress()
    val targetOpacity = when {
        isActive -> 1f
        isPast   -> 0.18f
        else     -> 0.25f
    }
    // Active line grows via font size (20→26sp) only. The old 0.93→1.0 graphicsLayer scale tween made
    // rows visibly stretch/"melt" for a frame as the active line changed during a scroll — dropped it;
    // opacity alone carries the transition.
    val opacity by animateFloatAsState(targetOpacity, tween(200), label = "lineOpacity")

    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = opacity
            clip = false
        }
    ) {
        Surface(
            onClick = onSeek,
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color(0x1AFFFFFF),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (isActive && line.words.isNotEmpty()) {
                    // Karaoke wipe: each word fills left→right as it's sung, current word
                    // grows + glows on slow/held words. Apple Music style.
                    val activeIdx = line.words.indexOfLast { it.startMs <= progressMs }
                    WordWipeLine(words = line.words, activeIdx = activeIdx, progressMs = progressMs, fontSize = (24f * fontScale).sp, lineHeight = (30f * fontScale).sp)
                } else {
                    // Apple Music look: the active line is large + white; every other line is smaller
                    // and dim, so the sung line clearly stands out. (Inactive lines share one smaller
                    // size, so as the active line moves only two lines resize — the old active shrinks,
                    // the new one grows — which the scroll animation carries smoothly.)
                    val style = when {
                        isActive -> TextStyle(fontSize = (24f * fontScale).sp, fontWeight = FontWeight.Bold, lineHeight = (30f * fontScale).sp, letterSpacing = (-0.4).sp, color = Color.White)
                        isPast   -> TextStyle(color = Color(0xFFCCCCCC), fontSize = (18.5f * fontScale).sp, fontWeight = FontWeight.SemiBold, lineHeight = (24f * fontScale).sp, letterSpacing = (-0.2).sp)
                        else     -> TextStyle(color = Color(0xFF8E8E93), fontSize = (18.5f * fontScale).sp, fontWeight = FontWeight.SemiBold, lineHeight = (24f * fontScale).sp, letterSpacing = (-0.2).sp)
                    }
                    Text(text = AnnotatedString(line.text), style = style)
                }

                val bg = line.background
                if (bg != null && bg.text.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    val bgProgress = progressMs + 300L  // -300ms early start
                    val bgLive = bgProgress in bg.startMs..(bg.endMs + 600L)
                    val bgTargetScale = if (bgLive) 1.08f else 0.93f
                    val bgScale by animateFloatAsState(bgTargetScale, tween(250), label = "bgScale")

                    Box(
                        Modifier.graphicsLayer {
                            scaleX = bgScale; scaleY = bgScale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                    ) {
                        if (bg.words.isNotEmpty()) {
                            // Same soft left→right wipe as the lead line, just smaller/dimmer.
                            val bgActiveIdx = bg.words.indexOfLast { it.startMs <= bgProgress }
                            WordWipeLine(
                                words = bg.words, activeIdx = bgActiveIdx, progressMs = bgProgress,
                                live = bgLive,
                                fontSize = 19.sp, lineHeight = 24.sp, weight = FontWeight.SemiBold,
                                sungColor = Color(0xFFE0E0E0), unsungColor = Color(0xFF6E6E73),
                            )
                        } else {
                            Text(
                                text = AnnotatedString(bg.text),
                                style = TextStyle(
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (bgLive) Color(0xFFE0E0E0) else Color(0xFF6E6E73),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueuePanel(
    queue: List<com.applemusicktv.data.model.Song>,
    currentIndex: Int,
    userQueue: List<com.applemusicktv.data.model.Song> = emptyList(),
    onSelect: (Int) -> Unit,
    onSelectUserQueue: (Int) -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    leftFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    var movingIndex by remember { mutableStateOf<Int?>(null) }

    // Always scroll to top (current song) when index changes or userQueue gains items
    LaunchedEffect(currentIndex, userQueue.size) {
        listState.animateScrollToItem(0)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Up Next", fontSize = 13.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Medium)
            if (movingIndex != null) Text("Hold OK to drop", fontSize = 10.sp, color = Color(0xFF888888))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (userQueue.isNotEmpty()) {
                item {
                    Text("Next Up", fontSize = 10.sp, color = Color(0xFFFA233B), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                items(userQueue.size, key = { "uq_${userQueue[it].id}_$it" }) { idx ->
                    val song = userQueue[idx]
                    Surface(
                        onClick = { onSelectUserQueue(idx) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x15FA233B), focusedContainerColor = Color(0x25FA233B)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        modifier = Modifier.fillMaxWidth().let { m ->
                            if (leftFocus != null) m.focusProperties { left = leftFocus } else m
                        },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("◆", fontSize = 9.sp, color = Color(0xFFFA233B), modifier = Modifier.width(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title + if (song.isMusicVideo) " (MV)" else "", fontSize = 13.sp, color = Color(0xFFDDDDDD), maxLines = 1)
                                Text(song.artistName, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1)
                            }
                            Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                        }
                    }
                }
                item {
                    Text("From Queue", fontSize = 10.sp, color = Color(0xFF666666), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
            val visibleQueue = queue.drop(currentIndex + 1)
            items(visibleQueue.size, key = { rel -> "${queue.getOrNull(currentIndex + 1 + rel)?.id}_${currentIndex + 1 + rel}" }) { rel ->
                // visibleQueue[rel] == queue[currentIndex + 1 + rel]. The +1 was missing,
                // so tapping a row played the track before it.
                val idx = currentIndex + 1 + rel
                val song = visibleQueue[rel]
                val isCurrent = false
                val isMoving = idx == movingIndex
                val movingMod = if (isMoving) Modifier.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            val target = (movingIndex!! - 1).coerceAtLeast(currentIndex + 1)
                            if (target != movingIndex) { onMove(movingIndex!!, target); movingIndex = target }
                            true
                        }
                        Key.DirectionDown -> {
                            val target = (movingIndex!! + 1).coerceAtMost(queue.lastIndex)
                            if (target != movingIndex) { onMove(movingIndex!!, target); movingIndex = target }
                            true
                        }
                        Key.Enter -> { movingIndex = null; true }
                        else -> false
                    }
                } else Modifier
                Surface(
                    onClick = { if (movingIndex == idx) movingIndex = null else onSelect(idx) },
                    onLongClick = { if (rel > 0) movingIndex = idx },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor        = when { isMoving -> Color(0x44FA233B); isCurrent -> Color(0x26FFFFFF); else -> Color.Transparent },
                        focusedContainerColor = if (isMoving) Color(0x55FA233B) else Color(0x33FFFFFF),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.fillMaxWidth().then(movingMod).let { m ->
                        if (leftFocus != null) m.focusProperties { left = leftFocus } else m
                    },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (isMoving) "⠿" else "${idx + 1}",
                            fontSize = 11.sp,
                            color = if (isMoving) Color(0xFFFA233B) else if (isCurrent) Color(0xFFFA233B) else Color(0xFFAAAAAA),
                            modifier = Modifier.width(20.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(song.title + if (song.isMusicVideo) " (MV)" else "", fontSize = 13.sp, color = if (isCurrent || isMoving) Color.White else Color(0xFFDDDDDD), maxLines = 1, fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal)
                            Text(song.artistName, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1)
                        }
                        Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    }
                }
            }
        }
    }
}

/** "Off" / "5 min" / "1 h" / "2 h" for a screensaver timeout in minutes. */
internal fun screensaverLabel(min: Int): String = when {
    min <= 0    -> "Off"
    min % 60 == 0 -> "${min / 60} h"
    else        -> "$min min"
}

private enum class TransportIcon { Play, Pause, Prev, Next, Panel }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TransportButton(
    icon: TransportIcon,
    onClick: () -> Unit,
    large: Boolean = false,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val btnSize = if (large) 64.dp else 48.dp
    // SF Symbols sit inside an optical bounding box (~30% padding), so they render smaller than the
    // old edge-to-edge Canvas glyphs at the same size — bump to compensate and read as Apple-sized.
    val canvasSize = if (large) 34.dp else 25.dp
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = onClick,
        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor        = Color.Transparent,
            focusedContainerColor = Color(0x22FFFFFF),
        ),
        scale  = ClickableSurfaceDefaults.scale(focusedScale = 1.10f),
        glow   = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.3f), 10.dp)),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
        modifier = modifier.size(btnSize),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            // Spinning ring while the track decrypts. Only composed when loading, so it
            // costs nothing during normal playback.
            if (loading) {
                val spin = rememberInfiniteTransition(label = "spin")
                val angle by spin.animateFloat(
                    0f, 360f,
                    infiniteRepeatable(tween(900, easing = LinearEasing), AnimRepeatMode.Restart),
                    label = "spinAngle",
                )
                // Rotate a layer rather than re-issuing drawArc with a new startAngle
                // each frame: the arc geometry is then rasterised once and the spin is
                // a matrix transform, which the Fire TV GPU does for free.
                Canvas(
                    Modifier.size(btnSize * 0.82f).graphicsLayer { rotationZ = angle },
                ) {
                    val sw = if (large) 3f.dp.toPx() else 2.2f.dp.toPx()
                    val inset = sw / 2f
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(
                        color = Color.White.copy(alpha = 0.22f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = Color.White,
                        startAngle = 0f, sweepAngle = 90f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round),
                    )
                }
            }
            val color = if (loading) Color.White.copy(alpha = 0.45f) else Color.White
            // SF Symbol transport glyphs (same set as the rest of the app).
            val glyph = when (icon) {
                TransportIcon.Play  -> com.applemusicktv.ui.components.Glyph.PLAY
                TransportIcon.Pause -> com.applemusicktv.ui.components.Glyph.PAUSE
                TransportIcon.Prev  -> com.applemusicktv.ui.components.Glyph.PREV
                TransportIcon.Next  -> com.applemusicktv.ui.components.Glyph.NEXT
                TransportIcon.Panel -> com.applemusicktv.ui.components.Glyph.QUEUE
            }
            com.applemusicktv.ui.components.Icon(glyph, size = canvasSize, color = color)
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NpMenuItem(
    label: String,
    modifier: Modifier = Modifier,
    icon: com.applemusicktv.ui.components.Glyph? = null,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2E2E30)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                    com.applemusicktv.ui.components.Icon(icon, size = 16.dp, color = Color(0xFFC0C0C4))
                }
            }
            Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
            if (checked) com.applemusicktv.ui.components.Icon(
                com.applemusicktv.ui.components.Glyph.CHECK, size = 14.dp, color = Color(0xFFFA233B))
        }
    }
}

@Composable
private fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = Color.White,
    /** Tracking is size-specific: large text reads too loose at 0, so tighten it. */
    letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    val scrollState = rememberScrollState()
    var overflows by remember(text) { mutableStateOf(false) }
    var measured  by remember(text) { mutableStateOf(false) }
    val alphaAnim = remember(text) { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(text, overflows) {
        if (!overflows) return@LaunchedEffect
        alphaAnim.snapTo(1f)
        kotlinx.coroutines.delay(1400)
        scrollState.animateScrollTo(
            scrollState.maxValue,
            androidx.compose.animation.core.tween(6000, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0.8f, 1f)),
        )
        kotlinx.coroutines.delay(600)
        alphaAnim.animateTo(0f, androidx.compose.animation.core.tween(500))
        scrollState.scrollTo(0)
        kotlinx.coroutines.delay(200)
        alphaAnim.animateTo(1f, androidx.compose.animation.core.tween(500))
        // stay — no further scroll
    }
    Box(
        modifier = modifier.clipToBounds().then(
            // When the text is scrolling, dissolve it at BOTH edges instead of hard-cutting.
            if (overflows) Modifier
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val w = size.width
                    val edge = 22f
                    // Only fade the LEFT edge once the text has actually scrolled off it —
                    // at rest (start of the title) the first letters must be fully solid.
                    val leftFade = if (scrollState.value > 2) Color.Transparent else Color.Black
                    val rightFade = if (scrollState.value < scrollState.maxValue - 2) Color.Transparent else Color.Black
                    drawRect(
                        Brush.horizontalGradient(
                            0f to leftFade,
                            (edge / w).coerceIn(0f, 0.5f) to Color.Black,
                            (1f - edge / w).coerceIn(0.5f, 1f) to Color.Black,
                            1f to rightFade,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = fontSize, fontWeight = fontWeight, color = color,
                letterSpacing = letterSpacing,
                shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = androidx.compose.ui.geometry.Offset(0f, 2f), blurRadius = 10f)),
            maxLines = 1, softWrap = false,
            textAlign = if (overflows) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
            overflow = if (overflows) androidx.compose.ui.text.style.TextOverflow.Visible else androidx.compose.ui.text.style.TextOverflow.Clip,
            modifier = if (overflows)
                Modifier.horizontalScroll(scrollState, enabled = false).graphicsLayer { alpha = alphaAnim.value }
            else
                Modifier.fillMaxWidth(),
            onTextLayout = { result ->
                if (!measured) { measured = true; overflows = result.didOverflowWidth }
            },
        )
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
