package com.applemusicktv.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Volume leveling — a slow automatic-gain pass that nudges every track toward the same perceived
 * loudness, so quiet masters come up and loud ones come down instead of being "all over the place".
 *
 * It's an RMS AGC, not full LUFS: measure the running RMS, aim it at [TARGET_RMS], and move a single
 * broadband gain there SLOWLY (over seconds) so it levels between tracks without pumping within one.
 * The gain is updated once per input buffer (one sqrt per buffer) and held constant across that
 * buffer's samples; a soft limiter on the output stops a boost from hard-clipping peaky material.
 *
 * App-wide on/off via [enabled]; when off it's a straight pass-through with no math on the hot path.
 */
class GainProcessor : BaseAudioProcessor() {

    private var isFloat = false

    private var rms = TARGET_RMS   // smoothed running RMS estimate
    private var gain = 1f          // current applied gain, eased toward target

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)

        if (!enabled) { out.put(inputBuffer); out.flip(); return }  // pass-through

        val inp = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val g = gain
        var sumSq = 0.0
        var count = 0
        if (isFloat) {
            val fin = inp.asFloatBuffer(); val fout = out.asFloatBuffer()
            while (fin.hasRemaining()) {
                val x = fin.get()
                sumSq += x.toDouble() * x; count++
                fout.put(softClip(x * g))
            }
        } else {
            val sin = inp.asShortBuffer(); val sout = out.asShortBuffer()
            while (sin.hasRemaining()) {
                val x = sin.get() / 32768f
                sumSq += x.toDouble() * x; count++
                sout.put((softClip(x * g) * 32767f).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())   // we consumed it all
        out.flip()

        // Update loudness + ease the gain once per buffer — slow, so leveling is between tracks.
        if (count > 0) {
            val bufRms = sqrt(sumSq / count).toFloat()
            rms += RMS_RATE * (bufRms - rms)
            val target = (TARGET_RMS / (rms + 1e-4f)).coerceIn(MIN_GAIN, MAX_GAIN)
            gain += GAIN_RATE * (target - gain)
        }
    }

    /** Soft limiter: linear until [KNEE], then compresses toward ±1 so a boosted peak never hard-clips. */
    private fun softClip(v: Float): Float = when {
        v >  KNEE ->  KNEE + (1f - KNEE) * tanh((v - KNEE) / (1f - KNEE))
        v < -KNEE -> -KNEE - (1f - KNEE) * tanh((-v - KNEE) / (1f - KNEE))
        else -> v
    }

    override fun onFlush() { gain = 1f; rms = TARGET_RMS }

    companion object {
        /** App-wide toggle, set from the Volume-leveling setting. */
        @Volatile var enabled: Boolean = false

        private const val TARGET_RMS = 0.16f   // ~ -16 dBFS RMS — a comfortable, loud-but-safe target
        private const val RMS_RATE   = 0.02f   // per-buffer RMS follow (~seconds) — no pumping
        private const val GAIN_RATE  = 0.02f   // per-buffer gain easing — slow so it doesn't breathe
        private const val MIN_GAIN   = 0.5f
        private const val MAX_GAIN   = 4.0f
        private const val KNEE       = 0.85f   // soft-limiter threshold
    }
}
