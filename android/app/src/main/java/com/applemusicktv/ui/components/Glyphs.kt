package com.applemusicktv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Vector glyphs drawn on a Canvas — the app avoids emoji/text symbols for UI chrome
 *  (they render inconsistently across Fire TV fonts and can't be tinted or scaled cleanly). */
enum class Glyph { PLAY, PAUSE, SHUFFLE, REPEAT, REPEAT_ONE, PLUS, PLAY_NEXT, ARTIST, ALBUM, QUEUE, CHECK, NEXT, PREV, CLOSE }

@Composable
fun Icon(glyph: Glyph, size: Dp = 16.dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = h * 0.11f
        when (glyph) {
            Glyph.PLAY -> drawPath(Path().apply {
                moveTo(w * 0.18f, h * 0.06f); lineTo(w * 0.92f, h * 0.5f); lineTo(w * 0.18f, h * 0.94f); close()
            }, color)
            Glyph.PAUSE -> {
                val bw = w * 0.22f
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.1f),
                    size = androidx.compose.ui.geometry.Size(bw, h * 0.8f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.3f))
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.1f),
                    size = androidx.compose.ui.geometry.Size(bw, h * 0.8f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.3f))
            }
            Glyph.NEXT -> {
                drawPath(Path().apply { moveTo(w * 0.12f, h * 0.12f); lineTo(w * 0.6f, h * 0.5f); lineTo(w * 0.12f, h * 0.88f); close() }, color)
                drawPath(Path().apply { moveTo(w * 0.58f, h * 0.12f); lineTo(w * 0.95f, h * 0.5f); lineTo(w * 0.58f, h * 0.88f); close() }, color)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.12f),
                    size = androidx.compose.ui.geometry.Size(stroke * 1.1f, h * 0.76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 0.4f))
            }
            Glyph.PREV -> {
                drawPath(Path().apply { moveTo(w * 0.88f, h * 0.12f); lineTo(w * 0.4f, h * 0.5f); lineTo(w * 0.88f, h * 0.88f); close() }, color)
                drawPath(Path().apply { moveTo(w * 0.42f, h * 0.12f); lineTo(w * 0.05f, h * 0.5f); lineTo(w * 0.42f, h * 0.88f); close() }, color)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.02f, h * 0.12f),
                    size = androidx.compose.ui.geometry.Size(stroke * 1.1f, h * 0.76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 0.4f))
            }
            Glyph.SHUFFLE -> {
                val s = Stroke(width = stroke, cap = StrokeCap.Round)
                // two crossing arrows
                drawPath(Path().apply { moveTo(w * 0.05f, h * 0.28f); cubicTo(w * 0.4f, h * 0.28f, w * 0.6f, h * 0.72f, w * 0.9f, h * 0.72f) }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.05f, h * 0.72f); cubicTo(w * 0.4f, h * 0.72f, w * 0.6f, h * 0.28f, w * 0.9f, h * 0.28f) }, color, style = s)
                // arrowheads
                drawPath(Path().apply { moveTo(w * 0.78f, h * 0.6f); lineTo(w * 0.95f, h * 0.72f); lineTo(w * 0.78f, h * 0.84f) }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.78f, h * 0.16f); lineTo(w * 0.95f, h * 0.28f); lineTo(w * 0.78f, h * 0.4f) }, color, style = s)
            }
            Glyph.REPEAT, Glyph.REPEAT_ONE -> {
                val s = Stroke(width = stroke, cap = StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(w * 0.28f, h * 0.2f); lineTo(w * 0.78f, h * 0.2f)
                    cubicTo(w * 0.95f, h * 0.2f, w * 0.95f, h * 0.5f, w * 0.78f, h * 0.5f)
                }, color, style = s)
                drawPath(Path().apply {
                    moveTo(w * 0.72f, h * 0.8f); lineTo(w * 0.22f, h * 0.8f)
                    cubicTo(w * 0.05f, h * 0.8f, w * 0.05f, h * 0.5f, w * 0.22f, h * 0.5f)
                }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.36f, h * 0.08f); lineTo(w * 0.24f, h * 0.2f); lineTo(w * 0.36f, h * 0.32f) }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.64f, h * 0.68f); lineTo(w * 0.76f, h * 0.8f); lineTo(w * 0.64f, h * 0.92f) }, color, style = s)
                if (glyph == Glyph.REPEAT_ONE) {
                    drawPath(Path().apply { moveTo(w * 0.46f, h * 0.42f); lineTo(w * 0.54f, h * 0.38f); lineTo(w * 0.54f, h * 0.62f) },
                        color, style = Stroke(width = stroke * 0.9f, cap = StrokeCap.Round))
                }
            }
            Glyph.PLUS -> {
                val s = Stroke(width = stroke * 1.15f, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * 0.5f, h * 0.15f); lineTo(w * 0.5f, h * 0.85f) }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.15f, h * 0.5f); lineTo(w * 0.85f, h * 0.5f) }, color, style = s)
            }
            Glyph.PLAY_NEXT -> {
                // play triangle + a bar to its right (queue-next)
                drawPath(Path().apply { moveTo(w * 0.1f, h * 0.15f); lineTo(w * 0.62f, h * 0.5f); lineTo(w * 0.1f, h * 0.85f); close() }, color)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.15f),
                    size = androidx.compose.ui.geometry.Size(stroke * 1.2f, h * 0.7f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 0.5f))
            }
            Glyph.ARTIST -> {
                // head + shoulders silhouette
                drawCircle(color, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.32f))
                drawPath(Path().apply {
                    moveTo(w * 0.18f, h * 0.9f)
                    cubicTo(w * 0.18f, h * 0.6f, w * 0.82f, h * 0.6f, w * 0.82f, h * 0.9f)
                }, color, style = Stroke(width = stroke * 1.3f, cap = StrokeCap.Round))
            }
            Glyph.ALBUM -> {
                drawCircle(color, radius = w * 0.42f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f),
                    style = Stroke(width = stroke))
                drawCircle(color, radius = w * 0.09f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f))
            }
            Glyph.QUEUE -> {
                val s = Stroke(width = stroke, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * 0.12f, h * 0.25f); lineTo(w * 0.72f, h * 0.25f) }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.12f, h * 0.5f); lineTo(w * 0.72f, h * 0.5f) }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.12f, h * 0.75f); lineTo(w * 0.5f, h * 0.75f) }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.7f, h * 0.62f); lineTo(w * 0.9f, h * 0.75f); lineTo(w * 0.7f, h * 0.88f); close() }, color)
            }
            Glyph.CHECK -> drawPath(Path().apply {
                moveTo(w * 0.12f, h * 0.55f); lineTo(w * 0.4f, h * 0.82f); lineTo(w * 0.9f, h * 0.18f)
            }, color, style = Stroke(width = stroke * 1.2f, cap = StrokeCap.Round))
            Glyph.CLOSE -> {
                val s = Stroke(width = stroke * 1.15f, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * 0.22f, h * 0.22f); lineTo(w * 0.78f, h * 0.78f) }, color, style = s)
                drawPath(Path().apply { moveTo(w * 0.78f, h * 0.22f); lineTo(w * 0.22f, h * 0.78f) }, color, style = s)
            }
        }
    }
}
