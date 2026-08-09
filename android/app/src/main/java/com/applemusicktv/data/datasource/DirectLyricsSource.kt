package com.applemusicktv.data.datasource

import android.util.Log
import com.applemusicktv.data.network.LyricBackground
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.network.LyricWord
import com.applemusicktv.media.AppleDirectClient
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.network.DirectAppleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Named

@Singleton
class DirectLyricsSource @Inject constructor(
    private val appleClient: AppleDirectClient,
    private val mutPrefs: MutPreferences,
    @Named("direct") private val httpClient: OkHttpClient,
) {

    // Prefetched / previously-resolved lyrics, keyed by songId. Lets the N+1
    // prefetch warm the next song's lyrics so they appear instantly on switch.
    // Each entry carries the fetch time and expires after 24h so stale lyrics
    // (corrections, re-syncs) get re-pulled.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<LyricLine>>>()
    private val cacheTtlMs = 24L * 60 * 60 * 1000
    private fun cached(songId: String): List<LyricLine>? {
        val (ts, lines) = cache[songId] ?: return null
        if (System.currentTimeMillis() - ts > cacheTtlMs) { cache.remove(songId); return null }
        return lines
    }
    private fun store(songId: String, lines: List<LyricLine>) { cache[songId] = System.currentTimeMillis() to lines }

    suspend fun getLyrics(
        songId: String,
        storefront: String,
        title: String = "",
        artist: String = "",
        durationSec: Long = 0,
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        cached(songId)?.let { return@withContext it }
        val bearer = appleClient.getBearer()
        val mut = mutPrefs.getMUT()
        Log.i("DirectLyrics", "getLyrics song=$songId sf=$storefront bearerLen=${bearer.length} mutLen=${mut.length} title='$title' artist='$artist'")
        if (bearer.isEmpty() || mut.isEmpty()) return@withContext emptyList()

        val headers = mapOf(
            "Authorization" to "Bearer $bearer",
            "Media-User-Token" to mut,
            "Origin" to "https://music.apple.com",
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
        )

        // 1. Try Apple TTML (syllable-lyrics then lyrics)
        val isLibrary = songId.startsWith("i.")
        val sf = storefront.ifEmpty { "us" }

        val ttmlLines = tryAppleTtml(songId, sf, isLibrary, headers)
        if (ttmlLines.isNotEmpty()) return@withContext ttmlLines.also { store(songId, it) }

        // 2. lrclib fallback
        if (title.isNotEmpty() && artist.isNotEmpty()) {
            val lrc = tryLrclib(title, artist, durationSec)
            if (lrc.isNotEmpty()) return@withContext lrc.also { store(songId, it) }
        }

        emptyList()
    }

    private fun tryAppleTtml(
        songId: String,
        sf: String,
        isLibrary: Boolean,
        headers: Map<String, String>,
    ): List<LyricLine> {
        val bases = buildList {
            if (isLibrary) add("https://amp-api-edge.music.apple.com/v1/me/library/songs/$songId")
            add("https://amp-api-edge.music.apple.com/v1/catalog/$sf/songs/$songId")
        }
        // Suffix-major, base-minor: exhaust the word-timed syllable-lyrics across EVERY
        // endpoint before ever settling for line-only "lyrics". A library song whose
        // library relationship only carries line lyrics used to short-circuit here and
        // never reach the catalog's syllable-lyrics — so word-by-word silently vanished.
        for (suffix in listOf("syllable-lyrics", "lyrics")) {
            for (base in bases) {
                try {
                    // NOTE: the @Named("direct") client's interceptor already attaches
                    // Authorization/Media-User-Token/Origin/User-Agent. Adding them here
                    // too produced a DUPLICATE Authorization header → Apple edge 400s.
                    val req = Request.Builder().url("$base/$suffix").build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string() ?: continue
                    Log.i("DirectLyrics", "TTML $suffix http=${resp.code} base=${base.substringAfterLast('/')} body=${body.take(160)}")
                    val ttml = JSONObject(body).optJSONArray("data")
                        ?.optJSONObject(0)?.optJSONObject("attributes")?.optString("ttml") ?: continue
                    if (ttml.isBlank()) continue
                    val lines = parseTtml(ttml)
                    if (lines.isNotEmpty()) {
                        Log.d("DirectLyrics", "Apple $suffix: ${lines.size} lines for $songId")
                        return lines
                    }
                } catch (_: Exception) {}
            }
        }
        return emptyList()
    }

    private fun tryLrclib(title: String, artist: String, durationSec: Long): List<LyricLine> {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        // 1. exact /api/get with duration, 2. /api/get without duration (dur mismatch 404s)
        val getUrls = buildList {
            if (durationSec > 0)
                add("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}&duration=$durationSec")
            add("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}")
        }
        for (url in getUrls) {
            try {
                val resp = httpClient.newCall(
                    Request.Builder().url(url).addHeader("User-Agent", "AppleMusicTV (github.com/applemusicktv)").build()
                ).execute()
                val body = resp.body?.string() ?: continue
                if (resp.code != 200) { Log.i("DirectLyrics", "lrclib get http=${resp.code}"); continue }
                val synced = JSONObject(body).optString("syncedLyrics").takeIf { it.isNotBlank() }
                Log.i("DirectLyrics", "lrclib get http=${resp.code} synced=${synced != null} title='$title'")
                if (synced != null) return parseLrc(synced)
            } catch (_: Exception) {}
        }
        // 3. fuzzy /api/search — pick first hit with synced lyrics
        try {
            val resp = httpClient.newCall(
                Request.Builder()
                    .url("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
                    .addHeader("User-Agent", "AppleMusicTV (github.com/applemusicktv)").build()
            ).execute()
            val body = resp.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val synced = arr.optJSONObject(i)?.optString("syncedLyrics")?.takeIf { it.isNotBlank() } ?: continue
                Log.i("DirectLyrics", "lrclib search hit=$i title='$title'")
                return parseLrc(synced)
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    // ── TTML parser ───────────────────────────────────────────────────────

    private data class Node(val tag: String, val attrs: String, val children: MutableList<Any> = mutableListOf())

    private fun parseTtml(ttml: String): List<LyricLine> {
        val tree = buildTree(tokenize(ttml))
        val pNodes = findAll(tree, "p")
        val lines = mutableListOf<LyricLine>()
        for (p in pNodes) {
            val begin = attr(p.attrs, "begin") ?: continue
            val startMs = parseTime(begin)
            val endMs = attr(p.attrs, "end")?.let { parseTime(it) } ?: (startMs + 5000)
            val words = mutableListOf<LyricWord>()
            var background: LyricBackground? = null
            for (span in childSpans(p)) {
                if (isBgSpan(span.attrs)) {
                    val bgWords = childSpans(span).mapNotNull { spanToWord(it) }.toMutableList()
                    if (bgWords.isEmpty()) spanToWord(span)?.let { bgWords.add(it) }
                    if (bgWords.isNotEmpty()) {
                        val bgBegin = attr(span.attrs, "begin")?.let { parseTime(it) } ?: bgWords.first().startMs
                        val bgEnd = attr(span.attrs, "end")?.let { parseTime(it) } ?: bgWords.last().endMs
                        background = LyricBackground(bgBegin, bgEnd, bgWords.joinToString(" ") { it.text.trim() }, bgWords)
                    }
                } else {
                    // Word-timed TTML nests differently per song: sometimes each word is a
                    // direct <span> under <p>, sometimes they're wrapped in a line-level
                    // <span>. Recurse to the LEAF timed spans so we always get per-word
                    // timing instead of collapsing the whole line into one "word".
                    val leaves = mutableListOf<Node>()
                    collectLeafSpans(span, leaves)
                    if (leaves.isEmpty()) spanToWord(span)?.let { words.add(it) }
                    else leaves.forEach { s -> spanToWord(s)?.let { words.add(it) } }
                }
            }
            val text = if (words.isNotEmpty()) words.joinToString(" ") { it.text.trim() } else flatText(p)
            if (text.isBlank()) continue
            lines.add(LyricLine(startMs, endMs, text, words, background))
        }
        return lines
    }

    private fun tokenize(xml: String): List<Any> {
        val tokens = mutableListOf<Any>()
        val re = Regex("""<(/?)([a-zA-Z0-9:_\-]+)([^<>]*?)(/?)\s*>|([^<]+)""")
        for (m in re.findAll(xml)) {
            val (closing, tag, attrs, selfClose, text) = m.destructured
            when {
                text.isNotEmpty() -> tokens.add(text)
                closing == "/" -> tokens.add(Triple("close", tag, ""))
                else -> tokens.add(Triple(if (selfClose == "/" || attrs.trimEnd().endsWith("/")) "self" else "open", tag, attrs))
            }
        }
        return tokens
    }

    private fun buildTree(tokens: List<Any>): Node {
        val root = Node("root", "")
        val stack = ArrayDeque<Node>().also { it.addLast(root) }
        for (t in tokens) {
            val top = stack.last()
            when (t) {
                is String -> top.children.add(t)
                is Triple<*, *, *> -> {
                    val (type, tag, attrs) = t as Triple<String, String, String>
                    when (type) {
                        "open" -> { val n = Node(tag, attrs); top.children.add(n); stack.addLast(n) }
                        "self" -> top.children.add(Node(tag, attrs))
                        "close" -> { val i = stack.indexOfLast { it.tag == tag }; if (i > 0) repeat(stack.size - i) { stack.removeLast() } }
                    }
                }
            }
        }
        return root
    }

    private fun findAll(node: Node, tag: String, out: MutableList<Node> = mutableListOf()): List<Node> {
        for (c in node.children) { if (c is Node) { if (c.tag == tag) out.add(c); findAll(c, tag, out) } }
        return out
    }

    private fun attr(attrs: String, name: String) =
        Regex("""(?:^|[\s:])$name="([^"]+)"""").find(attrs)?.groupValues?.get(1)

    private fun flatText(node: Node): String =
        node.children.joinToString("") { if (it is String) it else flatText(it as Node) }.trim()

    private fun childSpans(node: Node) =
        node.children.filterIsInstance<Node>().filter { it.tag == "span" || it.tag.endsWith(":span") }

    // Descend to the innermost timed spans (one per word). A span with no child spans is a
    // leaf word; otherwise recurse into its children.
    private fun collectLeafSpans(node: Node, out: MutableList<Node>) {
        for (span in childSpans(node)) {
            val inner = childSpans(span)
            if (inner.isEmpty()) out.add(span) else collectLeafSpans(span, out)
        }
    }

    private fun isBgSpan(attrs: String) =
        attrs.contains("""ttm:role="x-bg"""") || attrs.contains("""role="x-bg"""")

    private fun spanToWord(span: Node): LyricWord? {
        val begin = attr(span.attrs, "begin") ?: return null
        val text = flatText(span).trim()
        if (text.isEmpty()) return null
        val end = attr(span.attrs, "end")?.let { parseTime(it) } ?: (parseTime(begin) + 500)
        return LyricWord(parseTime(begin), end, text)
    }

    private fun parseTime(t: String): Long {
        val clean = t.trimEnd('s')
        val parts = clean.split(":").map { it.toDoubleOrNull() ?: 0.0 }
        return when (parts.size) {
            3 -> ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
            2 -> ((parts[0] * 60 + parts[1]) * 1000).toLong()
            else -> (parts[0] * 1000).toLong()
        }
    }

    // ── LRC parser ────────────────────────────────────────────────────────

    private fun parseLrc(lrc: String): List<LyricLine> {
        val tagRe = Regex("""\[(\d{1,2}):(\d{2})(?:[.:]([\d]{1,3}))?\]""")
        val out = mutableListOf<Pair<Long, String>>()
        for (raw in lrc.lines()) {
            val text = raw.replace(tagRe, "").trim()
            if (text.isEmpty()) continue
            for (m in tagRe.findAll(raw)) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                out.add((min * 60000 + sec * 1000 + frac) to text)
            }
        }
        out.sortBy { it.first }
        return out.mapIndexed { i, (startMs, text) ->
            val endMs = if (i + 1 < out.size) out[i + 1].first else startMs + 5000
            LyricLine(startMs, endMs, text, emptyList(), null)
        }
    }
}
