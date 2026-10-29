package com.applemusicktv.lyrics.spicy.canvas

import com.applemusicktv.lyrics.spicy.models.LyricsType

internal data class ContentSlot(val startPx: Float, val widthPx: Float)

internal data class LyricsLayoutMetrics(
    val viewportWidthPx: Float,
    val density: Float,
    val lyricsType: LyricsType,
    val appFontScale: Float,
) {
    val viewportWidthDp = viewportWidthPx / density.coerceAtLeast(0.01f)
    val baseFontSizeSp = when (lyricsType) {
        LyricsType.Static -> (viewportWidthDp * 0.05f).coerceIn(12.8f, 40f)
        else -> (viewportWidthDp * 0.07f).coerceIn(29.6f, 56f)
    } * appFontScale
    /**
     * The space between two lyric lines, on top of the row height. It follows the text size so a
     * new line always reads as further away than a wrapped row of the same line; a width-based
     * 1cqw was only ~14% of the text and the two looked the same.
     */
    // Gap between lines (0.4 → 0.62): more air between sentences, closer to Apple Music's spacing.
    val lineGapPx = baseFontSizeSp * density * 0.62f
    val lineHeightMultiplier = 1.1818182f

    fun lineHeightPx(fontSizeSp: Float): Float = fontSizeSp * density * lineHeightMultiplier

    /**
     * Duet lines are inset on the side they lean away from: 15cqw with Duet Line Padding, so the
     * two voices read as columns, else the 5cqw every other line gets.
     */
    fun contentSlot(hasDuet: Boolean, isRtl: Boolean, oppositeAligned: Boolean, wideDuet: Boolean = true): ContentSlot {
        if (!hasDuet) {
            return ContentSlot(viewportWidthPx * 0.05f, viewportWidthPx * 0.90f)
        }
        val inset = if (wideDuet) 0.15f else 0.05f
        val startsAtInset = if (isRtl) !oppositeAligned else oppositeAligned
        // Only the duet/call-response voice (the inset side) gets the narrow column. The primary
        // voice — most lines in a song that merely has a few adlib lines — keeps full width, so a
        // handful of opposite-aligned lines no longer shrink every line and force needless wraps.
        if (!startsAtInset) {
            return ContentSlot(viewportWidthPx * 0.05f, viewportWidthPx * 0.90f)
        }
        return ContentSlot(
            viewportWidthPx * inset,
            viewportWidthPx * (0.95f - inset),
        )
    }
}
