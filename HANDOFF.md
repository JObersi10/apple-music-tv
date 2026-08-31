# Handoff — Apple Music TV

Last updated: 2026-08-30

## Session 2026-08-30 — dead-song fallback, Listen Now resilience, scroll perf (v1.2)

Playback:
- **Dead library songs now play.** Uploaded/matched "internet songs" (`i.` rows) whose
  in-library release is withdrawn fail on-device DRM (`No value for license`) and have **no
  catalog relationship**. On a standalone failure `PlayerViewModel.onPlayerError` resolves a
  catalog id — the linked `catalog` relationship first, else a **title+artist catalog search**
  (`AppleDirectClient.searchCatalogSongId`, artist+title match only, never a blind top hit) — and
  rebuilds the on-device source from that catalog copy, exactly like playing from Apple Music.
  Proxy is now the *last* resort (`retryStandaloneOrProxy`), not the first. Verified: "It's Me,
  It's Verity" → catalog `6799279367` decrypts + plays on-device, no proxy.

Listen Now:
- **Personalization no longer vanishes.** `HomeViewModel` cache guard is now personalization-aware
  (`isPersonalized()` = presence of the `picks`/`gradient` shelves), not section-count based. A
  charts/moods/categories feed can't overwrite or get cached over a personalized feed. Fixes Home
  collapsing during Apple's intermittent `/me/recommendations` 500 streaks.
- **Dev → Re-check Server / Refresh** now reload Home+Library **after** reachability settles
  (`recheckServer(onDone=)` / `refresh(onDone=)`), fixing a race where the reload ran on the stale
  path and Listen Now didn't update.

Perf / UX:
- **Menu scroll lag** — `InAppWebServer.addLog` mirrored every log line to the proxy over HTTP;
  when the proxy is unreachable (standalone-primary) each blocked an IO thread ~1s and starved
  Coil image decode. Now skipped unless `serverReachable`.
- **Motion-card scroll jank** — `MotionArtwork` now waits **1 s after focus settles** before
  building the ExoPlayer. Arrowing through "Playlists Made for You" was building+releasing a
  decoder per card passed (the `ExoPlayerImpl Init…Release` churn). Scroll past fast → never starts.
- **Now Playing grey background** — palette load now uses a software RGB_565 bitmap, reuses Coil's
  cache, and retries once; a covers-with-art-but-grey-backdrop case (DIGIDI DIGIDI, real blue/purple)
  was the palette's separate fetch returning a non-readable/null bitmap. Missing/failed artwork now
  gets a **stable seeded colour** from the song id instead of flat grey (true B&W covers still grey).
- **Skeleton** — first row is the big Top Picks lockup (210 dp) so the swap to real Home doesn't jump.

Removed:
- **Radio shows** dropped from Browse (shelf `stations` items + "NEW RADIO SHOW" spotlight cards),
  both `browse.ts` and standalone `DirectBrowseSource` — they don't play yet.

Version bumped to **1.2** (`versionCode 3`); Dev-menu build line reads `BuildConfig.VERSION_NAME`.

**Radio filter is isLive-aware**: Browse hides only NON-live stations (radio shows/episodes);
Apple Music Radio LIVE stations (`attributes.isLive === true`) stay and play. A first pass filtered
ALL `type==="stations"` and wrongly removed the live radio shelf. Applied in `browse.ts` (shelf +
spotlight) and standalone `DirectBrowseSource`.

Built this turn: **sleep-timer 5s fade-out** (`SLEEP_FADE_MS`, volume ramp in `pollProgress`;
`sleepFadeActive` restores volume on cancel) and **Create Station** long-press item (playlist +
album context menus → `createSongStation` → `playStation("ra.{catalogId}")`; Apple exposes a
per-song station `ra.{catalogId}`, verified via `songs/{id}?include=station`).

**Backlog — queue editing (design agreed, not built).** Queue is `_state.queue` + `queueIndex`;
ExoPlayer holds one item, so edits are pure list mutation. Remove: drop from list, decrement index
if below current (removing the current = advance). Reorder: move in list, fix index. UI: long-press
a queue row → move-mode (D-pad Up/Down moves, OK drops), only over `userQueue`, tail stays fixed.
No decoder juggling — low risk.

**Backlog — lyrics translation (Apple-style, not built).** Show a dim translated line under each
original. First probe whether Apple's `songs/{id}/lyrics` (or `/syllable-lyrics`) accepts a target
language param → render second TTML line. If not, on-device ML Kit translation (no external API,
fits the no-external-calls rule). Decide after the probe.

**Next up (bug, not done): uneven vertical focus in Listen Now / Browse.** Scrolling DOWN
through shelves, a newly-hovered row/item isn't centered the way the others are — it lands
"half a bit" off, not consistently centered like the rest. Likely a LazyColumn
bring-into-view / scroll-alignment issue (focused shelf not settling to a consistent viewport
position). Investigate `HomeScreen`/`BrowseScreen` LazyColumn + item focus/`bringIntoView`.

**Next up (idea, not done): queue ↔ lyrics switching UX.** The Menu-button toggle between the
queue panel and lyrics feels clunky; user wants a better interaction. No design chosen yet — worth
exploring (swipe/tabs/peek, or a segmented control) next session.

---

## Session 2026-08-19 — Now Playing customization, perf/footprint, updater grant fix (v1.1 release prep)

## Session 2026-08-19 — Now Playing customization, perf/footprint, updater grant fix (v1.1 release prep)

Now Playing background + customization:
- **Distinct-colour orbs** — `NowPlayingScreen.spreadByHue` now keeps a swatch that differs in
  hue *or* brightness (was hue-only, 36°), so single-colour-family albums yield real
  muted/dark/light variants instead of computed shades. Dynamic blobs are each **pinned to one
  palette colour** (no pair-crossfade) and shrunk to 0.42 of the frame so they read as separate
  colour pools (oil-painting look) instead of Screen-blending into one gradient wash.
- **Beat consistency** — the Dynamic energy spring is now critically damped (`dampingRatio 1f`,
  was underdamped 0.5) so each hit lands once and decays without ringing.
- **New settings** (all in `PlayerViewModel` + `DevMenuScreen`, persisted in prefs):
  Orb speed (Projector), Lyrics size (0.8/1.0/1.35, applies to synced + unsynced + full-screen),
  Rounded/square artwork, Motion artwork toggle, Reduce motion, **Low Power Mode**, and
  **per-mode Intensity memory** (`intensity_dynamic` / `intensity_projector`; hidden on Black).
- **Volume leveling** (experimental, default off) — `media/GainProcessor` RMS AGC in the
  Media3 `DefaultAudioProcessorChain` (`gapConceal → beatProcessor → gain`). **Known bug: it
  can mute the stream** until skip/back — parked at user's request, do not ship enabled.

Perf/footprint: animation State read inside `drawBehind` (draw phase) not at composable scope,
killing ~60 fps recompose GC churn; palette bitmap decoded at 256² with memory cache disabled;
Coil memory cache hard-capped at 48 MB.

Crash fixes:
- **ANR "does not have a focused window"** (was killing the app) — the lyrics list handed focus
  to the active line even when it was scrolled off-screen (a detached `FocusRequester`). The
  `focusProperties { enter }` now only targets the active line if it's in
  `layoutInfo.visibleItemsInfo`, else `Default`.
- A JNI `accessed stale Local` abort was observed under CheckJNI (debug only) — **not our code**
  (the app has no native libs); it's a library (Media3/Coil) leaking JNI locals, aggravated by
  the Fire TV's memory pressure. Should not abort in a release build (CheckJNI off).

Updater — **grant fix (was silently broken on ungranted devices)**: `DevMenuScreen.UpdatesSection`
now checks `canRequestPackageInstalls()` before `UpdateChecker.install`; if missing it parks the
downloaded APK, sends the user to `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (with a `package:` URI,
falling back to the generic intent), and re-fires the install from an `ON_RESUME` lifecycle
observer. Verified against the live `v1.0` GitHub release; single flavour so any `.apk` asset
matches. To test the download/install leg, install a build whose versionName is below the
release, then Check for Updates.

## Session 2026-08-14 — Cache cap, self-updater, crash/bug reporting (v1.1)

Features:
- **100 MB artwork cache cap** — `AppleMusicApp` now implements Coil `ImageLoaderFactory`
  with a `DiskCache` capped at 100 MB (LRU) + memory at 15%. Coil's default was ~2% of the
  whole partition (unbounded on big drives). The 24 h stale-wipe still runs on top.
- **In-app updater** (`util/UpdateChecker`) — polls GitHub `releases/latest` on launch from
  `AppShell`; a red dot shows on the ⚙ tab (`TopNavBar.updateAvailable`) and Settings →
  Software (`DevMenuScreen.UpdatesSection`) shows version + notes + Download & Install. APK
  streams to `cacheDir/updates/`, installed via `FileProvider` (`${applicationId}.updates`) +
  `ACTION_VIEW` (direct install). Manifest gained `REQUEST_INSTALL_PACKAGES` + the provider;
  `res/xml/file_paths.xml` scopes it to `updates/`.
- **Beta updates toggle** (`util/UpdatePreferences`, Settings → Software) — off = stable
  `releases/latest`; on = newest non-draft release (prereleases included). Both the launch
  auto-check and the manual check honour it.
- **Crash log + bug report** — `util/CrashReporter` installs a global uncaught-exception
  handler (chains the previous one) writing the last crash to `filesDir/crash.log`.
  `InAppWebServer` added `GET /report` (downloadable text bundle: version + device + last
  crash + net/app logs), `POST /clear-crash`, and a Bug Report card on the phone page.

Review fixes (kotlin-reviewer): cancellation no longer swallowed by `runCatching`
(`rethrowCancellation()` + `ensureActive()` in the download loop); progress marshalled to
Main, throttled to whole-percent; APK URL must be `https://`; FileProvider scoped to `updates/`.

Version bumped to **1.1** (`versionCode 2`). Left as-is by request: the `/report` endpoint
stays unauthenticated (consistent with the rest of the LAN web server), version compare stays
numeric-only (no `-beta`-suffix handling).

## Session 2026-08-09 — Now Playing polish + background play / PiP

## Session 2026-08-09 — Now Playing polish + background play / PiP

UI/UX:
- Cross-dissolve transitions between player ⇄ full-screen lyrics ⇄ ambient screensaver
  (`Crossfade` over an `npMode` in `NowPlayingScreen`).
- Screensaver reverted to *replacing* the player (an overlay attempt let the player show
  through the 0.68 scrim); the shared `DynamicBackground` stays behind, so the swap is clean.
- Full-screen lyrics: auto-return to the play button after 5 s; the timer now arms on
  focus-in (not just scroll) so it actually fires; extra right margin so wide lines don't clip.
- Active lyric line no longer scales 1.08× (left-anchored) — that pushed wide lines past the
  right edge; emphasis now comes from font size only. Top/bottom of the lyric list soft-faded.
- Title marquee fades at both edges while scrolling (left edge solid at rest).
- Pause dots between lines inset to align with the lyric text. "Next: …" toast lowered
  toward the corner. Screensaver removed from the ··· menu (still in Settings).

Correctness:
- **Double-skip fixed** — the 200 ms poll loop kept firing `advanceQueue()` on `STATE_ENDED`
  while the next standalone source built. New `awaitingSongStart` flag also pins the UI clock
  at 0:00 on skip (was showing the previous song's position/duration) and guards the advance.
- Word-by-word: lyrics parser now recurses to leaf `<span>`s (nested word timing was
  collapsing to one whole-line "word"); syllable-lyrics tried on all endpoints before line.
- Search playlists: proxy path falls back to a direct Apple catalog search when it returns none.
- Beat detector widened (100→180 Hz, 2-pole) + sensitivity 1.5→1.1 so higher-keyed / dense
  electro kicks register.

Features:
- **Background play** (Settings toggle, default on): `MainActivity.onStop` no longer pauses
  when enabled or in PiP; the existing `AppleMusicPlaybackService` keeps audio alive. When
  paused, `keepScreenOn` is dropped so Fire TV's own screensaver/sleep runs.
- **Picture-in-Picture** (··· menu): `MainActivity.enterPip()` + `onPictureInPictureModeChanged`
  → `PlayerState.isInPip`; `AppShell` swaps to a minimal `PipView` (darkened art + title, no
  beat). Manifest: `supportsPictureInPicture`, `resizeableActivity`, PiP configChanges. **May
  be unsupported on some Fire TV hardware** — `enterPip` is a guarded no-op there.
- **24 h cache expiry**: lyrics cache entries carry a timestamp (evicted >24 h);
  `AppleMusicApp` clears Coil memory+disk cache once per 24 h.
- Onboarding "How to use it" step updated with the new features.

## State of the app

Works end-to-end: browse, library, search, playback (full stream + crossfade), lyrics
(word-sync), Now Playing background, motion cover, artist/album/playlist detail.

Durable architecture notes live in `CLAUDE.md`. This file is only the running log of
what changed recently and what is still unverified.

## What was done this session

### Audio quality — regression found and fixed
Tracks sounded degraded, worst on sustained vocals. Two wrong guesses before the real
cause:

- **Not** the encoder. Benchmarked with ffmpeg's `asdr` filter against a lossless ALAC
  reference: `aac_at` @256k = 43.6 dB, native `aac` @256k = 37.1 dB, native @128k
  (ffmpeg's silent default) = 23.7 dB. `-aac_at_quality` 0/1/2 land within 2 dB of each
  other and not monotonically — not worth setting.
- **Actual cause:** the `-fflags +genpts+igndts` and `-avoid_negative_ts make_zero` flags
  added earlier the same session. On a fragmented mp4 they shift the timeline ~50 ms per
  minute; `aresample=async=1` then chases the bad timestamps by inserting/dropping
  samples for the whole track. Continuous micro-stretching, audible as warble. Measured
  **-3.7 dB SDR with them vs 43.6 dB without**, at identical output size — a shift, not
  sample loss. Removed, with a DO-NOT-ADD comment in `_remux`.

Also corrected: `CLAUDE.md` claimed `32:ctrp64` delivers compressed AAC. It does not —
real measurements are 1327–1931 kbps (lossless ALAC). `stream_decrypt.py` now **measures**
source bitrate (`#EXTINF` sum vs decrypted bytes) instead of guessing from segment layout:
≤`LOSSY_CEILING_KBPS` (400) → stream-copy, above → transcode to 256k with explicit `-b:a`.

### Crossfade correctness
- **Repeat One** no longer crossfades. It was building a fade into `queue[idx+1]` and
  swapping it in, silently overriding repeat.
- **Repeat All on the last track** now wraps and fades into index 0. Fixed a latent bug
  found doing it: `actualNextIdx = (queueIndex+1).coerceIn(0, lastIndex)` clamps to
  `lastIndex` on a wrap, leaving the index on the song being faded *out* of.
- **Gapless**: consecutive tracks off the same album (same `albumId`, `trackNumber + 1`,
  no user queue) hand off with no fade. Live records and mixes were getting talked over.
- `restoreState` now fires its own N+1 prefetch after the restored song reaches
  `STATE_READY`. N+1 lives in `playQueueItem`, which restore bypasses — so after every
  app restart the *next* song was guaranteed cold and its crossfade always hard-cut.

### Stream cache and server
- Capped at **500 MB**, LRU by mtime, **byte-based** (track sizes vary 4–65 MB, so a file
  count is not a real ceiling).
- Startup now deletes only partial (non-`.mp4`) files. Completed files are atomically
  renamed and stay valid — and with `--watch` on, wiping the cache on every code edit
  would be brutal.
- Hot reload: `bun run --watch` is now the default for both `dev` and `start`.
  `start:once` is the no-watch escape hatch.
- Copy path gained a duration check: ffprobe the output against the `#EXTINF` sum and
  re-encode on a mismatch. Copy can't run `aresample=async=1`, so nothing else repairs
  fMP4 segment gaps there.

### `/api/status/apple` crash
`res.data.replace is not a function`. Apple stopped serving JSONP; the endpoint returns
bare JSON, and axios' default `transformResponse` JSON-parses anything JSON-shaped
regardless of content-type. Fixed with `responseType: "text"` + an identity transform and
a shape-tolerant parse.

**Second bug hiding behind it:** the parser branched on `e.eventStatus`, a field that does
not exist in Apple's feed — so even without the crash it would have reported everything
operational forever. Rewritten around the real fields (`statusType`, `epochStartDate`,
`epochEndDate`) with an `isOngoing` predicate, since Apple leaves resolved incidents in
the feed for days.

### UI
- **Loading ring** on the play/pause button while a track decrypts (`PlayerState.isLoading`).
  A cold track takes 15–20 s and the UI previously looked frozen.
- **Backdrop vibrancy**: palette swatches now get a saturation floor/boost and a **value
  ceiling** (`SAT_BOOST`/`SAT_FLOOR`/`VALUE_CEILING`). Pale high-value swatches were what
  made the backdrop compete with the white lyrics; deep saturated colors read as color,
  pastels read as light grey. Flat veil dropped 0x33 → 0x22, right-side gradient raised
  0x66 → 0x7A so the lyrics half stays dark.
- **··· menu** settings items (Beat Pulse, Crossfade, Shuffle, Repeat) leave the menu open
  so the label flip is visible and cycling stays fast. Only navigation and the sleep timer
  close it; Back dismisses.
- **Menu button from the Artist screen** pops back to the existing Now Playing instead of
  pushing a second copy on top of it.
- **Crossfade duration** (1–15 s) is now settable from the phone web server on :8080,
  backed by `CrossfadePreferences`. Like the other prefs classes it exposes a `StateFlow`
  so `PlayerViewModel` picks up edits live — a getter-only prefs class silently needs an
  app restart.

## Session 2026-07-29 (later) — setup, QR, focus, perf

### Added
- **First-run setup**, 5 steps (server IP + health check, token pairing, remote type, crossfade, tips). See CLAUDE.md for the gotchas — the Fire TV IME one is the nasty one.
- **QR code** for the pairing URL. Hand-rolled encoder, verified by decoding with OpenCV, pinned in `QrCodeTest` (runs in CI).
- **On-screen queue/lyrics toggle** for remotes with no Menu key, auto-detected.
- **Loading ring** on play/pause during a decrypt.

### Fixed
- Exit dialog let D-pad focus escape to the nav bar → now a real `Dialog`.
- Step 2 reported a token that wasn't there (it was asking the *server*, which keeps its own copy in `auth-state.json`).
- Restore landed on old songs: saves now happen on pause and on song change, and are skipped mid-crossfade (the title/position mismatch was the real cause).
- Repeat One crossfaded into the wrong song; Repeat All didn't wrap on a manual Next, and never prefetched the wrap target.
- Gapless: consecutive same-album tracks no longer get faded.
- Skipping left four decrypts racing (foreground track landed at 33s). Foreground requests now SIGKILL prefetches, and Android waits for `STATE_READY` before warming N+1.
- Crossfade/gapless log spam: ~50 duplicate lines per song, each an HTTP POST. Once per song now.
- `··· ` menu settings items no longer close the menu.
- Menu from Artist pops back to the existing Now Playing instead of pushing a second one.

### Confirmed working by the user
Onboarding flow, keyboard behaviour, step 2 token detection, Google TV toggle button, gapless, Repeat One, Repeat All wrap, restore, audio quality, beat pulse colours, crossfade, progress bar, lyrics.

### Session 2026-08-01 — standalone playback, lyrics polish

**Standalone playback toggle** (:8080 card). On-device Widevine instead of the proxy's
download + mp4decrypt + ffmpeg. This is a *speed* feature: ~1s start vs 15-20s cold.
Server still used for browse/library/lyrics. `playStandalone()` had been dead code since
it was written; it's now reachable and reworked into a suspend `buildStandaloneSource()`.

**Two ecc:kotlin-reviewer passes**, four real bugs found and fixed:
1. A seek while paused never reached the UI (my own regression from throttling progress
   pushes to ~1/sec — the gate only ran while playing).
2. The crossfade fade-out overwrote `fadeJob` without cancelling the song-start fade-in;
   on a track shorter than ~2x the crossfade length both ramped the same player.
3. Standalone raced its own setup — `playQueueItem` fell through to `play()` + fade before
   the Widevine source existed, so the previous track kept playing and faded back in.
4. The crossfade always loaded `repo.streamUrl`, and `buildCrossfadeExo()` has no DRM, so
   in standalone mode every transition errored into a hard cut.
Also: `AudioDeviceCallback` was never unregistered; `OnboardingViewModel` shadowed
kotlinx's atomic `StateFlow.update` with a plain read-modify-write.

**Other**
- Lyrics `&amp;` — TTML is XML; entities now decoded server-side.
- Lyrics auto-scroll rejoins at the next line change once the active line is visible again,
  instead of snapping back on a 5s timer.
- Sustained lines (Get Lucky) stay lit while the next line comes in.
- Onboarding: number-pad keyboard for the IP; finishing setup syncs the MUT and refetches
  Home + Library.
- Errors are Toasts.
- Progress pushed to state ~1x/sec instead of 5x (the UI interpolates per frame anyway).
- `iTunes Store` dropped from Apple status keywords — purchase outages aren't our problem.
- Dev menu: "Replay Setup".
- Server prints its LAN IP on startup.

### Standalone playback — WORKING as of 2026-08-01

Instant start (~1s vs 15-20s). No decrypt, no ffmpeg, no cache, no prefetch. Three
stacked bugs, each hiding the next — full detail in CLAUDE.md:
1. `METHOD=ISO-23001-7` unparseable by ExoPlayer → rewrite the key line.
2. No `pssh` in the init segment → synthesize a Widevine one from Apple's KID.
   (Confirmed by probing: scheme `cenc`, `tenc` v0, IV size 8, zero pssh boxes.)
3. `executeProvisionRequest` was a `ByteArray(0)` stub → real provisioning against
   Google's server. This was the last one, and the reason nothing worked.

Diagnosing #2 needed a box-level dump of the init segment; `PROBE_INIT_SEGMENT` in
PlayerViewModel turns it back on (logcat tag `AMProbe`). Off by default — it costs a
full segment download per play.

**Open question:** standalone is strictly faster than the proxy for playback. Worth
considering as the default, with the proxy as fallback rather than the other way round.
Not changed — that's a product call.

**Confirmed working:** playback, crossfade, and the ON-DEVICE badge. Standalone is now
the **default** path with the proxy as automatic fallback; `restoreState` uses it too.
Still unverified: gapless (same-album handoff) while standalone is on.

### Session 2026-08-01 (later)

- **Standalone: reverted to default OFF** (see CLAUDE.md). Fast, but audibly choppy on many tracks and the rebuffer-based fallback does not catch them. Crossfade + restore use it.
- **Search**: songs section (was fetched but never rendered), two compact columns,
  smaller album grid, slimmer search bar, recent-search chips, clears properly.
- **Lyrics**: removed the pinned/sustained line — it was the cause of lines sticking
  to the top and fading, because lrclib sets endMs to the next line's startMs so
  nearly every line qualified. Scroll only fires when the line leaves frame.
  Progress quantised to 50ms (was recomposing the whole panel 60x/sec — that was the
  "Without Me is laggy" bug). Unsynced lyrics now show, dimmed and unseekable.
- **prev** restarts the song past 10s. Cover cross-fades over 1s.
- **`[FMT]` diagnostic** per track; it's what found the buffer/stutter issue.
- **apple-design skill** installed at `~/.claude/skills/apple-design/`. First use:
  size-specific type tracking on Now Playing.

### Session 2026-08-01 (final) — full standalone

Ported every remaining proxy-only endpoint. Standalone now runs the whole app with
the server off: browse, library, search, artwork, lyrics and playback. See CLAUDE.md
for the architecture; `DirectBrowseSource` mirrors home.ts/browse.ts section for
section on purpose.

Also: Back returns to Listen Now; search gained a songs section, two-column compact
rows, recent-search chips and a long-press menu; prev restarts past 10s; cover
cross-fades; `[FMT]` diagnostic per track.

### Session 2026-08-01 (evening) — chop root-caused, NOT what we thought

The chop was fully diagnosed (see the long entry below and the memory note
`standalone-chop-is-fdk-decoder.md`). It is NOT fMP4 boundary gaps. Chain of proof:
- Captured decoded PCM: dropouts are runs of *exactly 2048 zero samples*, ~6–16% of
  playback, cutting real audio mid-waveform.
- logcat: `C2SoftAacDec: aacDecoder_DecodeFrame decoderErr = 0x4004 ... substituting
  silence`. Android's FDK decoder rejects those frames.
- NOT CPU (disabling beat DSP + motion video only marginally helped; content-specific).
- NOT SBR/codec: forced 28:ctrp256 (AAC-LC, no SBR) — still 392× 0x4004. Both ctrp
  flavors fail → **on-device Widevine CENC decryption is corrupting ~16% of frames**
  before decode. Proxy is clean because it decrypts server-side (like gamdl).

Dead ends tried & confirmed (do not repeat): ffmpeg decoder extension (built for
armeabi-v7a, isAvailable=true, but DRM routes encrypted audio to MediaCodec only →
FORMAT_UNSUPPORTED_DRM); alternate AAC decoder (only c2.android.aac.decoder exposed);
PCM packet-loss concealment (GapConcealProcessor — can't recover 16% missing audio,
stutters; left gated OFF). All diagnostics LEFT IN the tree at user request.

**DECISION: pursue Path 1 — replicate gamdl on-device.** Run our own Widevine CDM to
get the content key, decrypt segments in-app (Bento4/CENC-style), feed CLEAR AAC to the
already-bundled ffmpeg decoder (which then IS usable, since frames are no longer
encrypted). gamdl reference: github.com/glomatico/gamdl — see its interface/song.py,
downloader/song.py, ammuxer.py, and MEDIA_CODEC_FLAVOR_MAP (28:ctrp256=AAC-LC,
32:ctrp64=HE-AAC). Hard part = obtaining/using an L3 CDM (.wvd) on-device.

FIXED this session: lyrics-on-restore (restoreState now calls loadLyrics/loadMotion);
web-server Copy button (execCommand fallback for non-secure HTTP context). Loudness:
confirmed NO gain field in webPlayback JSON (AMWP log) — raw masters, nothing to apply.

### PATH 1 STATE (2026-08-04) — decrypt works; open UI/data bugs

In-app decrypt WORKS (clean AAC, seek via synthesized sidx, prefetch warms decrypt
cache, dedupe + generation guard stop fast-skip pileup, no server toasts in standalone,
no per-song fade-in). Menu clip fixed in BOTH album + playlist (heightIn 340 + verticalScroll)
— that was why "Go to Artist/Album" never showed (clipped off-screen).

STILL OPEN (need fresh logcat after a play — couldn't repro cheaply):
- Motion (animated cover) not showing. getMotion route exists (proxy MotionResponse +
  DirectMusicDataSource editorialVideo). Check AMWP/motion logs + whether motionUrl is null.
- Artist name not highlightable in Now Playing = song.artistId null. enrichSongIds calls
  repo.getSong; verify DirectMusicDataSource.getSong fills artistId for playlist songs.
- 505 lyrics don't render though lrclib returns synced=true — display guard
  (currentSong.id vs songId mismatch?) in loadLyrics/LyricsPanel. Data is fine.

### PATH 1 IN PROGRESS (2026-08-03) — on-device decrypt mostly working

On-device software Widevine CDM PROVEN (key matches server; see memory
`standalone-chop-is-fdk-decoder.md`). New files: `media/widevine/WidevineCdm.kt`,
`WvdBlob.kt` (embedded gamdl WVD), `CencDecryptor.kt`. Flags in PlayerViewModel:
`DECRYPT_IN_APP=true` (routes standalone through in-app decrypt), `PROBE_CDM_KEY=false`.
Ground-truth tool: `server/ref_key.py <songId>`.

- Apple audio = ONE fMP4 file, segmented only by #EXT-X-BYTERANGE (not separate segs).
  `buildDecryptedStandaloneSource` fetches the whole file once, `CencDecryptor.decryptWhole`
  rewrites moov (enca→mp4a, strip sinf/pssh) + AES-CTR every moof/mdat, writes clear_<id>.mp4,
  plays it with NO DRM. Decrypt runs ~1-5s, sizes sane (out ≈ in − ~560B).
- BUG FIXED (not yet built): ExoPlayer NPE in FragmentedMp4Extractor.parseTraf — left senc/
  saiz/saio in moof after clearing samples. Added `rewriteMoof`/`rewriteTraf` to strip them +
  patch trun.data_offset (−removed bytes). NEEDS BUILD+TEST.
- Also fixed (not built): exit dialog didn't dismiss on Exit — now sets showExitDialog=false +
  finish() fallback if moveTaskToBack fails (AppShell.kt:306).
- Blocked on: SABRENT drive was unmounted (JAVA_HOME/SDK live there) — remount to build.
- NEXT after build works: it downloads WHOLE file up front (~1-5s to first audio). Optimize to
  stream/range later. Watch for any songs still failing (was "some songs" before the moof fix).

### UNRESOLVED — start here

1. **Standalone audio chop → Path 1 (on-device decrypt).** Root cause is on-device CENC
   decrypt corrupting frames (see session note above + memory). Next session builds the
   gamdl-style in-app decrypt. ffmpeg decoder already bundled and ready to consume clear AAC.
2. **No loudness normalisation.** CONFIRMED: webPlayback JSON has NO per-asset gain
   field (checked via AMWP diagnostic — no gain/loudness/sound/volume/normal keys).
   Raw master differences. Only real fix = read the `iTunNORM` atom from each init
   segment (heavy) or a running-RMS compressor (rejected — pumps).
3. **Standalone can't be split from the data path.** One toggle switches both, so
   testing the data port means accepting the chop. A second preference would fix it.
4. **Search results in standalone** — confirm artist/album subtitles are populated;
   the direct search mapping may not fill `albumName`.
5. **Related albums** shelf is empty in standalone (documented, not a bug).
6. **`usingStandalone`** is still a write-only field. Dead code.
7. **Gapless is a clean cut**, not sample-accurate — ExoPlayer re-prepares between
   tracks. True gapless needs a pre-rolled second player swapped at the boundary.

### Known, not done
- `usingStandalone` is a write-only field. Dead.
- Toast collector has no `repeatOnLifecycle` — fires while backgrounded. Harmless.
- Gapless is a clean cut, not sample-accurate; ExoPlayer still re-prepares.
- Search history/suggestions and lazy album warming: still not started.

### To check next session
- **Standalone**: toggle ON, play a cold song (~1s?), check `[standalone]` in the log,
  then test a crossfade and toggling back OFF mid-session.
- Get Lucky sustained lines; lyrics scroll rejoin.
- Onboarding number pad; setup-finish refresh.

### To check next session
1. **Scan the QR with a phone.** Never actually tried. Encoder decodes from a clean PNG; camera-off-a-TV is untested.
2. **Skip mid-decrypt.** Server should log `aborting prefetch <id>` and the new song should land in ~8–15s instead of 30+. Not yet observed in a real log.
3. **Re-check Server** with the server off then on — Listen Now and Library should refetch.
4. **Loading ring animation smoothness.** It was described as glitchy; it now spins via a rotated layer instead of re-issuing `drawArc` per frame. Unverified whether that was enough.
5. **General choppiness on Now Playing.** Prime suspect is `pollProgress` pushing state 5×/sec while the screen is open. Deliberately not changed — needs profiling, not guessing.

### Not done
- No Dev-menu button for `resetOnboarding()` (the function exists).
- Gapless is a clean cut, not sample-accurate — ExoPlayer still re-prepares, so a small buffer gap remains. True gapless needs a pre-rolled second player swapped at the boundary with no fade curve.
- `MUSIC_KEYWORDS` in `apple-status.ts` still includes `iTunes Store`, so purchase-system outages fire a notification about a feature this app doesn't have.

## Known issues / not yet confirmed

- **Repeat All wrap** — the fade into index 0 is untested. Watch that the title advances
  to track 1 rather than staying on the last song; that's the `actualNextIdx` fix.
- **Gapless** — logic is in and logs `[CFXO] gapless: ...`, but no album has been listened
  through yet to confirm the handoff is actually clean.
- **Manual skip is a cold decrypt.** Prefetch only warms N+1 and N+2 in queue order, so
  skipping twice quickly or jumping to a random track means a 15–20 s wait. The loading
  ring makes it legible; it does not make it fast.
- **Storefront detect** still 500s on startup (Apple's `/v1/me/storefront`). Defaults to
  "us". Only affects catalog region.
- **Standalone mode covers playback decryption only.** Browse/library/search/lyrics/artwork
  all still need the proxy, so with the server off the app has no data. True no-PC mode
  means porting every `server/src/routes/` data endpoint to on-device Kotlin.

## Ideas evaluated and rejected

- **Play the first 15–30 s while the decrypt finishes.** Doesn't work with a progressive
  MP4: `+faststart` puts the `moov` up front declaring total duration and the full sample
  table, parsed once at open. Growing the file can't change that, and shifting
  Content-Length breaks Range requests. Chunked-without-length kills seeking.
- **Rewrite the stream as lazy per-segment HLS.** ffmpeg was only 13 of 24 s, so the
  architecture isn't the bottleneck — the download is. Also needs `mp4decrypt` run on
  init+segment pairs emitting segments without a duplicate `moov`, and per-segment
  independent transcoding of a lossless source reintroduces boundary artifacts.
- **Retry `stream_decrypt.py` on transient Apple failure.** A retry on a hung request
  means the crossfade window expires while waiting — a longer silence instead of a clean
  skip.
- **Prefetching track 1 when an album or playlist is merely opened.** Wasted decrypts for
  browsing.
