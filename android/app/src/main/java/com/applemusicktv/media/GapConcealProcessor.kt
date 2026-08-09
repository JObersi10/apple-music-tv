package com.applemusicktv.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packet-loss concealment for the standalone (on-device Widevine) path.
 *
 * Android's `c2.android.aac.decoder` intermittently emits a whole HE-AAC/SBR output
 * frame of **exact digital silence** (2048 samples ≈ 46 ms) instead of audio — about
 * once a second on some encodes (Love Me Again, Bonetrousle). That's the "chop". The
 * proxy never shows it because ffmpeg decodes server-side; on-device we can't swap the
 * decoder (DRM forces MediaCodec), so we repair the PCM after decode.
 *
 * Captured PCM proved the dropouts are runs of *strictly zero* samples on both
 * channels, and that real audio never holds exact zero for even 64 samples. So we key
 * on zero-runs: once [MIN_GAP] consecutive all-zero frames confirm a dropout, we fill
 * the rest of the gap by replaying the last [HIST] good samples (faded), then crossfade
 * back to real audio over [XF] samples. Output length equals input length, so playback
 * position — and therefore lyric sync — is untouched.
 *
 * Only ENCODING_PCM_16BIT is handled; any other encoding passes through unchanged.
 */
class GapConcealProcessor : BaseAudioProcessor() {

    private var channels = 2
    private var pcm16 = false

    // Per-channel ring of the most recent good samples, used as the concealment source.
    private lateinit var hist: Array<FloatArray>
    private var histPos = 0
    private var histCount = 0

    private var zeroRun = 0        // consecutive all-zero frames seen (pre-confirmation)
    private var inGap = false      // confirmed dropout in progress
    private var concealPhase = 0   // samples emitted since the gap was confirmed
    private var recPos = -1        // >=0 while crossfading real audio back in

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        hist = Array(channels) { FloatArray(HIST) }
        histPos = 0; histCount = 0
        zeroRun = 0; inGap = false; concealPhase = 0; recPos = -1
        // Pass the format through untouched; we only repair, never resample/remix.
        return if (pcm16) inputAudioFormat else inputAudioFormat
    }

    private fun pushHist(c: Int, v: Float) {
        hist[c][histPos] = v
    }

    /** Advance the ring write head once per frame, after all channels are stored. */
    private fun advanceHist() {
        histPos = (histPos + 1) % HIST
        if (histCount < HIST) histCount++
    }

    /** Concealment source sample for channel [c] at the current [concealPhase]. */
    private fun conceal(c: Int): Float {
        if (histCount == 0) return 0f
        val j = concealPhase % histCount
        val idx = (histPos - histCount + j + HIST) % HIST
        return hist[c][idx]
    }

    private fun concealGain(): Float {
        // Gentle fade so a long gap doesn't sound like a hard loop; a single 46 ms
        // frame barely fades at all.
        return (0.9f - 0.4f * (concealPhase.toFloat() / HIST)).coerceIn(0.4f, 0.9f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Master switch — when off, this is a plain passthrough (baseline behaviour).
        if (!ENABLED || !pcm16) {
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        val inBuf = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
        val frame = ShortArray(channels)

        while (inBuf.remaining() >= 2 * channels) {
            var allZero = true
            for (c in 0 until channels) {
                val s = inBuf.short
                frame[c] = s
                if (s.toInt() != 0) allZero = false
            }

            when {
                // ── Crossfading real audio back in after a gap ──
                recPos >= 0 -> {
                    val t = recPos.toFloat() / XF
                    val g = concealGain()
                    for (c in 0 until channels) {
                        val real = frame[c].toFloat()
                        val cv = conceal(c) * g
                        emit(out, real * t + cv * (1f - t))
                        pushHist(c, real)
                    }
                    advanceHist()
                    concealPhase++; recPos++
                    if (recPos >= XF) { recPos = -1; inGap = false; concealPhase = 0; zeroRun = 0 }
                }

                // ── Confirmed gap: keep concealing until real audio returns ──
                inGap -> {
                    if (allZero) {
                        val g = concealGain()
                        for (c in 0 until channels) emit(out, conceal(c) * g)
                        concealPhase++
                    } else {
                        recPos = 0            // real audio is back — start recovery this frame
                        val g = concealGain()
                        for (c in 0 until channels) {
                            val cv = conceal(c) * g
                            emit(out, cv)      // t=0 → full conceal on the seam frame
                            pushHist(c, frame[c].toFloat())
                        }
                        advanceHist()
                        concealPhase++; recPos++
                    }
                }

                // ── Normal audio (or an as-yet-unconfirmed short zero run) ──
                allZero -> {
                    zeroRun++
                    if (zeroRun >= MIN_GAP) {
                        inGap = true; concealPhase = 0
                        val g = concealGain()
                        for (c in 0 until channels) emit(out, conceal(c) * g)
                        concealPhase++
                    } else {
                        // Real zero-crossing — pass through untouched (don't poison hist).
                        for (c in 0 until channels) emit(out, frame[c].toFloat())
                    }
                }

                else -> {
                    zeroRun = 0
                    for (c in 0 until channels) {
                        emit(out, frame[c].toFloat())
                        pushHist(c, frame[c].toFloat())
                    }
                    advanceHist()
                }
            }
        }
        out.flip()
    }

    private fun emit(out: ByteBuffer, v: Float) {
        val i = v.toInt().coerceIn(-32768, 32767)
        out.putShort(i.toShort())
    }

    override fun onReset() {
        histPos = 0; histCount = 0
        zeroRun = 0; inGap = false; concealPhase = 0; recPos = -1
    }

    private companion object {
        const val ENABLED = false // gated off while the detection heuristic is refined
        const val HIST = 2048     // one HE-AAC/SBR output frame of concealment history
        const val MIN_GAP = 64    // consecutive zero frames that confirm a real dropout
        const val XF = 128        // recovery crossfade length (samples)
    }
}
