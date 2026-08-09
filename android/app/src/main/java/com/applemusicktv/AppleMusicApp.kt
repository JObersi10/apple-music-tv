package com.applemusicktv

import android.app.Application
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.InAppWebServer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AppleMusicApp : Application() {

    @Inject lateinit var webServer: InAppWebServer
    @Inject lateinit var repo: MusicRepository
    @Inject lateinit var mutPrefs: MutPreferences

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        clearStaleCaches()
        webServer.start(appScope)
        // Sync locally-stored MUT to proxy server on startup so ExoPlayer stream requests work
        val mut = mutPrefs.getMUT()
        if (mut.isNotEmpty()) {
            appScope.launch {
                runCatching { repo.syncMUTToServer(mut) }
            }
        }
    }

    /** Once every 24h, wipe cached artwork/HTTP so stale images/data get re-fetched. */
    private fun clearStaleCaches() {
        val prefs = getSharedPreferences("cache_meta", MODE_PRIVATE)
        val last = prefs.getLong("last_clear", 0L)
        val now = System.currentTimeMillis()
        if (now - last < 24L * 60 * 60 * 1000) return
        prefs.edit().putLong("last_clear", now).apply()
        appScope.launch {
            runCatching {
                coil.Coil.imageLoader(this@AppleMusicApp).apply {
                    memoryCache?.clear()
                    diskCache?.clear()
                }
            }
        }
    }
}
