package com.applemusicktv.util

import android.content.Context
import java.io.File

/**
 * Bounds the app's on-device downloaded-media scratch — the standalone in-app decrypt output
 * (`clear_*.mp4` / `standalone_*.m3u8` / `*.tmp`) and the music-video cache — which Coil's
 * image-cache cap does NOT govern and which otherwise grew unbounded (observed >300 MB on device).
 *
 * Only top-level files in `cacheDir` are touched, so Coil's `cacheDir/image_cache/` and the
 * updater's `cacheDir/updates/` subdirectories are left alone (they are directories, not files).
 * Coil's artwork cache is capped separately in [com.applemusicktv.AppleMusicApp].
 */
object MediaCacheManager {

    private fun mediaFiles(context: Context): List<File> =
        context.cacheDir.listFiles()?.filter { it.isFile }.orEmpty()

    /** Total bytes of the app's media scratch right now. */
    fun currentBytes(context: Context): Long = mediaFiles(context).sumOf { it.length() }

    /**
     * LRU-evicts (oldest `lastModified` first) down to [capBytes]. Never deletes the single newest
     * file — that is the one most likely being played or just written — so `capBytes == 0` collapses
     * the cache to just the active file rather than breaking playback.
     */
    fun trim(context: Context, capBytes: Long) {
        val files = mediaFiles(context).sortedBy { it.lastModified() }   // oldest first
        if (files.size <= 1) return
        val newest = files.last()
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= capBytes) break
            if (f === newest) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
