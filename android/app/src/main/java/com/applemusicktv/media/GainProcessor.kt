package com.applemusicktv.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Volume leveling — ReplayGain-style: ONE constant gain per track, decided before the track starts and
 * held dead-flat for its whole duration. This is a full rewrite of the old running-AGC design, which
 * eased the gain toward a target over the first ~8 s and then kept drifting — so every track started at
 * the wrong level (the "amplified/distorted at the start"), the level moved *within* a track (the one
 * thing a normalizer must never do), and the per-track memory held a half-settled moving value.
 *
 * How it works now:
 *  - The gain applied this play is a single constant, [appliedGain], chosen in [onTrackChanged] from the
 *    track's remembered measurement ([cacheGet]) — or **1.0 (flat, untouched)** the first time we ever
 *    see the track. Nothing eases it; every sample in the track gets the same gain.
 *  - Meanwhile we silently MEASURE the track's integrated loudness (sum of squares over all non-silent
 *    samples, independent of the gain we're applying). On track change / flush we convert that to the
 *    correct constant gain and store it ([cachePut]). The NEXT play of that track starts pre-levelled
 *    from sample 0, with no ramp and no drift.
 *  - A soft limiter on the output keeps a boosted peak from hard-clipping.
 *
 * Trade: a brand-new track plays flat (gain 1.0) on its first listen, then is correctly levelled on
 * every replay. That's deliberate — a look-ahead loudness measurement is impossible inside the realtime
 * playback path, and a flat first listen is far better than the old audible start-ramp + pumping.
 *
 * App-wide on/off via [enabled]; when off it's a straight pass-through with no math on the hot path.
 */
class GainProcessor : BaseAudioProcessor() {

    private var isFloat = false

    private var trackKey: String? = null
    private var appliedGain = 1f          // constant gain applied for the whole current track

    // Integrated-loudness measurement of the RAW (pre-gain) signal for the current track.
    private var measSumSq = 0.0
    private var measCount = 0L
    private var measuredLogged = false    // emitted the one "measured" line for this track yet

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    /** Commit the finished track's measurement, then adopt the new track's constant gain. */
    private fun onTrackChanged(newKey: String?) {
        commitMeasurement()                     // store what the outgoing track measured to
        trackKey = newKey
        // The gain for this play is the remembered measured constant, clamped — or a flat 1.0 the first
        // time we see the track. No easing, no running estimate: this value is used for every sample.
        val remembered = newKey?.let { cacheGet?.invoke(it) }
        appliedGain = (remembered ?: 1f).coerceIn(MIN_GAIN, MAX_GAIN)
        measSumSq = 0.0
        measCount = 0L
        measuredLogged = false
        logger?.invoke("VOL", "track=${newKey ?: "?"} gain=${"%.2f".format(appliedGain)} " +
            "(${if (remembered != null) "levelled" else "flat — first listen, measuring"})")
    }

    /** Turn the current track's integrated loudness into a constant gain and remember it. */
    private fun commitMeasurement() {
        val key = trackKey ?: return
        if (measCount < MIN_MEASURE_SAMPLES) return   // too little non-silent audio to trust
        val integratedRms = sqrt(measSumSq / measCount).toFloat()
        if (integratedRms < SILENCE_RMS) return
        val g = (TARGET_RMS / integratedRms).coerceIn(MIN_GAIN, MAX_GAIN)
        cachePut?.invoke(key, g)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)

        if (!enabled) { out.put(inputBuffer); out.flip(); return }  // pass-through

        if (currentTrackKey != trackKey) onTrackChanged(currentTrackKey)

        val inp = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val g = appliedGain
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
        // asShortBuffer()/asFloatBuffer() are VIEWS — writing through them does NOT advance `out`'s own
        // position, so a bare out.flip() would flip at position 0 and emit an EMPTY buffer (silence).
        // We wrote exactly `size` bytes, so advance to there before flipping.
        out.position(size)
        out.flip()

        // Accumulate the RAW signal's loudness (pre-gain) for this track. Skip near-silence so lead-in /
        // lead-out / a crossfade partner feeding quiet doesn't drag the integrated figure down.
        if (count > 0) {
            val bufRms = sqrt(sumSq / count).toFloat()
            if (bufRms >= SILENCE_RMS) {
                measSumSq += sumSq
                measCount += count
                // One informational line once we've gathered enough to know where this track will land.
                if (!measuredLogged && measCount >= MIN_MEASURE_SAMPLES) {
                    measuredLogged = true
                    val integrated = sqrt(measSumSq / measCount).toFloat()
                    val wouldBe = (TARGET_RMS / (integrated + 1e-4f)).coerceIn(MIN_GAIN, MAX_GAIN)
                    logger?.invoke("VOL", "measured rms=${"%.3f".format(integrated)} → next-play gain=${"%.2f".format(wouldBe)}")
                }
            }
        }
    }

    /** Soft limiter: linear until [KNEE], then compresses toward ±1 so a boosted peak never hard-clips. */
    private fun softClip(v: Float): Float = when {
        v >  KNEE ->  KNEE + (1f - KNEE) * tanh((v - KNEE) / (1f - KNEE))
        v < -KNEE -> -KNEE - (1f - KNEE) * tanh((-v - KNEE) / (1f - KNEE))
        else -> v
    }

    override fun onFlush() {
        // Track change / seek / stop: commit whatever the current track measured so a full listen is
        // remembered even if the user moves on. (A seek re-flushes mid-track; partial measurements below
        // MIN_MEASURE_SAMPLES are simply ignored by commitMeasurement.)
        commitMeasurement()
    }

    companion object {
        /** App-wide toggle, set from the Volume-leveling setting. */
        @Volatile var enabled: Boolean = false
        /** The track currently playing (Apple catalog id) — set by the player so per-track memory works. */
        @Volatile var currentTrackKey: String? = null
        /** Live-log sink (tag, msg). Wired to NetworkLog by the player. */
        @Volatile var logger: ((String, String) -> Unit)? = null
        /** Per-track MEASURED-gain memory, backed by a tiny prefs store the player wires in. */
        @Volatile var cacheGet: ((String) -> Float?)? = null
        @Volatile var cachePut: ((String, Float) -> Unit)? = null

        private const val TARGET_RMS = 0.16f    // ~ -16 dBFS RMS — a comfortable, loud-but-safe target
        private const val MIN_GAIN   = 0.5f     // deepest cut
        private const val MAX_GAIN   = 2.0f     // biggest boost. Constant (not chased), so a correct 2×
                                                // on a quiet master is the right steady level, not a blast;
                                                // the soft limiter catches any boosted peak.
        private const val KNEE       = 0.85f    // soft-limiter threshold
        private const val SILENCE_RMS = 0.02f   // below this a buffer/track is treated as silence (skip)
        // Need at least this much non-silent audio before a measurement is trustworthy enough to store
        // (~10 s stereo @ 48k). Shorter snippets (fast skips, seeks) don't poison the per-track memory.
        private const val MIN_MEASURE_SAMPLES = 10L * 48000 * 2
    }
}
