package com.applemusicktv.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class WebPlaybackResult(
    val adamId:   String,
    val hlsUrl:   String,
    val keyUri:   String,
)

@Singleton
class AppleDirectClient @Inject constructor() {

    private val http = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                .build())
        }
        .build()

    private var cachedBearer: String = ""

    suspend fun getBearer(): String = withContext(Dispatchers.IO) {
        if (cachedBearer.isNotEmpty()) return@withContext cachedBearer
        try {
            val html = http.newCall(Request.Builder().url("https://music.apple.com/").build())
                .execute().body!!.string()
            val scriptPath = Regex("""crossorigin src="(/assets/index[^"]+\.js)"""")
                .find(html)?.groupValues?.get(1) ?: return@withContext ""
            val js = http.newCall(Request.Builder().url("https://music.apple.com$scriptPath").build())
                .execute().body!!.string()
            val token = Regex("""(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)""")
                .find(js)?.value ?: return@withContext ""
            cachedBearer = token
            token
        } catch (e: Exception) {
            Log.e("AppleDirectClient", "Bearer scrape failed: ${e.message}")
            ""
        }
    }

    fun clearBearerCache() { cachedBearer = "" }

    suspend fun getWebPlayback(songId: String, bearer: String, mut: String): WebPlaybackResult =
        withContext(Dispatchers.IO) {
            // Apple's webPlayback wants a library song under "universalLibraryId"
            // and a catalog song under "salableAdamId". Sending the wrong form
            // returns failureType 1010 (NoSalableAdamId). We can't always tell
            // which a given id is, so try the natural form first, then the other.
            val isLibrary = songId.startsWith("i.")
            val forms = if (isLibrary)
                listOf("universalLibraryId", "salableAdamId")
            else
                listOf("salableAdamId", "universalLibraryId")

            var entry: JSONObject? = null
            var lastBody = ""
            for (field in forms) {
                val bodyStr = """{"$field":"$songId","language":"en-US"}"""
                val resp = http.newCall(
                    Request.Builder()
                        .url("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback")
                        .post(bodyStr.toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", "Bearer $bearer")
                        .addHeader("Cookie",        "media-user-token=$mut")
                        .addHeader("Origin",        "https://music.apple.com")
                        .build()
                ).execute()
                lastBody = resp.body!!.string()
                Log.d("AppleDirectClient", "webPlayback[$field] http=${resp.code} bearerLen=${bearer.length} body=${lastBody.take(200)}")
                val json = JSONObject(lastBody)
                if (json.has("songList") && json.getJSONArray("songList").length() > 0) {
                    entry = json.getJSONArray("songList").getJSONObject(0)
                    break
                }
            }
            if (entry == null) error("webPlayback rejected both forms: ${lastBody.take(200)}")
            val adamId = entry.getString("songId")
            val assets = entry.getJSONArray("assets")

            // Prefer ctrp256, fall back to any ctrp
            var assetUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val flavor = a.optString("flavor")
                if (flavor == "28:ctrp256") { assetUrl = a.getString("URL"); break }
                if (flavor.contains("ctrp") && assetUrl.isEmpty()) assetUrl = a.getString("URL")
            }
            if (assetUrl.isEmpty()) error("No CENC stream asset for $songId")

            // Resolve to media playlist and extract keyUri
            val (mediaUrl, hlsText) = resolveMediaPlaylist(assetUrl, bearer, mut)
            val keyUri = Regex("""URI="(data:[^"]+)"""").find(hlsText)?.groupValues?.get(1)
                ?: error("No key URI in HLS manifest")

            WebPlaybackResult(adamId = adamId, hlsUrl = mediaUrl, keyUri = keyUri)
        }

    private fun resolveMediaPlaylist(url: String, bearer: String, mut: String): Pair<String, String> {
        val headers = mapOf(
            "Authorization" to "Bearer $bearer",
            "Cookie"        to "media-user-token=$mut",
        )
        val text = fetchText(url, headers)
        if (!text.contains("#EXT-X-STREAM-INF")) return url to text

        // Master playlist → pick highest bandwidth
        val lines = text.lines()
        var bestBw = -1; var bestUrl = ""
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val next = lines.getOrNull(i + 1)?.trim() ?: continue
                if (next.isNotEmpty() && !next.startsWith("#") && bw >= bestBw) {
                    bestBw = bw
                    bestUrl = if (next.startsWith("http")) next
                              else url.substring(0, url.lastIndexOf('/') + 1) + next
                }
            }
        }
        if (bestUrl.isEmpty()) error("No variant in master playlist")
        return bestUrl to fetchText(bestUrl, headers)
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        return http.newCall(req).execute().body!!.string()
    }
}
