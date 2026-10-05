package com.applemusicktv.lyrics.spicy.canvas

import com.applemusicktv.lyrics.spicy.RenderConfig
import com.applemusicktv.lyrics.spicy.animation.ElementState

internal sealed interface LyricPaintPlan {
    data object ActiveGradient : LyricPaintPlan
    data class InactiveShadow(val alpha: Float, val blurRadius: Float) : LyricPaintPlan
}

internal fun lyricPaintPlan(
    state: ElementState,
    isBackground: Boolean,
    opacity: Float,
    blurRadius: Float,
    config: RenderConfig,
    /** The NotSung colour: a word's `--gradient-alpha-end`, or a whole line's in line-synced lyrics. */
    dimAlpha: Float = config.gradientAlphaDim,
): LyricPaintPlan {
    if (state == ElementState.Active) return LyricPaintPlan.ActiveGradient
    val stateAlpha = when {
        isBackground && state == ElementState.NotSung -> 0.3f
        isBackground -> 0.6f
        state == ElementState.NotSung -> dimAlpha
        else -> config.gradientAlphaBright
    }
    return LyricPaintPlan.InactiveShadow(
        alpha = (stateAlpha * opacity).coerceIn(0f, 1f),
        blurRadius = blurRadius.coerceAtLeast(0f),
    )
}
