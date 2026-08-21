package com.applemusicktv.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.applemusicktv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/** A newer GitHub release than the running build, with its APK asset. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Self-updater that polls this repo's GitHub Releases. `check()` compares the latest
 * (non-prerelease) release's tag against the running versionName; if newer, `download()`
 * fetches the APK and `install()` hands it to the system package installer.
 */
object UpdateChecker {
    private const val REPO = "JObersi10/apple-music-tv"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ALL = "https://api.github.com/repos/$REPO/releases?per_page=15"

    /**
     * Newest release that's newer than the current build, else null.
     * @param includeBeta when true, prereleases count too (the "beta" channel); otherwise
     *   only the stable `releases/latest` (which GitHub excludes prereleases from) is used.
     */
    suspend fun check(includeBeta: Boolean = false): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val release = if (includeBeta) newestRelease() else JSONObject(httpGet(LATEST))
            release?.let(::parseRelease)
        }.rethrowCancellation()
    }

    /** Newest non-draft release (GitHub returns them newest-first), or null if none. */
    private fun newestRelease(): JSONObject? {
        val arr = JSONArray(httpGet(ALL))
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (!r.optBoolean("draft")) return r
        }
        return null
    }

    private fun parseRelease(obj: JSONObject): UpdateInfo? {
        val tag = obj.optString("tag_name").ifEmpty { obj.optString("name") }
        val notes = obj.optString("body").trim()
        var apkUrl = ""
        var size = 0L
        obj.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
        }
        // Defence-in-depth: only ever install over TLS.
        if (!apkUrl.startsWith("https://")) return null

        // The rolling "dev" prerelease (published by CI on every push to main) carries a non-numeric tag,
        // so the version compare can't rank it. Offer it whenever its commit differs from this build's —
        // that's what makes Beta updates track each new CI build instead of never firing.
        val isRolling = tag.equals("dev", ignoreCase = true) || parts(tag).isEmpty()
        if (isRolling) {
            val localSha = BuildConfig.GIT_SHA
            val remoteSha = Regex("commit\\s+([0-9a-fA-F]{7,40})").find(notes)?.groupValues?.get(1)
            // Can't tell them apart (source build, or no commit in the notes) → don't nag.
            if (localSha == "unknown" || remoteSha == null) return null
            val same = remoteSha.startsWith(localSha, true) || localSha.startsWith(remoteSha, true)
            if (same) return null
            return UpdateInfo("dev · ${remoteSha.take(7)}", notes, apkUrl, size)
        }

        if (!isNewer(tag, BuildConfig.VERSION_NAME)) return null
        return UpdateInfo(tag.trimStart('v', 'V'), notes, apkUrl, size)
    }

    /** Stream the APK into cache, reporting 0f..1f progress. */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            val conn = open(info.apkUrl)
            val total = if (info.sizeBytes > 0) info.sizeBytes else conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var n: Int
                    var lastPct = -1
                    while (input.read(buf).also { n = it } != -1) {
                        ensureActive() // stay cancellable through a long download
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct / 100f) }
                            }
                        }
                    }
                }
            }
            out
        }.rethrowCancellation()
    }

    /** Launch the system installer for a downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Keep structured concurrency intact: never bury a cancellation inside a Result. */
    private fun <T> Result<T>.rethrowCancellation(): Result<T> =
        onFailure { if (it is CancellationException) throw it }

    private fun httpGet(url: String): String =
        open(url, "application/vnd.github+json").inputStream.bufferedReader().use { it.readText() }

    private fun open(url: String, accept: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "apple-music-tv")
            if (accept != null) setRequestProperty("Accept", accept)
        }

    /** Dotted-version integer parts after dropping a leading "v"; empty for a non-numeric tag like "dev". */
    private fun parts(s: String) = s.trim().trimStart('v', 'V')
        .split('.', '-', '+', '_').mapNotNull { it.toIntOrNull() }

    /** Numeric dotted-version compare after dropping a leading "v"; "1.1" > "1.0.0". */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
