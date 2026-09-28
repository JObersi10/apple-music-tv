# Release Notes

## Unreleased (post-1.3, lyrics polish)
- **Karaoke lyrics reworked to match Apple Music.** Words do a left→right gradient wipe and rise a
  small **uniform** amount (rises fast, then holds). **Held words** (≥1s) swell and their letters glow
  in sequence, then settle at the same level as the rest — no reflow of the lines below, no end-jump.
  The swell/glow respects Low Power / Reduce Motion.
- **Background vocals fixed** — no longer show up pre-lifted/lit before their turn or stay frozen lifted
  on already-sung lines; more gap above them.
- **"No Lyrics Found"** shows only after the fetch completes; **real lyrics stay on idle** (queue and
  the message fade); **next song's lyrics are prefetched**; lyrics + motion reload on cold reopen.
- **Standalone lyrics** now merge syllable spans ("fa vo rite" → "favorite").
- Motion cover fills the box (no black gap on fast skip); translated lyrics dimmer + left-aligned.

## v1.3

### Sign-in / getting your token
- **New companion phone app — [AM MUT Extractor](https://github.com/JObersi10/am-mut-extractor).**
  Sign into Apple Music on your phone (where the keyboard works), copy the token, paste it on the TV's
  `:8080` page. This is now the recommended way to get your Music-User-Token.
- **On-TV "Connect to Apple Music" sign-in is disabled for now** (shows a "Coming soon!" toast). The
  in-app WebView login is unreliable on Fire TV — Amazon's WebView keyboard mangles password fields
  (first character doubled, backspace broken, passwords barely register). The code stays in for a
  future revisit; use the phone extractor meanwhile.
- Docs (README, technical guide, MUT flow) now point at the extractor app.

### Now Playing
- **Dynamic background** is more colourful (richer saturation/brightness), still smooth on Fire TV.
- **No more green flash** when opening Now Playing — artwork palettes are cached.
- **Crossfade fixes** — the on-screen song now flips at the fade midpoint (no more "wrong song on
  screen" during a crossfade); no more two tracks playing at once when skipping mid-crossfade.
- **Translated lyrics** are indented and dimmer when inactive, so the translation reads as a secondary
  gloss instead of competing with the main line.
- Cover art fetched at a lighter resolution — loads a bit faster on this panel.

### Navigation / UI
- **New UI is now the only UI** (Home / New / Videos / Radio). The old screens and the Dev "New UI"
  toggle were removed.
- **Album page:** pressing down from the top bar (or opening an album) now lands on **Play**, not the
  artist-name link.
- **Dev menu:** setting/clearing the PC server IP now re-checks reachability immediately, so the
  Server status updates without a manual re-check.

### Playback
- **Standalone (on-device) no longer turns itself off when there's no PC server.** It used to disable
  the toggle after a few failures even though standalone was the only path — now it only falls back to
  the proxy (and disables) when a server is actually reachable.

### Library
- **Playlist song count is accurate.** Placeholder/unavailable rows in Apple's feed no longer inflate
  the "N songs" count (e.g. a 297-song playlist that reported 300).

### Music videos & artist page (from the prior branch work)
- Album "Music Videos" shelf shows this album's videos, not every video the artist made.
- Artist page V2 with the wide editorial hero banner.
- "Popular" dot uses Apple's real popularity signal, only on standout tracks.

### App
- Version bumped to **1.3** (versionCode 4) — shown in Dev → App version and Android app details.

### Known limits / parked
- On-TV Apple sign-in (use the phone extractor for now).
- Music-video picture "bleed" across tabs on this MediaTek Fire TV (firmware, not app-fixable).
- Dolby Atmos / spatial / hi-res passthrough.
- Native lyrics pronunciation (romaji/pinyin).
