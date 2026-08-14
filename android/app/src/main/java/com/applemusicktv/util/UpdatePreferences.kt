package com.applemusicktv.util

import android.content.Context

/** Whether the self-updater should also offer prerelease ("beta") builds. */
object UpdatePreferences {
    private const val PREFS = "update_prefs"
    private const val KEY_BETA = "beta"

    fun betaEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BETA, false)

    fun setBeta(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BETA, on).apply()
    }
}
