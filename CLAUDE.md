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
GitHub Actions (`.github/workflows/android.yml`) builds the debug APK on every push and uploads it as an artifact.

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
- Bearer JWT scraped from music.apple.com at server startup AND on `/auth/status` call
- Storefront auto-detected from `/v1/me/storefront` when MUT is set — cached in `server/src/auth.ts`
- **How user sets MUT**: Fire TV runs HTTP server on port 8080. Open `http://<FireTV-IP>:8080` on phone, paste token

## Playback
- ExoPlayer used directly in `PlayerViewModel` — no MediaController/service IPC (avoids null race)
- `PlayerViewModel` hoisted to `AppShell`, passed down — single instance, shared state
- **Full stream**: `GET /api/stream/:songId` → library IDs (`i.xxx`) use library endpoint, catalog IDs use catalog endpoint → rewrites EXT-X-KEY to proxy → ExoPlayer decrypts AES-128
- Library song IDs start with `i.` — NEVER swap them for catalog IDs; stream route handles both
- When MUT set → always full stream. When no MUT → preview URL fallback
- `playAlbum(songs, idx)` defaults `useFullStream = hasMUT()`
- State (queue + position) persisted to SharedPreferences on `onCleared()`
- Stream serves a **remuxed progressive MP4** (ffmpeg `+faststart` after mp4decrypt) with HTTP Range → ExoPlayer seeks instantly. Apple's decrypted output is fragmented `hlsf`, which ExoPlayer plays unreliably — the remux is required.
- **Multi-segment HLS**: some tracks (e.g. Drake, Eminem) use fMP4 HLS with init-segment + multiple audio segments. `stream_decrypt.py:fetch_encrypted` downloads ALL segments and concatenates them before decryption. Grabbing only `#EXT-X-MAP` (init) causes choppy/silent audio. Variant selection capped at 500 kbps to avoid lossless ALAC variants (which would produce huge files). fMP4 multi-seg tracks get AAC re-encode (`-c:a aac -b:a 256k`) to fix `C2SoftAacDec 0x4004` errors from per-segment header variations; regular HLS uses `-c copy`.
- ExoPlayer built with a 60s connect/read `DefaultHttpDataSource` (first decrypt is slow) + one-shot re-prepare on error. `DefaultLoadControl`: min 15s / max 60s buffer. `DefaultRenderersFactory` with `EXTENSION_RENDERER_MODE_PREFER` + decoder fallback.
- Remote/controller media buttons: handled in `MainActivity.dispatchKeyEvent` AND via a Media3 `MediaSession` (external/Bluetooth controllers route through MediaSession, not dispatchKeyEvent). Rewind/FF keys mapped to prev/next.
- `next()`/`prev()` use `seekToNext/Previous` (smart, wrap at ends); bare `*MediaItem` no-op at boundaries.
- **Standalone (on-device) fallback**: `useStandalone() = !serverPrefs.serverReachable` (health-checked at startup; flipped on a network `onPlayerError`). Uses `AppleDirectClient` (bearer scrape + webPlayback, tries both `universalLibraryId`/`salableAdamId` forms) + `AppleMusicDrmCallback` (Widevine). `usingStandalone` guards against proxy↔standalone loops.
- **KNOWN LIMIT**: standalone only covers *playback decryption* — browse/library/search/lyrics/artwork still need the proxy. So with the server off, the app has no data. True "no-PC" mode (Option A) requires porting all `server/src/routes/` data endpoints to on-device Kotlin.
- PC server IP stored in `ServerPreferences`, set via Dev menu; empty = use `PROXY_BASE_URL` default.

## Server routes
- `GET /api/search?term=` — catalog search
- `GET /api/stream/:songId` — decrypts CENC to a **seekable cache file** (`$TMPDIR/am_stream_cache/`) then serves it with HTTP **Range support** (206) so ExoPlayer can scrub instantly. `stream_decrypt.py` takes `outPath` arg → writes file + prints `ok` (no stdout piping). Concurrent Range requests share one decrypt via `inFlight` map. Asset pick: `28:ctrp256` → any `ctrp` → any URL'd asset (region fallback). On boot, calls `ensureBearer()` (scrapes if empty) and strips non-numeric prefixes (e.g. `a.12345`) from catalog IDs before sending to webPlayback. Falls back to alternate ID form (library↔catalog) if songList is empty.
- `GET /api/lyrics/:songId` — **Apple first**: tries `/syllable-lyrics` (word-level TTML) then `/lyrics` (line-level). TTML parsed via tag-tree walk that separates `ttm:role="x-bg"` bg-vocal spans → `words[]`. Timestamps strip trailing `s` suffix; span matching is namespace-tolerant (`tt:span`). `itunes:timing="Word"` = word-by-word; `"Line"` = line-sync only. **Fallback: lrclib.net** (line-synced LRC, no auth). Returns `{lines, source: apple|lrclib|none}`.
- `GET /api/motion/:songId` — resolves song→album, requests album `?extend=editorialVideo`, returns `{video}` = square motion-art HLS loop URL (or null). Powers animated Now Playing cover.
- `GET /api/library/songs|albums|playlists|artists` — personal library (needs MUT)
- `GET /api/library/playlists/:id/tracks` — playlist tracks. `p.xxx` → library endpoint; `pl.xxx` → catalog endpoint (editorial/shared/generated playlists)
- `GET /auth/status` — scrapes bearer + detects storefront if needed, returns `{hasMUT, hasBearer, mutSetAt}`
- `POST /auth/token {mut}` — store MUT server-side + triggers storefront detection
- `DELETE /auth/token` — clear server MUT
- Server persists bearer + MUT across restarts in `server/auth-state.json`

## Artwork URLs
Template format: `{w}x{h}bb.{f}` — must replace `{w}`, `{h}`, AND `{f}` (→ "jpg").
Library items: artwork may be in `relationships.catalog.data[0].attributes.artwork.url` not `attributes.artwork.url`.

## Navigation
- Top nav bar (TopNavBar): centered pill-style, white pill = selected tab
- Tabs: Listen Now / Library / Search / Now Playing / Dev
- Fire TV Menu button (KEYCODE_MENU): if on Now Playing → toggle queue/lyrics panel; else → navigate to Now Playing
- `PlayerViewModel` and `NavigationViewModel` both hoisted in AppShell via `hiltViewModel()`
- Routes: Home, Library, Search, NowPlaying, DevMenu, AlbumDetail, ArtistDetail, PlaylistDetail
- Album/Playlist detail = full-screen (artwork left panel + tracklist right panel, like Apple TV)
- `KEYCODE_MEDIA_FAST_FORWARD` / `KEYCODE_MEDIA_REWIND` → seek ±15s

## Now Playing screen
- Default view: lyrics (synced timed, past dimmed, active white+large, future dark)
- Menu button toggles to queue view (odd toggleCount = queue, even = lyrics)
- NowPlayingBar hidden when on Now Playing screen
- **Lyrics engine**: per-word color via `AnnotatedString` + `SpanStyle`. Active word lerps grey→white over its duration; slow words (>700ms) get a white glow shadow with triangle envelope (fades out before next word). Active line scales 1.08×. Background vocals: -300ms offset, stay active size until their own `endMs`, same lerp/glow as main. Gap ≥4s → 3-dot placeholder (sequential grey→white grow, then all shrink together). Lyrics jump-scroll instantly on first load; animate after. Only active line receives live `progressMs` — inactive lines get clamped values to skip per-word work.
- Long-press context menu: `delay(150)` before `requestFocus()` so touch-up from long-press doesn't auto-trigger the first item.
- Transport buttons: `border = ClickableSurfaceDefaults.border(noBorder, noBorder)` to suppress yellow focus border.

## Library
- Sort bar above content: SortField (DEFAULT/NAME/ARTIST/DATE) × SortDir (ASC/DESC), reversal applies to all fields incl. DEFAULT
- Playlist cards have ▶ button on right side of name row; long-press to **pin** → pin floats to top (alphabetical among pins), pin icon overlay top-right. Pins persisted to `library_cache` SharedPreferences (`pinned_playlists` key, comma-separated IDs).
- Play + Shuffle buttons at top of album/playlist track lists
- **Persistent cache**: `LibraryViewModel` serializes lists to `library_cache` SharedPreferences (Moshi) → shows instantly on cold start, refreshes in background. Don't blank content to a spinner while `isLoading` if cached data exists (focus escapes to top bar otherwise).
- Long-press (hold OK) a song → context menu (Play Next / Add to Queue); menu auto-focuses first item.
- Library album/artist IDs (`l.`/`r.`) need library endpoints, not catalog — `albums.ts`/`artists.ts` branch on the prefix and resolve to catalog where needed.

## Artist page
- `ArtistDetailScreen` + `ArtistDetailViewModel`, fed by `GET /api/artists/:id/full` (raw amp-api with `views=top-songs,latest-release,full-albums,featured-albums,similar-artists`).
- Library artist ids (`r.`) resolve to catalog first. Sections: hero header, bio, top songs (play/shuffle), latest release, albums, featured, similar artists.

## Now Playing background
- Animated **color-pool visualizer** (`DynamicBackground`): 3 radial blobs (reduced from 5 for Fire TV perf) driven by 2 infinite animators. Palette swatches from cover art, over black. No artwork image → never pixelated. Fullscreen (AppShell overlays nav bar on top layer with `padding(top=0)` for Now Playing). Stream cache cleared on server startup.
- Motion (animated) album art plays as a muted looping video over the cover when `GET /api/motion/:songId` returns one.

## Phone web server (port 8080)
- Runs on Fire TV, open on phone to manage token
- Shows: MUT status, Set Token form, Network Activity log (OkHttp requests), App Logs
- All OkHttp requests logged to `NetworkLog` singleton → shown in 8080 page, auto-refreshes every 3s
- Endpoints: `GET /`, `GET /status`, `GET /logs`, `GET /netlogs`, `POST /set-token`, `POST /clear-token`

## User preferences
- **Caveman words** — short, direct responses. no fluff.
- Don't auto-focus text fields (no `LaunchedEffect { focusRequester.requestFocus() }` on load)
- MUT input only via phone web server (8080), not in-app text field
- Don't use emojis unless asked
- PC Server IP input in Dev menu is an exception — user explicitly requested it
