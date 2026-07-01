# Apple Music TV — Claude Notes

## What this is
Native Android TV / Fire TV Apple Music client.
- **Android app** — Jetpack Compose for TV, Media3 ExoPlayer, Hilt DI, Retrofit+Moshi
- **Proxy server** — Bun + Hono wrapping Apple Music APIs (scraped auth, no Apple Developer account needed)

## Key paths
- Android: `android/app/src/main/java/com/applemusicktv/`
- Server: `server/src/`
- ADB target: `192.168.1.246:5555` — always `adb connect 192.168.1.246` first
- Proxy URL: `http://192.168.1.190:3000/` (hardcoded in `app/build.gradle.kts` as `PROXY_BASE_URL`)

## Build commands
```bash
# Android (SABRENT external drive must be mounted for SDK)
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon
adb connect 192.168.1.246 && adb install -r app/build/outputs/apk/debug/app-debug.apk

# Server
cd server && bun run --watch src/index.ts
```

## Build config
- AGP 8.7.0, Kotlin 2.1.20, KSP 2.1.20-1.0.31, Gradle 8.9, Hilt 2.56
- JDK: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- Android SDK: `/Volumes/SABRENT/Applications/AndroidSDK` (on external drive — must be mounted)
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
- Remote media buttons (play/pause/next/prev) handled in `MainActivity.dispatchKeyEvent`
- **Standalone mode**: if no PC server IP set in Dev menu → `PlayerViewModel` uses `AppleDirectClient` (bearer scrape + webPlayback API) + `AppleMusicDrmCallback` (Widevine via Apple license server) — no laptop needed
- PC server IP stored in `ServerPreferences`, set via Dev menu bottom field

## Server routes
- `GET /api/search?term=` — catalog search
- `GET /api/stream/:songId` — decrypts CENC to a **seekable cache file** (`$TMPDIR/am_stream_cache/`) then serves it with HTTP **Range support** (206) so ExoPlayer can scrub instantly. `stream_decrypt.py` takes `outPath` arg → writes file + prints `ok` (no stdout piping). Concurrent Range requests share one decrypt via `inFlight` map. Asset pick: `28:ctrp256` → any `ctrp` → any URL'd asset (region fallback).
- `GET /api/lyrics/:songId` — **Apple first** (word-by-word TTML, parsed via real tag-tree walk that separates `ttm:role="x-bg"` background-vocal spans). **Fallback: lrclib.net** (line-synced LRC, no auth) when Apple has none — resolves song meta (title/artist/album/duration) to query lrclib. Returns `{lines, source: apple|lrclib|none}`. TTML spans → `words[]`, bg vocals → `background{words[]}`.
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

## Library
- Sort bar above content: SortField (DEFAULT/NAME/ARTIST/DATE) × SortDir (ASC/DESC)
- Playlist cards have ▶ button on right side of name row
- Play + Shuffle buttons at top of album/playlist track lists

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
