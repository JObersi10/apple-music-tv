package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-run setup state.
 *
 * Versioned deliberately: when a future step is added, bump [CURRENT_VERSION] and
 * existing users see only the new step instead of being walked through the whole
 * flow again.
 */
@Singleton
class OnboardingPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)

    val completed: Boolean get() = prefs.getInt("completed_version", 0) >= CURRENT_VERSION

    fun markCompleted() = prefs.edit { putInt("completed_version", CURRENT_VERSION) }

    /** Dev-menu escape hatch so the flow can be re-tested without clearing app data. */
    fun reset() = prefs.edit { putInt("completed_version", 0) }

    /**
     * Remote type. Auto-detected, but the user can override it in step 3 — detection
     * reads Amazon's system feature flag, and a rooted or repackaged box can lie.
     */
    var remoteOverride: String
        get() = prefs.getString("remote_override", REMOTE_AUTO) ?: REMOTE_AUTO
        set(v) = prefs.edit { putString("remote_override", v) }

    companion object {
        const val CURRENT_VERSION = 1
        const val REMOTE_AUTO = "auto"
        const val REMOTE_FIRE = "fire"
        const val REMOTE_GOOGLE = "google"
    }
}
