package com.applemusicktv.data.datasource

import android.util.Log
import com.applemusicktv.BuildConfig
import com.applemusicktv.data.network.LyricBackground
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.network.LyricWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standalone (no-PC) Spicy Lyrics source — the on-device mirror of server/src/lib/spicy.ts.
 *
 * Resolves the song to a Spotify id via Spotify's ANONYMOUS web-player search (a token scraped from
 * the public embed page — no login), then asks Spicy Lyrics for that id's word-synced lyrics. Uses a
 * **client** key (sl_pk_…, shippable) injected from local.properties; the server uses the secret key.
 *
 * Everything rides Spotify's undocumented web contract — fails SOFT on any change so the caller falls
 * through to Apple/lrclib. Uses its OWN OkHttpClient: the @Named("direct") client force-attaches
 * Apple Authorization/Media-User-Token to every request, which must NOT reach Spotify or Spicy.
 */
data class SpicyResult(val lines: List<LyricLine>, val synced: Boolean, val writtenBy: List<String>)

@Singleton
class SpicyLyricsSource @Inject constructor() {
    private val key: String = BuildConfig.SPICY_LYRICS_KEY.trim()
    fun enabled(): Boolean = key.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Mirror Spotify-search + Spicy-lyrics calls into the shared NetworkLog so they show up in the
        // :8080 page and the bug report (this client has no proxy interceptor of its own).
        .addInterceptor { chain ->
            val t0 = System.currentTimeMillis()
            val resp = chain.proceed(chain.request())
            com.applemusicktv.data.NetworkLog.add(
                chain.request().method, chain.request().url.toString(), resp.code,
                System.currentTimeMillis() - t0,
            )
            resp
        }
        .build()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L

    suspend fun fetch(title: String, artist: String, album: String, durationSec: Long): SpicyResult? =
        withContext(Dispatchers.IO) {
            if (!enabled() || title.isBlank() || artist.isBlank()) return@withContext null
            val ids = try { resolveSpotifyIds(title, artist, album, durationSec) }
                catch (e: Exception) { Log.i("SpicyLyrics", "resolve failed: ${e.message}"); return@withContext null }
            if (ids.isEmpty()) return@withContext null

            var plain: SpicyResult? = null
            for (id in ids) {
                val hit = try { fetchId(id) } catch (e: Exception) { Log.i("SpicyLyrics", "fetch $id failed: ${e.message}"); continue } ?: continue
                val timed = hit.lines.any { it.startMs >= 0 }
                val wordSynced = hit.lines.any { it.words.isNotEmpty() }
                if (wordSynced || timed) {
                    Log.i("SpicyLyrics", "HIT $id synced lines=${hit.lines.size} words=${hit.lines.sumOf { it.words.size }}")
                    return@withContext hit.copy(synced = true)
                }
                if (plain == null) plain = hit
            }
            plain
        }

    // ── Anonymous Spotify session ────────────────────────────────────────────
    private fun spotifyToken(force: Boolean): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (!force && tokenExpiresAtMs - 60_000 > now) return it }
        val req = Request.Builder()
            .url("https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: throw IllegalStateException("empty embed body")
            val m = Regex("""<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
                ?: throw IllegalStateException("no __NEXT_DATA__")
            val session = JSONObject(m.groupValues[1])
                .getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
                .getJSONObject("settings").getJSONObject("session")
            val token = session.optString("accessToken").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("no accessToken")
            cachedToken = token
            tokenExpiresAtMs = session.optLong("accessTokenExpirationTimestampMs", now + 30 * 60_000)
            return token
        }
    }

    private data class Cand(val id: String, val title: String, val artists: List<String>, val durationMs: Long)

    private fun spotifySearch(query: String): List<Cand> {
        val run = { token: String ->
            val variables = JSONObject(mapOf(
                "searchTerm" to query, "offset" to 0, "limit" to 20, "numberOfTopResults" to 5,
                "includeAudiobooks" to false, "includePreReleases" to true, "includeAlbumPreReleases" to false,
                "includeAuthors" to false, "includeEpisodeContentRatingsV2" to false,
            )).toString()
            val extensions = JSONObject(mapOf(
                "persistedQuery" to mapOf("version" to 1,
                    "sha256Hash" to "eff59fa0a3d026b88b56fddbcf4bdfa16a186b8175a5c1a358c072e053c2e5b0"),
            )).toString()
            val url = "https://api-partner.spotify.com/pathfinder/v1/query".toHttpUrl()
                .newBuilder()
                .addQueryParameter("operationName", "searchDesktop")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions)
                .build()
            client.newCall(Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("app-platform", "WebPlayer").build()).execute()
        }
        var token = spotifyToken(false)
        var resp = run(token)
        if (resp.code == 401) { resp.close(); token = spotifyToken(true); resp = run(token) }
        resp.use {
            if (it.code != 200) throw IllegalStateException("search HTTP ${it.code}")
            val root = JSONObject(it.body?.string() ?: "{}")
            root.optJSONArray("errors")?.let { errs ->
                for (i in 0 until errs.length())
                    if (errs.optJSONObject(i)?.optString("message") == "PersistedQueryNotFound")
                        throw IllegalStateException("Spotify rotated the persisted query")
            }
            val items = root.optJSONObject("data")?.optJSONObject("searchV2")
                ?.optJSONObject("tracksV2")?.optJSONArray("items")
                ?: throw IllegalStateException("search shape changed")
            val out = ArrayList<Cand>()
            for (i in 0 until items.length()) {
                val d = items.optJSONObject(i)?.optJSONObject("item")?.optJSONObject("data") ?: continue
                val id = d.optString("id").takeIf { s -> s.isNotBlank() }
                    ?: d.optString("uri").substringAfterLast(':').takeIf { s -> s.isNotBlank() } ?: continue
                val titleV = d.optString("name").takeIf { s -> s.isNotBlank() } ?: continue
                val durMs = d.optJSONObject("duration")?.optLong("totalMilliseconds", -1) ?: -1
                if (durMs < 0) continue
                val artistsArr = d.optJSONObject("artists")?.optJSONArray("items")
                val artists = ArrayList<String>()
                if (artistsArr != null) for (j in 0 until artistsArr.length())
                    artistsArr.optJSONObject(j)?.optJSONObject("profile")?.optString("name")
                        ?.takeIf { s -> s.isNotBlank() }?.let(artists::add)
                if (artists.isEmpty()) continue
                out.add(Cand(id, titleV, artists, durMs))
            }
            return out
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun resolveSpotifyIds(title: String, artist: String, album: String, durationSec: Long): List<String> {
        val wantTitle = norm(title); val wantArtist = norm(artist)
        fun delta(c: Cand) = if (durationSec <= 0) 0.0 else kotlin.math.abs(c.durationMs / 1000.0 - durationSec)
        fun isMatch(c: Cand): Boolean {
            val t = norm(c.title)
            if (!(t == wantTitle || t.contains(wantTitle) || wantTitle.contains(t))) return false
            if (c.artists.none { val n = norm(it); n.isNotEmpty() && (n.contains(wantArtist) || wantArtist.contains(n)) }) return false
            if (durationSec > 0 && delta(c) > 6) return false
            return true
        }
        val queries = buildList { add("$title $artist".trim()); if (album.isNotBlank()) add("$title $album".trim()) }
        val seen = LinkedHashMap<String, Cand>()
        for ((qi, q) in queries.withIndex()) {
            val cands = try { spotifySearch(q) } catch (e: Exception) { if (qi == 0) throw e else continue }
            for (c in cands) seen.putIfAbsent(c.id, c)
            if (qi == 0 && seen.values.any { isMatch(it) }) break
        }
        return seen.values.filter { isMatch(it) }.sortedBy { delta(it) }.map { it.id }.distinct().take(3)
    }

    // ── Spicy fetch + parse ──────────────────────────────────────────────────
    private fun fetchId(spotifyId: String): SpicyResult? {
        val req = Request.Builder()
            .url("https://api.spicylyrics.org/v1/lyrics/$spotifyId")
            .header("Authorization", "Bearer $key").build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (resp.code != 200) throw IllegalStateException("Spicy HTTP ${resp.code}")
            return parse(JSONObject(resp.body?.string() ?: "{}"))
        }
    }

    /** Merge syllables into words. `IsPartOfWord` on a syllable means it joins the NEXT one. */
    private fun mergeSyllables(arr: JSONArray?): List<LyricWord> {
        val words = ArrayList<LyricWord>()
        if (arr == null) return words
        var curText = StringBuilder(); var curStart = -1L; var curEnd = 0L; var open = false
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val text = if (s.has("Text")) s.optString("Text") else continue
            if (!s.has("StartTime") || !s.has("EndTime")) continue
            val start = (s.optDouble("StartTime") * 1000).toLong()
            val end = (s.optDouble("EndTime") * 1000).toLong()
            if (!open) { curText = StringBuilder(text); curStart = start; curEnd = end; open = true }
            else { curText.append(text); curEnd = end }
            if (!s.optBoolean("IsPartOfWord", false)) {
                val t = curText.toString().trim()
                if (t.isNotEmpty()) words.add(LyricWord(curStart, curEnd, t))
                open = false
            }
        }
        if (open) { val t = curText.toString().trim(); if (t.isNotEmpty()) words.add(LyricWord(curStart, curEnd, t)) }
        return words
    }

    private fun parse(root: JSONObject): SpicyResult? {
        if (root.optInt("Status", -1) != 200) return null
        val body = root.optJSONObject("Body") ?: return null
        val writers = ArrayList<String>()
        body.optJSONArray("SongWriters")?.let { for (i in 0 until it.length()) it.optString(i).trim().takeIf { s -> s.isNotEmpty() }?.let(writers::add) }

        val lines = ArrayList<LyricLine>()
        val content = body.optJSONArray("Content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                if (item.optString("Type").equals("Interlude", true)) continue
                val lead = item.optJSONObject("Lead")
                if (lead == null) {
                    val text = item.optString("Text").trim()
                    if (text.isEmpty() || !item.has("StartTime")) continue
                    val start = (item.optDouble("StartTime") * 1000).toLong()
                    val end = (item.optDouble("EndTime", item.optDouble("StartTime")) * 1000).toLong()
                    lines.add(LyricLine(start, end, text, emptyList(), null)); continue
                }
                val words = mergeSyllables(lead.optJSONArray("Syllables"))
                val bgWords = ArrayList<LyricWord>()
                item.optJSONArray("Background")?.let { bg -> for (j in 0 until bg.length())
                    bgWords.addAll(mergeSyllables(bg.optJSONObject(j)?.optJSONArray("Syllables"))) }
                if (words.isEmpty() && bgWords.isEmpty()) continue
                val all = words + bgWords
                val start = if (lead.has("StartTime")) (lead.optDouble("StartTime") * 1000).toLong() else all.minOf { it.startMs }
                val end = if (lead.has("EndTime")) (lead.optDouble("EndTime") * 1000).toLong() else all.maxOf { it.endMs }
                val background = if (bgWords.isNotEmpty()) LyricBackground(
                    bgWords.minOf { it.startMs }, bgWords.maxOf { it.endMs },
                    bgWords.joinToString(" ") { it.text }, bgWords) else null
                lines.add(LyricLine(start, end,
                    words.joinToString(" ") { it.text }.ifEmpty { background?.text ?: "" }, words, background))
            }
        }
        if (lines.isEmpty()) body.optJSONArray("Lines")?.let {
            for (i in 0 until it.length()) it.optJSONObject(i)?.optString("Text")?.trim()
                ?.takeIf { s -> s.isNotEmpty() }?.let { t -> lines.add(LyricLine(-1, -1, t, emptyList(), null)) }
        }
        return if (lines.isNotEmpty()) SpicyResult(lines, lines.any { it.startMs >= 0 }, writers) else null
    }
}
