package com.applemusicktv.lyrics.spicy.canvas.kawarp

import com.applemusicktv.lyrics.spicy.support.AudioAnalysis
import kotlin.math.exp

/**
 * How fast the background moves at a point in the song, from its audio analysis: the section's
 * tempo against 120 BPM times a factor for its loudness, plus a pulse on each confident beat that
 * decays over the beat. 1 is the normal speed; the result stays within 0.1..3.
 */
object BackgroundSpeed {
    private const val BASE_TEMPO = 120f
    private const val BEAT_PULSE_MAX = 1.5f
    private const val BEAT_PULSE_DECAY = 5f
    private const val MIN_BEAT_CONFIDENCE = 0.4f

    fun at(seconds: Float, analysis: AudioAnalysis): Float {
        val section = active(analysis.sections, seconds, { it.start }, { it.duration })
        var speed = if (section != null) {
            section.tempo / BASE_TEMPO * loudnessFactor(section.loudness)
        } else {
            analysis.tempo / BASE_TEMPO * loudnessFactor(analysis.loudness)
        }
        val beat = active(analysis.beats, seconds, { it.start }, { it.duration })
        if (beat != null && beat.confidence > MIN_BEAT_CONFIDENCE) {
            val into = (seconds - beat.start) / beat.duration
            speed += BEAT_PULSE_MAX * exp(-BEAT_PULSE_DECAY * into) * beat.confidence
        }
        return speed.coerceIn(0.1f, 3f)
    }

    /** -40 dB and below → 0.5, 0 dB → 1.2, linear between. */
    internal fun loudnessFactor(dB: Float): Float = 0.5f + (dB + 40f).coerceAtLeast(0f) / 40f * 0.7f

    /**
     * The element whose span holds [t]. Sections and beats run in time order without overlapping,
     * so it can only be the last one starting at or before [t]: found by binary search, as this
     * runs every frame.
     */
    private inline fun <T> active(items: List<T>, t: Float, start: (T) -> Float, duration: (T) -> Float): T? {
        var lo = 0
        var hi = items.size - 1
        var at = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (start(items[mid]) <= t) { at = mid; lo = mid + 1 } else hi = mid - 1
        }
        val item = items.getOrNull(at) ?: return null
        return item.takeIf { t < start(it) + duration(it) }
    }
}
