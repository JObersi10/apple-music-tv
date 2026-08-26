package com.applemusicktv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.applemusicktv.R

/**
 * UI chrome glyphs. These are Apple's own SF Symbols, exported to SVG and converted to Android
 * vector drawables (res/drawable/ic_*.xml). Rendered white-filled and tinted at draw time via
 * [ColorFilter], so the same asset works in any color. The app avoids emoji/text symbols for chrome
 * (they render inconsistently across Fire TV fonts and can't be tinted or scaled cleanly).
 */
enum class Glyph { PLAY, PAUSE, SHUFFLE, REPEAT, REPEAT_ONE, PLUS, PLAY_NEXT, ARTIST, ALBUM, QUEUE, CHECK, NEXT, PREV, CLOSE, RADIO, MOON, LYRICS, STAR, BACK, GEAR, GEAR_BADGE, QUEUE_ADD, ADD_TO }

@DrawableRes
private fun Glyph.res(): Int = when (this) {
    Glyph.PLAY       -> R.drawable.ic_play_fill
    Glyph.PAUSE      -> R.drawable.ic_pause_fill
    Glyph.SHUFFLE    -> R.drawable.ic_shuffle
    Glyph.REPEAT     -> R.drawable.ic_repeat
    Glyph.REPEAT_ONE -> R.drawable.ic_repeat_1
    Glyph.PLUS       -> R.drawable.ic_plus
    Glyph.PLAY_NEXT  -> R.drawable.ic_text_line_first_and_arrowtriangle_forward
    Glyph.ARTIST     -> R.drawable.ic_microphone_dynamic_on_stand
    Glyph.ALBUM      -> R.drawable.ic_square_stack
    Glyph.QUEUE      -> R.drawable.ic_list_bullet
    Glyph.CHECK      -> R.drawable.ic_checkmark
    Glyph.NEXT       -> R.drawable.ic_forward_fill
    Glyph.PREV       -> R.drawable.ic_backward_fill
    Glyph.CLOSE      -> R.drawable.ic_xmark
    Glyph.RADIO      -> R.drawable.ic_radio_fill
    Glyph.MOON       -> R.drawable.ic_moon_fill
    Glyph.LYRICS     -> R.drawable.ic_quote_bubble
    Glyph.STAR       -> R.drawable.ic_star_fill
    Glyph.BACK       -> R.drawable.ic_chevron_left
    Glyph.GEAR       -> R.drawable.ic_gear
    Glyph.GEAR_BADGE -> R.drawable.ic_gear_badge
    Glyph.QUEUE_ADD  -> R.drawable.ic_text_badge_plus
    Glyph.ADD_TO     -> R.drawable.ic_plus_circle
}

@Composable
fun Icon(glyph: Glyph, size: Dp = 16.dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(glyph.res()),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}
