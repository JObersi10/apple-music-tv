# Handoff — Apple Music TV

Last updated: 2026-08-01

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
