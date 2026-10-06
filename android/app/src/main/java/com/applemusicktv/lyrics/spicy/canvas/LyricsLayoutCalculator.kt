package com.applemusicktv.lyrics.spicy.canvas

import com.applemusicktv.R

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.withStyle
import com.applemusicktv.lyrics.spicy.models.interludeDotTimes
import com.applemusicktv.lyrics.spicy.models.Line
import com.applemusicktv.lyrics.spicy.models.LyricsType
import com.applemusicktv.lyrics.spicy.models.Word
import com.applemusicktv.lyrics.spicy.parser.RtlDetector

internal object LyricsLayoutCalculator {
    /** Scale of the active line in line-synced lyrics (reference: data-lyrics-type="Line" .line.Active). */
    internal const val ACTIVE_LINE_SCALE = 1.05f
    /** Global letter tightening (sp) — letters read a hair wide on this TV at the v2 size. */
    internal const val LETTER_SPACING_SP = -0.5f

    private val bundledFontFamily = FontFamily(
        Font(R.font.lyrics_regular, FontWeight.Normal),
        Font(R.font.lyrics_medium, FontWeight.Medium),
        Font(R.font.lyrics_semibold, FontWeight.SemiBold),
        Font(R.font.lyrics_bold, FontWeight.Bold)
    )

    /**
     * The Lyrics Font setting: the phone's font or one the user picked, for every script, in
     * place of ours. Null keeps ours.
     */
    internal var fontOverride by mutableStateOf<FontFamily?>(null)
        private set

    /** Names [fontOverride], so measured lyrics can tell a font change from none. */
    internal var fontKey by mutableStateOf("")
        private set

    internal fun setFont(family: FontFamily?, key: String) {
        fontOverride = family
        fontKey = key
    }

    /** The font for lyrics and the text around them (the song header, the controls' times). */
    internal val spicyFontFamily: FontFamily get() = fontOverride ?: bundledFontFamily
    private val vazirmatnFontFamily = variableFamily(R.font.vazirmatn_variable)
    private val georgianFontFamily = variableFamily(R.font.noto_sans_georgian_variable)

    /**
     * A variable font at each weight the renderer asks for. The weight has to be set on the
     * font's `wght` axis: declared alone, every weight drew the file's default instance (400),
     * so bold Arabic and Georgian lyrics came out Regular.
     */
    @OptIn(ExperimentalTextApi::class)
    private fun variableFamily(resId: Int) = FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
            Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
    )

    private fun fontFamilyFor(text: String): FontFamily = fontOverride ?: when (ScriptFontSelector.select(text)) {
        LyricScriptFont.DEFAULT -> spicyFontFamily
        LyricScriptFont.VAZIRMATN -> vazirmatnFontFamily
        LyricScriptFont.NOTO_SANS_GEORGIAN -> georgianFontFamily
    }

    internal fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    /** The string to render for a word: its romanization when [romanize] is on and available. */
    private fun displayText(word: Word, romanize: Boolean): String =
        if (romanize) (word.romanizedText ?: word.text) else word.text

    /** Whether [line] shows romanized: when asked, or when only its romanization has text. */
    private fun romanizes(line: Line, romanize: Boolean): Boolean =
        romanize || (line.words.none { it.text.isNotBlank() } && line.words.any { !it.romanizedText.isNullOrBlank() })

    /**
     * Whether a line's block should sit on the right edge of the lyrics column.
     *
     * Plain lines: right-aligned only if RTL. Duet lines normally put the primary voice (v1,
     * `!oppositeAligned`) on the left and the guest on the right — swapped for RTL duets, so an
     * RTL duet mirrors instead of stacking both voices on the same side.
     */
    private fun resolveRightAligned(hasDuet: Boolean, isRtl: Boolean, oppositeAligned: Boolean, isSongwriter: Boolean): Boolean {
        if (isSongwriter) return false
        if (!hasDuet) return isRtl
        return if (isRtl) !oppositeAligned else oppositeAligned
    }

    fun calculateLineLayouts(
        lines: List<Line>,
        canvasWidth: Float,
        textMeasurer: TextMeasurer,
        density: Float,
        lyricsType: LyricsType,
        fontSizeScale: Float = 1.0f,
        romanize: Boolean = false,
        simpleMode: Boolean = false,
        wideDuet: Boolean = true,
    ): List<LineLayout> {

        val layouts = mutableListOf<LineLayout>()
        var currentY = 0f
        val metrics = LyricsLayoutMetrics(canvasWidth, density, lyricsType, fontSizeScale)
        val lineSpacing = metrics.lineGapPx
        
        val hasDuet = lines.any { it.oppositeAligned }
        val baseFontSize = metrics.baseFontSizeSp.sp
        val bgFontSize = baseFontSize * 0.75f

        for (line in lines) {
            val isInterlude = line.isInterlude
            val isBg = line.isBackground
            val fontSize = if (isBg) bgFontSize else baseFontSize
            val fontWeight = when {
                lyricsType == LyricsType.Static -> FontWeight.Medium
                isBg -> FontWeight.SemiBold
                else -> FontWeight.Bold
            }

            if (isInterlude) {
                // Instrumental interludes are rendered as three dots.
                // You can adjust the multiplier here to make the dots bigger or smaller:
                val dotFontSize = baseFontSize * 1.3f // FIXED: dots=1.3x font
                // Reference .dotGroup gap: clamp(0.005rem, 1.7cqw, 0.18rem), 1rem = 16dp.
                // `--dot-gap`: clamp(0.005rem, 1.7cqw, 0.18rem); Simple: clamp(0.0067rem, 1.76cqw, 0.32rem).
                val dotGap = if (simpleMode) (canvasWidth * 0.0176f).coerceIn(0.0067f * 16f * density, 0.32f * 16f * density)
                    else (canvasWidth * 0.017f).coerceIn(0.005f * 16f * density, 0.18f * 16f * density)
                val dotLayouts = interludeDotTimes(line.startMs, line.endMs).mapIndexed { dotIdx, (dotStart, dotEnd) ->
                    val dotWord = Word("•", dotStart, dotEnd)
                    val result = textMeasurer.measure(
                        text = AnnotatedString("•"),
                        style = TextStyle(
                            fontFamily = spicyFontFamily,
                            fontSize = dotFontSize,
                            fontWeight = fontWeight,
                            color = Color.White,
                            textMotion = TextMotion.Animated,
                            // Distinct per-dot identity, same rationale as the word-level hack below:
                            // three identical "•" glyphs would otherwise share one cached
                            // TextLayoutResult (and its highlight/paint state) across dots.
                            letterSpacing = (dotIdx * 0.0000001f).sp,
                        )
                    )
                    val dotW = result.size.width.toFloat()
                    WordLayout(dotWord, result, Offset(dotIdx * (dotW + dotGap), 0f))
                }
                // Reference .dot line-height: 0.65 of the dot's font size, not the glyph box.
                val dotH = dotLayouts.firstOrNull()?.textLayoutResult?.layoutInput?.let { input ->
                    with(input.density) { input.style.fontSize.toPx() } * 0.65f
                } ?: 0f
                val totalDotsW = dotLayouts.lastOrNull()?.let { it.relativeOffset.x + it.textLayoutResult.size.width } ?: 0f

                // Inherit alignment (and RTL-ness) from the next non-interlude, non-background line.
                val nextLine = lines
                    .firstOrNull { it.startMs > line.startMs && !it.isInterlude && !it.isBackground && !it.isSongwriter }
                val nextLineAlignment = nextLine?.oppositeAligned ?: false
                val nextLineIsRtl = nextLine != null &&
                    RtlDetector.isRtl(nextLine.words.joinToString(" ") { displayText(it, romanizes(nextLine, romanize)) })
                val dotsRightAligned = resolveRightAligned(hasDuet, nextLineIsRtl, nextLineAlignment, isSongwriter = false)

                val slot = metrics.contentSlot(hasDuet, nextLineIsRtl, nextLineAlignment, wideDuet)
                layouts.add(LineLayout(line, dotLayouts, currentY, dotH, totalDotsW, totalDotsW, true, isBg,
                    nextLineAlignment, false, nextLineIsRtl, dotsRightAligned, slot.startPx, slot.widthPx))
                currentY += 0f // Interludes collapse when not active.
                continue
            }


            // Standard lyric line layout.
            val romanizeLine = romanizes(line, romanize)
            val lineIsRtl = RtlDetector.isRtl(line.words.joinToString(" ") { displayText(it, romanizeLine) })
            val lineFontFamily = fontFamilyFor(line.words.joinToString(" ") { displayText(it, romanizeLine) })
            val contentSlot = metrics.contentSlot(hasDuet, lineIsRtl, line.oppositeAligned, wideDuet)
            // An active line-synced line grows 1.05x away from its aligned edge. The reference
            // wraps it inside a 5cqw padding that the growth fills; here it must wrap narrower
            // so the enlarged line still ends at the margin instead of running past it.
            val lineMaxWidth = if (lyricsType == LyricsType.Line) contentSlot.widthPx / ACTIVE_LINE_SCALE
                else contentSlot.widthPx
            val gapStyle = TextStyle(
                fontFamily = lineFontFamily,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = Color.White,
            )
            val zero = textMeasurer.measure(text = AnnotatedString("0"), style = gapStyle)
            // Word-synced lines are rows of word elements spaced by `margin-right: 0.32ch`; line-
            // synced and static lines are plain text, spaced by a real space.
            val textMode = lyricsType == LyricsType.Line || lyricsType == LyricsType.Static
            val wordGap = if (textMode) {
                textMeasurer.measure(text = AnnotatedString("a a"), style = gapStyle).size.width -
                    textMeasurer.measure(text = AnnotatedString("aa"), style = gapStyle).size.width.toFloat()
            } else zero.size.width.toFloat() * 0.18f  // tightened from 0.32ch — read too wide on the TV
            // A left-aligned word element's box includes its trailing 0.32ch (an ::after margin,
            // absent on the line's last word), so it must fit too. Opposite-aligned lines use
            // column-gap instead, which only sits between words on the same row.
            val trailingGap = if (!textMode && !line.oppositeAligned && !lineIsRtl) wordGap else 0f
            // Every row sits on the line font's own baseline, like a CSS line box whose strut is
            // the primary font. Fallback glyphs (CJK) have taller line boxes and a lower baseline;
            // aligning only within the row let an all-CJK row sink toward the next line.
            // CSS centres the font's box (ascent + descent) in the line height, splitting the
            // difference as half-leading. Vazirmatn's box is 1.56em against a 1.18em line, so
            // its baseline rises 0.19em from where the raw font metrics put it.
            val rowBaseline = zero.firstBaseline + (metrics.lineHeightPx(fontSize.value) - zero.size.height) / 2f

            data class Piece(
                val word: Word,
                val text: String,
                val layout: androidx.compose.ui.text.TextLayoutResult,
                val sourceIdx: Int,
                val charIdx: Int,
                val fullWidth: Float,
                val startX: Float,
                val isCjkPiece: Boolean,
                val isPartOfWord: Boolean
            )

            val pieces = mutableListOf<Piece>()
            for (wIdx in line.words.indices) {
                val word = line.words[wIdx]
                val style = TextStyle(
                    fontFamily = lineFontFamily,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    color = Color.White,
                    // Words move by a pixel or two and scale every frame. Like CSS's
                    // will-change: transform, render them unsnapped so they glide instead of
                    // stepping between whole pixel rows (the "bobbing" after a word is sung).
                    textMotion = TextMotion.Animated,
                    // Pull the letters ~1px tighter (LETTER_SPACING_SP) — they read a hair wide at this
                    // size. The tiny per-word identity term on top keeps Compose from sharing cached
                    // TextLayoutResults (and highlight state) between identical words in different lines.
                    letterSpacing = (LETTER_SPACING_SP + System.identityHashCode(word) % 1000 * 0.0000001f).sp
                )
                
                val text = displayText(word, romanizeLine)
                val fullResult = textMeasurer.measure(text, style)
                val fullW = fullResult.size.width.toFloat()

                // `word.isPartOfWord` records that the ORIGINAL text had no whitespace before this
                // token — true both for real hyphen-continuations ("Hel-"+"lo") and, just as often,
                // for CJK/Hangul syllable spans (which never have inter-word whitespace in the source
                // TTML). Romanization must preserve the source's spacing exactly: a syllable glued to
                // its neighbour in the original script stays glued when romanized (こんにちは → "konnichiwa",
                // not "kon nichi wa"), and only tokens that had real whitespace in the source get a gap.
                // A blank piece (the other view's half of a line-timed romanization) takes no room.
                val effectiveIsPartOfWord = word.isPartOfWord || text.isEmpty()

                if (word.isLetterGroup) {
                    val graphemes = com.applemusicktv.lyrics.spicy.parser.GraphemeSegmenter.segment(text)
                    // Position each letter at its TRUE kerned x within the whole word (from the word's own
                    // layout), not by summing per-letter measured widths — measuring letters in isolation
                    // loses kerning and double-counts side bearings, which spread held words ("doors",
                    // "core") visibly wide before they're sung.
                    var charOffset = 0
                    for (charIdx in graphemes.indices) {
                        val charText = graphemes[charIdx]
                        // Two instances of the same letter within one word (e.g. the two "a"s in
                        // "california") would otherwise measure with identical (text, style) and
                        // share one cached TextLayoutResult, coupling their highlight state — mix
                        // the char index into letterSpacing on top of the word's own identity.
                        val charStyle = style.copy(
                            letterSpacing = (style.letterSpacing.value + charIdx * 0.00000001f).sp
                        )
                        val charResult = textMeasurer.measure(charText, charStyle)
                        val startX = runCatching { fullResult.getHorizontalPosition(charOffset, true) }
                            .getOrDefault(0f)
                        pieces.add(Piece(
                            word = word,
                            text = charText,
                            layout = charResult,
                            sourceIdx = wIdx,
                            charIdx = charIdx,
                            fullWidth = fullW,
                            startX = startX,
                            isCjkPiece = charText.firstOrNull()?.let(::isCjk) == true,
                            isPartOfWord = charIdx > 0 || effectiveIsPartOfWord
                        ))
                        charOffset += charText.length
                    }
                } else {
                    pieces.add(Piece(
                        word = word,
                        text = text,
                        layout = fullResult,
                        sourceIdx = wIdx,
                        charIdx = 0,
                        fullWidth = fullW,
                        startX = 0f,
                        isCjkPiece = false,
                        isPartOfWord = effectiveIsPartOfWord
                    ))
                }
            }

            val lineBreaks = LineWrapper.breaks(
                pieces.map { LineWrapper.Piece(it.layout.size.width.toFloat(), it.text, it.isPartOfWord) },
                maxWidth = lineMaxWidth,
                wordGap = wordGap,
                trailingGap = trailingGap,
                textMode = textMode,
            )

            // Assemble WordLayouts into rows.
            val allRows = mutableListOf<Pair<Float, List<WordLayout>>>()
            var currentRowY = 0f
            var maxRowWidth = 0f
            val explicitRowHeight = metrics.lineHeightPx(fontSize.value)

            for (b in 0 until lineBreaks.size - 1) {
                val startIdx = lineBreaks[b]
                val endIdx = lineBreaks[b+1]
                
                val rowPieces = mutableListOf<WordLayout>()
                var rowX = 0f
                for (idx in startIdx until endIdx) {
                    val piece = pieces[idx]
                    val pieceWidth = piece.layout.size.width.toFloat()
                    val actualGap = if (rowX > 0f && !piece.isPartOfWord) wordGap else 0f
                    
                    val baseline = piece.layout.firstBaseline
                    val yShift = if (!baseline.isNaN() && !rowBaseline.isNaN()) rowBaseline - baseline else 0f
                    
                    rowPieces.add(WordLayout(
                        word = piece.word,
                        textLayoutResult = piece.layout,
                        relativeOffset = Offset(rowX + actualGap, currentRowY + yShift),
                        sourceWordIndex = piece.sourceIdx,
                        charIndex = piece.charIdx,
                        fullWordWidth = piece.fullWidth,
                        startXOffset = piece.startX
                    ))
                    rowX += pieceWidth + actualGap
                }
                
                allRows.add(rowX to rowPieces)
                maxRowWidth = maxOf(maxRowWidth, rowX)
                if (b < lineBreaks.size - 2) {
                    currentRowY += explicitRowHeight
                }
            }
            
            // A lead with no words (its line is only background vocals) takes no room, so the
            // background vocals sit where the line would.
            val totalHeight = when {
                pieces.isEmpty() && !isBg -> 0f
                allRows.isEmpty() -> explicitRowHeight
                else -> currentRowY + explicitRowHeight
            }
            val totalWidth = maxRowWidth

            // Apply alignment and flatten. RTL duet lines mirror the LTR duet convention (primary
            // right, guest left) instead of both stacking on the right — see resolveRightAligned.
            val isRightAligned = resolveRightAligned(hasDuet, lineIsRtl, line.oppositeAligned, line.isSongwriter)
            val wordLayouts = mutableListOf<WordLayout>()
            for ((rWidth, rowPieces) in allRows) {
                val alignmentShift = if (isRightAligned) maxRowWidth - rWidth else 0f
                for (wLayout in rowPieces) {
                    // Our layout builds each row left-to-right in source (logical reading) order.
                    // For LTR that's also visual order, but RTL reading order is right-to-left, so
                    // the row must be mirrored within its own width — otherwise words still read
                    // left-to-right, just shifted as a block (the bug: "words are ordered left to
                    // right" instead of right to left).
                    val mirroredX = if (lineIsRtl) {
                        rWidth - wLayout.relativeOffset.x - wLayout.textLayoutResult.size.width
                    } else {
                        wLayout.relativeOffset.x
                    }
                    wordLayouts.add(wLayout.copy(
                        relativeOffset = Offset(mirroredX + alignmentShift, wLayout.relativeOffset.y)
                    ))
                }
            }

            val prevIsInterlude = layouts.lastOrNull()?.isInterlude ?: false
            val drawY = if (isBg && !prevIsInterlude) currentY - lineSpacing else currentY

            layouts.add(LineLayout(line, wordLayouts, drawY, totalHeight, totalWidth, maxRowWidth, false, isBg,
                line.oppositeAligned, line.isSongwriter, lineIsRtl, isRightAligned,
                contentSlot.startPx, contentSlot.widthPx))
            
            val bottomY = drawY + totalHeight
            currentY = maxOf(currentY, bottomY + lineSpacing)
        }
        return layouts
    }
}
