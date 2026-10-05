package com.applemusicktv.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
) {
    val built = remember(lyrics) { buildSpicyDocument(lyrics) }
    // Footer under the lyrics: "Written by …", "Provided by: <origin>", the community note, and the
    // "Made by @…" / "Uploaded by @…" credits with avatars — exactly like Spicy Player.
    val footer = remember(credits, origin, maker, uploader) {
        LyricsFooter(
            songwriters = credits,
            provenance = LyricsProvenance(provider = "Spicy Lyrics", contributor = origin),
            maker = maker?.let { LyricsCredit(it.name, it.url, it.avatar) },
            uploader = uploader?.let { LyricsCredit(it.name, it.url, it.avatar) },
        )
    }
    val docId = remember(lyrics) {
        "${built.lines.size}:${lyrics.firstOrNull()?.startMs ?: 0}:${lyrics.firstOrNull()?.text?.hashCode() ?: 0}"
    }
    LyricsView(
        lines = built.lines,
        documentId = docId,
        footer = footer,
        currentTimeMs = { progressState.value + offsetMs },
        onSeekWord = onSeek,
        modifier = modifier.fillMaxSize(),
        // The renderer's base size scales with the (wide) TV canvas, so it reads large — trim it.
        fontSizeScale = fontScale * V2_FONT_SCALE,
        // Snap scroll (smooth spring was laggy + flashed). The glow halo is zeroed in LyricsAnimator
        // (glowSpline) — NOT via glowEnabled, because that shadow path also carries the distance blur we
        // keep. Low Power / Reduce Motion (richMotion=false) drops the software distance-blur (StackBlur),
        // the heaviest per-frame cost — so low-power on v2 is genuinely cheap.
        config = (if (richMotion) RenderConfig.FULL else RenderConfig.SIMPLE)
            .copy(distanceBlurEnabled = richMotion),
        lyricsType = built.type,
        romanize = false,
        isPlaying = isPlaying,
        // Keep the active line a bit lower than the default 0.25 (more context above it).
        focusAnchorFraction = 0.4f,
    )
}

private const val V2_FONT_SCALE = 0.72f

private class SpicyDoc(val lines: List<SLine>, val type: LyricsType)

/** Our LyricLine list → Spicy renderer lines. Lead + background become sibling lines sharing a groupId. */
private fun buildSpicyDocument(lyrics: List<LyricLine>): SpicyDoc {
    if (lyrics.isEmpty()) return SpicyDoc(emptyList(), LyricsType.Static)

    // Unsynced (plain) — tokenize so the Static render path can wrap each line.
    if (lyrics.all { it.startMs < 0 }) {
        return SpicyDoc(
            lyrics.map { SLine(tokenize(it.text, 0L, 0L), 0L, 0L, role = LineRole.LEAD) },
            LyricsType.Static,
        )
    }

    var anyWords = false
    val out = ArrayList<SLine>()
    lyrics.forEachIndexed { i, ln ->
        if (ln.startMs < 0) return@forEachIndexed
        val leadWords = if (ln.words.isNotEmpty()) {
            anyWords = true
            ln.words.map { SWord(it.text, it.startMs, it.endMs) }
        } else tokenize(ln.text, ln.startMs, ln.endMs) // line-synced: split so the line can wrap
        out.add(SLine(leadWords, ln.startMs, ln.endMs, role = LineRole.LEAD, groupId = i,
            oppositeAligned = ln.oppositeAligned)) // duet/alternate vocals → right-aligned

        ln.background?.let { bg ->
            val bgEnd = if (bg.endMs > 0) bg.endMs else ln.endMs
            val bgWords = if (bg.words.isNotEmpty()) bg.words.map { SWord(it.text, it.startMs, it.endMs) }
                else tokenize(bg.text, bg.startMs, bgEnd)
            out.add(SLine(bgWords, bg.startMs, bgEnd, role = LineRole.BACKGROUND, groupId = i, oppositeAligned = true))
        }
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
