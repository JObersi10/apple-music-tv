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

    suspend fun getLyrics(
        songId: String,
        storefront: String,
        title: String = "",
        artist: String = "",
        durationSec: Long = 0,
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        val bearer = appleClient.getBearer()
        val mut = mutPrefs.getMUT()
        if (bearer.isEmpty() || mut.isEmpty()) return@withContext emptyList()

        val headers = mapOf(
            "Authorization" to "Bearer $bearer",
            "Music-User-Token" to mut,
            "Origin" to "https://music.apple.com",
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
        )

        // 1. Try Apple TTML (syllable-lyrics then lyrics)
        val isLibrary = songId.startsWith("i.")
        val sf = storefront.ifEmpty { "us" }

        val ttmlLines = tryAppleTtml(songId, sf, isLibrary, headers)
        if (ttmlLines.isNotEmpty()) return@withContext ttmlLines

        // 2. lrclib fallback
        if (title.isNotEmpty() && artist.isNotEmpty()) {
            val lrc = tryLrclib(title, artist, durationSec)
            if (lrc.isNotEmpty()) return@withContext lrc
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
        for (base in bases) {
            for (suffix in listOf("syllable-lyrics", "lyrics")) {
                try {
                    val req = Request.Builder().url("$base/$suffix").apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                    }.build()
                    val body = httpClient.newCall(req).execute().body?.string() ?: continue
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
        try {
            val params = buildString {
                append("track_name=").append(java.net.URLEncoder.encode(title, "UTF-8"))
                append("&artist_name=").append(java.net.URLEncoder.encode(artist, "UTF-8"))
                if (durationSec > 0) append("&duration=$durationSec")
            }
            val req = Request.Builder()
                .url("https://lrclib.net/api/get?$params")
                .addHeader("User-Agent", "AppleMusicTV (github.com/applemusicktv)")
                .build()
            val body = httpClient.newCall(req).execute().body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: return emptyList()
            return parseLrc(synced)
        } catch (_: Exception) { return emptyList() }
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
                    spanToWord(span)?.let { words.add(it) }
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
