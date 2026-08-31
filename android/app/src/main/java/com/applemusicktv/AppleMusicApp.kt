package com.applemusicktv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
class AppleMusicApp : Application(), ImageLoaderFactory {

    /**
     * Coil's default disk cache grows to ~2% of the whole partition — unbounded on a big
     * Fire TV. Cap artwork on disk at 100 MB (LRU-evicted) and memory hard at 48 MB — 15% of RAM was
     * ~225 MB on this box, far too much to hold in a memory-starved foreground app.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizeBytes(64 * 1024 * 1024).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            // Fire TV menu smoothness: RGB_565 halves bitmap memory for opaque artwork (no alpha
            // needed) → far less GC churn while flinging shelves. respectCacheHeaders(false) keeps
            // decoded art in cache so re-focusing a shelf never re-fetches/re-decodes.
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()

    @Inject lateinit var webServer: InAppWebServer
    @Inject lateinit var repo: MusicRepository
    @Inject lateinit var mutPrefs: MutPreferences

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.applemusicktv.util.CrashReporter.install(this)
        clearStaleCaches()
        webServer.boot(appScope)   // starts only if the Dev toggle is on
        // Route volume-leveling diagnostics into the APP log (so they show under App Log, not Network,
        // and stream live on the :8081 event port).
        com.applemusicktv.media.GainProcessor.logger = { tag, msg -> webServer.addLog(tag, msg) }
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
