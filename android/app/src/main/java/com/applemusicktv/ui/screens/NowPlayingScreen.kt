package com.applemusicktv.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

    Box(modifier = modifier.fillMaxSize()) {
        val rawEnergy by playerVm.beatAnalyzer.energy.collectAsState()
        val beatEnergy by animateFloatAsState(rawEnergy, tween(80), label = "beat")
        DynamicBackground(artworkUrl = song?.artworkUrl(1200), songKey = song?.id ?: "", energy = beatEnergy)

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
                kotlinx.coroutines.delay(10_000)
            }
        }
        Text(
            clockText,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 72.dp, top = 14.dp),
            fontSize = 15.sp,
            color = Color(0xCCFFFFFF),
        )

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
                        .size(280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A1A2E)),
                ) {
                    if (song.artworkUrl != null) {
                        AsyncImage(
                            model = song.artworkUrl(560),
                            contentDescription = song.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Animated (motion) album art loops on top of the static
                    // cover when Apple provides it; fades in over the artwork.
                    if (state.motionUrl != null) {
                        MotionCover(url = state.motionUrl!!, modifier = Modifier.fillMaxSize())
                    }
                }

                Spacer(Modifier.height(14.dp))

                var showOptionsMenu by remember { mutableStateOf(false) }
                var showSleepSubmenu by remember { mutableStateOf(false) }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(song.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, modifier = Modifier.weight(1f))
                    Surface(
                        onClick = { showOptionsMenu = true; showSleepSubmenu = false },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x1AFFFFFF), focusedContainerColor = Color(0x33FFFFFF)),
                        modifier = Modifier.size(32.dp),
                    ) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("···", fontSize = 13.sp, color = Color.White) } }
                }
                Spacer(Modifier.height(4.dp))
                if (song.artistId != null) {
                    Surface(
                        onClick = { onArtistClick(song.artistId) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0x1AFFFFFF)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                    ) {
                        Text(song.artistName, fontSize = 14.sp, color = Color(0xFFAAAAAA), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                } else {
                    Text(song.artistName, fontSize = 14.sp, color = Color(0xFFAAAAAA))
                }
                Text(song.albumName, fontSize = 12.sp, color = Color(0xFF888888))

                Spacer(Modifier.height(12.dp))

                val playFocus = remember { FocusRequester() }
                LaunchedEffect(song.id) {
                    try { playFocus.requestFocus() } catch (_: Exception) {}
                }
                // Shuffle / prev / play / next / repeat
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TransportButton(
                        "⇄", onClick = playerVm::toggleShuffle,
                        tint = if (state.isShuffled) Color(0xFFFA233B) else Color(0x66FFFFFF),
                    )
                    TransportButton("⏮", onClick = playerVm::prev)
                    TransportButton(
                        if (state.isPlaying) "⏸" else "▶",
                        onClick = playerVm::togglePlayPause,
                        large = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    TransportButton("⏭", onClick = playerVm::next)
                    TransportButton(
                        when (state.repeatMode) { RepeatMode.One -> "↻¹"; else -> "↻" },
                        onClick = playerVm::toggleRepeat,
                        tint = if (state.repeatMode != RepeatMode.Off) Color(0xFFFA233B) else Color(0x66FFFFFF),
                    )
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
                                val remaining = state.sleepTimerEndsAt?.let { ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) }
                                if (remaining != null) NpMenuItem("Cancel Timer (${remaining}m left)") { playerVm.cancelSleepTimer(); showOptionsMenu = false }
                                listOf(15, 30, 45, 60).forEach { min ->
                                    NpMenuItem("$min minutes") { playerVm.setSleepTimer(min); showOptionsMenu = false }
                                }
                            } else {
                                val timerLabel = state.sleepTimerEndsAt?.let {
                                    val m = ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                                    "Sleep Timer (${m}m)"
                                } ?: "Sleep Timer"
                                NpMenuItem(timerLabel, Modifier.focusRequester(menuFocus)) { showSleepSubmenu = true }
                                if (song.artistId != null) NpMenuItem("Go to Artist") { onArtistClick(song.artistId); showOptionsMenu = false }
                                if (song.albumId != null) NpMenuItem("Go to Album") { onAlbumClick(song.albumId); showOptionsMenu = false }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                val duration = song.durationMs.takeIf { it > 0 } ?: 1L
                val progress = (smoothProgressMs.toFloat() / duration).coerceIn(0f, 1f)
                Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFFFA233B)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.SpaceBetween) {
                    Text(formatMs(smoothProgressMs), fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFFAAAAAA))
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
                            onSelect = { idx -> playerVm.playFromQueue(idx) },
                            onMove = { from, to -> playerVm.moveQueueItem(from, to) },
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
                            onSelect = { idx -> playerVm.playFromQueue(idx) },
                            onMove = { from, to -> playerVm.moveQueueItem(from, to) },
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

    DisposableEffect(url) {
        onDispose { exo.release() }
    }

    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(600), label = "motionFade")

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                player = exo
            }
        },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/** Extracts a dark base color + a vibrant accent color from the artwork. */
@Composable
private fun rememberArtworkPalette(artworkUrl: String?): List<Color> {
    val context = LocalContext.current
    val fallback = listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460), Color(0xFF533483), Color(0xFF0D0D0D), Color(0xFF220033))
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
                p.vibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch,
                p.lightVibrantSwatch, p.darkMutedSwatch, p.lightMutedSwatch, p.dominantSwatch,
            ).map { swatch ->
                // Darken bright colors: scale down luminance so nothing blows out
                val c = Color(swatch.rgb)
                val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
                val scale = if (lum > 0.55f) 0.55f / lum else 1f
                Color(c.red * scale, c.green * scale, c.blue * scale)
            }.distinct()
            if (picked.size >= 2) colors = picked.take(6)
        } catch (_: Exception) {}
    }
    return colors
}

/**
 * Living "pool of colors" backdrop — several soft radial blobs of the cover's
 * palette that slowly drift and pulse, blended over black. No artwork image, so
 * it's never pixelated; pure gradient light like Apple Music's ambient mode.
 */
@Composable
private fun DynamicBackground(artworkUrl: String?, songKey: String, energy: Float = 0f) {
    val palette = rememberArtworkPalette(artworkUrl)
    val animated = palette.mapIndexed { i, c ->
        animateColorAsState(c, tween(1500), label = "blob$i").value
    }

    val infinite = rememberInfiniteTransition(label = "pool")
    val t1 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse), label = "t1")
    val t2 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(27_000, easing = LinearEasing), RepeatMode.Reverse), label = "t2")
    val t3 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(34_000, easing = LinearEasing), RepeatMode.Reverse), label = "t3")

    // 4 blobs — Screen blend forces an offscreen composite per draw; 4 is the perf ceiling on Fire TV
    val colors4 = List(4) { animated[it % animated.size] }

    Box(Modifier.fillMaxSize().background(Color(0xFF050505))) {
        Box(Modifier.fillMaxSize().drawBehind {
            val w = size.width; val h = size.height
            val beatScale = 1f + energy * 0.18f
            val beatAlpha = 0.52f + energy * 0.12f
            val r = maxOf(w, h) * 0.62f * beatScale
            val nudge = energy * maxOf(w, h) * 0.04f
            val nudgeOffsets = listOf(
                Offset( nudge,  nudge * 0.5f),
                Offset(-nudge, -nudge * 0.7f),
                Offset( nudge * 0.6f, -nudge),
                Offset(-nudge * 0.4f,  nudge * 0.8f),
            )
            val centers = listOf(
                Offset(lerp(0.05f, 0.40f, t1) * w, lerp(0.10f, 0.45f, t2) * h),
                Offset(lerp(0.95f, 0.60f, t2) * w, lerp(0.05f, 0.50f, t3) * h),
                Offset(lerp(0.15f, 0.50f, t3) * w, lerp(0.90f, 0.55f, t1) * h),
                Offset(lerp(0.80f, 0.45f, t1) * w, lerp(0.80f, 0.40f, t3) * h),
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
            // Suppress hotspot where blobs converge centrally
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x44000000), Color(0x00000000)),
                    center = Offset(w * 0.5f, h * 0.5f), radius = maxOf(w, h) * 0.35f,
                ),
                radius = maxOf(w, h) * 0.35f, center = Offset(w * 0.5f, h * 0.5f),
            )
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

    // Track user-initiated scrolls so auto-scroll doesn't fight them.
    // Re-enable auto-scroll 5s after the user stops touching the list.
    var userScrolled by remember { mutableStateOf(false) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) userScrolled = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && userScrolled) {
            kotlinx.coroutines.delay(5_000)
            userScrolled = false
        }
    }

    LaunchedEffect(scrollAnchor, lyrics.size) {
        val target = (scrollAnchor - 3).coerceAtLeast(0)
        if (lyrics.isEmpty()) return@LaunchedEffect
        if (firstLoad.value) {
            listState.scrollToItem(target)
            firstLoad.value = false
        } else if (!userScrolled) {
            scope.launch { listState.animateScrollToItem(target) }
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(end = 16.dp).nestedScroll(nestedScrollConnection),
        contentPadding = PaddingValues(top = 32.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(lyrics.size, key = { lyrics[it].startMs }) { idx ->
            val line = lyrics[idx]
            val isActive = idx == activeIndex
            val isPast = idx < passedIndex || (idx == passedIndex && activeIndex == -1)
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
    onSelect: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    var movingIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(queue, currentIndex) {
        if (queue.isNotEmpty()) listState.scrollToItem(currentIndex.coerceIn(0, queue.lastIndex))
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Up Next", fontSize = 13.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Medium)
            if (movingIndex != null) Text("Hold OK to drop", fontSize = 10.sp, color = Color(0xFF888888))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(queue.size, key = { idx -> "${queue[idx].id}_$idx" }) { idx ->
                val song = queue[idx]
                val isCurrent = idx == currentIndex
                val isMoving = idx == movingIndex
                var visible by remember(idx, currentIndex) { mutableStateOf(idx >= currentIndex) }
                LaunchedEffect(currentIndex) {
                    if (idx < currentIndex) visible = false
                }
                AnimatedVisibility(
                    visible = visible,
                    exit = slideOutVertically(tween(350)) { -it } + fadeOut(tween(300)),
                ) {
                val movingMod = if (isMoving) Modifier.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            val target = (movingIndex!! - 1).coerceAtLeast(0)
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
                    onLongClick = { if (idx > currentIndex) movingIndex = idx },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor        = when { isMoving -> Color(0x44FA233B); isCurrent -> Color(0x26FFFFFF); else -> Color.Transparent },
                        focusedContainerColor = if (isMoving) Color(0x55FA233B) else Color(0x33FFFFFF),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.fillMaxWidth().then(movingMod),
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
                } // AnimatedVisibility
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TransportButton(icon: String, onClick: () -> Unit, large: Boolean = false, tint: Color = Color.White, modifier: Modifier = Modifier) {
    val size = if (large) 52.dp else 40.dp
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = onClick,
        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor        = if (large) Color(0xFFFA233B) else Color(0x26FFFFFF),
            focusedContainerColor = if (large) Color(0xFFE01F33) else Color(0x40FFFFFF),
        ),
        scale  = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        glow   = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color(0xFFFA233B).copy(alpha = 0.5f), 12.dp)),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
        modifier = modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(icon, fontSize = if (large) 18.sp else 14.sp, color = tint)
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

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
