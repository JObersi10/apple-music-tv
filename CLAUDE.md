# Apple Music TV — Claude Notes

## What this is
Native Android TV / Fire TV Apple Music client.
- **Android app** — Jetpack Compose for TV, Media3 ExoPlayer, Hilt DI, Retrofit+Moshi
- **Proxy server** — Bun + Hono wrapping Apple Music APIs (scraped auth, no Apple Developer account needed)

## Key paths
- Android: `android/app/src/main/java/com/applemusicktv/`
- Server: `server/src/`
- ADB target: your Fire TV's LAN IP — `adb connect <FIRE_TV_IP>` first
- Proxy URL: set `proxyBaseUrl` in `android/local.properties` (gitignored); `app/build.gradle.kts` reads it into `PROXY_BASE_URL` (default `http://10.0.2.2:3000/` for the emulator). Also overridable at runtime in the Dev menu.

## Config files (gitignored — never commit)
- `server/.env` — machine paths (`GAMDL_SITE`, `PYTHON_BIN`, `MP4DECRYPT_BIN`, `FFMPEG_BIN`). Bun auto-loads it. Create via `server/setup-mac.sh` or `setup-windows.ps1`, or copy `server/.env.example`.
- `server/auth-state.json` — persisted bearer + MUT.
- `android/local.properties` — SDK path + `proxyBaseUrl`.

## Build commands
```bash
# One-shot server setup (installs bun/python/gamdl/ffmpeg/mp4decrypt, writes .env)
cd server && ./setup-mac.sh        # or: powershell -File setup-windows.ps1
bun run --watch src/index.ts

# Android
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon
adb connect <FIRE_TV_IP> && adb install -r app/build/outputs/apk/debug/app-debug.apk
```
GitHub Actions (`.github/workflows/android.yml`) builds the debug APK on every push, uploads it as an artifact, **and publishes a rolling `dev` pre-release** (tag `dev`) so anyone can download the APK without logging in to GitHub.

## Build config
- AGP 8.7.0, Kotlin 2.1.20, KSP 2.1.20-1.0.31, Gradle 8.9, Hilt 2.56
- JDK: Android Studio's bundled JBR (`.../Contents/Home`)
- Android SDK: set `sdk.dir` in `android/local.properties`
- minSdk 23 (tv-foundation requirement), compileSdk 35
- `TvLazyVerticalGrid` does NOT exist in tv-material 1.0.0 — use standard `LazyVerticalGrid`
- Clickable `Surface` → `ClickableSurfaceDefaults.*`, non-clickable → `SurfaceDefaults.*`
- No `composeOptions` block needed with Kotlin 2.x compose plugin
- `SurfaceDefaults.shape(...)` doesn't work — pass `RoundedCornerShape(...)` directly

## Auth / MUT flow
- **Music-User-Token (MUT)** — required for full streams, lyrics, personal library
- Android stores MUT in `MutPreferences` (SharedPreferences)
- OkHttp interceptor in `NetworkModule` adds `X-Music-User-Token` header on every Retrofit request
- **ExoPlayer does NOT use OkHttp** — so proxy server must have MUT stored server-side for stream requests
- On app startup (`AppleMusicApp.onCreate`), local MUT is synced to proxy via `repo.syncMUTToServer()`
- Setting MUT via phone web server (8080) also POSTs to proxy server (`repo.syncMUTToServer`)
- Bearer JWT scraped from music.apple.com at server startup AND on `/auth/status` call. **Auto-refreshed every 12h** (`ensureBearer` in `auth.ts` — staleness check against `bearerScrapedAt`). Call `invalidateBearer()` to force immediate re-scrape (done automatically on 401/403 from Apple).
- Storefront auto-detected from `/v1/me/storefront` when MUT is set — cached in `server/src/auth.ts`
- **How user sets MUT**: Fire TV runs HTTP server on port 8080. Open `http://<FireTV-IP>:8080` on phone, paste token
- **MUT expiry notification**: `authErrorFlow` in `MusicRepository` emits on HTTP 401 → `PlayerViewModel` fires an Android system notification (channel `am_alerts`, id 42) with the actual device IP in the body

## Playback
- ExoPlayer used directly in `PlayerViewModel` — no MediaController/service IPC (avoids null race)
- `PlayerViewModel` hoisted to `AppShell`, passed down — single instance, shared state
- **Full stream**: `GET /api/stream/:songId` → library IDs (`i.xxx`) use library endpoint, catalog IDs use catalog endpoint → rewrites EXT-X-KEY to proxy → ExoPlayer decrypts AES-128
- Library song IDs start with `i.` — NEVER swap them for catalog IDs; stream route handles both
- When MUT set → always full stream. When no MUT → preview URL fallback
- `playAlbum(songs, idx)` defaults `useFullStream = hasMUT()`
- State (queue + position) persisted to SharedPreferences on `onCleared()`
- Stream serves a **remuxed progressive MP4** (ffmpeg `+faststart` after mp4decrypt) with HTTP Range → ExoPlayer seeks instantly. Apple's decrypted output is fragmented `hlsf`, which ExoPlayer plays unreliably — the remux is required.
- **Multi-segment HLS**: some tracks use fMP4 HLS with init-segment + multiple audio segments. `stream_decrypt.py:fetch_encrypted` downloads ALL segments in parallel (`asyncio.gather`) and concatenates them before decryption. Grabbing only `#EXT-X-MAP` (init) causes choppy/silent audio. The remux step (`_remux`) uses `-af aresample=async=1` to repair timestamp gaps at segment boundaries (those gaps cause `UnexpectedDiscontinuityException` in ExoPlayer). **Never add `-fflags +genpts` or `+igndts`** — on a fragmented mp4 they shift the timeline ~50ms/min, and `aresample=async=1` then inserts/drops samples for the whole track chasing the bad timestamps. Continuous micro-stretching, plainly audible as warble on sustained vocals; measured 43.6 dB SDR without them vs **-3.7 dB** with them, at identical output size (a shift, not sample loss).
- **Asset selection / remux strategy**: pick `32:ctrp64` → `28:ctrp256` → any `ctrp` → any URL'd asset (only `ctrp` is Widevine-decryptable; `cbcp` is FairPlay, `ibhp` is spatial). The `256`/`64` suffix is the AES key size, NOT bitrate — **the same flavor string delivers AAC on some tracks and lossless ALAC (~1.9 Mbps) on others**, so `stream_decrypt.py` decides by measuring: total `#EXTINF` duration vs decrypted bytes. ≤`LOSSY_CEILING_KBPS` (400) → stream-copy (~1s, no second-generation loss), with an automatic re-encode fallback if the copy comes out <85% of input size **or** if its ffprobe duration deviates from the `#EXTINF` sum by more than max(0.25s, 0.3%) — copy can't run `aresample=async=1`, so nothing else repairs fMP4 segment-boundary gaps on that path. Above it → transcode to AAC `TARGET_KBPS` (256k, `AAC_BITRATE` env), preferring `aac_at` (AudioToolbox) — ~2× faster than ffmpeg's native `aac` **and** measurably better: at 256k, SDR vs a lossless source is 43.6 dB for `aac_at` vs 37.1 dB for native `aac` (and 23.7 dB for native at its 128k default). `-aac_at_quality` is not worth setting: 0/1/2 measure within 2 dB and not monotonically. **Always pass `-b:a` explicitly** — omitting it silently lands on ffmpeg's ~128 kbps default. Variant bandwidth cap (500 kbps) in `resolveMediaPlaylist` only applies to master playlists; most ctrp assets are a single media playlist with no variants to choose from.
- ExoPlayer built with a 60s connect/read `DefaultHttpDataSource` (first decrypt is slow) + one-shot re-prepare on error. `DefaultLoadControl`: min 15s / max 60s buffer. `DefaultRenderersFactory` with `EXTENSION_RENDERER_MODE_PREFER` + decoder fallback.
- Remote/controller media buttons: handled in `MainActivity.dispatchKeyEvent` AND via a Media3 `MediaSession` (external/Bluetooth controllers route through MediaSession, not dispatchKeyEvent). Rewind/FF keys mapped to prev/next.
- **Crossfade**: 5s before song end → build `crossfadeExo` at volume 0, `play()` immediately (starts buffering). Old player fades out over 5s; `crossfadeExo` fades **in only during the last 3s** (`cfFadeStartStep`). On `STATE_ENDED` snap during fade → cancel fadeJob, set `cfExo.volume=1f`, call `cfExo.play()`, swap as main player. If cfExo bad/null → rebuild player + `advanceQueue()`.
- **Decrypt priority**: a foreground `GET /api/stream/:id` **SIGKILLs every other running decrypt — prefetches AND abandoned foreground jobs** (`abortBackgroundJobs`) — four concurrent decrypts split the WAN download four ways (measured 13–17s each vs ~6s, foreground track landing at 33s). A prefetch already in flight for the *requested* song is promoted, not killed. `/prefetch` also refuses to start while a foreground decrypt runs or another prefetch is active; dropping it is safe since the next song boundary re-requests N+1. Android side: `playQueueItem` waits for `STATE_READY` before firing N+1 and cancels the pending `prefetchJob` on every new song, so hammering skip only warms the song you land on.
- **HTTP prefetch**: `GET /api/stream/prefetch/:songId` — fire-and-forget on server, returns 200 immediately while decrypt runs in background. Android calls this in `prefetchSong()` (sends `X-Music-User-Token` header). N+1 prefetched at song start (`playQueueItem`); N+2 prefetched at 2/3 through (`pollProgress`). `preloadedForSongId` tracks which song is prefetched to avoid duplicates. N+2 uses `queue[queueIndex+2]` (not +1) even when `userQueue.size==1`. `restoreState` bypasses `playQueueItem`, so it fires its **own** N+1 prefetch — but only after the restored song reaches `STATE_READY` (bounded 60s wait), since racing two cold decrypts delays the audio needed immediately. Without that, the song after a restored one was always cold and every crossfade out of it hard-cut.
- **Crossfade guards**: never fades when `repeatMode == One` (there is no next song — fading would swap in the wrong track). `repeatMode == All` on the last song wraps to index 0, and `actualNextIdx` is forced to 0 there (`coerceIn` would clamp to `lastIndex` and leave the index on the song being faded out). No next song at all → hard stop, no fade. **Gapless**: consecutive tracks off the same album (same `albumId`, or same `albumName` when ids are null, plus `trackNumber + 1`, and no user queue) skip the fade entirely — live records and segued albums were being talked over.
- **crossfadeSkipSongId**: if cfExo errors, marks that song ID to skip crossfade on retry → plays normally via `advanceQueue`.
- **UI progress cadence**: `pollProgress` loops every 200ms (needed to catch `STATE_ENDED` and the crossfade window promptly) but only pushes `progressMs` into `_state` about once a second. `rememberSmoothProgressMs` interpolates per frame from that anchor, so pushing 5×/sec only recomposed every `PlayerState` reader for no visible gain.
- **Single-item queue**: ExoPlayer holds only one media item at a time. `_state.queue` is our queue list. `playQueueItem(idx)` loads a single item + prepare + play. Auto-advance via `pollProgress` polling `STATE_ENDED` every 200ms → `advanceQueue()`. `onPlayerError` also calls `advanceQueue()`. This avoids ExoPlayer multi-item auto-advance bugs and Bluetooth MediaSession restart issues.
- `next()`/`prev()` call `playQueueItem(queueIndex ± 1)` directly. prev() always goes to previous song (no restart-current threshold).
- **Standalone (on-device) playback** — WORKS, and it's the fast path. `StandalonePreferences` (`standalone_prefs`, StateFlow), **default ON**, toggled on the :8080 page. Now Playing shows a green `ON-DEVICE` badge top-right while it's the active path (`PlayerState.standaloneActive`). `restoreState` uses it too — it bypasses `playQueueItem`, so without its own branch the first song after a restart always paid the proxy decrypt. `useStandalone() = enabled || !serverPrefs.serverReachable`. ExoPlayer decrypts HLS segments as it plays, so a track starts in ~1s instead of the proxy's 15-20s download+mp4decrypt+ffmpeg. Getting it working needed **three** fixes, each hiding the next:
  1. Apple signals CENC as `#EXT-X-KEY:METHOD=ISO-23001-7`, which ExoPlayer's HLS parser rejects outright (`Couldn't match METHOD=...`).
  2. The init segment has **no `pssh` box** (verified — scheme is `cenc`, `tenc` v0, IV size 8). So simply deleting the key line left the CDM with no init data and playback died in `queueSecureInputBuffer` with a bare `IllegalArgumentException`. `rewriteKeyLine` re-emits it as `METHOD=SAMPLE-AES-CTR` with a Widevine KEYFORMAT and a pssh synthesized from Apple's KID.
  3. `AppleMusicDrmCallback.executeProvisionRequest` returned `ByteArray(0)` — a stub. The Fire TV CDM needs provisioning, got nothing, and the session died before any key was requested. **Provisioning is a Google call**, not an Apple one: POST to `request.defaultUrl + "&signedRequest=" + data`.
  The rewritten playlist is written to `cacheDir` and played from `file://`, so segment URIs must be absolutised. `buildStandaloneSource` is **suspend** — callers must await it before `play()`, or the previous track keeps playing and fades back in. `buildCrossfadeExo()` has no DRM of its own, so the crossfade partner needs its own standalone source. Prefetch is skipped in standalone. Three consecutive failures auto-disables the toggle. `PROBE_INIT_SEGMENT` in `PlayerViewModel` dumps `schm`/`tenc`/`pssh` to logcat (`AMProbe`) when this breaks again — it downloads a full init segment, so it's off by default.
- **KNOWN LIMIT**: standalone covers *playback only* — browse/library/search/lyrics/artwork still need the proxy, so with the server off the app has no data. True no-PC mode means porting `server/src/routes/` to Kotlin. The hard parts (bearer scrape, webPlayback, Widevine) are already done in `AppleDirectClient`/`AppleMusicDrmCallback`; what's left is 9 route files of amp-api calls + JSON mapping.
- PC server IP stored in `ServerPreferences`, set via Dev menu; empty = use `PROXY_BASE_URL` default.

## Server routes
- `GET /api/status/apple` — Apple Music services only (`MUSIC_KEYWORDS`); `iTunes Store` was removed because purchase outages fired notifications about a feature this app lacks. Checks Apple System Status (`system_status_en_US.js`), filters for Apple Music / iTunes services, returns `{ok, services[], checkedAt}`. Cached 2 min. Apple now serves **bare JSON, not JSONP** — and axios' default `transformResponse` JSON.parses it, so the body arrives as an object; the code handles both shapes. An event counts as a live issue only when `epochEndDate` is null or in the future (resolved incidents linger in the feed for days). There is no `eventStatus` field. Called by Android on startup; fires notification (id 43) if any service has an issue. Also called automatically when library routes get a 500 from Apple.
- `GET /api/search?term=` — catalog search
- `GET /api/stream/:songId` — decrypts CENC to a **seekable cache file** (`$TMPDIR/am_stream_cache/`) then serves it with HTTP **Range support** (206) so ExoPlayer can scrub instantly. `stream_decrypt.py` takes `outPath` arg → writes file + prints `ok` to stdout (piped to Bun console for 8081 visibility). Concurrent Range requests share one decrypt via `inFlight` map. Asset pick: `32:ctrp64` → `28:ctrp256` → any `ctrp` → any URL'd asset. On boot, calls `ensureBearer()` and strips non-numeric prefixes from catalog IDs. Falls back to alternate ID form (library↔catalog) if songList is empty. `idleTimeout: 0` on Bun server — decrypt takes 5-10s; default 10s timeout would kill the connection.
- Lyrics text is XML-decoded (`decodeEntities`) at tree-build time — Apple's TTML delivers `&` as `&amp;`.
- `GET /api/lyrics/:songId` — **Apple first**: tries `/syllable-lyrics` (word-level TTML) then `/lyrics` (line-level). TTML parsed via tag-tree walk that separates `ttm:role="x-bg"` bg-vocal spans → `words[]`. Timestamps strip trailing `s` suffix; span matching is namespace-tolerant (`tt:span`). `itunes:timing="Word"` = word-by-word; `"Line"` = line-sync only. **Fallback: lrclib.net** (line-synced LRC, no auth). Returns `{lines, source: apple|lrclib|none}`.
- `GET /api/motion/:songId` — resolves song→album, requests album `?extend=editorialVideo`, returns `{video}` = square motion-art HLS loop URL (or null). Powers animated Now Playing cover.
- `GET /api/library/songs|albums|playlists|artists` — personal library (needs MUT). Uses `appleGet()` helper: on 401/403 from Apple, invalidates bearer + re-scrapes + retries once. On 500, triggers Apple status check. Logs Apple's full error body on failure.
- `GET /api/library/playlists/:id/tracks` — playlist tracks. `p.xxx` → library endpoint; `pl.xxx` → catalog endpoint (editorial/shared/generated playlists)
- `GET /api/stream/prefetch/:songId` — fire-and-forget decrypt trigger. Returns 200 immediately; decrypt runs in background via `ensureDecrypted`. If already cached, returns `{cached:true}`.
- `GET /auth/status` — scrapes bearer + detects storefront if needed, returns `{hasMUT, hasBearer, mutSetAt}`
- `POST /auth/token {mut}` — store MUT server-side + triggers storefront detection
- `DELETE /auth/token` — clear server MUT
- Server persists bearer + MUT across restarts in `server/auth-state.json`
- On startup the server prints its LAN IPv4 addresses — that's what gets typed into first-run setup.
- Stream cache (`$TMPDIR/am_stream_cache/`) capped at **500 MB**, LRU by mtime (`evictCache`, byte-based not file-count — track sizes vary 4–65 MB). Cache hits touch mtime. On startup only **partial** (non-`.mp4`) files are deleted; completed files survive restarts, which matters because `bun run start` is `--watch`. Writes to `.tmp` then atomically renames → no partial files served.

## Artwork URLs
Template format: `{w}x{h}bb.{f}` — must replace `{w}`, `{h}`, AND `{f}` (→ "jpg").
Library items: artwork may be in `relationships.catalog.data[0].attributes.artwork.url` not `attributes.artwork.url`.

## App lifecycle
- **Back button from home/browse**: shows exit dialog. The dialog is a real `Dialog` — as a plain overlay `Box`, D-pad focus walked out to the nav bar behind it.
- **Back button from home/browse (detail)**: shows exit dialog ("Exit Apple Music TV?"). Confirm → `activity.moveTaskToBack(true)` — app backgrounds, music keeps playing. Do NOT use `finish()` — that kills the process.

## Navigation
- Top nav bar (TopNavBar): centered pill-style, white pill = selected tab
- Tabs: Listen Now / Library / Search / Now Playing / Dev
- **Non-Fire-TV remotes have no Menu key** (Google TV, Chromecast, Onn). `TvDevice.isFireTv` detects via `amazon.hardware.fire_tv` + MANUFACTURER; when false, Now Playing shows an extra list-icon transport button wired to `toggleQueuePanel()`. Fire TV keeps Menu and gets no extra button.
- Fire TV Menu button (KEYCODE_MENU): if on Now Playing → toggle queue/lyrics panel; else → navigate to Now Playing
- `PlayerViewModel` and `NavigationViewModel` both hoisted in AppShell via `hiltViewModel()`
- Routes: Home, Library, Search, NowPlaying, DevMenu, AlbumDetail, ArtistDetail, PlaylistDetail
- Album/Playlist detail = full-screen (artwork left panel + tracklist right panel, like Apple TV)
- `KEYCODE_MEDIA_FAST_FORWARD` / `KEYCODE_MEDIA_REWIND` → seek ±15s

## Now Playing screen
- Default view: lyrics (synced timed, past dimmed, active white+large, future dark)
- Menu button toggles to queue view (odd toggleCount = queue, even = lyrics)
- NowPlayingBar hidden when on Now Playing screen
- **Lyrics scroll**: user scroll disables auto-scroll; it resumes only once the active line is visible again, and then on the next line change (Apple Music behaviour). No timer — a 5s re-arm yanked the list while you were still reading.
- **Sustained lines**: a line whose `endMs` runs past the next line's `startMs` (Get Lucky) stays lit alongside the newer one — `isActive` is any line whose own window covers now, not just the newest. Not a sticky-header pin; both lines are simply live.
- **Lyrics engine**: per-word color via `AnnotatedString` + `SpanStyle`. Active word lerps grey→white over its duration; slow words (>700ms) get a white glow shadow with triangle envelope (fades out before next word). Active line scales 1.08×. Background vocals: -300ms offset, stay active size until their own `endMs`, same lerp/glow as main. Gap ≥4s → 3-dot placeholder (sequential grey→white grow, then all shrink together). Lyrics jump-scroll instantly on first load; animate after. Only active line receives live `progressMs` — inactive lines get clamped values to skip per-word work.
- Long-press context menu: `delay(800)` before `requestFocus()` + `clickBlocked` flag so OK release from long-press doesn't auto-trigger the first item. Back/Escape key on the overlay Box dismisses without navigating back.
- Transport buttons: `border = ClickableSurfaceDefaults.border(noBorder, noBorder)` to suppress yellow focus border. D-pad Left from Prev button → artist name via `Modifier.focusProperties { left = artistFocusHolder }`.
- **Progress bar**: focusable Surface. When unfocused = passive 4dp bar. When focused = 6dp bar grows, Left/Right moves a scrub cursor (ghost overlay) ±10s, time readout turns white showing scrub position, OK/Enter commits the seek. Cursor resyncs to playback when focus lost.
- **Loading ring**: `PlayerState.isLoading` (set in `pollProgress` from `STATE_BUFFERING` + `playWhenReady || crossfadeInProgress`) draws a spinning arc around the play/pause button and dims the icon. A cold decrypt is 15–20s and the UI otherwise looks frozen. The spinner is only composed while loading, so it costs nothing during playback.
- **··· menu**: Sleep Timer (15/30/45/60 min or End of Song), Beat Pulse (Normal 1×/Strong 2×/Insane 3.5×), Shuffle, Repeat (Off/All/One), Go to Artist, Go to Album. Settings items (Beat Pulse, Crossfade, Shuffle, Repeat) leave the menu open so the label flip is visible and cycling stays fast — only navigation and the sleep timer close it; Back dismisses.

## Library
- Sort bar above content: SortField (DEFAULT/NAME/ARTIST/DATE) × SortDir (ASC/DESC), reversal applies to all fields incl. DEFAULT
- Playlist cards have ▶ button on right side of name row; long-press to **pin** → pin floats to top (alphabetical among pins), pin icon overlay top-right. Pins persisted to `library_cache` SharedPreferences (`pinned_playlists` key, comma-separated IDs).
- Play + Shuffle buttons at top of album/playlist track lists
- **Persistent cache**: `LibraryViewModel` serializes lists to `library_cache` SharedPreferences (Moshi) → shows instantly on cold start, refreshes in background. Don't blank content to a spinner while `isLoading` if cached data exists (focus escapes to top bar otherwise).
- Long-press (hold OK) a song → context menu (Play Next / Add to Queue / Go to Artist / Go to Album); 800ms delay + `clickBlocked` flag prevents accidental first-item trigger. Back/Escape dismisses without navigating back.
- Library album/artist IDs (`l.`/`r.`) need library endpoints, not catalog — `albums.ts`/`artists.ts` branch on the prefix and resolve to catalog where needed.
- DRM failure (failureType 3077) → server returns 404 → ExoPlayer skips to next song without retry loop.

## Artist page
- `ArtistDetailScreen` + `ArtistDetailViewModel`, fed by `GET /api/artists/:id/full` (raw amp-api with `views=top-songs,latest-release,full-albums,featured-albums,similar-artists`).
- Library artist ids (`r.`) resolve to catalog first. Sections: hero header, bio, top songs (play/shuffle), latest release, albums, featured, similar artists.

## Now Playing background
- **`DynamicBackground`** — fluid color backdrop. No artwork image, pure canvas gradient, never pixelated. Fullscreen (AppShell overlays nav bar on top layer).
- **Color extraction**: `rememberArtworkPalette` loads artwork via Coil at 1200px (static artwork URL, NOT the motion video URL), runs `Palette.from()`, picks up to 6 distinct swatches (vibrant → light vibrant → dark vibrant → muted → light muted → dark muted → dominant). Each swatch is pushed toward vivid in HSV: saturation `× SAT_BOOST` floored at `SAT_FLOOR`, value capped at `VALUE_CEILING` (1.45 / 0.55 / 0.80). The value ceiling is the important one — a pale high-value swatch is what makes the backdrop compete with the white lyrics, because pastels read as light grey while deep saturated colors read as color. Don't push `VALUE_CEILING` past ~0.85. A plain saturation *filter* was tried and removed earlier (it stripped vivid pinks/teals). Falls back to near-black palette for truly black artwork (dom luma < 0.06).
- **Blobs**: 4 radial gradient blobs, one draw call each, `BlendMode.Screen` — where blobs overlap, colors add/brighten. Base alpha `0.58 + energy*0.20`. Colors cross-fade over 1.5s on song change. Blob radius `0.62 × max(w,h)`. **Do not exceed 4 blobs** — Screen blend forces an offscreen compositing pass per draw call; 6 blobs caused lag on Fire TV. No hardware blur (no-op on Fire TV API < 31).
- **Motion**: 3 `InfiniteTransition` floats (t1: 20s, t2: 27s, t3: 34s), `LinearEasing`, `RepeatMode.Reverse`. Centers biased toward screen edges so blobs don't converge in the middle.
- **Darkening**: radial gradient at center (black 47%→0%, radius 55% of screen) + horizontal gradient on right half (0%→40% black) for lyrics readability + flat `0x33000000` overlay. Beat pulse scales radius by `1 + energy*0.25`.
- **Beat reactivity**: `BeatAnalyzer` is a @Singleton *bus* (`energy: StateFlow<Float>`, `latencyMs`); each ExoPlayer gets its own `BeatProcessor : BaseAudioProcessor` from `beatAnalyzer.newProcessor()`, injected via `BeatAwareRenderersFactory`. Only the **active** processor publishes (`beatAnalyzer.activate(p)`) — an AudioProcessor can't be shared between two audio sinks, and the crossfade player needs its own so beats keep working after the swap (`promoteCrossfadeBeat()`). Detection is bass onset, not RMS level: mono downmix → one-pole LPF at 130 Hz → energy in fixed 10 ms windows → onset when a window exceeds `mean + 1.5·stddev` of the last ~1 s, 120 ms refractory → punch-then-decay envelope (~250 ms fall). Emits only on >0.015 change to limit recomposition. Collected in `DynamicBackground` only (prevents full-screen recompose). `beatMultiplier` (1×/2×/3.5×) scales raw energy before animation — user-controllable via ··· menu ("Beat Pulse: Normal/Strong/Insane"). Only active for PCM_16BIT; bypassed for float output.
- **Perf budget**: 6 draw calls per frame (4 blobs + center darken + right gradient). 3 drift animators. Do NOT increase beyond 4 blobs, add `Modifier.blur()`, multi-pass draws, or `CubicBezierEasing` — all caused lag on Fire TV.
- Motion (animated) album art plays as a muted looping video (`MotionCover`) over the static cover when `GET /api/motion/:songId` returns a URL. Palette is extracted from the STATIC artwork URL, not the motion video.
- **`MotionCover` green flash fix**: shutter stays black (`setShutterBackgroundColor(BLACK)`); `ready` flag resets to `false` on `ON_PAUSE` and flips back on `ON_RESUME` once decoder outputs first frame. Lifecycle observer added via `DisposableEffect(lifecycleOwner, exo)`. `update` lambda reattaches player to view. Do NOT use `setShutterBackgroundColor(TRANSPARENT)` — causes green YUV frame on surface reattach.

## Phone web server (port 8080)
- Runs on Fire TV, open on phone to manage token
- Shows: MUT status, Set Token form, Network Activity log (OkHttp requests), App Logs
- All OkHttp requests logged to `NetworkLog` singleton → shown in 8080 page, auto-refreshes every 3s
- Also settable from the phone page: **Lyrics Offset** (global, no per-song), **Beat Latency**, **Crossfade** (1–15s slider). Each backing prefs class exposes a `StateFlow` that `PlayerViewModel` collects, so edits apply live without an app restart — a prefs class that only exposes a getter will silently need a restart.
- Endpoints: `GET /`, `GET /status`, `GET /logs`, `GET /netlogs`, `POST /set-token`, `POST /clear-token`, `POST /set-lyrics-offset`, `POST /set-beat-latency`, `POST /set-crossfade`, `POST /set-standalone`

## User preferences
- **Caveman words** — short, direct responses. no fluff.
- Don't auto-focus text fields (no `LaunchedEffect { focusRequester.requestFocus() }` on load)
- MUT input only via phone web server (8080), not in-app text field
- Don't use emojis unless asked
- PC Server IP input in Dev menu is an exception — user explicitly requested it

## First-run setup (onboarding)
- `OnboardingPreferences` (`onboarding_prefs`, versioned via `CURRENT_VERSION` so a new step doesn't replay the whole flow). `AppShell` shows `OnboardingScreen` instead of the nav shell until `completed`.
- 5 steps: server IP + health check → token pairing → remote type → crossfade → tips.
- **The IP is a button, not a live text field.** A focused `BasicTextField` opens the Fire TV IME by itself, and `SoftwareKeyboardController.hide()` is a **no-op on Fire TV** — dismissal must also call `InputMethodManager.hideSoftInputFromWindow`. Press OK to edit, Done/Back to close.
- Step 2 polls for the token and auto-advances **only on a false→true transition**. It counts only the *on-device* MUT: the proxy persists its own copy in `auth-state.json`, so asking the server reports a token that isn't there.
- `restoreState()` is gated on `completed` — otherwise a first-run user gets audio behind the setup screen.
- `TvDevice.isFireTv` (via `amazon.hardware.fire_tv` + MANUFACTURER) decides whether Now Playing shows the extra on-screen queue/lyrics toggle; step 3's override wins over detection.
- `QrCode` (`util/QrCode.kt`) — hand-rolled byte-mode, EC level M, versions 1–6. Verified by decoding rendered matrices with OpenCV's `QRCodeDetector` and pinned in `QrCodeTest`. **Does not match segno byte-for-byte** (segno emits an extra `0x00` after the terminator); both decode, so don't "fix" it toward segno. Rendered with a ≥4-module quiet zone (spec minimum). **Not yet scanned by a real phone.**

## State persistence
- `saveState()` runs on the 10s ticker (while playing), **on pause**, and **immediately on every song change**. It **returns early during a crossfade**: `_state.currentSong` is already the *next* song while `player` is still the outgoing one, so a save there pairs one song's title with another's position — that was the cause of restores landing on an old track.
