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

## 2. Now Playing background = ambient EDITORIAL VIDEO — NOT BUILT (this is "Dynamic v2")
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

## 3. Native lyrics translations + pronunciation — NOT BUILT (candidate upgrade)
- Apple's lyrics entity carries **translations** and **pronunciation/transliteration** natively:
  `isTranslationAutomaticallyCreated`, `getTranslationAvailableLiveResult`,
  `getTranslationSelectedLiveResult`, `lyricsTranslationsEnabledByDefault`, layout
  `lyrics_word_pronunciation`. Capability endpoint: `musicSubscription/timeSyncedLyrics`.
- Could replace our on-device Google-translate gloss with Apple's official per-line translation +
  romaji/pinyin pronunciation. Exact request param not a dex literal (built at runtime) — likely an
  `l=`/`extend`/`with` on the `/lyrics` or `/syllable-lyrics` call. Needs probing with a real bearer.

## 4. editorialArtwork (richer hero theming) — NOT BUILT (nice-to-have)
- Albums/artists expose **`editorialArtwork`**: `getEditorialArtworkImageUrl`,
  `getEditorialArtworkGradient`, `getEditorialArtworkBGColor`, `getEditorialArtworkTextGradient`.
- A wide hero image + gradient + bg colour + text gradient — Apple uses it for artist/album hero
  headers and readable text overlays. Request via `extend=editorialArtwork`. Would upgrade the
  ArtistDetail/AlbumDetail hero look.

## 5. Not worth borrowing
- SpatialAudio / Dolby Atmos / Hi-Res Lossless traits (`SongTrait_HighResolutionLossless`,
  `KEY_DOLBY_ATMOS_*`, `SUPPORTSSPATIALIZATION`) — we don't decode Atmos/spatial; irrelevant.

## Auth (unchanged from what we already do)
- Same amp-api model: `Authorization: Bearer <JWT>` + `Music-User-Token` + `Origin:
  https://music.apple.com`. Nothing new needed; our proxy + AppleDirectClient already do this.
