# Release Notes — `feat/radio-artist-shazam-v2`

Everything in this branch (40 commits off `main`). Grouped by area, newest work first within each.

## Now Playing — background
- **New "Dynamic" look, more colourful.** Album-colour beat blobs with richer saturation/brightness
  (base alpha 0.70, SAT_BOOST 1.55, VALUE_CEILING 0.84). Beat-reactive, smooth on Fire TV.
- **No more green flash** when opening Now Playing — extracted artwork palettes are cached, so the
  screen shows the song's real colours instantly instead of animating up from a seeded palette.
- **Ambient / "lava lamp" mode** was built (blurred cover, then real colour-blobs matching Apple's
  own `colorBlobs` mechanism) but **removed** — this MediaTek Fire TV can't render 3+ big animated
  translucent layers without visible stutter. The colourfulness was folded into Dynamic instead.
- Background modes: **Dynamic / Projector / Black**.

## Now Playing — crossfade & playback
- **Fixed: wrong song on screen during crossfade.** The display used to flip to the incoming track
  when the fade *started*, so you'd see the next song while still hearing the current one. It now
  flips at the fade **midpoint**, when the incoming track is actually the louder one.
- **Fixed: two songs playing at once** when skipping/navigating mid-crossfade (the incoming crossfade
  player is now fully torn down in `playQueueItem`).
- **Progress bar / scrubber restored** and idle-transition cosmetics fixed (title/artist spacing no
  longer collapses; the focused play/pause glow is no longer clipped while chrome fades).

## Now Playing — lyrics
- **First line is now reachable with the D-pad** (the up-escape guard no longer eats DirectionUp at
  the top of the list).
- **Fixed entry scroll** — lyrics land at their resting position on open instead of snapping on the
  next line.
- **Lyrics translation** — on-device translation, plus a native-first server route that prefers
  Apple's own per-line translation and falls back to machine translation.

## Music videos
- **Album "Music Videos" shelf** — a dedicated server route returns MVs that belong to *this* album
  (matched by album name / track titles), no longer every video the artist ever made.
- **MV picture "bleed" accepted as a hardware limit.** On this MTK Fire TV the secure video buffer
  latches on the compositor and can linger across tabs; every app-side fix was tried and failed, so
  the app hard-stops the video codec on leaving Now Playing and shows a "Press Back to return" toast
  **only** when you switch to another tab with a video active. Audio keeps playing like a song.
- **MV queue** — added videos appear under the current track and are cursor-selectable; interviews /
  uploaded videos play as videos everywhere; better MV audio-quality selection; Library music videos.

## Artist page (V2)
- Redesigned artist page: full-bleed hero, scroll-driven dimming, top-songs/albums/featured/similar
  shelves, stepwise focus-up navigation.
- **editorialArtwork hero** — uses Apple's wide editorial banner (with its theming colours) when the
  artist has one, falling back to the square photo.

## Popular ("best songs") dot
- Uses Apple's real **`popularity`** attribute (`extend=popularity`). Shows the **grey** dot to the
  **left of the track number**, only on the album's standout tracks (relative top-tier, not every
  song), with an artist-top-songs fallback when the attribute is missing.

## Radio
- Radio tab, Shazam-style radio identification, station playback via Apple's working next-tracks
  endpoint.

## Navigation & UI
- **Top bar hides only on Now Playing / lyrics / video** (route-based) — it now stays visible on
  album and artist pages.
- **Unified context menu** across the whole app (one `AmContextMenu`: icons, labels, artwork
  preview), with the long-hold accidental-click fixed and Go-to-Artist/Album everywhere.
- New V2 UI is the default (Home/New naming, Videos + Radio destinations).

## Standalone (on-device playback)
- Standalone decode-on-device path hardened: better diagnostics for a null source, and it no longer
  hangs waiting on a dead proxy when the server is down.
- The **Standalone toggle now lives only in the Dev menu** (removed from the :8080 phone page).
- Still **default OFF** — some encodes have fMP4 segment gaps the PC remux repairs that chop on-device.

## Server / build
- New routes: album music-videos, native-first lyrics translation; `extend=popularity` and
  `extend=editorialArtwork` on the relevant catalog calls.
- CI: bumped `android-actions/setup-android` v3 → v4 (fixed the SDK licence / sdkmanager break).

## Docs
- `docs/apple-apk-findings.md` — reverse-engineering notes on Apple Music's popular dot, ambient
  background mechanism, native lyrics translation, editorialArtwork, and Dolby Atmos/spatial
  (parked: gamdl already downloads it via a wrapper; the open work is passthrough to the receiver).

## Known limits / parked
- MV picture bleed on this specific Fire TV (firmware, not app-fixable).
- Ambient/lava-lamp background (too heavy for this device).
- Dolby Atmos / spatial / hi-res playback (source is downloadable; passthrough not built).
- Native lyrics **pronunciation** (romaji/pinyin) not built.
