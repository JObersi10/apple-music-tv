# Now Playing Dynamic Background — How It's Made

The full story of the fluid color backdrop behind the Now Playing screen: the
process, the design decisions, and the code. Everything lives in one composable file:
[`NowPlayingScreen.kt`](android/app/src/main/java/com/applemusicktv/ui/screens/NowPlayingScreen.kt),
with beat data coming from
[`BeatAnalyzer.kt`](android/app/src/main/java/com/applemusicktv/media/BeatAnalyzer.kt).

---

## 1. What it is

A full-screen, pure-canvas gradient backdrop — **no artwork image is ever drawn**.
Four soft radial color "blobs" drift slowly in the four quadrants, Screen-blended so
where they overlap the colors brighten. The blob colors are extracted from the album
art, pushed toward vivid, and cross-faded on every song change. The whole thing
pulses gently with the music's beat.

Why no blurred image? A blurred bitmap pixelates when upscaled on a TV and needs a
hardware blur that is a no-op on Fire TV (API < 31). A canvas gradient is resolution-
independent and cheap. See [`DynamicBackground`](android/app/src/main/java/com/applemusicktv/ui/screens/NowPlayingScreen.kt:586).

---

## 2. The pipeline

```
album artwork URL
   │  (Coil load @1200px, allowHardware=false)
   ▼
Palette.from(bitmap)  ──►  up to 7 swatches
   │  vibrant → lightVibrant → darkVibrant → muted → lightMuted → darkMuted → dominant
   ▼
per-swatch HSV push:  sat ×1.45 (floor 0.55),  value cap 0.80
   │
   ▼
spreadByHue()  ──►  6 colors ≥28° apart on the hue wheel
   │
   ▼
4 drifting radial blobs  ──►  BlendMode.Screen
   │        ▲                        ▲
   │        │ beat energy            │ 3–4 InfiniteTransition drift floats
   │        │ (radius + alpha pulse) │ (20s / 27s / 34s / 15s, Reverse)
   ▼
center + right-side darkening (lyrics readability)
```

### Step A — Load artwork and extract a palette
[`rememberArtworkPalette`](android/app/src/main/java/com/applemusicktv/ui/screens/NowPlayingScreen.kt:536)

- The artwork template (`{w}x{h}bb.{f}`) is filled to **1200×1200 jpg**. The palette
  is always taken from the **static** cover, never the motion-art video URL.
- Coil loads with `allowHardware(false)` — `Palette` needs a software bitmap it can
  read pixels from.
- `Palette.from(bitmap).generate()` yields up to 7 named swatches. They're sorted by
  `population` (how much of the image that color covers) so dominant colors lead.
- Fallback: a near-black six-color list, used before the image loads, on any error,
  or when the artwork is genuinely black (dominant luma < 0.06).

### Step B — Push each swatch toward vivid
Same function, the HSV block:

```kotlin
android.graphics.Color.colorToHSV(swatch.rgb, hsv)
hsv[1] = (hsv[1] * SAT_BOOST).coerceIn(SAT_FLOOR, 1f)   // 1.45, floor 0.55
hsv[2] = hsv[2].coerceAtMost(VALUE_CEILING)              // 0.80
```

The **value ceiling is the important one**. A pale, high-value swatch reads as light
grey and competes with the white lyrics on top. Capping value (and flooring
saturation) forces deep, saturated colors that read as *color*, not haze. A plain
saturation *filter* was tried and removed — it stripped vivid pinks and teals.
Constants: [`SAT_BOOST`/`SAT_FLOOR`/`VALUE_CEILING`](android/app/src/main/java/com/applemusicktv/ui/screens/NowPlayingScreen.kt:530).

**Do not push `VALUE_CEILING` past ~0.85** — that's the line where the backdrop
starts washing out the lyrics.

### Step C — Spread the colors around the hue wheel
[`spreadByHue`](android/app/src/main/java/com/applemusicktv/ui/screens/NowPlayingScreen.kt:508)

Picks up to 6 colors that are each **≥28° apart** in hue, so the four blobs don't all
end up the same shade of one color. If fewer than 6 pass the spacing test, the
remaining slots are filled with the closest non-duplicates.

---

## 3. Rendering the blobs
[`DynamicBackground`](android/app/src/main/java/com/applemusicktv/ui/screens/NowPlayingScreen.kt:586)

### Color cross-fade on song change
Each palette color is wrapped in `animateColorAsState(tween(1500))`, so when the song
changes the backdrop **melts** from the old palette to the new one over 1.5s instead
of cutting.

### "Vibing" color cycling
Four blobs, but six palette colors. Each blob slowly lerps between two of them, driven
by one of the drift floats, so the colors keep subtly shifting even on a paused track:

```kotlin
val colorFracs = listOf(t4, 1f - t3, t1, 1f - t2)
val colors4 = List(4) { i -> lerp(animated[(i*2)%n], animated[(i*2+1)%n], colorFracs[i]) }
```

### Drift
Three-to-four `rememberInfiniteTransition` floats at **20s / 27s / 34s / 15s**,
`LinearEasing`, `RepeatMode.Reverse`. The four blob centers are biased toward the four
corners (e.g. top-left blob roams `x∈[0.02,0.28]`, `y∈[0.05,0.32]`) so they never
converge in the middle where the lyrics live.

### The draw call
Inside a single `drawBehind`:

1. **4 radial-gradient blobs**, `radius = max(w,h) * 0.62 * beatScale`, each fading
   `color(alpha=beatAlpha) → color(alpha=0)`, drawn with **`BlendMode.Screen`**
   (overlaps add/brighten).
2. **Center darkening** — a radial black-47%→0% gradient at screen center.
3. **Right-side darkening** — a horizontal 0%→48% black gradient on the right, where
   the lyrics sit, for contrast.
4. **A flat `0x22000000` veil** — kept low, because it mutes every hue equally;
   readability comes from the right-side gradient instead.

---

## 4. Beat reactivity

The pulse comes from [`BeatAnalyzer`](android/app/src/main/java/com/applemusicktv/media/BeatAnalyzer.kt),
a `@Singleton` bus exposing `energy: StateFlow<Float>`.

- Each ExoPlayer instance gets its **own** `BeatProcessor` (a Media3
  `BaseAudioProcessor`) via `beatAnalyzer.newProcessor()`, injected through
  `BeatAwareRenderersFactory`. An `AudioProcessor` can't be shared between two audio
  sinks, and the crossfade player needs its own so beats keep working after the swap.
  Only the **active** processor publishes (`activate(p)`).
- **Detection is bass-onset, not loudness**: mono downmix → one-pole low-pass at
  130 Hz → energy in fixed 10 ms windows → onset when a window exceeds
  `mean + 1.5·stddev` of the last ~1 s, with a 120 ms refractory → a punch-then-decay
  envelope (~250 ms fall). Emits only on >0.015 change to limit recomposition.
- Only runs for `PCM_16BIT` output; bypassed for float.

In the composable:

```kotlin
val rawEnergy by beatAnalyzer.energy.collectAsState()
val scaledRaw = (rawEnergy * beatMultiplier).coerceIn(0f, 1f)
val energy by animateFloatAsState(scaledRaw, spring(dampingRatio = 0.5f, ...))
```

- `beatMultiplier` is the user's **Beat Pulse** setting — Normal 1× / Strong 2× /
  Insane 3.5× from the ··· menu.
- A low-stiffness spring smooths the raw onset so the visual bounces rather than
  snaps.
- `energy` feeds three things: blob **radius** (`1 + energy*0.25`), blob **alpha**
  (`0.66 + energy*0.22`), and a small positional **nudge** of each blob.

`energy` is collected **only inside `DynamicBackground`**, so a beat doesn't recompose
the whole Now Playing screen — just the backdrop.

---

## 5. Performance budget (Fire TV)

The Fire TV Stick 4K is weak, so the backdrop is deliberately capped:

- **6 draw calls per frame**: 4 blobs + center darken + right gradient.
- **≤4 blobs.** `BlendMode.Screen` forces an offscreen compositing pass per draw
  call; 6 blobs caused visible lag.
- **3–4 drift animators.** No more.
- **No `Modifier.blur()`**, no multi-pass draws, no `CubicBezierEasing` — every one of
  those was tried and lagged. Stick to `LinearEasing` and flat gradient math.

---

## 6. Motion (animated) album art

Separate from the backdrop: when `GET /api/motion/:songId` returns a URL,
[`MotionCover`](android/app/src/main/java/com/applemusicktv/ui/screens/NowPlayingScreen.kt:430)
plays a muted, looping square video over the static cover. Note the palette is still
extracted from the **static** artwork URL, not the video. The green-flash fix keeps
`setShutterBackgroundColor(BLACK)` and re-arms a `ready` flag on resume — never use
`TRANSPARENT`, which shows a green YUV frame on surface reattach.

---

## 7. Tuning cheat-sheet

| Want to change… | Touch this |
|---|---|
| How vivid / how pale | `SAT_BOOST`, `SAT_FLOOR`, `VALUE_CEILING` (≤0.85) |
| How different the 4 blob colors are | `spreadByHue` `minAngle` (28°) |
| Cross-fade speed on song change | `animateColorAsState(tween(1500))` |
| Drift speed | the four `tween(20_000 / 27_000 / 34_000 / 15_000)` |
| Blob size | `radius = max(w,h) * 0.62f` |
| How hard it pulses | `beatScale`/`beatAlpha` factors, or user Beat Pulse setting |
| Lyrics contrast | right-side `horizontalGradient` end alpha (`0x7A`), flat veil (`0x22`) |
| Beat sensitivity | `mean + 1.5·stddev` threshold + refractory in `BeatAnalyzer` |

> Golden rule: this backdrop sits **behind white lyrics**. Every knob trades vibrancy
> against readability. When in doubt, keep it darker.
