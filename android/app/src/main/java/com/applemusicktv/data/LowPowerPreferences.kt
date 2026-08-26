package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low Power Mode. Trades a small hitch for memory/CPU on a starved Fire TV.
 *
 * OFF (default) = seamless: while a music video plays and you browse other tabs, the secure video
 * decoder stays ALIVE (the PlayerView is kept mounted, shrunk to 1px behind the window), so
 * returning to Now Playing is instant — no ~0.5s re-acquire stall.
 *
 * ON = low power: the secure decoder is FREED whenever you leave Now Playing (only audio keeps
 * going) and re-acquired on return. Lighter on RAM, so the OS is less likely to low-memory-kill the
 * app, at the cost of a brief blip entering Now Playing.
 */
@Singleton
class LowPowerPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("low_power_prefs", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    /** StateFlow so the :8080 / Dev toggle applies without an app restart. */
    val enabled: StateFlow<Boolean> = _enabled

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean("enabled", on) }
        _enabled.value = on
    }
}
