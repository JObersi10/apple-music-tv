package com.applemusicktv.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Pass-through AudioProcessor that computes short-time RMS energy from PCM frames.
 * Energy emission is delayed by [latencyMs] to compensate for audio output latency
 * (e.g. Bluetooth A2DP adds ~150-300ms between PCM write and audible output).
 */
@Singleton
class BeatAnalyzer @Inject constructor() : BaseAudioProcessor() {

    private val _energy = MutableStateFlow(0f)
    val energy: StateFlow<Float> = _energy

    /** Set to match current audio output latency (0 for speakers, ~200 for BT). */
    @Volatile var latencyMs: Long = 0L

    private var isFloat = false
    private var runningAvg = 0f

    // Ring buffer of (emitAtMs, energy) pairs
    private val pending = ArrayDeque<Pair<Long, Float>>()

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
        val e = if (isFloat) {
            val view = dup.asFloatBuffer()
            var sumSq = 0f; var n = 0
            while (view.hasRemaining()) { val s = view.get(); sumSq += s * s; n++ }
            if (n > 0) sqrt(sumSq / n).coerceIn(0f, 1f) else null
        } else {
            val view = dup.asShortBuffer()
            var sumSq = 0L; var n = 0
            while (view.hasRemaining()) { val s = view.get().toLong(); sumSq += s * s; n++ }
            if (n > 0) (sqrt(sumSq.toFloat() / n) / 32768f).coerceIn(0f, 1f) else null
        }

        val now = System.currentTimeMillis()
        val delay = latencyMs

        // Onset detection: emit a pulse when energy rises sharply above the running average.
        // This makes visuals fire on actual beats rather than tracking volume level.
        if (e != null) {
            val avg = runningAvg
            runningAvg = avg * 0.92f + e * 0.08f   // slow-decay average
            val onset = if (avg > 0.01f) (e / avg).coerceIn(0f, 3f) / 3f else e
            val out = onset.coerceIn(0f, 1f)
            if (delay <= 0L) {
                _energy.value = out
            } else {
                pending.addLast(Pair(now + delay, out))
            }
        }

        // Drain anything whose emit time has passed
        while (pending.isNotEmpty() && pending.peekFirst().first <= now) {
            _energy.value = pending.pollFirst().second
        }

        val out = replaceOutputBuffer(byteCount)
        out.put(inputBuffer)
        out.flip()
    }

    fun resetBeat() {
        pending.clear()
        runningAvg = 0f
        _energy.value = 0f
    }

    override fun onReset() { resetBeat() }
}
