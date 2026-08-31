package com.applemusicktv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * A looping, muted motion-artwork video drawn over a card's static cover.
 *
 * Used ONLY by the "Playlists Made for You" shelf (Get Up!, Chill, Your Essentials…), which is the
 * one row Apple animates on the web Home page. It is deliberately not applied everywhere: each
 * animated card costs a video decoder, and Fire TV has very little headroom (see the perf notes in
 * CLAUDE.md — this is the same box where 6 gradient blobs caused lag).
 *
 * For the same reason the player only exists while [play] is true — the row animates the focused
 * card rather than running five decoders at once. Everything is torn down on dispose.
 *
 * A TextureView (not PlayerView/SurfaceView) is used for the same reason as the Now Playing motion
 * cover: a SurfaceView is a hole punched through the window, ignores Compose transforms, and would
 * not stay inside a rounded, scaled card.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MotionArtwork(url: String, play: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ready by remember(url) { mutableStateOf(false) }

    // Wait ~1s after focus settles before spinning up the video decoder. Arrowing THROUGH a row
    // (Playlists Made for You) focuses each card for a moment; without this every card you pass
    // built and released an ExoPlayer, and that churn was the scroll jank. Scroll past fast → the
    // decoder never starts.
    var playDebounced by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, play) {
        if (play) { kotlinx.coroutines.delay(1000); playDebounced = true }
        else playDebounced = false
    }

    val exo = remember(url, playDebounced) {
        if (!playDebounced) null else {
        // Cap the adaptive ladder — motion-art masters offer up to ~6 Mbps 1080p HEVC, far more than a
        // small card needs, and it choked the decoder. A low rung looks identical at card size.
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            parameters = buildUponParameters().setMaxVideoSize(480, 480).setMaxVideoBitrate(1_200_000).build()
        }
        ExoPlayer.Builder(context).setTrackSelector(selector).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
        }
    }
    DisposableEffect(exo) { onDispose { exo?.release() } }

    // Only cross-fade in once the decoder has actually produced a frame, so the static cover is
    // never replaced by a black box mid-load.
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
        }
        exo?.addListener(l)
        onDispose { exo?.removeListener(l) }
    }

    val alpha by animateFloatAsState(if (ready && playDebounced) 1f else 0f, tween(320), label = "motionArt")
    if (exo == null) return

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
