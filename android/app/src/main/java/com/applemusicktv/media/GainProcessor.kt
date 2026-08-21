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
 * broadband gain there VERY slowly so it levels BETWEEN tracks without pumping WITHIN one. The gain is
 * updated once per input buffer and held constant across that buffer's samples; a soft limiter on the
 * output stops a boost from hard-clipping peaky material.
 *
 * Per-track memory: the settled gain for a track is remembered (via [cacheGet]/[cachePut], persisted by
 * the ViewModel), so replaying it starts at the right level immediately with no audible ramp. The
 * current track is announced through [currentTrackKey].
 *
 * App-wide on/off via [enabled]; when off it's a straight pass-through with no math on the hot path.
 */
class GainProcessor : BaseAudioProcessor() {

    private var isFloat = false
    private var sampleRate = 48000

    private var rms = TARGET_RMS   // smoothed running RMS estimate
    private var gain = 1f          // current applied gain, eased toward target
    private var trackKey: String? = null
    private var samplesSinceLog = 0L
    private var samplesInTrack = 0L   // for the settle-then-lock behaviour
    private var lastLoggedGain = -1f  // last gain we emitted a VOL line for (-1 = none yet)
    private var settleLogsSent = 0    // how many of the 3 "analyzing" lines we've sent this track
    private var settledLogged = false // emitted the single "settled" summary yet?

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                sampleRate = inputAudioFormat.sampleRate
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    /** Save the last track's settled gain and adopt the new track's remembered gain (or a neutral start). */
    private fun onTrackChanged(newKey: String?) {
        val old = trackKey
        if (old != null) cachePut?.invoke(old, gain)   // remember where the last track settled
        trackKey = newKey
        gain = newKey?.let { cacheGet?.invoke(it) } ?: 1f
        rms = TARGET_RMS / gain.coerceAtLeast(0.01f)    // keep rms consistent with the adopted gain
        samplesSinceLog = 0L
        samplesInTrack = 0L
        lastLoggedGain = -1f
        settleLogsSent = 0
        settledLogged = false
        logger?.invoke("VOL", "track=${newKey ?: "?"} start gain=${"%.2f".format(gain)} (${if (newKey != null && cacheGet?.invoke(newKey) != null) "remembered" else "fresh"})")
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)

        if (!enabled) { out.put(inputBuffer); out.flip(); return }  // pass-through

        if (currentTrackKey != trackKey) onTrackChanged(currentTrackKey)

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
        // asShortBuffer()/asFloatBuffer() are VIEWS — writing through them does NOT advance `out`'s own
        // position, so a bare out.flip() here would flip at position 0 and emit an EMPTY buffer (dead
        // silence). We wrote exactly `size` bytes, so advance to there before flipping.
        out.position(size)
        out.flip()

        // Update loudness + ease the gain once per buffer — VERY slow, so leveling is between tracks and
        // a bass drop after a quiet vocal doesn't yank the gain (which was the audible pumping).
        if (count > 0) {
            val bufRms = sqrt(sumSq / count).toFloat()
            samplesInTrack += count
            // Ignore near-silence buffers entirely: they drag the RMS toward 0 and make `target` shoot to
            // MAX (the crossfade player feeding silence was the `target=3.74 buf=0.000` garbage). No update,
            // no log for those.
            if (bufRms >= SILENCE_RMS) {
                rms += RMS_RATE * (bufRms - rms)
                val target = (TARGET_RMS / (rms + 1e-4f)).coerceIn(MIN_GAIN, MAX_GAIN)
                // Settle then (nearly) lock: adapt normally for the first few seconds of a track to find
                // its level, then a much slower rate so the gain holds steady instead of wandering with
                // each verse/chorus. Replays start pre-settled from the remembered gain.
                val rate = if (samplesInTrack > SETTLE_SAMPLES) GAIN_RATE * 0.12f else GAIN_RATE
                gain += rate * (target - gain)

                val locked = samplesInTrack > SETTLE_SAMPLES
                if (!locked) {
                    // Settling: shoot exactly 3 "analyzing" lines across the settle window — at ~1/4, 1/2
                    // and 3/4 of the way — not a per-second stream.
                    val next = SETTLE_SAMPLES * (settleLogsSent + 1) / 4
                    if (settleLogsSent < 3 && samplesInTrack >= next) {
                        settleLogsSent++
                        logger?.invoke("VOL", "analyzing… gain=${"%.2f".format(gain)} rms=${"%.3f".format(rms)} → target=${"%.2f".format(target)}")
                    }
                } else if (!settledLogged) {
                    // Just crossed into locked: emit ONE line of what it ended up doing, then go quiet.
                    settledLogged = true
                    lastLoggedGain = gain
                    logger?.invoke("VOL", "settled at gain=${"%.2f".format(gain)} (${if (gain > 1.01f) "boosted +${"%.0f".format((gain - 1f) * 100)}%" else if (gain < 0.99f) "cut ${"%.0f".format((1f - gain) * 100)}%" else "unchanged"})")
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
        // Persist the settled gain on flush (track change/seek) so it's remembered even mid-listen.
        trackKey?.let { cachePut?.invoke(it, gain) }
    }

    companion object {
        /** App-wide toggle, set from the Volume-leveling setting. */
        @Volatile var enabled: Boolean = false
        /** The track currently playing (Apple catalog id) — set by the player so per-track memory works. */
        @Volatile var currentTrackKey: String? = null
        /** Live-log sink (tag, msg). Wired to NetworkLog by the player. */
        @Volatile var logger: ((String, String) -> Unit)? = null
        /** Per-track gain memory, backed by a tiny prefs store the player wires in. */
        @Volatile var cacheGet: ((String) -> Float?)? = null
        @Volatile var cachePut: ((String, Float) -> Unit)? = null

        private const val TARGET_RMS = 0.16f    // ~ -16 dBFS RMS — a comfortable, loud-but-safe target
        private const val RMS_RATE   = 0.004f   // per-buffer RMS follow — slow window, no bass/vocal chase
        private const val GAIN_RATE  = 0.0012f  // per-buffer gain easing — very slow (~tens of s): between-track
        private const val MIN_GAIN   = 0.5f
        private const val MAX_GAIN   = 4.0f
        private const val KNEE       = 0.85f    // soft-limiter threshold
        private const val SILENCE_RMS = 0.02f   // below this a buffer is treated as silence (skip)
        private const val SETTLE_SAMPLES = 8L * 48000 * 2   // ~8 s stereo before the gain (nearly) locks
    }
}
