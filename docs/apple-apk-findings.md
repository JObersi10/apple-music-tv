# Apple Music APK — reverse-engineering findings

Analysed `com.apple.android.music 6.5.3` (base.apk dex string dump). What Apple's own
client does, and what we can borrow. Source of truth for the features below.

## 1. Album "popular" dot — IMPLEMENTED
- Apple reads a **per-track `popularity`** attribute (float, ~0..1), returned only when the
  catalog request adds **`?extend=popularity`**.
- Dex symbols: `getPopularity`/`setPopularity`, `POPULARITY_THRESHOLD`, `hasPopularityIndicator`,
  plus `chartPosition` / `maxChartPosition`. The dot shows when `popularity >= threshold`.
- Web glyph = a 6px filled circle, `data-testid="popular-glyph"`, aria "Popular Track".
- **Our impl:** `catalogAlbumTracks` + server `albums.ts` request `extend=popularity`;
  `popularity` threaded SongDto→Song; `AlbumDetailViewModel` dots tracks `>= 0.5` (scale-aware:
  0..1→0.5, 0..100→50), falls back to artist top-songs match if the attribute is absent. Dot is a
  grey circle to the LEFT of the track number. Log tag `AMPopular`.
- Threshold is a guess (Apple's exact `POPULARITY_THRESHOLD` value wasn't a literal in the dex) —
  tune 0.5 if too many/few dots.

### Popular dot tuning (2026-09-24)
- 0.5 absolute threshold dotted almost every track. Fixed: dot is now RELATIVE to the album — top
  quartile by `popularity`, capped at 5, and strictly above the album median (a flat "greatest hits"
  album where every track is equally popular gets no dots). `AlbumDetailViewModel`.

## 2. Now Playing background = ambient EDITORIAL VIDEO — BUILT as "Dynamic v2" (2026-09-24)
- Apple's now-playing backdrop is driven by **`EditorialVideo`** (model
  `com.apple.android.music.mediaapi.models.internals.EditorialVideo`) with quality **`Flavor`s**.
- Flavors seen: **`motionSquare`**, **`previewFrame`**. `previewFrame` carries
  `getPreviewFrameArtworkGradient` + `getPreviewFrameArtworkBGColor` → the gradient/tint behind it.
- Also `ambientColor`, `setAmbientShadowColor`, and `renderEffect` (blur) — Apple blurs the video
  and tints with an ambient colour.
- We already have `GET /api/motion/:songId` returning the square motion HLS (that's `motionSquare`).
- **Dynamic v2 plan (user wants a Settings toggle):** full-screen ambient motion video behind Now
  Playing, cover-cropped + dimmed + ambient-colour tint; fall back to current DynamicBackground
  (colour blobs) when no motion art. Gate behind a `Dynamic v2` switch in Settings/Dev. Reuse the
  MotionCover ExoPlayer (SurfaceView, own overlay plane). One decoder only (swap the small cover
  motion off when the backdrop is on). NOTE: user first saw a raw version and disliked it as the
  default — that's why it must be an opt-in toggle, not forced.

- **BUILT:** `NowPlayingBackground.AMBIENT` ("Dynamic v2"), selectable in the Dev/Settings background
  cycle. `AmbientBackground` in `NowPlayingScreen.kt` renders the album's motion art (`/api/motion`)
  fullscreen cover-cropped + ambient-colour tint + dark scrim; no motion art → album cover upscaled
  from a 120px fetch (soft, since `Modifier.blur` is a no-op on this Fire TV). One decoder: the small
  card MotionCover is suppressed while Dynamic v2 is showing the same loop fullscreen.

### How Apple's Android app actually renders it (confirmed from dex, 2026-09-24)
- The now-playing backdrop is NOT a beat visualiser and NOT a sharp photo. Dex symbols:
  `GenerationBackgroundParams(blurEffect, colorBlobsBlurEffect, bubbleBlur, overlayBlur,
  postBlurEffect)` → Apple draws soft **colour blobs / bubbles** from the artwork colours and
  **heavily blurs** them (a lava lamp). Blur is done with **RenderScript Toolkit**
  (`com.apple.android.music.utils.ImageBlurTransformation`, "radius should be between 1 and 25",
  `nativeBlurBitmap`) — a Coil Transformation, exactly the pattern we now use. Colours come from
  `getPreviewFrameArtworkGradient` / `getPreviewFrameArtworkBGColor`; motion video (`motionSquare`)
  is used over-scanned when the album has one.
- **Our "Ambient" mode** matches this: `BlurTransformation` (box-blur Coil transform, no RenderEffect
  needed) applied to 3 drifting/rotating/scaling cover layers = flowing blurred colour; motion video
  over-scanned when present; ambient-colour tint; a beat "bloom" that expands the colour on each hit.

## 3. Native lyrics translations + pronunciation — BUILT (translation; pronunciation still open)
- **BUILT (2026-09-24):** `GET /api/lyrics/:songId/translation?to=<lang>` — native-first. Requests
  the lyrics with `l=<lang>`; if Apple returns TTML whose text actually differs from the original
  (≥40% of lines), that's Apple's own translation, returned aligned line-for-line. Otherwise falls
  back to the keyless machine translator so it never regresses. Android: `repo.translateLinesForSong`
  prefers this when the proxy is reachable, else on-device Google. NOTE: the exact Apple param is a
  best guess (`l=`) — verify against the live server; the machine fallback guarantees it still works.
- Pronunciation/transliteration (romaji/pinyin) still not built.

## 3b. (was §3) Native lyrics translations + pronunciation — original notes
- Apple's lyrics entity carries **translations** and **pronunciation/transliteration** natively:
  `isTranslationAutomaticallyCreated`, `getTranslationAvailableLiveResult`,
  `getTranslationSelectedLiveResult`, `lyricsTranslationsEnabledByDefault`, layout
  `lyrics_word_pronunciation`. Capability endpoint: `musicSubscription/timeSyncedLyrics`.
- Could replace our on-device Google-translate gloss with Apple's official per-line translation +
  romaji/pinyin pronunciation. Exact request param not a dex literal (built at runtime) — likely an
  `l=`/`extend`/`with` on the `/lyrics` or `/syllable-lyrics` call. Needs probing with a real bearer.

## 4. editorialArtwork (richer hero theming) — BUILT for artist hero (2026-09-24)
- **BUILT:** artist `/full` route + standalone `DirectMusicDataSource` request `extend=editorialArtwork`
  and expose `heroUrl` (widest flavour: subscriptionHero → bannerUber → centeredFullscreenBackground
  → …), `heroBgColor`, `heroTextColor`. `ArtistDetailScreenV2` uses `heroUrl` as the backdrop when
  present (Apple's own wide banner), falling back to the square artist photo. Album hero theming with
  editorialArtwork still open (nice-to-have).

## 4b. (was §4) editorialArtwork — original notes
- Albums/artists expose **`editorialArtwork`**: `getEditorialArtworkImageUrl`,
  `getEditorialArtworkGradient`, `getEditorialArtworkBGColor`, `getEditorialArtworkTextGradient`.
- A wide hero image + gradient + bg colour + text gradient — Apple uses it for artist/album hero
  headers and readable text overlays. Request via `extend=editorialArtwork`. Would upgrade the
  ArtistDetail/AlbumDetail hero look.

## 5. Dolby Atmos / Spatial / Hi-Res — DEFERRED (revisit later, potentially valuable on Fire TV)
- Traits: `SongTrait_HighResolutionLossless`, `KEY_DOLBY_ATMOS_*`, `SUPPORTSSPATIALIZATION`.
- Not built and NOT currently decoded. User flagged this as worth doing later: Fire TV can pass Dolby
  Atmos/spatial through to a capable AVR/soundbar, so if executed properly (select the `ec+3`/Atmos
  asset instead of `ctrp`, pass the E-AC-3 JOC bitstream through ExoPlayer with
  `AudioAttributes`/passthrough, let the receiver render) it could be a real feature. Blocked on: the
  Atmos asset is a different (non-Widevine `ctrp`) flavour we'd have to decrypt+remux without
  transcoding, and the user has no Atmos equipment to verify against yet. Park it; don't delete.

## Auth (unchanged from what we already do)
- Same amp-api model: `Authorization: Bearer <JWT>` + `Music-User-Token` + `Origin:
  https://music.apple.com`. Nothing new needed; our proxy + AppleDirectClient already do this.
