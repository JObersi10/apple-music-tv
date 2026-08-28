package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsOffsetPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("lyrics_prefs", Context.MODE_PRIVATE)

    private val _offsetMs = MutableStateFlow(prefs.getLong("offset_ms", 0L))
    /** Emits on every change so the UI picks up edits made from the phone web server. */
    val offsetMs: StateFlow<Long> = _offsetMs

    fun getOffset(): Long = _offsetMs.value

    fun setOffset(ms: Long) {
        prefs.edit { putLong("offset_ms", ms) }
        _offsetMs.value = ms
    }
}
