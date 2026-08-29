package com.applemusicktv.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * A/V-sync for the music-video player. On Bluetooth the audio reaches your ears ~350 ms after the
 * TV shows the frame, so lips run ahead of the sound. We reuse the SAME offset the audio player
 * applies to its beat/lyric clock and delay the VIDEO by it — implemented by reporting an audio
 * position that lags by `offsetUs`, so ExoPlayer holds each frame until the (delayed) audio catches
 * up. Audio itself is untouched; only frame-release timing shifts. `offsetUsProvider` is read live,
 * so plugging/unplugging Bluetooth or nudging the offset takes effect without rebuilding the player.
 */
@UnstableApi
class DelayVideoRenderersFactory(
    context: Context,
    private val offsetUsProvider: () -> Long,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            ?: return null
        return object : ForwardingAudioSink(base) {
            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                val p = super.getCurrentPositionUs(sourceEnded)
                if (p == AudioSink.CURRENT_POSITION_NOT_SET) return p
                return (p - offsetUsProvider()).coerceAtLeast(0)
            }
        }
    }
}
