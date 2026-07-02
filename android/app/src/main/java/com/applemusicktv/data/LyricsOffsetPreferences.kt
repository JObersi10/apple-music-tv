package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsOffsetPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("lyrics_prefs", Context.MODE_PRIVATE)

    fun getOffset(): Long = prefs.getLong("offset_ms", 0L)
    fun setOffset(ms: Long) = prefs.edit { putLong("offset_ms", ms) }
}
