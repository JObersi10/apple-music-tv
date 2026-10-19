# Changelog

User-facing changes, newest first. Each entry covers one PR/branch. Only net
features and fixes to previously-shipped behavior are listed — not the internal
back-and-forth of tuning something added in the same entry.

## feat/radio-artist-shazam-v2 — 2026-10-19

### Added
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
