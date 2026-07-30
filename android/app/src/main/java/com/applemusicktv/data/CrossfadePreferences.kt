package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Crossfade length, settable from the phone web server (port 8080). */
@Singleton
class CrossfadePreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("crossfade_prefs", Context.MODE_PRIVATE)

    private val _durationMs = MutableStateFlow(prefs.getLong("duration_ms", DEFAULT_MS))
    /** Emits on every change so playback picks up edits without an app restart. */
    val durationMs: StateFlow<Long> = _durationMs

    fun getDuration(): Long = _durationMs.value

    fun setDuration(ms: Long) {
        val clamped = ms.coerceIn(MIN_MS, MAX_MS)
        prefs.edit { putLong("duration_ms", clamped) }
        _durationMs.value = clamped
    }

    companion object {
        const val DEFAULT_MS = 7_000L
        const val MIN_MS = 1_000L
        const val MAX_MS = 15_000L
    }
}
