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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shared beat bus. Each ExoPlayer gets its own [BeatProcessor] (an AudioProcessor
 * can't be shared between two audio sinks), but only the *active* processor is
 * allowed to publish — so during a crossfade the visuals keep following whichever
 * player is the one we're actually listening to.
 */
@Singleton
class BeatAnalyzer @Inject constructor() {

    private val _energy = MutableStateFlow(0f)
    val energy: StateFlow<Float> = _energy

    /** Set to match current audio output latency (0 for speakers, ~200 for BT). */
    @Volatile var latencyMs: Long = 0L

    @Volatile private var activeId: Int = -1
    @Volatile private var active: BeatProcessor? = null
    private var nextId = 0

    fun newProcessor(): BeatProcessor = BeatProcessor(this, nextId++)

    /** Make [p] the only processor allowed to drive [energy]. */
    fun activate(p: BeatProcessor) {
        activeId = p.id
        active = p
        p.resetBeat()
    }

    internal fun publish(id: Int, value: Float) {
        if (id == activeId) _energy.value = value
    }

    internal fun isActive(id: Int) = id == activeId

    /** Drop buffered pulses and detector history (e.g. after a latency change). */
    fun resetBeat() {
        active?.resetBeat()
        _energy.value = 0f
    }

    // ── DIAGNOSTIC: raw decoded-PCM capture ────────────────────────────────
    // The active BeatProcessor writes post-decrypt PCM here so we can inspect the
    // standalone chop offline. Remove once the gap-repair processor is built.
    @Volatile var captureOut: java.io.OutputStream? = null
    private var captureLeft = 0
    @Synchronized fun startCapture(out: java.io.OutputStream, bytes: Int) {
        captureOut = out; captureLeft = bytes
    }
    @Synchronized fun capture(buf: java.nio.ByteBuffer) {
        val out = captureOut ?: return
        if (captureLeft <= 0) {
            try { out.flush(); out.close() } catch (_: Exception) {}
            captureOut = null; return
        }
        val d = buf.duplicate()
        val n = minOf(d.remaining(), captureLeft)
        val arr = ByteArray(n); d.get(arr, 0, n)
        try { out.write(arr) } catch (_: Exception) {}
        captureLeft -= n
    }
}

/**
 * Bass-focused onset detector. Instead of tracking overall loudness (which stays
 * flat through dense mixes and reacts to vocals/cymbals), it:
 *  1. downmixes to mono, blocks DC, then cascades 3 low-pass poles at 100 Hz
 *     (-18 dB/oct) so only kick/bass gets through — one pole let vocals in,
 *  2. measures energy in fixed 10 ms windows so timing doesn't depend on buffer size,
 *  3. flags an onset when a window jumps above `mean + k·stddev` of the last ~1 s,
 *     with a refractory gap so one kick fires once,
 *  4. outputs a punch-then-decay envelope, which is what actually reads as rhythmic.
 *
 * Emission is delayed by [BeatAnalyzer.latencyMs] to compensate for output latency
 * (Bluetooth A2DP adds ~150-300 ms between PCM write and audible sound).
 */
class BeatProcessor internal constructor(
    private val bus: BeatAnalyzer,
    internal val id: Int,
) : BaseAudioProcessor() {

    private var isFloat = false
    private var channels = 2
    private var sampleRate = 44100

    // --- cascaded one-pole low-pass (bass isolation) ---
    // A single pole rolls off at only -6 dB/oct, so a 130 Hz cutoff still passed
    // most of a vocal fundamental. Three stages => -18 dB/oct, vocals stay out.
    private var lpAlpha = 0.02f
    private val lp = FloatArray(LP_STAGES)
    // --- DC / rumble removal ---
    private var hpAlpha = 0.004f
    private var hp = 0f

    // --- fixed analysis window ---
    private var windowSamples = 441          // 10 ms @ 44.1 kHz
    private var winAcc = 0f                  // sum of squares
    private var winCount = 0

    // --- adaptive threshold over the last ~1 s of windows ---
    private val hist = ArrayDeque<Float>()
    private var histSum = 0f
    private var histSumSq = 0f
    private val histMax = 100

    // --- output envelope ---
    private var level = 0f
    private var windowsSinceBeat = 99
    private var lastEmitted = -1f

    // Ring buffer of (emitAtMs, energy) pairs for latency compensation
    private val pending = ArrayDeque<Pair<Long, Float>>()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                channels = inputAudioFormat.channelCount.coerceAtLeast(1)
                sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
                windowSamples = (sampleRate / 100).coerceAtLeast(64)
                // alpha for a one-pole LPF at CUTOFF_HZ
                lpAlpha = (2f * Math.PI.toFloat() * CUTOFF_HZ / sampleRate).coerceIn(0.001f, 0.9f)
                hpAlpha = (2f * Math.PI.toFloat() * HP_HZ / sampleRate).coerceIn(0.0001f, 0.5f)
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount == 0) return

        // Skip all DSP entirely when this player isn't the one being heard.
        // DSP_ENABLED gates the whole per-sample analysis loop — a diagnostic to test
        // whether that work on the playback thread is starving the audio decoder
        // (frame-drop chop) on the weak Fire TV.
        if (DSP_ENABLED && bus.isActive(id)) {
            bus.capture(inputBuffer)   // diagnostic PCM tap (no-op unless capturing)
            val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (isFloat) {
                val v = dup.asFloatBuffer()
                while (v.remaining() >= channels) {
                    var mono = 0f
                    for (c in 0 until channels) mono += v.get()
                    feed(mono / channels)
                }
            } else {
                val v = dup.asShortBuffer()
                while (v.remaining() >= channels) {
                    var mono = 0f
                    for (c in 0 until channels) mono += v.get().toFloat()
                    feed(mono / (channels * 32768f))
                }
            }
            drain()
        }

        val out = replaceOutputBuffer(byteCount)
        out.put(inputBuffer)
        out.flip()
    }

    /** One mono sample: low-pass, accumulate, close the window when full. */
    private fun feed(sample: Float) {
        hp += hpAlpha * (sample - hp)          // running DC estimate
        var x = sample - hp                     // high-passed
        for (i in 0 until LP_STAGES) {          // cascaded low-pass
            lp[i] += lpAlpha * (x - lp[i])
            x = lp[i]
        }
        winAcc += x * x
        if (++winCount < windowSamples) return

        val e = sqrt(winAcc / winCount)
        winAcc = 0f; winCount = 0
        closeWindow(e)
    }

    private fun closeWindow(e: Float) {
        val n = hist.size
        val mean = if (n > 0) histSum / n else 0f
        val varr = if (n > 0) (histSumSq / n - mean * mean).coerceAtLeast(0f) else 0f
        val sd = sqrt(varr)

        // Decay the envelope one window's worth.
        level *= DECAY_PER_WINDOW
        windowsSinceBeat++

        if (n >= MIN_HIST && windowsSinceBeat >= REFRACTORY_WINDOWS) {
            val thresh = mean + SENSITIVITY * sd + 1e-5f
            if (e > thresh && e > FLOOR) {
                // How far past the threshold it landed → how hard the pulse hits.
                val strength = ((e - mean) / (sd + 1e-5f) / 4f).coerceIn(0.4f, 1f)
                if (strength > level) level = strength
                windowsSinceBeat = 0
            }
        }

        hist.addLast(e); histSum += e; histSumSq += e * e
        if (hist.size > histMax) {
            val old = hist.pollFirst(); histSum -= old; histSumSq -= old * old
        }

        // Emit sparsely — this drives recomposition of the whole background.
        if (abs(level - lastEmitted) > 0.015f || (level == 0f && lastEmitted != 0f)) {
            lastEmitted = level
            val delay = bus.latencyMs
            if (delay <= 0L) bus.publish(id, level)
            else pending.addLast(Pair(System.currentTimeMillis() + delay, level))
        }
    }

    /** Release anything whose latency-compensated emit time has arrived. */
    private fun drain() {
        val now = System.currentTimeMillis()
        while (pending.isNotEmpty() && pending.peekFirst().first <= now) {
            bus.publish(id, pending.pollFirst().second)
        }
    }

    fun resetBeat() {
        pending.clear()
        hist.clear(); histSum = 0f; histSumSq = 0f
        winAcc = 0f; winCount = 0
        lp.fill(0f); hp = 0f; level = 0f; lastEmitted = -1f
        windowsSinceBeat = 99
        bus.publish(id, 0f)
    }

    override fun onReset() { resetBeat() }

    private companion object {
        const val DSP_ENABLED = true       // DIAGNOSTIC: off = skip beat DSP on audio thread
        const val LP_STAGES = 2             // -12 dB/oct — wider passband so higher-keyed
                                            // kicks/basslines (e.g. "I Gotta Feeling") still
                                            // fall inside the band instead of getting rolled off.
        const val CUTOFF_HZ = 180f          // kick/bass band, widened from 100 Hz to catch
                                            // beats whose fundamental sits higher up.
        const val HP_HZ = 25f               // kill DC and sub rumble
        const val SENSITIVITY = 1.1f        // stddevs above mean to count as a beat — lowered
                                            // so kicks in dense electro mixes (wobble bass keeps
                                            // the running mean high) still clear the bar.
        const val REFRACTORY_WINDOWS = 12   // 120 ms → max ~500 BPM
        const val MIN_HIST = 25             // need 250 ms of history before firing
        const val FLOOR = 0.004f            // ignore near-silence
        const val DECAY_PER_WINDOW = 0.955f // ~10 ms step → ~250 ms fall
    }
}
