package com.applemusicktv.media

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice

/**
 * Rumbles a connected game controller to the bass. Fed the [BeatAnalyzer] energy envelope; on each
 * onset it fires a short amplitude-scaled vibration on every gamepad that has a motor. A plain TV
 * remote has no gamepad vibrator, so it's a silent no-op there — that's the auto-detect: rumble only
 * happens when an actual controller is attached.
 */
class RumbleController(context: Context) {
    private val appCtx = context.applicationContext

    /** Vibrators belonging to attached game controllers (re-scanned cheaply on demand). */
    private fun gamepadVibrators(): List<Vibrator> {
        val out = ArrayList<Vibrator>()
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            val isGamepad = (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!isGamepad) continue
            @Suppress("DEPRECATION") val vib = dev.vibrator  // device is SDK 30; vibratorManager is 31+
            if (vib != null && vib.hasVibrator()) out.add(vib)
        }
        return out
    }

    /** True when at least one attached controller can rumble (used to auto-detect gamepad vs remote). */
    fun hasGamepadRumble(): Boolean = gamepadVibrators().isNotEmpty()

    /** The phone/tablet's own vibrator, used when no game controller is attached (a plain TV box has
     *  none, so it stays a silent no-op there — exactly the gamepad-vs-remote auto-detect). */
    private val deviceVibrator: Vibrator? by lazy {
        @Suppress("DEPRECATION")
        (appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() }
    }

    private var lastEnergy = 0f
    private var lastFireNs = 0L

    /** Intensity multiplier, 0f..2f (100% = 1f). Scales every pulse's amplitude. */
    @Volatile var strength: Float = 1f
    /** "beat" = steady pulse on every onset; "drums" = sharper, kick-weighted (ignores soft onsets). */
    @Volatile var follow: String = "beat"

    private fun scaleAmp(amp: Int): Int = (amp * strength).toInt().coerceIn(1, 255)

    /**
     * Feed the latest beat energy (0..~1). Fires on a rising bass onset with a PS5-style
     * attack-decay envelope whose punch and length scale with how hard the hit is — a soft kick
     * is a light short tap, a heavy kick is a sharp crack that falls off over ~90ms. Not a flat
     * 40ms blip every time, so it feels alive and tracks the groove instead of buzzing uniformly.
     *
     * The energy bus is already delayed by [BeatAnalyzer.latencyMs], so this is in sync with the
     * audible beat (the same offset the lyrics/visuals ride).
     */
    fun onEnergy(energy: Float) {
        if (strength <= 0f) return
        val e = energy.coerceIn(0f, 1f)
        val onset = (e - lastEnergy).coerceIn(0f, 1f)   // how hard the onset is, not just level
        // "drums" mode wants only the sharp hits (kicks/snares) — higher bar, bigger rise required.
        // "beat" mode reacts to the steady pulse too, so smaller onsets still tap.
        val drums = follow == "drums"
        val levelGate = if (drums) 0.15f else 0.10f
        val riseGate  = if (drums) 0.05f else 0.025f
        val rising = e > levelGate && onset > riseGate
        lastEnergy = e
        if (!rising) return
        // Refractory so a sustained swell doesn't machine-gun the motor (~70ms min gap).
        val now = System.nanoTime()
        if (now - lastFireNs < 70_000_000L) return
        lastFireNs = now

        val vibs = gamepadVibrators().ifEmpty { listOfNotNull(deviceVibrator) }
        if (vibs.isEmpty()) return

        // Peak amplitude blends absolute level + onset strength → louder, sharper hits punch harder.
        // Drums mode leans harder on the transient so kicks crack and the body stays quiet.
        val punch = (if (drums) 0.35f * e + 0.65f * onset else 0.55f * e + 0.45f * onset).coerceIn(0f, 1f)
        val peak = scaleAmp((45 + punch * 210f).toInt())
        val effect = buildEnvelope(peak, punch)
        for (v in vibs) runCatching {
            if (effect != null) v.vibrate(effect)
            else @Suppress("DEPRECATION") v.vibrate((30 + (punch * 40)).toLong())
        }
    }

    /**
     * A short waveform that cracks to [peak] then decays — stronger hits get a longer tail. Modern
     * controllers (and most phones) honor per-step amplitudes, so this reads as a textured thump
     * rather than a square buzz.
     */
    private fun buildEnvelope(peak: Int, punch: Float): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val tail = (punch * 60f).toInt()               // 0..60ms extra on heavy hits
        val mid = (peak * 0.6f).toInt().coerceIn(1, 255)
        val low = (peak * 0.28f).toInt().coerceIn(1, 255)
        // attack(8ms,peak) → body(mid) → decay(low, scaled)
        val timings = longArrayOf(8L, 14L, (18 + tail).toLong())
        val amps = intArrayOf(peak, mid, low)
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    /**
     * Fire one representative pulse so the user can feel the current strength: a deep kick, a crisp
     * snare, a soft note, or a big drop. Scaled by [strength] exactly like live playback.
     */
    fun test(kind: String) {
        if (strength <= 0f) return
        val vibs = gamepadVibrators().ifEmpty { listOfNotNull(deviceVibrator) }
        if (vibs.isEmpty()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) when (kind) {
            // kick: low, round, a little sustain
            "kick"  -> VibrationEffect.createWaveform(longArrayOf(10L, 55L), intArrayOf(scaleAmp(210), scaleAmp(70)), -1)
            // snare: short, sharp crack
            "snare" -> VibrationEffect.createWaveform(longArrayOf(6L, 12L), intArrayOf(scaleAmp(230), scaleAmp(40)), -1)
            // note: light tap
            "note"  -> VibrationEffect.createOneShot(18L, scaleAmp(95))
            // drop: build then a heavy hit
            "drop"  -> VibrationEffect.createWaveform(
                longArrayOf(40L, 40L, 14L, 90L),
                intArrayOf(scaleAmp(60), scaleAmp(120), scaleAmp(255), scaleAmp(110)), -1)
            else    -> VibrationEffect.createOneShot(30L, scaleAmp(180))
        } else null
        for (v in vibs) runCatching {
            if (effect != null) v.vibrate(effect) else @Suppress("DEPRECATION") v.vibrate(40L)
        }
    }

    fun stop() {
        lastEnergy = 0f
        gamepadVibrators().forEach { runCatching { it.cancel() } }
        runCatching { deviceVibrator?.cancel() }
    }
}
