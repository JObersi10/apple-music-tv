package com.applemusicktv.media

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class BeatAwareRenderersFactory(
    context: Context,
    val beatProcessor: BeatProcessor,
    private val gapConceal: GapConcealProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessorChain(
            // Gap repair FIRST so the decoder's dropped-frame silences are concealed
            // before the beat detector (and the speakers) ever see them.
            DefaultAudioSink.DefaultAudioProcessorChain(gapConceal, beatProcessor)
        )
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
