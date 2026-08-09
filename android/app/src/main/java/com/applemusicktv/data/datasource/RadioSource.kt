package com.applemusicktv.data.datasource

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A tunable internet-radio station (non-DRM plain stream). */
data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val faviconUrl: String?,
    val country: String,
    val tags: String,
    val bitrate: Int,
)

/**
 * Free, keyless internet-radio directory (radio-browser.info). Uses its OWN plain
 * HTTP client — never the Apple `direct` client — so no Apple token leaks to a third
 * party. Stream URLs are plain MP3/AAC/HLS that ExoPlayer plays with no decrypt.
 */
@Singleton
class RadioSource @Inject constructor() {

    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val base = "https://de1.api.radio-browser.info/json"
    private val ua = "AppleMusicTV/1.0 (Fire TV internet radio)"

    suspend fun top(limit: Int = 80): List<RadioStation> =
        fetch("$base/stations/topclick/$limit")

    suspend fun byCountry(code: String): List<RadioStation> =
        fetch("$base/stations/bycountrycodeexact/${code.uppercase()}?order=clickcount&reverse=true&limit=150")

    /** radio-browser searches by full country NAME here (e.g. "Netherlands", "Curaçao"). */
    suspend fun byCountryName(name: String): List<RadioStation> =
        fetch("$base/stations/bycountryexact/${enc(name)}?order=clickcount&reverse=true&limit=150")

    /** Full country directory (name → ISO code) for spell-correction of typed names. */
    suspend fun countryList(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(Request.Builder().url("$base/countries").header("User-Agent", ua).build()).execute()
            val arr = JSONArray(resp.body?.string() ?: return@withContext emptyList())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                name to o.optString("iso_3166_1")
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Best-effort geolocate by IP → ISO country code + name. Null on failure. */
    suspend fun detectCountry(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(Request.Builder()
                .url("https://ipapi.co/json/").header("User-Agent", ua).build()).execute()
            val o = org.json.JSONObject(resp.body?.string() ?: return@withContext null)
            val code = o.optString("country_code").takeIf { it.isNotBlank() } ?: return@withContext null
            val name = o.optString("country_name").takeIf { it.isNotBlank() } ?: code
            code to name
        } catch (e: Exception) { Log.w("RadioSource", "geo failed: ${e.message}"); null }
    }

    suspend fun search(query: String): List<RadioStation> =
        fetch("$base/stations/search?name=${enc(query)}&order=clickcount&reverse=true&limit=60&hidebroken=true")

    private suspend fun fetch(url: String): List<RadioStation> = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val out = ArrayList<RadioStation>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val stream = o.optString("url_resolved").ifEmpty { o.optString("url") }
                if (stream.isBlank()) continue
                out.add(RadioStation(
                    id = o.optString("stationuuid", stream),
                    name = o.optString("name").trim().ifEmpty { "Unknown" },
                    streamUrl = stream,
                    faviconUrl = o.optString("favicon").takeIf { it.isNotBlank() },
                    country = o.optString("countrycode"),
                    tags = o.optString("tags"),
                    bitrate = o.optInt("bitrate"),
                ))
            }
            out
        } catch (e: Exception) {
            Log.w("RadioSource", "fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
