package com.applemusicktv.lyrics.spicy.canvas.kawarp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.graphics.ColorSpace
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * 1:1 port of @kawarp/core 1.2.0 (MIT), with the options in [SPICY_OPTIONS].
 *
 * Image-change path (KawarpBlurCore): tint → 8 Kawase passes at 128×128, stored as
 * RGBA_F16 "album" bitmaps (half-float FBO parity). Per-frame path: one fused AGSL
 * shader = the original's BLEND + DOMAIN_WARP + OUTPUT passes, PLUS the
 * `saturate(2.5) brightness(0.65)` filter over the whole canvas — composed in one shader since blend/warp/output are
 * pure functions of uv and the CSS filter is just a final per-pixel color transform.
 */
private const val KAWARP_FUSED_AGSL = """
uniform float2 uResolution;
uniform float uTime;
uniform float uBlend;
uniform float uIntensity;
uniform float uSaturation;
uniform float uDithering;
uniform float uScale;
uniform shader texCur;
uniform shader texNext;

const float BLUR_SIZE = 128.0;
// The reference never sizes its canvas, so WebGL draws into the default 300x150 backbuffer
// and CSS stretches it over the page.
const float2 CANVAS_SIZE = float2(300.0, 150.0);

float3 mod289v3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float2 mod289v2(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float3 permute(float3 x) { return mod289v3(((x * 34.0) + 1.0) * x); }

float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439,
                            -0.577350269189626, 0.024390243902439);
    float2 i  = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289v2(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m; m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.y = a0.y * x12.x + h.y * x12.y;
    g.z = a0.z * x12.z + h.z * x12.w;
    return 130.0 * dot(m, g);
}

float hash(float3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

half4 main(float2 fragCoord) {
    // WebGL texcoords start at the bottom-left, and the cover is uploaded without flipping,
    // so it shows upside down (and the warp drifts accordingly), as in a browser. Same here.
    float2 vTexCoord = float2(fragCoord.x / uResolution.x, 1.0 - fragCoord.y / uResolution.y);

    // OUTPUT pass uv-scale, applied up front (warp is pure in uv, so order commutes)
    float2 uv = clamp((vTexCoord - 0.5) / uScale + 0.5, 0.0, 1.0);

    // DOMAIN_WARP pass
    float t = uTime * 0.05;
    float2 center = uv - 0.5;
    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));
    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));
    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));
    float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
    float2 warpedUV = clamp(uv + warp * uIntensity, 0.0, 1.0);

    // BLEND pass, fused: sample both album textures at the warped coordinate.
    // GL texel centers are at (i+0.5)/N; eval coords map [0,1] → [0,BLUR_SIZE].
    float2 texPos = warpedUV * BLUR_SIZE;
    half4 c1 = texCur.eval(texPos);
    half4 c2 = texNext.eval(texPos);
    half4 color = mix(c1, c2, half(uBlend));

    // OUTPUT pass: vignette, saturation, dithering (in canvas texcoord space)
    float2 vc = vTexCoord - 0.5;
    float vignette = 1.0 - dot(vc, vc) * 0.3;
    color.rgb *= half(vignette);
    half gray = dot(color.rgb, half3(0.299, 0.587, 0.114));
    color.rgb = mix(half3(gray), color.rgb, half(uSaturation));
    // Dither per backbuffer pixel, then bilinearly stretched like the browser's upscale.
    float frame = floor(uTime * 60.0);
    float2 grid = vTexCoord * CANVAS_SIZE - 0.5;
    float2 cell = floor(grid);
    float2 f = grid - cell;
    float2 lo = clamp(cell, float2(0.0), CANVAS_SIZE - 1.0);
    float2 hi = clamp(cell + 1.0, float2(0.0), CANVAS_SIZE - 1.0);
    float noise = mix(
        mix(hash(float3(lo.x, lo.y, frame)), hash(float3(hi.x, lo.y, frame)), f.x),
        mix(hash(float3(lo.x, hi.y, frame)), hash(float3(hi.x, hi.y, frame)), f.x),
        f.y);
    color.rgb += half3(half((noise - 0.5) * uDithering));
    // WebGL's default canvas backbuffer is 8-bit UNORM: this is where the browser
    // clamps before CSS ever sees the pixels, same as here.
    color.rgb = clamp(color.rgb, half3(0.0), half3(1.0));

    // `filter: saturate(2.5) brightness(0.65)` over the whole canvas, on top of the shader's own
    // internal saturation/vignette/dither above. CSS saturate() is a luma-preserving mix
    // using Rec.709-ish weights (0.213/0.715/0.072), distinct from the shader's own 0.299/
    // 0.587/0.114 (Rec.601) — kept separate to match the spec exactly. Chained CSS filter
    // primitives each rasterize to 8-bit before the next runs, so clamp between them too —
    // skipping this let saturate(2.5) push channels past 1.0 where brightness(0.65)
    // could no longer pull them back down, which is why the port looked too bright.
    half cssGray = dot(color.rgb, half3(0.213, 0.715, 0.072));
    color.rgb = mix(half3(cssGray), color.rgb, half(2.5));
    color.rgb = clamp(color.rgb, half3(0.0), half3(1.0));
    color.rgb *= half(0.65);

    return color;
}
"""

/** The background's Kawarp options. */
private val SPICY_OPTIONS = KawarpOptions(
    warpIntensity = 1f,
    blurPasses = 8,
    animationSpeed = 0.1f,
    saturation = 1.5f,
    dithering = 0.008f,
    transitionDuration = 500f,
    tintIntensity = 0f,
    scale = 1f,
)

/** dynamicBackground.ts: after transitionDuration*2 ms, bump to 1000ms. */
private const val KAWARP_TRANSITION_DURATION_MS = 1000f

/**
 * Stores the blurred album as half floats, like WebGL's half-float FBOs. Written raw
 * (premultiplied, sRGB-encoded) so nothing is rounded to 8 bits on the way, which bands.
 * The ShortBuffer holds the half floats' raw bits, which is what the HalfFloat lint flags.
 */
@SuppressLint("HalfFloat")
@RequiresApi(Build.VERSION_CODES.O)
private fun floatsToF16Bitmap(pixels: FloatArray, size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGBA_F16, true,
        ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
    val bytes = ByteBuffer.allocate(size * size * 8).order(ByteOrder.nativeOrder())
    val halves = bytes.asShortBuffer()
    for (i in 0 until size * size * 4 step 4) {
        val a = pixels[i + 3].coerceIn(0f, 1f)
        halves.put(Half.toHalf(pixels[i].coerceIn(0f, 1f) * a))
        halves.put(Half.toHalf(pixels[i + 1].coerceIn(0f, 1f) * a))
        halves.put(Half.toHalf(pixels[i + 2].coerceIn(0f, 1f) * a))
        halves.put(Half.toHalf(a))
    }
    bmp.copyPixelsFromBuffer(bytes)
    return bmp
}

private fun bitmapToFloats(src: Bitmap): Triple<FloatArray, Int, Int> {
    val w = src.width; val h = src.height
    val ints = IntArray(w * h)
    src.getPixels(ints, 0, w, 0, 0, w, h)
    val out = FloatArray(w * h * 4)
    for (i in ints.indices) {
        val p = ints[i]
        out[i * 4] = ((p shr 16) and 0xFF) / 255f
        out[i * 4 + 1] = ((p shr 8) and 0xFF) / 255f
        out[i * 4 + 2] = (p and 0xFF) / 255f
        out[i * 4 + 3] = ((p ushr 24) and 0xFF) / 255f
    }
    return Triple(out, w, h)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun KawarpBackground(
    coverArtBitmap: Bitmap?,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    animate: Boolean = true,
    blurIntensity: Int = 60,
    /** How fast to move while playing, read every frame; null (or returning null) is the normal speed. */
    speed: (() -> Float?)? = null,
) {
    // Kawarp's default is blurPasses=8; this app exposes one shared
    // slider for both engines. Kawase blur passes are much stronger per-step than the
    // legacy StackBlur radius, so scaling this the same way the legacy path does (0-100%
    // -> 0-20px) makes the slider's default (60%) way blurrier than intended for Kawarp.
    // Anchor 60% to the library's own reference default (8 passes) instead of its max (40).
    val blurPasses = (blurIntensity.coerceIn(0, 100) * 8f / 60f).roundToInt().coerceIn(1, 40)
    val shader = remember { RuntimeShader(KAWARP_FUSED_AGSL) }
    val engine = remember { KawarpEngine(SPICY_OPTIONS) }
    val blackAlbum = remember {
        Bitmap.createBitmap(KawarpBlurCore.BLUR_SIZE, KawarpBlurCore.BLUR_SIZE,
            Bitmap.Config.RGBA_F16).apply { eraseColor(Color.BLACK) }
    }

    // currentAlbum/nextAlbum mirror the original's FBO swap: first cover fades in
    // from the initial black texture, later covers crossfade from the previous one.
    var currentAlbum by remember { mutableStateOf(blackAlbum) }
    var nextAlbum by remember { mutableStateOf(blackAlbum) }
    var frameTick by remember { mutableLongStateOf(0L) }

    // Paused: a tenth of the speed. Playing: the song's beat-driven speed where there is one, else 1.
    val latestSpeed by rememberUpdatedState(speed)

    // dynamicBackground.ts bumps transitionDuration 500 → 1000 after 2×500ms.
    LaunchedEffect(Unit) {
        delay((SPICY_OPTIONS.transitionDuration * 2).toLong())
        engine.transitionDuration = KAWARP_TRANSITION_DURATION_MS
    }

    // processNewImage(): blur off the UI thread, swap FBOs, start the transition. The first
    // cover shows at once; a blur change re-blurs in place (reblurCurrentImage()).
    var lastCover by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverArtBitmap, blurPasses) {
        val src = coverArtBitmap ?: return@LaunchedEffect
        val blurred = withContext(Dispatchers.Default) {
            val (floats, w, h) = bitmapToFloats(src)
            val out = KawarpBlurCore.process(
                floats, w, h,
                blurPasses = blurPasses,
                tintColor = SPICY_OPTIONS.tintColor,
                tintIntensity = SPICY_OPTIONS.tintIntensity,
            )
            floatsToF16Bitmap(out, KawarpBlurCore.BLUR_SIZE)
        }
        when {
            nextAlbum === blackAlbum -> { currentAlbum = blurred; nextAlbum = blurred }
            src === lastCover -> nextAlbum = blurred
            else -> {
                currentAlbum = nextAlbum
                nextAlbum = blurred
                engine.startTransition(System.currentTimeMillis())
            }
        }
        lastCover = src
    }

    val isPlayingUpdated by rememberUpdatedState(isPlaying)
    LaunchedEffect(animate, nextAlbum) {
        var last = 0L
        // Still (low performance mode): frames only while a new cover fades in.
        while (animate || engine.blendFactor(System.currentTimeMillis()) < 1f) {
            // Paused, once slowed to a tenth of the speed, 20 frames a second look the same.
            if (!isPlayingUpdated && engine.currentAnimationSpeed < PAUSED_SPEED_SETTLED &&
                engine.blendFactor(System.currentTimeMillis()) >= 1f) delay(PAUSED_FRAME_MS)
            withFrameNanos { now ->
                engine.targetAnimationSpeed = if (isPlayingUpdated) latestSpeed?.invoke() ?: 1f else 0.1f
                if (last != 0L) engine.tick((now - last) / 1_000_000_000f)
                last = now
                frameTick = now // invalidate the Canvas
            }
        }
        frameTick = System.nanoTime() // the crossfade's last, finished frame
    }

    // The album BitmapShaders and the ShaderBrush wrap objects that only change on a cover swap;
    // recreating them every frame (as the draw block used to) was pure per-frame allocation.
    val texCur = remember(currentAlbum) {
        BitmapShader(currentAlbum, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
    }
    val texNext = remember(nextAlbum) {
        BitmapShader(nextAlbum, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
    }
    val shaderBrush = remember(shader) { ShaderBrush(shader) }

    // Drawn into a 300x150 layer that is then stretched over the page, rather
    // than running the shader for every screen pixel (~60x the work on a phone).
    BoxWithConstraints(modifier.fillMaxSize()) {
        val stretchX = constraints.maxWidth / CANVAS_WIDTH.toFloat()
        val stretchY = constraints.maxHeight / CANVAS_HEIGHT.toFloat()
        Canvas(
            Modifier
                .layout { measurable, _ ->
                    val canvas = measurable.measure(Constraints.fixed(CANVAS_WIDTH, CANVAS_HEIGHT))
                    layout(canvas.width, canvas.height) { canvas.place(0, 0) }
                }
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = stretchX
                    scaleY = stretchY
                    // Rasterised at 300x150 first, then scaled (with bilinear filtering) as a texture.
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") frameTick
            // render(): the crossfade is eased with a half cosine.
            val blend = 0.5f - 0.5f * cos(engine.blendFactor(System.currentTimeMillis()) * PI.toFloat())
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uTime", engine.accumulatedTime)
            shader.setFloatUniform("uBlend", blend)
            shader.setFloatUniform("uIntensity", SPICY_OPTIONS.warpIntensity)
            shader.setFloatUniform("uSaturation", SPICY_OPTIONS.saturation)
            shader.setFloatUniform("uDithering", SPICY_OPTIONS.dithering)
            shader.setFloatUniform("uScale", SPICY_OPTIONS.scale)
            shader.setInputShader("texCur", texCur)
            shader.setInputShader("texNext", texNext)
            drawRect(brush = shaderBrush)
        }
    }
}

/** An unsized WebGL canvas's default 300x150 backbuffer. */
private const val CANVAS_WIDTH = 300
private const val CANVAS_HEIGHT = 150

/** Paused, the background redraws about this often (20 fps). */
private const val PAUSED_FRAME_MS = 50L
/** The paused speed (0.1) counts as reached below this. */
private const val PAUSED_SPEED_SETTLED = 0.12f
