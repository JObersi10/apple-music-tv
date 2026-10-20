package com.applemusicktv.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.network.LyricsCreditDto
import com.applemusicktv.lyrics.spicy.RenderConfig
import com.applemusicktv.lyrics.spicy.canvas.LyricsView
import com.applemusicktv.lyrics.spicy.models.Line as SLine
import com.applemusicktv.lyrics.spicy.models.LineRole
import com.applemusicktv.lyrics.spicy.models.LyricsCredit
import com.applemusicktv.lyrics.spicy.models.LyricsFooter
import com.applemusicktv.lyrics.spicy.models.LyricsProvenance
import com.applemusicktv.lyrics.spicy.models.LyricsType
import com.applemusicktv.lyrics.spicy.models.Word as SWord

/**
 * "Lyrics Renderer v2" — hosts the ported Spicy Player Canvas renderer ([LyricsView]) in place of our
 * Compose [LyricsPanel]. Adapts our [LyricLine] model (from the Spicy/Apple fetch) into the renderer's
 * Line/Word model; the renderer handles letter synthesis, spring motion, gradient wipe, scrolling, and
 * the pinned "Written by" footer itself.
 *
 * Renderer ported from TheX24/spicy-player (AGPL-3.0); see README attribution.
 */
@Composable
fun SpicyLyricsPanel(
    lyrics: List<LyricLine>,
    credits: List<String>,
    progressState: State<Long>,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    isPlaying: Boolean = true,
    richMotion: Boolean = true,
    origin: String? = null,
    maker: LyricsCreditDto? = null,
    uploader: LyricsCreditDto? = null,
    dpadFocus: androidx.compose.ui.focus.FocusRequester? = null,
    escapeFocus: androidx.compose.ui.focus.FocusRequester? = null,
    romanize: Boolean = false,
    translations: List<String> = emptyList(),
    translateNonLatinOnly: Boolean = false,
    /** Flips when the downloadable Japanese pronunciation pack finishes, so romanization re-runs and
     *  kanji readings appear without needing a song change. */
    romanizeReloadKey: Any = Unit,
    /** Song length (ms); when >0 and the lyrics are unsynced, they auto-scroll in time with the song. */
    durationMs: Long = 0L,
) {
    // Pronunciation: romanize the lyrics off the main thread (dictionary-backed Japanese etc. is heavy),
    // keyed on the lyrics + the toggle. Null until computed / when off. Applied per-word below so the
    // renderer can draw romaji/pinyin under the original text.
    val romanizedData by androidx.compose.runtime.produceState<List<List<String?>>?>(null, lyrics, romanize, romanizeReloadKey) {
        value = if (!romanize || lyrics.isEmpty()) null
            else runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.applemusicktv.lyrics.spicy.romanization.RomanizationService.romanize(leadWordTexts(lyrics))
                }
            }.getOrNull()
    }
    val built = remember(lyrics, romanizedData, translations, translateNonLatinOnly) { buildSpicyDocument(lyrics, romanizedData, translations, translateNonLatinOnly) }
    val romanizeActive = romanize && built.lines.any { l -> l.words.any { it.romanizedText != null } }
    // Footer under the lyrics: "Written by …", "Provided by: <origin>", the community note, and the
    // "Made by @…" / "Uploaded by @…" credits with avatars — exactly like Spicy Player.
    val footer = remember(credits, origin, maker, uploader) {
        // When Show Credits is OFF the callers null EVERYTHING (credits/origin/maker/uploader). In that
        // case emit a bare empty footer — NOT one carrying a provenance — or the hardcoded provider line
        // ("Provided by: Spicy Lyrics") still renders. Only attach provenance when there's real credit.
        val hasAny = credits.isNotEmpty() || origin != null || maker != null || uploader != null
        if (!hasAny) LyricsFooter()
        else LyricsFooter(
            songwriters = credits,
            provenance = LyricsProvenance(provider = "Spicy Lyrics", contributor = origin),
            maker = maker?.let { LyricsCredit(it.name, it.url, it.avatar) },
            uploader = uploader?.let { LyricsCredit(it.name, it.url, it.avatar) },
        )
    }
    val docId = remember(lyrics) {
        "${built.lines.size}:${lyrics.firstOrNull()?.startMs ?: 0}:${lyrics.firstOrNull()?.text?.hashCode() ?: 0}"
    }
    // Translation lands ~1s after the lyrics; fade the gloss in instead of popping it in. Reset on song
    // change (docId), then ease up once the translation lines actually exist in the document.
    val hasTrans = built.lines.any { it.isTranslation }
    val transReveal = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(docId, hasTrans) {
        if (!hasTrans) transReveal.snapTo(0f)
        else transReveal.animateTo(1f, androidx.compose.animation.core.tween(450))
    }
    LyricsView(
        translationAlpha = transReveal.value,
        lines = built.lines,
        documentId = docId,
        footer = footer,
        currentTimeMs = {
            val p = progressState.value
            // Before the track has actually started (position still 0 — loading, or paused at the top),
            // hold the lyric clock at -1s so the first word isn't pre-highlighted before it's sung.
            if (p <= 0L) -1000L else p + offsetMs
        },
        onSeekWord = onSeek,
        staticAutoScroll = {
            // Unsynced lyrics (Static) with a known duration → scroll fraction = elapsed fraction.
            if (built.type == LyricsType.Static && durationMs > 0L)
                ((progressState.value + offsetMs).toFloat() / durationMs).coerceIn(0f, 1f) else -1f
        },
        modifier = modifier.fillMaxSize(),
        // The renderer's base size scales with the (wide) TV canvas, so it reads large — trim it.
        fontSizeScale = fontScale * V2_FONT_SCALE,
        // Snap scroll (smooth spring was laggy + flashed). Glow + distance-blur are ON in full mode and
        // dropped together in Low Power / Reduce Motion (richMotion=false → RenderConfig.SIMPLE, which
        // zeroes glowSpline in LyricsAnimator, plus distanceBlurEnabled=false here kills the StackBlur —
        // the heaviest per-frame cost). So low-power on v2 is genuinely cheap.
        config = (if (richMotion) RenderConfig.FULL else RenderConfig.SIMPLE)
            .copy(distanceBlurEnabled = richMotion),
        lyricsType = built.type,
        romanize = romanizeActive,
        isPlaying = isPlaying,
        // Keep the active line a bit lower than the default 0.25 (more context above it).
        focusAnchorFraction = 0.4f,
        dpadFocus = dpadFocus,
        escapeFocus = escapeFocus,
    )
}

private const val V2_FONT_SCALE = 0.72f

private class SpicyDoc(val lines: List<SLine>, val type: LyricsType)

/**
 * The per-word text of each lead line, in the SAME order [buildSpicyDocument] emits lead words — so a
 * romanization computed from this list lines up index-for-index when fed back in. Background lines are
 * left out (romanization is for the lead vocal).
 */
private fun leadWordTexts(lyrics: List<LyricLine>): List<List<String>> {
    if (lyrics.isEmpty()) return emptyList()
    val allUnsynced = lyrics.all { it.startMs < 0 }
    return lyrics.map { ln ->
        when {
            allUnsynced -> tokenize(ln.text, 0L, 0L).map { it.text }
            ln.startMs < 0 -> emptyList()
            ln.words.isNotEmpty() -> ln.words.map { it.text }
            else -> tokenize(ln.text, ln.startMs, ln.endMs).map { it.text }
        }
    }
}

/** Our LyricLine list → Spicy renderer lines. Lead + background become sibling lines sharing a groupId. */
private fun buildSpicyDocument(lyrics: List<LyricLine>, romanized: List<List<String?>>? = null, translations: List<String> = emptyList(), nonLatinOnly: Boolean = false): SpicyDoc {
    if (lyrics.isEmpty()) return SpicyDoc(emptyList(), LyricsType.Static)
    fun rom(line: Int, word: Int): String? = romanized?.getOrNull(line)?.getOrNull(word)
    // A translation gloss for line i, only when it's real (non-blank, and not identical to the original —
    // skips instrumentals / untranslatable lines / lines already in the target language). Built as a
    // BACKGROUND-role sibling so it inherits the layout/scroll machinery, flagged isTranslation.
    // nonLatinOnly: only gloss lines that actually contain a non-Latin script (JP/CN/KR/…).
    fun transLine(i: Int, startMs: Long, endMs: Long, original: String): SLine? {
        if (nonLatinOnly && com.applemusicktv.lyrics.spicy.romanization.ScriptDetector.detect(original).isEmpty()) return null
        val t = translations.getOrNull(i)?.trim()?.takeIf { it.isNotBlank() && !it.equals(original.trim(), ignoreCase = true) } ?: return null
        return SLine(tokenize(t, startMs, endMs), startMs, endMs,
            role = LineRole.BACKGROUND, groupId = i, oppositeAligned = false, isTranslation = true)
    }

    // Unsynced (plain) — tokenize so the Static render path can wrap each line.
    if (lyrics.all { it.startMs < 0 }) {
        return SpicyDoc(
            buildList {
                lyrics.forEachIndexed { i, ln ->
                    add(SLine(tokenize(ln.text, 0L, 0L).mapIndexed { wi, w -> w.copy(romanizedText = rom(i, wi)) },
                        0L, 0L, role = LineRole.LEAD))
                    transLine(i, 0L, 0L, ln.text)?.let { add(it) }
                }
            },
            LyricsType.Static,
        )
    }

    var anyWords = false
    val out = ArrayList<SLine>()
    lyrics.forEachIndexed { i, ln ->
        if (ln.startMs < 0) return@forEachIndexed
        val leadWords = (if (ln.words.isNotEmpty()) {
            anyWords = true
            ln.words.map { SWord(it.text, it.startMs, it.endMs) }
        } else tokenize(ln.text, ln.startMs, ln.endMs)) // line-synced: split so the line can wrap
            .mapIndexed { wi, w -> w.copy(romanizedText = rom(i, wi)) }
        out.add(SLine(leadWords, ln.startMs, ln.endMs, role = LineRole.LEAD, groupId = i,
            oppositeAligned = ln.oppositeAligned)) // duet/alternate vocals → right-aligned

        ln.background?.let { bg ->
            val bgEnd = if (bg.endMs > 0) bg.endMs else ln.endMs
            val bgWords = if (bg.words.isNotEmpty()) bg.words.map { SWord(it.text, it.startMs, it.endMs) }
                else tokenize(bg.text, bg.startMs, bgEnd)
            out.add(SLine(bgWords, bg.startMs, bgEnd, role = LineRole.BACKGROUND, groupId = i, oppositeAligned = true))
        }
        transLine(i, ln.startMs, ln.endMs, ln.text)?.let { out.add(it) }
    }
    return SpicyDoc(out, if (anyWords) LyricsType.Syllable else LyricsType.Line)
}

/**
 * Split a whole-line string into space-delimited [SWord]s. A line-synced line has no per-word
 * timing, but the renderer wraps on Word boundaries — one Word holding the whole line can't wrap and
 * runs off the right edge. Each token carries the line's own start/end (the Line path fills the line
 * as one gradient, so the shared timing is correct).
 */
private fun tokenize(text: String, startMs: Long, endMs: Long): List<SWord> {
    val toks = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (toks.isEmpty()) return listOf(SWord(text, startMs, endMs))
    return toks.map { SWord(it, startMs, endMs) }
}
