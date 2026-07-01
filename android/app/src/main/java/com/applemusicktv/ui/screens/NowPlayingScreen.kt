package com.applemusicktv.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.palette.graphics.Palette
import androidx.tv.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.network.LyricWord
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerVm: PlayerViewModel,
    navVm: NavigationViewModel,
    modifier: Modifier = Modifier,
) {
    val state by playerVm.state.collectAsState()
    val song = state.currentSong

    val toggleCount by navVm.toggleQueue.collectAsState()
    val showQueue = toggleCount % 2 == 1

    val smoothProgressMs = rememberSmoothProgressMs(state.progressMs, state.isPlaying)

    Box(modifier = modifier.fillMaxSize()) {
        DynamicBackground(artworkUrl = song?.artworkUrl(1200), songKey = song?.id ?: "")

        if (song == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Nothing playing", color = Color(0xFF666666), fontSize = 18.sp)
            }
            return@Box
        }

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

                Spacer(Modifier.height(24.dp))

                Text(song.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(song.artistName, fontSize = 14.sp, color = Color(0xFFAAAAAA))
                Text(song.albumName, fontSize = 12.sp, color = Color(0xFF888888))

                Spacer(Modifier.height(20.dp))

                // Transport controls. The play/pause button grabs initial
                // focus so the D-pad can actually reach the on-screen controls
                // (without this, nothing on Now Playing is focusable and the
                // buttons + lyric taps appear dead — only hardware media keys,
                // which bypass focus, work).
                val playFocus = remember { FocusRequester() }
                LaunchedEffect(song.id) {
                    try { playFocus.requestFocus() } catch (_: Exception) {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TransportButton("⏮", onClick = playerVm::prev)
                    TransportButton(
                        if (state.isPlaying) "⏸" else "▶",
                        onClick = playerVm::togglePlayPause,
                        large = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    TransportButton("⏭", onClick = playerVm::next)
                }

                Spacer(Modifier.height(16.dp))

                val duration = song.durationMs.takeIf { it > 0 } ?: 1L
                val progress = (smoothProgressMs.toFloat() / duration).coerceIn(0f, 1f)
                Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFFFA233B)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.SpaceBetween) {
                    Text(formatMs(state.progressMs), fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                }
            }

            // Right — lyrics or queue
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (showQueue) {
                    QueuePanel(
                        queue = state.queue,
                        currentIndex = state.queueIndex,
                        onSelect = { idx ->
                            playerVm.player.seekToDefaultPosition(idx)
                            playerVm.player.play()
                        },
                    )
                } else if (state.lyrics.isNotEmpty()) {
                    LyricsPanel(
                        lyrics = state.lyrics,
                        progressMs = smoothProgressMs,
                        onSeek = { ms -> playerVm.player.seekTo(ms) },
                    )
                } else {
                    QueuePanel(
                        queue = state.queue,
                        currentIndex = state.queueIndex,
                        onSelect = { idx ->
                            playerVm.player.seekToDefaultPosition(idx)
                            playerVm.player.play()
                        },
                    )
                }

                Box(Modifier.align(Alignment.TopEnd)) {
                    val label = when {
                        showQueue -> "Queue  •  Menu = Lyrics"
                        state.lyrics.isNotEmpty() -> "Lyrics  •  Menu = Queue"
                        else -> "Queue"
                    }
                    Text(label, fontSize = 10.sp, color = Color(0x99FFFFFF))
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
private fun MotionCover(url: String, modifier: Modifier = Modifier) {
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
    val fallback = listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460), Color(0xFF533483))
    var colors by remember(artworkUrl) { mutableStateOf(fallback) }
    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null) return@LaunchedEffect
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(artworkUrl)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
            val p = Palette.from(bitmap).generate()
            // Pull several distinct swatches for a rich "pool of colors".
            val picked = listOfNotNull(
                p.vibrantSwatch, p.lightVibrantSwatch, p.mutedSwatch,
                p.darkVibrantSwatch, p.lightMutedSwatch, p.dominantSwatch,
            ).map { Color(it.rgb) }.distinct()
            if (picked.size >= 2) colors = picked.take(5)
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
private fun DynamicBackground(artworkUrl: String?, songKey: String) {
    val palette = rememberArtworkPalette(artworkUrl)
    // Smoothly cross-fade each blob's color when the song (palette) changes.
    val animated = palette.mapIndexed { i, c ->
        animateColorAsState(c, tween(1200), label = "blob$i").value
    }

    val infinite = rememberInfiniteTransition(label = "pool")
    // A few independent drift phases so blobs move at different rates.
    val t1 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(17_000, easing = LinearEasing), RepeatMode.Reverse), label = "t1")
    val t2 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(23_000, easing = LinearEasing), RepeatMode.Reverse), label = "t2")
    val t3 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(29_000, easing = LinearEasing), RepeatMode.Reverse), label = "t3")
    val pulse by infinite.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")

    Box(Modifier.fillMaxSize().background(Color(0xFF050505))) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                val w = size.width
                val h = size.height
                // Blob anchor points drift across the canvas.
                val centers = listOf(
                    androidx.compose.ui.geometry.Offset(lerp(0.15f, 0.35f, t1) * w, lerp(0.20f, 0.40f, t2) * h),
                    androidx.compose.ui.geometry.Offset(lerp(0.85f, 0.65f, t2) * w, lerp(0.25f, 0.15f, t3) * h),
                    androidx.compose.ui.geometry.Offset(lerp(0.20f, 0.40f, t3) * w, lerp(0.80f, 0.65f, t1) * h),
                    androidx.compose.ui.geometry.Offset(lerp(0.80f, 0.60f, t1) * w, lerp(0.75f, 0.90f, t2) * h),
                    androidx.compose.ui.geometry.Offset(0.5f * w, lerp(0.45f, 0.55f, t3) * h),
                )
                val baseRadius = maxOf(w, h) * 0.55f
                animated.forEachIndexed { i, color ->
                    val center = centers[i % centers.size]
                    val r = baseRadius * (if (i % 2 == 0) pulse else (2f - pulse))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.0f)),
                            center = center,
                            radius = r,
                        ),
                        radius = r,
                        center = center,
                    )
                }
            },
        )
        // Darkening vignette so foreground text/art stays readable.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.65f)),
                ),
            ),
        )
    }
}

private const val GAP_THRESHOLD_MS = 3000L
private const val LINE_END_GRACE_MS = 250L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricsPanel(lyrics: List<LyricLine>, progressMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Last line whose start has passed.
    val passedIndex = lyrics.indexOfLast { it.startMs <= progressMs }
    // Only actually "focused" while inside that line's own time window —
    // during instrumental gaps nothing is focused.
    val activeIndex = if (passedIndex >= 0 && progressMs <= lyrics[passedIndex].endMs + LINE_END_GRACE_MS) {
        passedIndex
    } else {
        -1
    }

    val scrollAnchor = passedIndex.coerceAtLeast(0)
    LaunchedEffect(scrollAnchor) {
        scope.launch {
            listState.animateScrollToItem((scrollAnchor - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Push the active line into the upper-middle of the panel (Apple-style)
        // instead of pinning it to the very top.
        contentPadding = PaddingValues(top = 120.dp, bottom = 220.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(lyrics.size) { idx ->
            val line = lyrics[idx]
            val isActive = idx == activeIndex
            val isPast = idx < passedIndex || (idx == passedIndex && activeIndex == -1)

            // Instrumental gap indicator before this line, if long enough
            // and we're currently sitting inside it.
            val prevEnd = if (idx > 0) lyrics[idx - 1].endMs else 0L
            val gapMs = line.startMs - prevEnd
            val inGap = progressMs in prevEnd until line.startMs
            if (gapMs >= GAP_THRESHOLD_MS && inGap && idx == passedIndex + 1) {
                MusicalDots(
                    fraction = ((progressMs - prevEnd).toFloat() / gapMs).coerceIn(0f, 1f),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            LyricLineRow(
                line = line,
                isActive = isActive,
                isPast = isPast,
                progressMs = progressMs,
                onSeek = { onSeek(line.startMs) },
            )
        }
    }
}

@Composable
private fun MusicalDots(fraction: Float, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val pulse by infinite.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(900, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            // Dots "fill in" one by one as the gap elapses, and pulse gently.
            val revealed = fraction > (i * 0.25f)
            val alpha = (if (revealed) 0.35f + 0.65f * pulse else 0.2f)
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        val s = if (revealed) 0.85f + 0.25f * pulse else 0.7f
                        scaleX = s
                        scaleY = s
                        this.alpha = alpha
                    }
                    .clip(RoundedCornerShape(50))
                    .background(Color.White),
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
        isPast -> 0.32f
        else -> 0.42f
    }
    val targetScale = if (isActive) 1f else 0.94f

    val opacity by animateFloatAsState(targetOpacity, tween(260), label = "lineOpacity")
    val scale by animateFloatAsState(targetScale, tween(260), label = "lineScale")

    Surface(
        onClick = onSeek,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0x1AFFFFFF),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Column(
            Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .graphicsLayer {
                    alpha = opacity
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                },
        ) {
            if (line.words.isNotEmpty()) {
                KaraokeLine(
                    words = line.words,
                    progressMs = progressMs,
                    fontSize = 27.sp,
                    baseColor = Color(0xFF8E8E93),
                    litColor = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(
                    text = line.text,
                    fontSize = if (isActive) 27.sp else 19.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) Color.White else Color(0xFFCCCCCC),
                    lineHeight = if (isActive) 34.sp else 26.sp,
                )
            }

            val bg = line.background
            if (bg != null && bg.words.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                KaraokeLine(
                    words = bg.words,
                    progressMs = progressMs,
                    fontSize = 20.sp,
                    baseColor = Color(0xFF6E6E73),
                    litColor = Color(0xFFE0E0E0),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** A single row of words, each independently wiped left→right as it's sung. */
@Composable
private fun KaraokeLine(
    words: List<LyricWord>,
    progressMs: Long,
    fontSize: androidx.compose.ui.unit.TextUnit,
    baseColor: Color,
    litColor: Color,
    fontWeight: FontWeight,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (word in words) {
            KaraokeWord(word, progressMs, fontSize, baseColor, litColor, fontWeight)
        }
    }
}

@Composable
private fun KaraokeWord(
    word: LyricWord,
    progressMs: Long,
    fontSize: androidx.compose.ui.unit.TextUnit,
    baseColor: Color,
    litColor: Color,
    fontWeight: FontWeight,
) {
    val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
    val fraction = ((progressMs - word.startMs).toFloat() / duration).coerceIn(0f, 1f)

    // Scale: dips slightly below rest, overshoots past 1x mid-word, settles.
    val scale = when {
        fraction <= 0f -> 0.96f
        fraction >= 1f -> 1f
        fraction < 0.7f -> lerp(0.96f, 1.06f, easeOutCubic(fraction / 0.7f))
        else -> lerp(1.06f, 1f, (fraction - 0.7f) / 0.3f)
    }
    // Slight upward bounce while singing, settling back to baseline.
    val yOffsetFraction = when {
        fraction <= 0f || fraction >= 1f -> 0f
        fraction < 0.9f -> lerp(0.01f, -0.14f, fraction / 0.9f)
        else -> lerp(-0.14f, 0f, (fraction - 0.9f) / 0.1f)
    }
    // Glow ramps up fast, holds, fades near the end of the word.
    val glow = when {
        fraction <= 0f || fraction >= 1f -> 0f
        fraction < 0.15f -> fraction / 0.15f
        fraction < 0.6f -> 1f
        else -> 1f - (fraction - 0.6f) / 0.4f
    }

    val brush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to litColor,
            (fraction).coerceIn(0f, 1f) to litColor,
            (fraction + 0.001f).coerceIn(0f, 1f) to baseColor,
            1f to baseColor,
        ),
    )

    val shadow = Shadow(
        color = Color.White.copy(alpha = 0.55f * glow),
        blurRadius = 18f * glow,
    )
    val textStyle = if (fraction <= 0f) {
        TextStyle(color = baseColor, fontSize = fontSize, fontWeight = fontWeight, shadow = shadow)
    } else {
        TextStyle(brush = brush, fontSize = fontSize, fontWeight = fontWeight, shadow = shadow)
    }

    Text(
        text = word.text,
        style = textStyle,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = yOffsetFraction * fontSize.value * density
        },
    )
}

private fun easeOutCubic(t: Float): Float {
    val f = t - 1f
    return f * f * f + 1f
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueuePanel(queue: List<com.applemusicktv.data.model.Song>, currentIndex: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Up Next", fontSize = 13.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(queue.size) { idx ->
                val song = queue[idx]
                val isCurrent = idx == currentIndex
                Surface(
                    onClick = { onSelect(idx) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor        = if (isCurrent) Color(0x26FFFFFF) else Color.Transparent,
                        focusedContainerColor = Color(0x33FFFFFF),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${idx + 1}", fontSize = 11.sp, color = if (isCurrent) Color(0xFFFA233B) else Color(0xFFAAAAAA), modifier = Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, fontSize = 13.sp, color = if (isCurrent) Color.White else Color(0xFFDDDDDD), maxLines = 1, fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal)
                            Text(song.artistName, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1)
                        }
                        Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TransportButton(icon: String, onClick: () -> Unit, large: Boolean = false, modifier: Modifier = Modifier) {
    val size = if (large) 52.dp else 40.dp
    Surface(
        onClick = onClick,
        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor        = if (large) Color(0xFFFA233B) else Color(0x26FFFFFF),
            focusedContainerColor = if (large) Color(0xFFE01F33) else Color(0x40FFFFFF),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        glow  = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color(0xFFFA233B).copy(alpha = 0.5f), 12.dp)),
        modifier = modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(icon, fontSize = if (large) 18.sp else 14.sp, color = Color.White)
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
