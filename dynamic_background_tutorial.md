# DynamicBackground + BeatAnalyzer — Implementation Context

Paste this into a new chat to give the AI full context for working on the animated background system.

---

## What this is

`DynamicBackground` is a fullscreen animated color backdrop on the Now Playing screen. It extracts up to 6 palette colors from the current song's artwork and renders 6 slowly-drifting radial gradient blobs over black. Blobs use `BlendMode.Screen` so overlapping areas mix/brighten like oil on water. Beat energy from ExoPlayer subtly pulses the blobs. No artwork image is rendered — pure Canvas gradients.

---

## File: `NowPlayingScreen.kt` — `rememberArtworkPalette`

```kotlin
@Composable
private fun rememberArtworkPalette(artworkUrl: String?): List<Color> {
    val context = LocalContext.current
    val fallback = listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460), Color(0xFF533483), Color(0xFF0D0D0D), Color(0xFF220033))
    var colors by remember(artworkUrl) { mutableStateOf(fallback) }
    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null) return@LaunchedEffect
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context).data(artworkUrl).allowHardware(false).build()
            val result = loader.execute(request)
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
            val p = Palette.from(bitmap).generate()
            val picked = listOfNotNull(
                p.vibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch,
                p.lightVibrantSwatch, p.darkMutedSwatch, p.lightMutedSwatch, p.dominantSwatch,
            ).map { swatch ->
                // Luminance cap: prevent bright whites/yellows from blowing out
                val c = Color(swatch.rgb)
                val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
                val scale = if (lum > 0.55f) 0.55f / lum else 1f
                Color(c.red * scale, c.green * scale, c.blue * scale)
            }.distinct()
            if (picked.size >= 2) colors = picked.take(6)
        } catch (_: Exception) {}
    }
    return colors
}
```

Loads artwork via Coil at 1200px, extracts up to 6 swatches, luminance-caps bright ones, falls back to dark palette if unavailable.

---

## File: `NowPlayingScreen.kt` — `DynamicBackground`

```kotlin
@Composable
private fun DynamicBackground(artworkUrl: String?, songKey: String, energy: Float = 0f) {
    val palette = rememberArtworkPalette(artworkUrl)
    // Cross-fade colors over 1.5s on song change
    val animated = palette.mapIndexed { i, c ->
        animateColorAsState(c, tween(1500), label = "blob$i").value
    }

    val infinite = rememberInfiniteTransition(label = "pool")
    val t1 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse), label = "t1")
    val t2 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(27_000, easing = LinearEasing), RepeatMode.Reverse), label = "t2")
    val t3 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(34_000, easing = LinearEasing), RepeatMode.Reverse), label = "t3")

    // Cycle colors to always have 6 blobs regardless of palette size
    val colors6 = List(6) { animated[it % animated.size] }

    Box(Modifier.fillMaxSize().background(Color(0xFF050505))) {
        Box(Modifier.fillMaxSize().drawBehind {
            val w = size.width; val h = size.height
            val beatScale = 1f + energy * 0.18f
            val beatAlpha = 0.48f + energy * 0.12f
            val r = maxOf(w, h) * 0.52f * beatScale
            // Centers biased toward edges so blobs don't converge in the middle
            val centers = listOf(
                Offset(lerp(0.05f, 0.35f, t1) * w, lerp(0.10f, 0.40f, t2) * h),
                Offset(lerp(0.95f, 0.65f, t2) * w, lerp(0.05f, 0.45f, t3) * h),
                Offset(lerp(0.15f, 0.45f, t3) * w, lerp(0.90f, 0.60f, t1) * h),
                Offset(lerp(0.80f, 0.55f, t1) * w, lerp(0.80f, 0.55f, t3) * h),
                Offset(lerp(0.02f, 0.40f, t2) * w, lerp(0.55f, 0.85f, t1) * h),
                Offset(lerp(0.85f, 0.55f, t3) * w, lerp(0.02f, 0.35f, t2) * h),
            )
            colors6.forEachIndexed { i, color ->
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = beatAlpha), color.copy(alpha = 0f)),
                        center = centers[i], radius = r,
                    ),
                    radius = r, center = centers[i],
                    blendMode = BlendMode.Screen,
                )
            }
            // Suppress white hotspot where all blobs converge centrally
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x55000000), Color(0x00000000)),
                    center = Offset(w * 0.5f, h * 0.5f), radius = maxOf(w, h) * 0.35f,
                ),
                radius = maxOf(w, h) * 0.35f, center = Offset(w * 0.5f, h * 0.5f),
            )
        })
    }
}
```

**Key design decisions:**
- `BlendMode.Screen` — overlapping blobs add color/light instead of layering. This is the iridescent oil-on-water effect. Requires black background to work correctly.
- 3 timers (20s/27s/34s), prime-ish periods, `LinearEasing` — each blob moves independently, nothing syncs, no easing artifacts on slow hardware.
- Centers biased to edges — keeps the middle from becoming a white blob.
- Center darken draw — one extra `drawCircle` at 33% black alpha, center only, suppresses any residual hotspot.
- No vignette — removed so colors show fully.
- No `Modifier.blur()` — no-op on Fire TV (API < 31) and causes lag.

**Call site in `NowPlayingScreen`:**
```kotlin
val beatEnergy by playerVm.beatAnalyzer.energy.collectAsStateWithLifecycle(0f)
val smoothEnergy by animateFloatAsState(beatEnergy, tween(80))

DynamicBackground(artworkUrl = song?.artworkUrl(1200), songKey = song?.id ?: "", energy = smoothEnergy)
```

---

## File: `BeatAnalyzer.kt`

`media/BeatAnalyzer.kt` — pass-through `BaseAudioProcessor` that computes RMS energy:

```kotlin
class BeatAnalyzer : BaseAudioProcessor() {
    private val _energy = MutableStateFlow(0f)
    val energy: StateFlow<Float> = _energy

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount == 0) return
        val view = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sumSq = 0L; var n = 0
        while (view.hasRemaining()) { val s = view.get().toLong(); sumSq += s * s; n++ }
        if (n > 0) _energy.value = (sqrt(sumSq.toFloat() / n) / 32768f).coerceIn(0f, 1f)
        val out = replaceOutputBuffer(byteCount); out.put(inputBuffer); out.flip()
    }

    override fun onReset() { _energy.value = 0f }
}
```

Bytes pass through unchanged. Only active for `PCM_16BIT` — ExoPlayer bypasses it for float output automatically.

---

## File: `BeatAwareRenderersFactory.kt`

```kotlin
class BeatAwareRenderersFactory(context: Context, val beatAnalyzer: BeatAnalyzer) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(beatAnalyzer))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
}
```

---

## `PlayerViewModel` wiring

```kotlin
val beatAnalyzer = BeatAnalyzer()

private val player = ExoPlayer.Builder(context)
    .setRenderersFactory(BeatAwareRenderersFactory(context, beatAnalyzer))
    .build()
```

---

## Performance constraints (Fire TV)

- **7 draw calls per frame** (6 blobs + 1 center darken) — do not exceed this
- **No `Modifier.blur()`** — no-op on API < 31, causes lag
- **No multi-pass blobs** — one `drawCircle` per blob only
- **No `CubicBezierEasing`** — choppy on slow devices, use `LinearEasing`
- **No rotation** (`graphicsLayer { rotationZ }`) — black corners + lag
- Beat reactivity is subtle: `energy * 0.18` scale, `energy * 0.12` alpha — keep it low
