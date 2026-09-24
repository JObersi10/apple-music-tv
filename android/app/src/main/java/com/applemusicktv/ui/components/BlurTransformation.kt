package com.applemusicktv.ui.components

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil.size.Size
import coil.transform.Transformation

/**
 * Cheap real blur for Coil images — `Modifier.blur` and `RenderEffect` are no-ops on this Fire TV
 * (API < 31), and simply upscaling a tiny bitmap looks blocky/pixelated ("low-res jpg mess"). This
 * downscales to a small work size, runs a few box-blur passes (a box blur repeated ≈ a Gaussian),
 * then hands the small blurred bitmap back — Coil/Compose upscales it with bilinear filtering, so it
 * reads as a smooth soft blur, not pixels. Runs once per image (cached), off the UI thread.
 */
class BlurTransformation(
    private val radius: Int = 24,
    private val workSize: Int = 96,
    private val passes: Int = 3,
) : Transformation {

    override val cacheKey: String = "blur:$radius:$workSize:$passes"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Downscale first — blurring a small bitmap is what makes this cheap, and the upscale that
        // follows softens it further.
        val ratio = input.height.toFloat() / input.width.coerceAtLeast(1)
        val w = workSize
        val h = (workSize * ratio).toInt().coerceAtLeast(1)
        val small = input.scale(w, h, filter = true)
        val out = small.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val r = radius.coerceIn(1, minOf(w, h) / 2)
        repeat(passes) {
            boxBlurH(px, w, h, r)
            boxBlurV(px, w, h, r)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlurH(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(w)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                var i = x - r
                while (i <= x + r) {
                    val xi = i.coerceIn(0, w - 1)
                    val c = px[row + xi]
                    a += (c ushr 24) and 0xFF; rr += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF; b += c and 0xFF; n++; i++
                }
                tmp[x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
            System.arraycopy(tmp, 0, px, row, w)
        }
    }

    private fun boxBlurV(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                var i = y - r
                while (i <= y + r) {
                    val yi = i.coerceIn(0, h - 1)
                    val c = px[yi * w + x]
                    a += (c ushr 24) and 0xFF; rr += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF; b += c and 0xFF; n++; i++
                }
                tmp[y] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
            for (y in 0 until h) px[y * w + x] = tmp[y]
        }
    }
}
