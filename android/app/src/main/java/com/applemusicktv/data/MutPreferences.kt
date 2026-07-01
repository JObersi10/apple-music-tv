package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MutPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("apple_music_tv", Context.MODE_PRIVATE)
    fun getMUT(): String = prefs.getString("mut", "") ?: ""
    fun setMUT(token: String) = prefs.edit { putString("mut", token) }
    fun hasMUT(): Boolean = getMUT().isNotEmpty()
}
