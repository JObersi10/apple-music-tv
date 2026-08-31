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
- **Standalone (on-device) playback** — WORKS, and it's the fast path. `StandalonePreferences` (`standalone_prefs`, StateFlow), **default OFF**, toggled on the :8080 page — see the stutter note below. Now Playing shows a green `ON-DEVICE` badge top-right while it's the active path (`PlayerState.standaloneActive`). `restoreState` uses it too — it bypasses `playQueueItem`, so without its own branch the first song after a restart always paid the proxy decrypt. `useStandalone() = enabled || !serverPrefs.serverReachable`. ExoPlayer decrypts HLS segments as it plays, so a track starts in ~1s instead of the proxy's 15-20s download+mp4decrypt+ffmpeg. Getting it working needed **three** fixes, each hiding the next:
  1. Apple signals CENC as `#EXT-X-KEY:METHOD=ISO-23001-7`, which ExoPlayer's HLS parser rejects outright (`Couldn't match METHOD=...`).
  2. The init segment has **no `pssh` box** (verified — scheme is `cenc`, `tenc` v0, IV size 8). So simply deleting the key line left the CDM with no init data and playback died in `queueSecureInputBuffer` with a bare `IllegalArgumentException`. `rewriteKeyLine` re-emits it as `METHOD=SAMPLE-AES-CTR` with a Widevine KEYFORMAT and a pssh synthesized from Apple's KID.
  3. `AppleMusicDrmCallback.executeProvisionRequest` returned `ByteArray(0)` — a stub. The Fire TV CDM needs provisioning, got nothing, and the session died before any key was requested. **Provisioning is a Google call**, not an Apple one: POST to `request.defaultUrl + "&signedRequest=" + data`.
  The rewritten playlist is written to `cacheDir` and played from `file://`, so segment URIs must be absolutised. `buildStandaloneSource` is **suspend** — callers must await it before `play()`, or the previous track keeps playing and fades back in. `buildCrossfadeExo()` has no DRM of its own, so the crossfade partner needs its own standalone source. Prefetch is skipped in standalone. Three consecutive failures auto-disables the toggle. `PROBE_INIT_SEGMENT` in `PlayerViewModel` dumps `schm`/`tenc`/`pssh` to logcat (`AMProbe`) when this breaks again — it downloads a full init segment, so it's off by default.
- **Standalone is default OFF.** Chop affects many ordinary tracks (Love Me Again, Without Me, Bonetrousle, otherside), and crucially those tracks do **not** rebuffer — so the stutter fallback never trips on them. It only catches genuine underruns. The chop is fMP4 segment gaps that the proxy repairs with `aresample=async=1`; nothing on-device does that today.
- **Standalone stutter fallback**: some encodes have fMP4 segment gaps the proxy's `aresample=async=1` remux repairs and ExoPlayer just plays through (otherside, Bonetrousle). Buffer size was raised (min 50s / max 180s / **32MB `setTargetBufferBytes`** — the default byte cap is video-sized and starves a 320kbps stream) but that only reduced it. So `PlayerViewModel` counts rebuffers per song and after `STUTTER_LIMIT` (3) moves that song to the proxy at the current position and remembers it in `proxyOnlySongIds`. `[FMT]` logs codec/bitrate/rate/channels/path/buffered% per track — that log is what identified this.
- **Dead library song → catalog copy fallback**: an uploaded/matched library row (`i.`) can have a withdrawn in-library release that fails on-device DRM (`No value for license`) AND **no `catalog` relationship**. On a standalone failure, `PlayerViewModel.onPlayerError` resolves a catalog id — `AppleDirectClient.resolveCatalogFor` (the `catalog` relationship) first, else `searchCatalogSongId(title, artist)` (catalog search, accepts only an artist+title match, never a blind top hit) — and rebuilds the on-device source from that catalog copy (`song.copy(id=catId)`), like playing from Apple Music. Only if that fails does `retryStandaloneOrProxy` fall to the proxy. `catalogRetriedSongId` guards one attempt per play (reset in `playQueueItem`). Verified: "It's Me, It's Verity" → catalog `6799279367` plays on-device.
- **Withdrawn tracks**: Apple 404s assets pulled from the catalogue (`failureType 3077`). The server caches those ids (`unavailableIds`) so ExoPlayer's retries don't each cost a webPlayback + license round-trip, and Android skips them without retrying. A library row can point at a withdrawn *release* while a different release of the same song is still live — those cannot be auto-mapped.
- **KNOWN LIMIT**: standalone covers *playback only* — browse/library/search/lyrics/artwork still need the proxy, so with the server off the app has no data. True no-PC mode means porting `server/src/routes/` to Kotlin. The hard parts (bearer scrape, webPlayback, Widevine) are already done in `AppleDirectClient`/`AppleMusicDrmCallback`; what's left is 9 route files of amp-api calls + JSON mapping.
- PC server IP stored in `ServerPreferences`, set via Dev menu; empty = use `PROXY_BASE_URL` default.
- **Music video (secure surface / library bleed)**: a music video keeps playing AUDIO across tabs like a song; the PICTURE must show ONLY on Now Playing. The bleed = a secure (HDCP/Widevine) SurfaceView on the `setZOrderMediaOverlay(true)` hardware plane keeps its last protected frame latched there; View flags (GONE) and disabling the video *track* on a live decoder do NOT reap it. Fix: `MusicVideoViewModel.detachVideo()` does a full audio-only REBUILD (`buildAndPlay(disableVideo=true, seekMs=pos)`) — releases the secure decoder + clears the surface synchronously — then `AppShell` UNMOUNTS the PlayerView (`surfaceMounted=false` via `LaunchedEffect(isOnNowPlaying)`, after detach) so the content-free SurfaceView is destroyed and the plane freed. `attachVideo()` rebuilds WITH video on return. Ordering (release decoder → then destroy view) is the trick. Don't revert to track-disable (bleeds) or full-release-no-rebuild (kills audio). See memory `video-surface-bleed`.

## Server routes
- `GET /api/status/apple` — Apple Music services only (`MUSIC_KEYWORDS`); `iTunes Store` was removed because purchase outages fired notifications about a feature this app lacks. Checks Apple System Status (`system_status_en_US.js`), filters for Apple Music / iTunes services, returns `{ok, services[], checkedAt}`. Cached 2 min. Apple now serves **bare JSON, not JSONP** — and axios' default `transformResponse` JSON.parses it, so the body arrives as an object; the code handles both shapes. An event counts as a live issue only when `epochEndDate` is null or in the future (resolved incidents linger in the feed for days). There is no `eventStatus` field. Called by Android on startup; fires notification (id 43) if any service has an issue. Also called automatically when library routes get a 500 from Apple.
- `GET /api/search?term=` — catalog search. Also returns `curators[]` (editorial **categories**): curators, apple-curators, AND multirooms, each tagged `kind` (`multiroom`|`curator`|`apple-curator`), multirooms first. **Multiroom discovery trick**: multirooms surface only when `editorial-items` is in the search `types` — and Apple 400s that type unless a **broad** type set rides along (`artists,albums,songs,playlists,curators,apple-curators,music-videos,stations,editorial-items` + `with=serverBubbles,topResults`). The response then has a `category` group of `editorial-items` whose `attributes.link.feature==="multirooms"` and `link.url` = `.../multi-room/<id>`; extract `<id>`. Result-group keys are inconsistent (`curator`/`category`/…) so scan every group by item `type`.
- `GET /api/browse/curator/:id?apple=0|1` — a curator page as `{title, description, sections:[{title, albums}]}`. `apple=1` → `apple-curators`, else `curators`. Rich apple-curators (Tomorrowland Live Sets) hang content off a **grouping → tabs → children**: fetch `/v1/editorial/{sf}/groupings/{gid}?include=tabs`, take the default tab's children (kind 326/345), shelf title from `attributes.name` (NOT `title`). Simple curators (Formula 1) fall back to the flat `playlists` relationship.
- `GET /api/browse/categories` — the genre/mood/decade **tile grid** (Apple's "Browse by Genre"). Three sibling editorial rooms — Genres `6456176470`, Moods & Activities `6456176472`, Decades `6456176471` (`/v1/editorial/{sf}/rooms/{id}?include=contents&extend=editorialArtwork`) — each a list of `apple-curators` with editorial artwork. Returns `{sections:[{title, items:[curator]}]}`; Rewind/Replay/Wrapped filtered, "Apple Music " name prefix stripped. Rendered as photo tiles atop the Browse tab; a tile opens its `apple-curator` category page. Ported to standalone (`DirectMusicDataSource.getCategories` via `edRoom`).
- `GET /api/browse/multiroom/:id` — editorial multiroom (`/v1/editorial/{sf}/multirooms/{id}`) → `{title, description, sections}`; children kind 404=hero(desc), 345=shelf. **Section key must be `albums`, not `items`** — that's what the Android `HomeSection` model reads (an `items` key parses to empty shelves).
- `GET /api/browse/room/:id` — a plain editorial ROOM (the "See all"/"More" page behind a Browse shelf; a shelf's own editorial-element id IS its room id, e.g. Daily Top 100 = `6503108310`). Flat contents → one section.
- `GET /api/albums/station/:id/tracks` — a radio station's rolling queue. Apple streams stations track-by-track: the ONLY working call is **POST `/v1/me/stations/next-tracks/{id}`** with `{}` body (all `/stations/next`, `/stations/queue`, GET variants 405). Returns 1–3 catalog songs per call; the route POSTs ~12× (deduped) → ~20-track queue. Standalone mirror: `DirectMusicDataSource.stationTracks`. Station cards call `playStation`. See memory `station-playback`.
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

## Spotlight rows (Home + Browse)
- **Home "Top Picks for You"** (`style:"picks"` in `home.ts`): `/me/recommendations` has no shelf by that name, so it's rebuilt from the lead item of each personalized artist/station rec (kind `music-recommendations`, excluding Playlists Made for You / Recently Played), captioned by its row title. Rendered as big square lockups (`AlbumCard` size 210). Leads the page; "Playlists Made for You" (`style:"gradient"`, `GradientCard`) sits ~4th. Recently Played is never the hero.
- **Browse "New"** (`style:"spotlight"`): the REAL Apple Featured shelf — grouping element kind `316`, whose `relationships.children` are editorial cards (kind `317` album/playlist, `320` radio-show). Each card's `attributes.designBadge` is the exact label ("ADD TO YOUR LIBRARY" / "UPDATED PLAYLIST" / "NEW RADIO SHOW" / "NEW RADIO EPISODE"); `contents.data[0]` is the album/playlist/curator/station. `SpotlightHeroCard`: label + title + artist ABOVE the wide art (its OWN aspect ratio — forcing 16:9 letterboxed = off-centre), short editorial blurb captioned inside. Do NOT revert to the old lead-item synthesis (it dropped playlists/radio).
- Both mirrored on the standalone direct path (`DirectBrowseSource`; rec `kind` captured from the feed). `HomeSection`/`AlbumDto` carry `style`, `tagline`, `wideArtworkUrl`; `editorialNotes` holds the short blurb. Section `style` routes rendering in `HomeScreen`/`BrowseScreen`.
- **Search "More to Explore"**: pill links → editorial destinations via `onCuratorClick(id, kind)`; kind maps to a Category route prefix (`room-`, `ac-`, `grouping-`, `c-`). Links: Music Videos (`grouping-34`), Behind the Songs (`ac-1554941247`), Charts/Genre/Moods/Decades (`room-…`). `browse/room/:id` prefixes apple-curator/curator ids (`ac-`/`c-`) and `CategoryScreen` routes those tiles into nested category pages.
- **Music Videos / groupings**: `GET /api/browse/grouping/:id` (e.g. `34` = Music Videos) returns a MultiRoomDto whose shelves are `videos` (music-videos + uploaded-videos) or `albums`. `CategoryScreen` renders `videos` shelves as 16:9 thumbnails and plays them via `playerVm.playVideos(dtos, idx)`. `CategoryViewModel` handles the `grouping-` prefix. Mirrored standalone (`DirectMusicDataSource.getGrouping` + `edItemToVideo`).
- **Interviews**: `uploaded-videos` (Watch Interviews, Behind the Songs, radio interviews) are treated as **videos** everywhere (browse `/`, grouping, curator `groupingSections`) — before, they fell through to the album branch and rendered as empty, unplayable cards.
- **Home personalization guard**: `HomeViewModel.isPersonalized()` = a feed contains a `picks` (Top Picks) or `gradient` (Playlists Made for You) shelf. `/me/recommendations` 500s in streaks; a non-personalized feed (charts/moods/categories) must NEVER overwrite or get cached over a personalized one — this is keyed on personalization presence, NOT section count (the old size≤4 test wiped good caches once moods+categories pushed it >4). `load()` keeps looping attempts while it only has a non-personalized feed but a personalized cache.
- **Dev refresh race**: `DevMenuViewModel.recheckServer(onDone=)` / `refresh(onDone=)` run the Home+Library reload AFTER reachability is settled (`playerVm.recheckServer().join()` first). The buttons previously fired `onDataRefresh()` in parallel, reloading on the stale path so Listen Now didn't update.
- **Radio shows removed from Browse**: `stations` items are filtered from shelves and "NEW RADIO SHOW" (`type==="stations"`) spotlight cards skipped, in both `browse.ts` and standalone `DirectBrowseSource` — radio doesn't play yet, so they'd be dead cards.
- **Motion-card scroll jank**: `MotionArtwork` waits **1 s after focus settles** (`playDebounced`) before building its ExoPlayer. Arrowing through a motion row (Playlists Made for You) otherwise built+released a decoder per card passed — that churn was the scroll lag.
- **Now Playing grey backdrop**: `rememberArtworkPalette` decodes a software RGB_565 bitmap (Palette can't read hardware bitmaps), reuses Coil's cache, retries once; missing/failed artwork gets a **seeded** colour from the song id (not flat grey). Real B&W covers still go grey via the monochrome branch (they produce a bitmap).
- **`InAppWebServer.addLog`** mirrors each log line to the proxy over HTTP — **skipped when `!serverReachable`**, else each line blocked an IO thread up to `connectTimeout` (1s) on a dead proxy and starved Coil decode (menu scroll lag in standalone-primary mode). The :8081 SSE stream still carries logs live.
- **Skeletons**: `ShelfSkeleton` (shimmer placeholder shelves) shows while Home/Browse load with no cached data — replaced the spinner. Loading branch guards on `sections/shelves.isEmpty()` so cached content never flips back to skeleton.
- **Menu-scroll perf**: shared Coil `ImageLoader` is RGB_565 + `respectCacheHeaders(false)` + no crossfade (halves bitmap memory on Fire TV); LazyRow/Column items carry `contentType` hints.

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
- **Left sidebar** (`SectionSidebar` in `LibraryScreen.kt`): vertical section nav — Playlists / Albums / Artists / Songs. Selected = red-tinted pill (`0x33FA233B`) with a red accent bar. Replaced an earlier top-tabs experiment (user wanted categories on the left, modernized). Sort bar + pins unchanged, still to the right of the sidebar.
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
- **Color extraction**: `rememberArtworkPalette` loads artwork via Coil at 256px (static artwork URL, NOT the motion video URL), runs `Palette.from().maximumColorCount(32)`, then ranks **every** quantized swatch (not just the 6 named roles) by `saturation*0.62 + prominence*0.38` so vivid accents (a teal logo, a red jacket) outrank a large dull background. `spreadByHue`/`spreadByValue` then pick 6 distinct. Each swatch is pushed toward vivid in HSV: saturation `× SAT_BOOST` floored at `SAT_FLOOR`, value capped at `VALUE_CEILING` (1.45 / 0.55 / 0.80). The value ceiling is the important one — a pale high-value swatch is what makes the backdrop compete with the white lyrics, because pastels read as light grey while deep saturated colors read as color. Don't push `VALUE_CEILING` past ~0.85. A plain saturation *filter* was tried and removed earlier (it stripped vivid pinks/teals). Falls back to near-black palette for truly black artwork (dom luma < 0.06).
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

## Standalone data path (no-PC mode)
- `StandalonePreferences` gates **everything**, not just playback: `MusicRepository.useProxy = serverReachable && !standalone`. The server-down case falls through the same direct path, which is why it doubles as the offline fallback.
- `DirectMusicDataSource` — search, library (songs/albums/playlists/artists), playlist + album tracks, album/song/artist detail, artist full (mapped into `ArtistFullDto` so screens can't tell the paths apart), genres, motion artwork. Library ids (`l.`/`r.`/`i.`/`p.`) resolve through their `catalog` relationship where one exists.
- `DirectBrowseSource` — a section-for-section port of `home.ts` and `browse.ts`. Titles, order and filtering are deliberately identical to the proxy's; don't "simplify" it into a generic charts list, that was tried and the tabs looked wrong.
- **Categories on the direct path**: `DirectMusicDataSource.searchCurators` / `getCurator` / `getMultiRoom` mirror the proxy (same broad-type editorial-items search, grouping tabs, `Ed*` Moshi DTOs in `DirectAppleApi.kt`). One gap: the multiroom **hero blurb is dropped** standalone — its `description` is a string|object union the typed parser can't take; shelves are what matter.
- **Not ported**: related albums (Apple has no endpoint, and `AlbumDto` carries no artist id to derive it from — the shelf just doesn't render) and Apple system status.
- **Sound Check is not applied on either path.** Apple normalises loudness per track; we play files at their mastered level, so quiet masters (FEEL) are quiet. The proxy's 256k re-encode is *also* quieter than standalone's ~400k original, which makes the two paths differ in level as well. Unverified lead: `webPlayback` may carry a per-asset gain.

## Search screen
- The search box is a button until pressed (`editing`). Two bugs to not re-introduce: nothing setting `editing` back to false leaves the field mounted and it keeps reclaiming focus (IME reopens forever); and `onFocusChanged` fires with `isFocused=false` on mount *before* the `FocusRequester` runs, so closing on that alone shuts the editor instantly. `hasFocused` guards the second.
- Songs were fetched into `SearchResults` long before the UI rendered them — the section simply didn't exist.
- **Categories** row (curators + multirooms): the top curator/multiroom also leads the Top Results row; the full set is a "Categories" shelf above Playlists. Cards route by `kind` into `CategoryScreen` via an id prefix — `mr-`/`ac-`/`c-` (parsed in `CategoryViewModel`). `CategoryScreen` uses `contentPadding` (not `Modifier.padding`, which clips) + per-row vertical padding so the focus border/glow isn't sliced at the edges.

## Back behaviour
- Back from Library/Search/Now Playing/Browse/Dev → Listen Now. Only Listen Now offers the exit dialog.
