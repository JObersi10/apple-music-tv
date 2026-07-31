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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
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
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.ui.viewmodel.RepeatMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val smoothProgressMs = rememberSmoothProgressMs(state.progressMs, state.isPlaying)
    val adjustedProgressMs = smoothProgressMs + state.lyricsOffsetMs

    DisposableEffect(Unit) {
        playerVm.nowPlayingVisible = true
        onDispose { playerVm.nowPlayingVisible = false }
    }

    val artistFocusHolder = remember { FocusRequester() }

    Box(modifier = modifier.fillMaxSize()) {
        DynamicBackground(artworkUrlTemplate = song?.artworkUrl, songKey = song?.id ?: "", beatAnalyzer = playerVm.beatAnalyzer, beatMultiplier = state.beatIntensity)

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
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 72.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sleepLabel != null) {
                Text(sleepLabel, style = TextStyle(fontSize = 15.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
            }
            Text(clockText, style = TextStyle(fontSize = 15.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
        }

        val playFocus = remember { FocusRequester() }

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            // Left — artwork + info + controls
            Column(
                modifier = Modifier.width(320.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A1A2E)),
                ) {
                    if (song.artworkUrl != null) {
                        AsyncImage(
                            model = song.artworkUrl(600),
                            contentDescription = song.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (state.motionUrl != null) {
                        MotionCover(url = state.motionUrl!!, modifier = Modifier.fillMaxSize())
                    }
                }

                Spacer(Modifier.height(10.dp))

                var showOptionsMenu by remember { mutableStateOf(false) }
                var showSleepSubmenu by remember { mutableStateOf(false) }

                Box(Modifier.fillMaxWidth()) {
                    MarqueeText(
                        song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 38.dp),
                    )
                    Surface(
                        onClick = { showOptionsMenu = true; showSleepSubmenu = false },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x1AFFFFFF), focusedContainerColor = Color(0x33FFFFFF)),
                        modifier = Modifier.align(Alignment.CenterEnd).size(32.dp),
                    ) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("···", fontSize = 13.sp, color = Color.White) } }
                }
                Spacer(Modifier.height(5.dp))
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
                MarqueeText(song.albumName, fontSize = 12.sp, color = Color(0xFF888888),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp))

                Spacer(Modifier.height(10.dp))

                LaunchedEffect(song.id) {
                    try { playFocus.requestFocus() } catch (_: Exception) {}
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                                NpMenuItem("← Back", Modifier.focusRequester(menuFocus)) { showSleepSubmenu = false }
                                if (state.sleepTimerEndsAt != null || state.sleepAfterSong)
                                    NpMenuItem("Cancel Timer") { playerVm.cancelSleepTimer(); showOptionsMenu = false }
                                NpMenuItem("End of Song") { playerVm.setSleepAfterSong(); showOptionsMenu = false }
                                listOf(15, 30, 45, 60).forEach { min ->
                                    NpMenuItem("$min minutes") { playerVm.setSleepTimer(min); showOptionsMenu = false }
                                }
                            } else {
                                val timerLabel = when {
                                    state.sleepAfterSong -> "Sleep: End of Song ✓"
                                    state.sleepTimerEndsAt != null -> {
                                        val m = ((state.sleepTimerEndsAt!! - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                                        "Sleep Timer (${m}m left)"
                                    }
                                    else -> "Sleep Timer"
                                }
                                NpMenuItem(timerLabel, Modifier.focusRequester(menuFocus)) { showSleepSubmenu = true }
                                val beatLabel = when (state.beatIntensity) { 1.0f -> "Beat Pulse: Normal"; 2.0f -> "Beat Pulse: Strong"; else -> "Beat Pulse: Insane" }
                                NpMenuItem(beatLabel) { playerVm.cycleBeatIntensity() }
                                NpMenuItem(if (state.crossfadeEnabled) "Crossfade: On" else "Crossfade: Off") { playerVm.toggleCrossfade() }
                                // Settings items leave the menu open so you can see the label
                                // flip and keep cycling. Only navigation and the sleep timer
                                // close it; Back dismisses.
                                NpMenuItem(if (state.isShuffled) "Shuffle: On" else "Shuffle: Off") { playerVm.toggleShuffle() }
                                val repeatLabel = when (state.repeatMode) { RepeatMode.Off -> "Repeat: Off"; RepeatMode.All -> "Repeat: All"; RepeatMode.One -> "Repeat: One" }
                                NpMenuItem(repeatLabel) { playerVm.toggleRepeat() }
                                if (song.artistId != null) NpMenuItem("Go to Artist") { onArtistClick(song.artistId); showOptionsMenu = false }
                                if (song.albumId != null) NpMenuItem("Go to Album") { onAlbumClick(song.albumId); showOptionsMenu = false }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

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
                    onClick = { if (seekBarFocused) { playerVm.player.seekTo(scrubMs); runCatching { playFocus.requestFocus() } } },
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

            // Right — lyrics or queue
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val label = when {
                    showQueue -> "Queue  •  Menu = Lyrics"
                    state.lyrics.isNotEmpty() -> "Lyrics  •  Menu = Queue"
                    else -> "Queue"
                }
                Text(
                    label,
                    fontSize = 10.sp,
                    color = Color(0x99FFFFFF),
                    modifier = Modifier.align(Alignment.End).padding(bottom = 6.dp),
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth().fillMaxHeight()) {
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
                            progressMs = adjustedProgressMs,
                            onSeek = { ms -> playerVm.player.seekTo(ms) },
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
}

/**
 * Interpolates smooth 60fps playback position between the ~200ms server
 * polling ticks, so word-by-word lyric animation doesn't stutter.
 */
@Composable
private fun rememberSmoothProgressMs(reportedMs: Long, isPlaying: Boolean): Long {
    val anchorRealMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anchorPosMs  = remember { mutableLongStateOf(reportedMs) }
    var smoothMs by remember { mutableLongStateOf(reportedMs) }

    LaunchedEffect(reportedMs) {
        anchorPosMs.longValue  = reportedMs
        anchorRealMs.longValue = System.currentTimeMillis()
        smoothMs = reportedMs
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            withFrameMillis {
                smoothMs = anchorPosMs.longValue + (System.currentTimeMillis() - anchorRealMs.longValue)
            }
        }
    }

    return smoothMs
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

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                // Keep shutter black — hides the green YUV frame on surface reattach.
                // Alpha on the outer modifier handles the fade-in instead.
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = exo
            }
        },
        update = { view -> view.player = exo },
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

// Deduplicate colors that are too close in hue; keeps order (population-sorted input → dominant colors first)
private fun spreadByHue(colors: List<Color>, n: Int, minAngle: Float = 28f): List<Color> {
    val result = mutableListOf<Color>()
    val chosenHues = mutableListOf<Float>()
    for (c in colors) {
        val h = c.hsvHue()
        if (chosenHues.all { hueDist(h, it) >= minAngle }) {
            result.add(c); chosenHues.add(h)
            if (result.size == n) break
        }
    }
    // Fill remaining slots with closest non-duplicate if we didn't get n
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
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context).data(artworkUrl).allowHardware(false).build()
            val result = loader.execute(request)
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
            val p = Palette.from(bitmap).generate()
            val picked = listOfNotNull(
                p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch,
                p.mutedSwatch, p.lightMutedSwatch, p.darkMutedSwatch, p.dominantSwatch,
            ).sortedByDescending { it.population }.map { swatch ->
                // Push toward vivid, and away from white. A pale high-value swatch is
                // what makes the backdrop compete with the white lyrics — so floor the
                // saturation and cap the value instead of just clipping near-white.
                // Deep saturated colors read as color; pastels read as light grey.
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(swatch.rgb, hsv)
                hsv[1] = (hsv[1] * SAT_BOOST).coerceIn(SAT_FLOOR, 1f)
                hsv[2] = hsv[2].coerceAtMost(VALUE_CEILING)
                Color(android.graphics.Color.HSVToColor(hsv))
            }.distinct()
            val dom = Color(p.getDominantColor(0xFF050505.toInt()))
            val domLum = 0.2126f * dom.red + 0.7152f * dom.green + 0.0722f * dom.blue

            if (domLum < 0.06f) {
                // Truly black artwork — nothing to extract
                colors = fallback
            } else if (picked.size >= 2) {
                colors = spreadByHue(picked, 6)
            } else {
                val dark  = Color(dom.red * 0.4f, dom.green * 0.4f, dom.blue * 0.4f)
                val light = Color((dom.red + 0.3f).coerceAtMost(1f), (dom.green + 0.3f).coerceAtMost(1f), (dom.blue + 0.3f).coerceAtMost(1f))
                colors = listOf(dom, light, dark, dom, light, dark)
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
private fun DynamicBackground(artworkUrlTemplate: String?, songKey: String, beatAnalyzer: com.applemusicktv.media.BeatAnalyzer, beatMultiplier: Float = 1f) {
    val rawEnergy by beatAnalyzer.energy.collectAsState()
    val scaledRaw = (rawEnergy * beatMultiplier).coerceIn(0f, 1f)
    val energy by animateFloatAsState(scaledRaw, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = androidx.compose.animation.core.Spring.StiffnessLow), label = "beat")

    // Palette derived from the full-res artwork for color accuracy
    val paletteUrl = artworkUrlTemplate?.replace("{w}", "1200")?.replace("{h}", "1200")?.replace("{f}", "jpg")
    val palette = rememberArtworkPalette(paletteUrl)
    val animated = palette.mapIndexed { i, c ->
        animateColorAsState(c, tween(1500), label = "blob$i").value
    }

    val infinite = rememberInfiniteTransition(label = "pool")
    val t1 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(20_000, easing = LinearEasing), AnimRepeatMode.Reverse), label = "t1")
    val t2 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(27_000, easing = LinearEasing), AnimRepeatMode.Reverse), label = "t2")
    val t3 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(34_000, easing = LinearEasing), AnimRepeatMode.Reverse), label = "t3")
    val t4 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(15_000, easing = LinearEasing), AnimRepeatMode.Reverse), label = "t4")

    // Each blob slowly cycles between two palette colors for a "vibing" effect
    val n = animated.size
    val colorFracs = listOf(t4, 1f - t3, t1, 1f - t2)
    val colors4 = List(4) { i ->
        lerp(animated[(i * 2) % n], animated[(i * 2 + 1) % n], colorFracs[i])
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF050505))) {
        // Color blobs
        Box(Modifier.fillMaxSize().drawBehind {
            val w = size.width; val h = size.height
            val beatScale = 1f + energy * 0.25f
            val beatAlpha = 0.66f + energy * 0.22f
            val r = maxOf(w, h) * 0.62f * beatScale
            val nudge = energy * maxOf(w, h) * 0.02f
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
            colors4.forEachIndexed { i, color ->
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = beatAlpha), color.copy(alpha = 0f)),
                        center = centers[i], radius = r,
                    ),
                    radius = r, center = centers[i],
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

private const val GAP_THRESHOLD_MS = 4000L
private const val LINE_END_GRACE_MS = 250L
private const val GAP_FADEOUT_MS    = 500L


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricsPanel(lyrics: List<LyricLine>, progressMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val passedIndex = lyrics.indexOfLast { it.startMs <= progressMs }
    val activeIndex = if (passedIndex >= 0 && progressMs <= lyrics[passedIndex].endMs + LINE_END_GRACE_MS) passedIndex else -1

    val scrollAnchor = passedIndex.coerceAtLeast(0)
    val firstLoad = remember { mutableStateOf(true) }

    // Track user-initiated scrolls so auto-scroll doesn't fight them. Auto-scroll
    // resumes the way Apple Music does: not on a timer, but when you bring the
    // current line back on screen — and then only at the next line change, so it
    // rejoins the flow instead of yanking mid-line.
    var userScrolled by remember { mutableStateOf(false) }
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
        val target = (scrollAnchor - 3).coerceAtLeast(0)
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
        }
        listState.animateScrollToItem(target)
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(end = 16.dp).nestedScroll(nestedScrollConnection)
            .onPreviewKeyEvent { ev: androidx.compose.ui.input.key.KeyEvent ->
                // Block upward D-pad escape to top nav bar from lyrics
                ev.key == Key.DirectionUp && ev.type == KeyEventType.KeyDown &&
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            },
        contentPadding = PaddingValues(top = 32.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(lyrics.size, key = { lyrics[it].startMs }) { idx ->
            val line = lyrics[idx]
            // A sustained line ("we're up all night to get lucky") runs past the start of
            // the next one. Light every line whose own window covers now, not just the
            // newest — so the held line stays lit above while the new one comes in
            // underneath, instead of being dimmed to "past" mid-phrase.
            val isActive = idx == activeIndex ||
                (progressMs in line.startMs..(line.endMs + LINE_END_GRACE_MS) && idx <= passedIndex)
            val isPast = !isActive &&
                (idx < passedIndex || (idx == passedIndex && activeIndex == -1))
            // Only pass progressMs into active line — inactive lines skip per-word work.
            val lineProgress = if (isActive) progressMs else if (isPast) line.endMs else line.startMs - 1L

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
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            LyricLineRow(
                line = line,
                isActive = isActive,
                isPast = isPast,
                progressMs = lineProgress,
                onSeek = { onSeek(line.startMs) },
            )
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    progressMs: Long,
    onSeek: () -> Unit,
) {
    val targetOpacity = when {
        isActive -> 1f
        isPast   -> 0.18f
        else     -> 0.25f
    }
    val targetScale = if (isActive) 1.08f else 0.93f
    val opacity by animateFloatAsState(targetOpacity, tween(200), label = "lineOpacity")
    val scale   by animateFloatAsState(targetScale,   tween(200), label = "lineScale")

    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = opacity
            scaleX = scale
            scaleY = scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            clip = false
        }
    ) {
        Surface(
            onClick = onSeek,
            modifier = Modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color(0x1AFFFFFF),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                val mainText: AnnotatedString? = if (isActive && line.words.isNotEmpty()) {
                    val activeIdx = line.words.indexOfLast { it.startMs <= progressMs }
                    buildAnnotatedString {
                        line.words.forEachIndexed { i, word ->
                            if (i > 0) append(" ")
                            val dur = (word.endMs - word.startMs).coerceAtLeast(1L)
                            val isCurrent = i == activeIdx
                            val frac = if (isCurrent)
                                ((progressMs - word.startMs).toFloat() / dur).coerceIn(0f, 1f)
                            else 0f
                            val color = when {
                                i < activeIdx -> Color.White
                                isCurrent -> lerp(Color(0xFF8E8E93), Color.White, frac)
                                else -> Color(0xFF8E8E93)
                            }
                            val glowFrac = if (isCurrent && dur > 700L) (1f - kotlin.math.abs(frac * 2f - 1f)) else 0f
                            val glow = if (glowFrac > 0f)
                                Shadow(color = Color.White.copy(alpha = 0.55f * glowFrac), blurRadius = 18f)
                            else null
                            withStyle(SpanStyle(color = color, shadow = glow)) {
                                append(word.text)
                            }
                        }
                    }
                } else null

                val mainStyle = when {
                    isActive -> TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp,
                        color = if (mainText == null) Color.White else Color.Unspecified)
                    isPast   -> TextStyle(color = Color(0xFFCCCCCC), fontSize = 20.sp, fontWeight = FontWeight.Normal, lineHeight = 27.sp)
                    else     -> TextStyle(color = Color(0xFF8E8E93), fontSize = 20.sp, fontWeight = FontWeight.Normal, lineHeight = 27.sp)
                }
                Text(text = mainText ?: AnnotatedString(line.text), style = mainStyle)

                val bg = line.background
                if (bg != null && bg.text.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    val bgProgress = progressMs + 300L  // -300ms early start
                    val bgLive = bgProgress in bg.startMs..(bg.endMs + 600L)
                    val bgTargetScale = if (bgLive) 1.08f else 0.93f
                    val bgScale by animateFloatAsState(bgTargetScale, tween(250), label = "bgScale")

                    val bgText: AnnotatedString? = if (bg.words.isNotEmpty()) {
                        val bgActiveIdx = bg.words.indexOfLast { it.startMs <= bgProgress }
                        buildAnnotatedString {
                            bg.words.forEachIndexed { i, word ->
                                if (i > 0) append(" ")
                                val dur = (word.endMs - word.startMs).coerceAtLeast(1L)
                                val isCurrent = i == bgActiveIdx
                                val frac = if (isCurrent && bgLive)
                                    ((bgProgress - word.startMs).toFloat() / dur).coerceIn(0f, 1f) else 0f
                                val col = when {
                                    !bgLive && i <= bgActiveIdx -> Color(0xFF6E6E73)
                                    i < bgActiveIdx -> Color(0xFFE0E0E0)
                                    isCurrent && bgLive -> lerp(Color(0xFF6E6E73), Color(0xFFE0E0E0), frac)
                                    else -> Color(0xFF6E6E73)
                                }
                                val bgGlowFrac = if (isCurrent && bgLive && dur > 700L) (1f - kotlin.math.abs(frac * 2f - 1f)) else 0f
                                val glow = if (bgGlowFrac > 0f)
                                    Shadow(color = Color.White.copy(alpha = 0.4f * bgGlowFrac), blurRadius = 14f) else null
                                withStyle(SpanStyle(color = col, shadow = glow)) { append(word.text) }
                            }
                        }
                    } else null

                    Text(
                        text = bgText ?: AnnotatedString(bg.text),
                        style = TextStyle(
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (bgText == null) (if (bgLive) Color(0xFFE0E0E0) else Color(0xFF6E6E73)) else Color.Unspecified,
                        ),
                        modifier = Modifier.graphicsLayer {
                            scaleX = bgScale; scaleY = bgScale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        },
                    )
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
                                Text(song.title, fontSize = 13.sp, color = Color(0xFFDDDDDD), maxLines = 1)
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
            items(visibleQueue.size, key = { rel -> "${queue.getOrNull(currentIndex + rel)?.id}_${currentIndex + rel}" }) { rel ->
                val idx = currentIndex + rel
                val song = visibleQueue[rel]
                val isCurrent = rel == 0
                val isMoving = idx == movingIndex
                val movingMod = if (isMoving) Modifier.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            val target = (movingIndex!! - 1).coerceAtLeast(currentIndex)
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
                            Text(song.title, fontSize = 13.sp, color = if (isCurrent || isMoving) Color.White else Color(0xFFDDDDDD), maxLines = 1, fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal)
                            Text(song.artistName, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1)
                        }
                        Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    }
                }
            }
        }
    }
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
    val btnSize = if (large) 60.dp else 44.dp
    val canvasSize = if (large) 28.dp else 20.dp
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
            val strokeW = if (large) 3.2f else 2.6f
            Canvas(Modifier.size(canvasSize)) {
                // Use DrawScope.size (canvas px dimensions), not the Dp variables above
                val w = this.size.width
                val h = this.size.height
                val sw = strokeW
                when (icon) {
                    TransportIcon.Play -> {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(w * 0.12f, 0f)
                            lineTo(w, h * 0.5f)
                            lineTo(w * 0.12f, h)
                            close()
                        }
                        drawPath(path, color = color)
                    }
                    TransportIcon.Pause -> {
                        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.10f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.28f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw, sw))
                        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.62f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.28f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw, sw))
                    }
                    TransportIcon.Prev -> {
                        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(sw * 1.5f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw, sw))
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(w, 0f)
                            lineTo(sw * 2.5f, h * 0.5f)
                            lineTo(w, h)
                            close()
                        }
                        drawPath(path, color = color)
                    }
                    TransportIcon.Panel -> {
                        // Three stacked bars — "show the list"
                        val barH = sw * 1.2f
                        listOf(0f, (h - barH) * 0.5f, h - barH).forEach { top ->
                            drawRoundRect(
                                color,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                                size = androidx.compose.ui.geometry.Size(w, barH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2f, barH / 2f),
                            )
                        }
                    }
                    TransportIcon.Next -> {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, 0f)
                            lineTo(w - sw * 2.5f, h * 0.5f)
                            lineTo(0f, h)
                            close()
                        }
                        drawPath(path, color = color)
                        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w - sw * 1.5f, 0f), size = androidx.compose.ui.geometry.Size(sw * 1.5f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw, sw))
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NpMenuItem(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2E2E30)),
    ) {
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

@Composable
private fun MarqueeText(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 16.sp, fontWeight: FontWeight = FontWeight.Normal, color: Color = Color.White) {
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
    Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = TextStyle(fontSize = fontSize, fontWeight = fontWeight, color = color,
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
