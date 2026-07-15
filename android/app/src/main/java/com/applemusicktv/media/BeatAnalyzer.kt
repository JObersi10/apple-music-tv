package com.applemusicktv.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pass-through AudioProcessor that computes short-time RMS energy from PCM frames.
 * Exposes [energy] (0..1) for the UI to drive beat-reactive visuals.
 * Only active for PCM_16BIT — if the decoder outputs float, ExoPlayer bypasses it.
 */
class BeatAnalyzer : BaseAudioProcessor() {

    private val _energy = MutableStateFlow(0f)
    val energy: StateFlow<Float> = _energy

    private var isFloat = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> { isFloat = false; inputAudioFormat }
            C.ENCODING_PCM_FLOAT -> { isFloat = true;  inputAudioFormat }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount == 0) return

        val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (isFloat) {
            val view = dup.asFloatBuffer()
            var sumSq = 0f; var n = 0
            while (view.hasRemaining()) { val s = view.get(); sumSq += s * s; n++ }
            if (n > 0) _energy.value = sqrt(sumSq / n).coerceIn(0f, 1f)
        } else {
            val view = dup.asShortBuffer()
            var sumSq = 0L; var n = 0
            while (view.hasRemaining()) { val s = view.get().toLong(); sumSq += s * s; n++ }
            if (n > 0) _energy.value = (sqrt(sumSq.toFloat() / n) / 32768f).coerceIn(0f, 1f)
        }

        val out = replaceOutputBuffer(byteCount)
        out.put(inputBuffer)
        out.flip()
    }

    fun reset() { _energy.value = 0f }

    override fun onReset() { reset() }
}
