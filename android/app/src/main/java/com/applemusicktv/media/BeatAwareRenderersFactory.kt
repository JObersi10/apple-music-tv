package com.applemusicktv.media

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class BeatAwareRenderersFactory(
    context: Context,
    val beatProcessor: BeatProcessor,
    private val gapConceal: GapConcealProcessor,
    private val gain: GainProcessor = GainProcessor(),
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessorChain(
            // Gap repair FIRST so the decoder's dropped-frame silences are concealed before anything
            // else sees them. Gain leveling BEFORE the beat detector so the detector analyses the same
            // normalized signal the listener hears — otherwise a boosted quiet track still produces weak
            // beats. Gain only scales amplitude, never timing, so the beat stays in sync.
            DefaultAudioSink.DefaultAudioProcessorChain(gapConceal, gain, beatProcessor)
        )
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
