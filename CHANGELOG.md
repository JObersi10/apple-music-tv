# Changelog

User-facing changes, newest first. Each entry covers one PR/branch. Only net
features and fixes to previously-shipped behavior are listed — not the internal
back-and-forth of tuning something added in the same entry.

## feat/radio-artist-shazam-v2 — 2026-10-27

### Added
- **Settings redesigned** as a tvOS split-view: a category sidebar (Playback / Lyrics /
  Now Playing / Audio & Sync / System / Developer) with a content pane on the right.
- **Game controller support**: R1/L1 switch tabs, Options = Now Playing / lyrics↔queue,
  Y = play/pause, L2/R2 = previous/next.
- **Sign in on the TV** during setup (in-app Apple Music login) — with a note that it
  works best with a touchscreen or cursor; the phone token is still the reliable way.
- **Music videos stream through the PC server** when it's reachable (no more cross-tab
  video "bleed"); falls back to on-device otherwise.

### Fixed
- **Adding a Radio song to a playlist** works now (it added the station, not the track).
- **Reopening while Apple Music radio is playing** restores the station (and Now Playing
  shows it) instead of getting stuck on the last song.
- **The current radio song updates live** on Now Playing without leaving and returning.
- **Matched / iTunes-Match songs no longer skip**; uploaded songs play.
- **Faster song start through the server** (~8× quicker) and correct high-quality audio.
- **Motion album art is smooth again** (and full quality) instead of choppy.
- **Lyric translation fades in** smoothly; held-word letters no longer clip the next word.
- **A song that fails mid-play resumes where it was**, not from 0:00.

## feat/radio-artist-shazam-v2 — 2026-10-19

### Added
- **Uploaded & iTunes-Match songs play now.** Tracks you added to your library
  from your own files (that used to fail silently) stream correctly — the app
  plays your actual uploaded file, both through the server and on-device.
- **Japanese kanji now reads out in the pronunciation line** (not just kana),
  via the downloadable pronunciation pack.
- **Lyric translation on the main (Spicy) lyrics renderer.** A translated-meaning
  line now shows under each lyric, dim and small, in your chosen language — on the
  renderer the app actually uses (it was only on the old one before). It fades in
  when it loads. Settings → **Translate lyrics** is now one simple Off → language
  picker, with an optional **"Only non-Latin lines"** so you can gloss just
  Japanese/Chinese/Korean/… and skip languages you already read. Translation also
  falls back to Google when Apple's own translation is missing or left untranslated.
- **Japanese pronunciation now reads kanji, via a downloadable pack.** Turning on
  Lyrics pronunciation downloads a Japanese dictionary (~13MB, once) so kanji get
  proper romaji — not just kana. The dictionary isn't shipped inside the app, so
  the app stays small; it's fetched on first use.
- **Unsynced lyrics scroll in time with the song.** Lyrics that have no per-line
  timing now glide from top to bottom as the song plays, instead of sitting still.
- **Home refreshes itself** every 20 seconds, so it recovers on its own after a
  network blip without you pressing refresh.
- **App installs on phones/tablets** too (for testing), not just TV.
- **Community translations.** The app's own text can now be translated by adding a
  language file — see docs/CONTRIBUTING-i18n.md (Dutch example included).
- **Volume Leveling, rewritten (Settings).** Now a single, constant per-track
  gain (ReplayGain-style: measure a track once, then play every later listen at
  the right level from the first second). No more ramping or drifting mid-song.
  New tracks play flat the first listen, then are levelled on replay.
- **Lyrics credits toggle (Settings → "Lyrics credits").** Show or hide the
  "Written by / Provided by / Made by / Uploaded by" footer under the lyrics.
- **Translate language picker (Settings → "Translate language").** Choose the
  target language for "Translate Lyrics" (was stuck on the device language, so
  English→English did nothing).
- **Lyrics prefetch for the next song.** The next track's lyrics are fetched as
  soon as the current song's load, so skipping shows them with no blank delay.
- **Responsive Now Playing layout.** Padding, the cover, the left column and the
  split now scale to the screen, so the player holds up from phones and tablets
  to 720p→4K TVs (and the left side is roomier).
- **"No Lyrics Found" auto-hides** after 10 seconds instead of sitting there.

### Fixed
- **One-frame lyric flash** when a line finished — it no longer blinks out for a
  frame as it hands off to the faded state.
- **Prev song's lyrics no longer hang** ~half a second on skip — when the next
  song's lyrics are already prefetched they swap in almost instantly.
- **Artwork and translations recover through network blips** — they now retry
  instead of staying blank after a failed connection.
- **"Provided by" credit** no longer shows when Lyrics credits are turned off.
- **Motion-cover drift.** The animated album art is now a TextureView, so it
  grows and drifts *with* the cover during the idle "breathe" instead of sliding
  off on its own.
- **1px black edge** around the album cover (static and motion) is gone.
- **Lyrics glow.** The held/slow-word glow now actually renders (it was drawn in
  a way that cast no halo on this hardware, so it flashed once or not at all),
  and it tracks the text's brightness — word-by-word songs glow with the letter
  swell, line-by-line songs glow as the line fills (no more grey text that glows).
- **Sung lyrics are now full white** (were a dim grey); not-yet-sung lines are a
  bit more legible, and the Now Playing background is a touch darker for contrast.
- **Smoother line fade-out** — a finished line dims and shrinks together instead
  of greying out abruptly and then shrinking.
- **Up Next total time** no longer reads 0:00 for a song added from Browse/Search.
- **Up Next text** is readable on bright/light album backdrops (was faint grey).
- **Home stops oscillating** between "needs refresh" and normal on startup.
- **Song title auto-scrolls** again when it's too long to fit.

### Known / next
- Uploaded & iTunes-Match library songs (the ones synced from a Mac) don't play
  yet — they need Apple's `getExtendedAssetUrls` path, not the stream decrypt.
- Lyric translation shows a target-language picker, but the translated text and
  pronunciation (romaji/pinyin) sub-line aren't displayed in the v2 renderer yet.
- Playback reporting (so TV plays show in your Apple Music history) is not done.
