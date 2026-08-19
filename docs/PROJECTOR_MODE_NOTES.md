# Projector Mode — what AMTV does differently

Context for another AI: the reference guide (PhairPlay's `docs/PROJECTOR_MODE.md`) describes
the *original* projector mode. This file lists where the Apple Music TV (AMTV) implementation
**diverges** from it. If you're porting, read the reference for the shared skeleton, then apply
these deltas — they're the parts that actually make it look good on a TV behind lyrics.

Files: `media/BeatAnalyzer.kt` (the DSP) and `ui/screens/NowPlayingScreen.kt`
(`DynamicBackground`, projector branch).

---

## 1. Three band orbs, not one energy blob

Reference drives the visuals from a single **bass-onset energy** envelope (one number, 0..1).

AMTV runs a **3-band filter bank** and draws **one orb per band**:

- **bass** = low-pass of the mono mid (kick/bass)
- **treble** = mid minus a 4 kHz low-pass (the high residue — cymbals/hats)
- **vocal** = band-passed **mid** minus band-passed **side**, i.e. `(L+R)/2` vs `(L-R)/2`.
  Only centre-panned content survives, so the singer shows and wide-panned instruments cancel.

It's a filter bank, **not an FFT** — three multiplies per sample. At orb scale the difference
from a real FFT is invisible and it's cheap enough for the weak Fire TV audio thread.

The bass onset envelope from the reference still exists (`energy`) but it only drives the
**Dynamic** background's blobs. Projector uses the three band levels instead.

## 2. Normalise by SWELL-ABOVE-BASELINE, not level-vs-peak  ← the important one

The reference (and AMTV's first attempt) normalised each band against a slowly-decaying
**peak**: `ratio = raw / peak`. On a TV this **pins at max forever** — a steady-loud band
(bass on a four-on-the-floor track) sits at its own peak, so `ratio ≈ 1` constantly. Orbs
look maxed and dead.

AMTV normalises against a **slow per-band baseline** (~1.5 s EMA) and measures the *relative
rise above it*:

```
base += 0.02 * (raw - base)            // ~1.5 s follow, seeded on first window
excess = (raw / base - 1).coerceIn(0, EXCESS_MAX) / EXCESS_MAX
norm   = gate(excess)                   // 5% noise gate
level += (norm - level) * (rising ? ATTACK : RELEASE)   // fast up, slower down
```

"At its own average" → **0**, not 1. It takes a genuine rise (a kick, a vocal entrance) to
light up, so the orb **pulses on the beat and settles between hits** instead of pinning.

Tuning that mattered:
- `EXCESS_MAX = 1.1` — headroom so normal hits land mid-range and only the biggest peak
  reaches full. Set it low (~0.65) and everything clips flat at 1 again (the "maxed" look).
- `ATTACK = 0.42`, `RELEASE = 0.11` — asymmetric: snap up on the hit, fall back **between**
  hits so the pulse is visible. Too-slow release + frequent kicks = never falls = looks maxed.

## 3. Per-band character so the three read as three

Same radius/response for every orb looks like one thing blinking. AMTV gives each band its own:

- **base radius**: bass `0.26·min(w,h)`, vocal `0.21`, treble `0.16` (bass is the big slow one).
- **size ride**: treble punches biggest *relative* to its size (`1.05`) vs bass (`0.75`).
- each orb rides its **own** drift animator + phase offset, so the composition never repeats.

## 4. Dim at rest, flare on the hit (readability)

Lyrics live on the right half in grey/white. A constant bright glow washes them out.

- **halo alpha RIDES the beat**: `0.24 + level*0.42` — dim at rest, bright only on the hit.
  (Reference kept halo alpha constant to avoid lifting the black frame; on true black with
  `BlendMode.Screen` a beat-riding halo is fine and is the expressive part.)
- **core** is tiny, whitened (`lerp(col, White, 0.6)` — a real glow's hottest point desaturates)
  and punches hard (`0.12 + level*0.62`). Small area, so it flares without lifting the black.

Net: big delta between rest and peak = expressive, but the *resting* level stays quiet enough
to read lyrics over.

## 5. Colour: three distinct album accents that drift

- Palette picks the **three most-separated** accents from the cover (`spreadByHue`, or
  `spreadByValue` for monochrome sleeves — see #6).
- Each orb slowly lerps a *little* toward the next accent (`0.10 + 0.18·t`) so colours evolve
  over time without an orb ever losing its identity. Not a full cycle — "a bit of changing."

## 6. Monochrome covers give grey/white, not invented colour

Detect a black-and-white sleeve **before** the saturation boost (`maxSat < 0.18`). If it's
mono: force `sat=0`, keep the brightness spread, and separate the orbs **by value**
(`spreadByValue`) instead of hue — hue is meaningless when everything's grey, so hue-spread
would collapse all three orbs onto one shade. Result: three shades of grey/white, honest to
the cover, instead of a fake colour the boost would hallucinate.

## 7. TRUE black + a LIGHT edge fade

- Projector fills with `Color.Black`, not the `#050505` panel-lift the Dynamic mode uses. A
  projector throws light on a wall; the near-black lift becomes a visible grey rectangle.
- Edge vignette is **light** (`0.10` of w/h). The reference/first attempt used a heavy vignette
  that painted black back over the glow and killed it. Orbs are small and pulled inward, so they
  never reach the edge anyway — the fade just guarantees no lit rectangle at the frame border.
- Plus one right-side gradient for lyric readability (a gradient, not an edge fade).

## 8. Compose spring on top of the DSP

The DSP already smooths (attack/release). The Compose layer adds a spring
(`dampingRatio 0.6, StiffnessMediumLow`) so band levels glide between the ~33/sec DSP updates.
Over-damp it (0.95) and it stops tracking the beat; under-damp and it jitters. 0.6 tracks.

Positional drift is kept **gentle** (`0.08·w`, `0.045·h`) — "move less" is about the orbs not
swimming around the frame; the *pulse* (size/brightness) is where the energy goes.
