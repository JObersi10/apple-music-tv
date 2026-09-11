package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User cap on the app's on-device downloaded-media cache: the standalone in-app decrypt output
 * (`clear_*.mp4` / `standalone_*.m3u8`) plus the music-video cache. Coil's artwork cache is bounded
 * separately (see [com.applemusicktv.AppleMusicApp]); this is the one that grew unbounded past
 * 300 MB. Exposes a [StateFlow] so a change applies without an app restart.
 */
@Singleton
class CachePreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("cache_prefs", Context.MODE_PRIVATE)

    private val _capBytes = MutableStateFlow(prefs.getLong("cap_bytes", DEFAULT_BYTES))
    val capBytes: StateFlow<Long> = _capBytes

    fun getCap(): Long = _capBytes.value

    fun setCap(bytes: Long) {
        val v = bytes.coerceIn(0L, MAX_BYTES)
        prefs.edit { putLong("cap_bytes", v) }
        _capBytes.value = v
    }

    companion object {
        const val MB = 1024L * 1024L
        /** Selectable caps: Off (keep only the active file) → 2 GB. */
        val OPTIONS = longArrayOf(0L, 50 * MB, 100 * MB, 150 * MB, 250 * MB, 500 * MB, 1000 * MB, 2000 * MB)
        const val DEFAULT_BYTES = 150L * MB
        const val MAX_BYTES = 2000L * MB
    }
}
