# Handoff — Apple Music TV

Last updated: 2026-07-29

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
