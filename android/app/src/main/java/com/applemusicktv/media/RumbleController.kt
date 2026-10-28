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

    private var lastEnergy = 0f

    /** Feed the latest beat energy (0..~1). Fires a tap on a rising bass onset. */
    fun onEnergy(energy: Float) {
        val rising = energy > 0.16f && energy > lastEnergy + 0.03f
        lastEnergy = energy
        if (!rising) return
        val amp = (60 + (energy.coerceIn(0f, 1f) * 195f)).toInt().coerceIn(1, 255)
        val vibs = gamepadVibrators()
        if (vibs.isEmpty()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            VibrationEffect.createOneShot(40L, amp) else null
        for (v in vibs) runCatching {
            if (effect != null) v.vibrate(effect) else @Suppress("DEPRECATION") v.vibrate(40L)
        }
    }

    fun stop() {
        lastEnergy = 0f
        gamepadVibrators().forEach { runCatching { it.cancel() } }
    }
}
