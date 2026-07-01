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
        webServer.start(appScope)
        // Sync locally-stored MUT to proxy server on startup so ExoPlayer stream requests work
        val mut = mutPrefs.getMUT()
        if (mut.isNotEmpty()) {
            appScope.launch {
                runCatching { repo.syncMUTToServer(mut) }
            }
        }
    }
}
