package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standalone playback: decrypt on-device with Widevine instead of asking the proxy
 * to download, mp4decrypt and re-encode the track.
 *
 * This is a speed feature, not just a fallback — the proxy path can't play a byte
 * until the whole file is decrypted and remuxed (15-20s cold), while ExoPlayer
 * decrypts HLS segments as it goes and starts in about a second. The server stays
 * connected for browse/library/lyrics and diagnostics.
 *
 * Default ON: it is strictly faster than the proxy for playback, and the proxy
 * path remains the automatic fallback whenever a Widevine source can't be built.
 */
@Singleton
class StandalonePreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("standalone_prefs", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    /** StateFlow so the :8080 toggle applies without an app restart. */
    val enabled: StateFlow<Boolean> = _enabled

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean("enabled", on) }
        _enabled.value = on
    }
}
