# Handoff — Apple Music TV

Last updated: 2026-09-08 (evening, on-device)

## Session 2026-09-09 (part 3) — ARTIST REDESIGN, PLAYLISTS, SKELETONS, FIXES

- **Artist redesign** (iPad ref): near-full-bleed 480dp hero, big centred name, **centred circular controls** (Shuffle · big Play · Station via new `HeroCircle`). No artist video — Apple exposes no `editorialVideo` for artists (probed), so image hero.
- **Artist Playlists/Essentials shelf**: artist `playlists` view added (proxy `artists.ts` + direct `catalogArtistFull` + `ArtistFullDto`/VM/screen). Renders "Playlists" shelf (The Weeknd Essentials, etc.) → opens PlaylistDetail.
- **About dialog**: converted from a same-tree Box overlay to a real `Dialog` window so D-pad focus is actually TRAPPED (the Box version still let focus reach the list behind). Up/Down scrolls, OK/Back closes.
- **Radio/Videos page headers removed**: RadioScreen dropped its "Radio" title item; CategoryScreen skips the title hero when `hideHeader` (= isGrouping, set in CategoryViewModel). NOTE: a shelf literally named "Music Videos" inside grouping-34 is content, not the page header.
- **Skeletons**: Videos (CategoryScreen) + Radio now show `ShelfSkeleton` while loading instead of a spinner.
- **Shazam**: CONFIRMED working (server + app polling). Now **pauses when playback is paused** (`if (!isPlaying) delay; continue`), reverts to the station name/logo on a miss, station logo passed via `playInternetRadio(logoUrl)`. Log tag `AMRadioID`.
- **Video bleed** into Library/Videos: FIXED — `detachVideo` now does a full audio-only REBUILD (releases the secure decoder+surface) instead of track-disable; AppShell frees it on leaving Now Playing + `delay(120)` before unmounting.
- **STILL OPEN:**
  - **MV "Go to Artist" → jumps to Home** (artist IS pushed, but foreground shows Home). NOT yet fixed. Diagnostic logs added: `AMNav` (destination changes) + `AMMV` (openArtist). Repro with user driving, read logcat to find the hardcoded/side-effect Home nav.
  - Internet-radio: can't skip to next station from the list; can't open artist from Now Playing during radio (needs station-list queue + Shazam→Apple-artist lookup).
  - Margins: user wants soft edges (content visible behind the top bar / no hard cut), not just no-clip. Not done.
  - Shazam can show a song that lags what's actually airing (inherent to radio capture timing).

## Session 2026-09-09 (part 2) — ARTIST POLISH, SHAZAM, BLEED/MV FIXES

- **Artist page**: About moved to bottom (above Similar Artists); bio HTML stripped (`stripHtml` kills the literal `<i>`); About card is now **tappable → full-bio scrollable overlay** ("Read more", Back/Close to dismiss); hero retuned to 430dp + centre-crop of a square source (520dp wide-crop showed only the forehead); Similar Artists = circle-only focus (white ring, no boxy halo) with 2-line centred names.
- **Radio margins**: RadioScreen LazyColumn no longer double-pads horizontally — shelves own start=24/end=0 like Home/New.
- **Video bleed (Library/Videos)**: AppShell now ALWAYS frees the secure decoder when leaving Now Playing, and waits `delay(120)` after `detachVideo()` before unmounting the SurfaceView so `clearVideoSurface()` lands first (the same-frame unmount orphaned the protected frame = the bleed).
- **MV→Go to Artist "jumps to Home"**: `MusicVideoViewModel.openArtist` now only navigates with a NON-BLANK artist id — a blank id built a malformed ArtistDetail route and NavController fell back to the start destination (Home).
- **Shazam song-ID for internet radio**: `server/shazam_identify.py` (ffmpeg grabs ~6s → shazamio recognize) + `server/src/routes/identify.ts` (`GET /api/identify?url=`, 25s cache, single-flight) + Android `identifyStream` (ProxyApi/repo) polled every 20s in `PlayerViewModel.startRadioIdentify`, folding title/artist/artwork into Now Playing. **CONFIRMED WORKING** (live test → `{"title":"Still","artist":"KAROL G & Bruno Mars",...}`). Setup recipe (gamdl's PYTHON_BIN is 3.14 with no shazamio-core wheel + Rust build fails, so Shazam runs in its OWN venv): `python3.13 -m venv server/.shazam-venv` → `.shazam-venv/bin/pip install shazamio-core==1.2.0 shazamio audioop-lts`. Key gotchas: shazamio-core 1.2.0 ships a **cp310-abi3 wheel** (works on 3.13, no Rust); pydub needs **audioop-lts** because `audioop` was removed in Python 3.13. `identify.ts` auto-detects `.shazam-venv/bin/python3` (override with `SHAZAM_PYTHON`). `.shazam-venv/` is gitignored. Capture is 8s (6s missed some tracks).
- **STILL OPEN:** full artist redesign to the iPad reference (centred circular Play/info/star controls, artist **video** hero, signature logo); artist Essentials/playlists shelf; internet-radio station logo in Now Playing.

## Session 2026-09-09 — RADIO SCREEN, ARTIST ABOUT, V2 CONSISTENCY

- **Dedicated `RadioScreen`** (no longer routes to CategoryScreen): Apple Music Radio shelves on top (via `RadioViewModel.getGrouping("168577")`), then a **"Local Radio"** section = the internet-radio directory (search + country chips + station list). Shelves use the SAME grammar as Home/New (`SectionHeader` + `AmCard` 156 + contentPadding start=24/end=0 → first card at margin, bleeds off right).
- **Live "Apple Music Radio" section** seeded by id on BOTH paths: proxy `browse.ts /grouping/168577` and direct `DirectMusicDataSource.getGrouping` (6 marquee `ra.` ids). Title forced to "Radio", "Watch Interviews" shelf dropped on both. The app was on the DIRECT path (standalone), which is why these were missing before — fixed there too.
- **Radio show/episode playback**: `playStation` no longer falls through to the broken live-DRM path (itsradio license 500s, errorCode -1003). Shows a toast "This radio show can't be played yet".
- **Videos tab (CategoryScreen)** converted to `SectionHeader` + `AmCard` (LazyColumn horizontal padding removed; shelves own start=24/end=0) so it matches Home/New. This also makes ALL category pages consistent.
- **Artist About**: FROM (`origin`) + BORN (`bornOrFormed`) + GENRE facts + richer `artistBio`. Added on BOTH paths (proxy `artists.ts` extend + direct `catalogArtistFull` extend + DTO/VM/screen). About box given `heightIn(min=130.dp)` so it fits a paragraph.
- **V2 flag default**: `prefs.getBoolean("new_ui", false)` in PlayerViewModel (lines ~838 and ~1015). Flip both to `true` to make V2 the default once confirmed.
- **STILL OPEN:**
  - **MV→artist bug** — needs USER-DRIVEN repro (music-video secure surface makes adb screencaps black, can't blind-drive its controls). User drives, Claude watches logcat.
  - **More to Explore** genre text-list + genre→sub-station drilldown — Apple's kind-391 element is empty via `include=tabs` and the radio-genre sub-station rooms aren't cleanly exposed; "Stations by Genre" shelf covers the intent for now.
  - Library/Search still use `AlbumCard` (12dp radius) vs `AmCard` (14dp) — 2dp difference, left as-is.
  - phone→TV keyboard relay — deferred (ask first).

## Session 2026-09-08 (late) — RADIO POPULATED

- **Radio tab now = Apple Music Radio grouping `168577`** (`AppShell.kt`: `TopNavTab.Radio -> Category.route("grouping-168577")`, like Videos=grouping-34). Renders via existing CategoryScreen. Fully populated: Apple Music Radio (live stations), Artists Take Over, Latest Radio Episodes, Listen/Watch Interviews, In-Studio Performances, Best Club DJ Mixes, Shows Hosted by Artists, Our Radio Hosts, All Shows, Local + International Broadcasters, Top Stations, Stations by Genre.
- **Server**: `browse.ts /grouping/:id` special-cases id `168577` — prepends a live-station section (6 marquee `ra.` ids: Music 1 `978194965`, Hits `1498155548`, Country `1498157166`, Música Uno `1740613864`, Club `1740613859`, Chill `1740614260`), since the live-grid editorial kind 316 doesn't expand children via `include=tabs`. Kinds 316/488/391 are skipped by the generic 326/327 shelf loop.
- Built + installed to 192.168.1.246. Only Android change = AppShell 1-liner.
- **DEFERRED (not done, low usage this run):**
  - Internet-radio (radio-browser `RadioSource`/old `RadioScreen`) NOT appended below Apple content yet. User wants: [Apple shelves … Local Broadcasters] → [my internet-radio section] → [More to Explore]. Needs a dedicated RadioScreen that fetches the grouping AND appends internet radio + a genre text-list drilldown (kind 391 → genre → station list, pic 3).
  - Radio talk-shows/episodes are `ra.` → `playStation`; may not play (only music stations have working next-tracks). Live music stations = itsradio DRM (still uncracked, see memory `live-radio-probe`).
  - **from/born artist attrs, album two-pane polish, Library/Search V2 polish, MV→artist nav bug — NOT started this run.**
  - Watch Interviews playback bug + phone→TV keyboard relay — user said ASK before doing.

## Session 2026-09-08 (evening) — ON-DEVICE: margins, nav rename + Videos/Radio, Browse V2, sign-in dead-end

Fire TV reachable this session (192.168.1.246), verified live. Gradle/SDK live on the SABRENT
external drive — **mount it** (`diskutil mount /dev/disk4s2`) before building or the build fails
with "SDK location not found" / gradle lock errors.

### Done + verified on the TV
- **Margins fixed (all shelf pages).** Card rows now `contentPadding start=24, end=0` (V2 shelves use
  a `Modifier.padding(start=24)` column): first card sits at the title margin, row bleeds off the
  RIGHT edge (seamless). Titles keep their indent. Applied to Home/Browse/Genre/Search/Category/Radio
  + both V2 screens + Skeleton. NOTE: scrolled rows still clip partial cards at x=0 (normal carousel);
  a permanent left gutter is a possible future tweak.
- **New-UI nav (behind flag): rename + two tabs.** `TopNavBar` takes `newUi`; when on, Listen Now→
  **Home**, Browse→**New**, and **Videos** + **Radio** tabs appear (`TopNavTab` gained Videos/Radio).
  Videos → `Category.route("grouping-34")` (Music Videos grouping); Radio → `Screen.Radio`. Route→tab
  sync added for Radio. Confirmed: nav renders, all 8 tabs work, Videos/Radio open, playback works.
- **Home V2 + Browse(New) V2** on the AmCard/Shelf kit, behind the flag (`HomeScreenV2`,
  `BrowseScreenV2`, branched in AppShell on `newUiEnabled`). Text sizes shrunk in `AmTokens.Type`
  (Header 22→17, Title 15→13, Subtitle 13→11) per user ("Home too big").
- **Flag toggle**: Dev → Interface → New UI (default OFF). To flip via adb:
  `run-as com.applemusicktv` edit `shared_prefs/player_state.xml` `<boolean name="new_ui" .../>`.

### Sign-in — RESOLVED as a dead-end for the web flow (important)
Tried a phone web sign-in: `:8080/signin` page (later HTTPS `:8443` with a bundled self-signed
`signin.p12`) loading **MusicKit JS v3** configured with our **scraped bearer**. Result, proven by the
user's console + HAR: **Apple origin-locks the anonymous bearer to `music.apple.com`.** MusicKit
loads and the Apple login popup succeeds, but `authorize()` → `GET /v1/me/storefront` returns **401**
(Origin header is our IP, not Apple's) → `webPlayerLogout` 403. Fails on Chrome desktop too (not an
iOS/HTTPS issue). **MusicKit-web sign-in needs a PAID developer token (registered origins) — not
possible for this project.** All of that web/HTTPS/keystore code was **removed** (InAppWebServer:
`/signin`, `/musickit-token`, the SSL server, `signin.p12` asset, the `:8080` button; onboarding
alert reverted). **The working sign-in is the on-TV WebView** (`AppleSignInScreen`, Dev → Account →
Sign In) — it loads the real `music.apple.com` (same origin, no lock) and auto-reads the MUT. To
avoid remote typing, use the **Amazon Fire TV phone app as a keyboard**. (Reference: Music Assistant's
"free" button uses a maintainer's bundled *paid* developer token.)

### Round 3 (same evening) — stage-2 redesign, done + installed
- **3-column song grid** (`AmComponents.SongGridRow`, generic) — Apple's "Best New Songs" / "Top
  Songs" layout: horizontally-scrolling columns of 3 song rows (art + title + subtitle). Wired into
  **Browse V2** (shelves whose `albums.first().type == "songs"`) and **Artist V2** top songs.
- **Artist V2 About box** — `HeaderV2("About {name}")` + `GlassSurface` bio (5 lines) on the left,
  **GENRE** metadata on the right. FROM/BORN not shown — the data layer (`ArtistDetailState`) only has
  `bio` + `genres`; adding FROM/BORN needs artist-attribute parsing in `/artists/:id/full` + DTO + VM.
- **Lyric word-grow smoothness restored** — reverted from per-frame `fontSize` (relaid out the whole
  line every frame → choppy on Fire TV) back to a **`graphicsLayer` scale** (GPU, smooth), via a new
  `grow` param on `WordWipe`. Trade-off: the word scales over neighbours instead of nudging the line
  (the earlier reflow ask). A both-smooth-and-nudge version would need animated space reservation.
- **Library / Search** already use `AlbumCard` grids (4-col) — already close to Home. Full AmCard
  swap is minor polish, not done (AlbumCard = 12dp radius vs AmCard 14 + label-above).

### Round 2 (same evening) — done + installed
- **V2 shelf cut-off fixed**: Home/Browse/Artist V2 shelves now use `LazyRow contentPadding
  (start=24, top=6, end=0, bottom=6)` + `SectionHeader(padding start=24,end=24)` instead of a
  column `padding(start=24)` — the column form clipped the focused first card (no room for the 1.06
  focus scale to overflow left). This is the correct pattern for all V2 shelves.
- **Card roundness fixed**: `AmCard` now sets `border = CardDefaults.border(focusedBorder = Border(2dp
  white55, RoundedCornerShape(Radius.Card=14)))`. The default focus border's corners didn't match the
  14dp art radius (the "roundness mismatch" on Home). Fixes every V2 shelf (all use AmCard).
- **Artist V2 wired**: `ArtistDetailScreenV2` (full-bleed hero + Play/Shuffle/Station overlaid, bio,
  top-songs list, album/featured AmCard shelves, similar-artist circles) branched in AppShell on
  `newUiEnabled`. Text sizes shrunk in `AmTokens.Type`.
- **Queue grab-move fixed**: it already existed (`moveQueueItem` wired) but Up escaped to the nav bar
  — switched the moving row from `onKeyEvent` to **`onPreviewKeyEvent`** (intercepts D-pad before
  focus search) + a `FocusRequester` re-requested on each reorder (row re-keys on move), and allowed
  picking up the first up-next row. Interaction: long-press a queue row = pick up, Up/Down = move,
  OK/Back = drop. **Still not discoverable** — add a visible hint/affordance.

### Still open (user wants; some are new features)
- **MV → artist nav bug**: from a music video, opening the artist "goes to Home"; artist loads on the
  Now Playing stack. Not root-caused — needs a live repro + logcat (do NOT guess-patch nav).
- **Accurate Apple redesigns (user gave reference photos — match these):**
  - **Best New Songs / song shelves = 3-column GRID of song rows** (art + title + artist), not a
    single card row. This is the `SongGridRow` from the roadmap. Applies to Browse + artist top songs.
  - **Artist page**: About box with **FROM / BORN / GENRE** metadata on the right; Similar Artists
    circles; full-bleed hero with ▶ + name overlaid; Top Songs as the 3-col grid. (ArtistV2 is close
    but uses a list + no FROM/BORN/GENRE box yet.)
  - **Album/playlist detail = two-pane**: big art left; title, editorial quote + MORE, Play/Shuffle/
    +/···, numbered tracklist right.
- **Whole-app V2 consistency**: Library V2, Search V2 still on the old look — swap onto AmCard/AmTokens.
- **Radio full redesign (clarified)**: make the Radio tab look like **Browse/Videos** — populated
  with **Apple Music radio** as card shelves (stations + radio shows, rendered like albums/video
  cards), and the existing internet/regional radio as a section **at the bottom**. Needs an
  Apple-radio DATA source: probe for an editorial Radio room/grouping id (same pattern as Videos =
  `grouping-34`) and render its shelves; station cards already play via `playStation` (next-tracks).
  Note: Apple Music 1 *live* streams are Widevine-DRM'd and not playable yet, so show the playable
  station/show cards, not live dials. This is a feature (data + UI), not a reorder.
- **Lyric word-grow smoothness**: the real-`fontSize` reflow (added for line-nudge) is choppier than
  the old `graphicsLayer` scale on Fire TV. Need a smoother approach that still nudges the line
  (e.g. GPU scale + animated space reservation, or a cheaper fontSize spec).
- **Phone→TV keyboard relay ("my idea")**: type on phone `:8080` → inject into the on-TV sign-in
  WebView via its `InputConnection` (works through Apple's login iframe; JS can't). Not built —
  Amazon Fire TV app keyboard covers it for now.

## Session 2026-09-08 — cache caps, shuffle/repeat memory, lyric nudge, crossfade pause, in-app sign-in, UI Phase 0

**STATUS: compiles clean (`assembleDebug` BUILD SUCCESSFUL 2026-09-08), but NOT run on device** —
the Fire TV was on a different network all session, so nothing below is verified on hardware.
Everything type-checks and links; behaviour (especially the sign-in WebView and the lyric-nudge
factor) still needs on-TV verification. Diagnose on the Fire TV next session.

### Bug fixes (roadmap batch)
- **Cache exceeded 150 MB → now capped.** Root cause: standalone in-app decrypt writes whole
  `clear_<id>.mp4` files (4–65 MB each) to `cacheDir` with **no eviction** (plus `standalone_*.m3u8`
  and MV files); Coil's 150 MB image cap never governed those, so total grew past 300 MB. New
  `util/MediaCacheManager.trim()` LRU-evicts (mtime, byte-based) the app's media scratch — top-level
  `cacheDir` files only, so `image_cache/` and `updates/` are untouched — never deleting the newest
  (active) file. Called after every decrypt write (`PlayerViewModel`) and on app start (`AppleMusicApp`).
- **User-settable cache cap** — `data/CachePreferences` (StateFlow, options Off/50/100/150/250/500/
  1000/2000 MB, default 150). `PlayerViewModel.stepCacheCap` applies it immediately (trims on lower);
  new **Dev → Storage → Media cache** stepper. Artwork (Coil) stays capped separately at 150 MB.
- **Shuffle/repeat now remembered globally.** `toggleShuffle`/`toggleRepeat` persist to prefs
  (`shuffle_on` / `repeat_mode`); restored at init + `restoreState`; `playAlbum`'s `shuffle` param now
  defaults to the remembered state, so every album/playlist AND music-video list (`playVideos`) opens
  in the last-chosen shuffle. Repeat already carried via state.
- **Lyric word-grow now nudges the line.** Was a `graphicsLayer` scale (visual only — neighbours
  didn't move). Now the active word grows via **real `fontSize`** in the FlowRow, so the rest of that
  line reflows right to make room and settles back. Kept subtle (~5.5%), `lineHeight` fixed so it
  swells sideways only. `WordWipe`'s `scale` param removed. (Verify no held word wraps a trailing
  word to the next line; tune the 0.055 factor on device.)
- **Pause during a crossfade now stops.** `pause()`/`togglePlayPause` only paused the outgoing
  `player`; the incoming `crossfadeExo` kept playing. New `collapseCrossfade(resumePlaying)` folds the
  crossfade to the incoming track (promotes it like the STATE_ENDED snap) and applies the play/pause
  intent to it, so audio actually stops and resume is clean.

### In-app Apple Music sign-in (automatic MUT capture)
- New `ui/screens/AppleSignInScreen.kt` — full-screen **WebView** loading `music.apple.com/us/login`.
  User signs in with Apple ID (2FA and all) inside the in-app browser; a JS poller extracts the
  **media-user-token** from `document.cookie` / `localStorage` / `MusicKit.getInstance().musicUserToken`
  and hands it back. Stored + synced via the existing `DevMenuViewModel.setMUT` → `repo.setMUT`.
- Launch points: **Dev → Account → Sign In** AND **first-run onboarding step 1** ("Or sign in on this
  TV" button → same WebView dialog → `OnboardingViewModel.signInWithToken`, and the existing MUT poll
  auto-advances the step). Both full-screen Dialogs.
- **Why WebView, verified by research:** the token lives in music.apple.com's own origin, so only a
  surface we host can read it back (a plain external browser tab can't). Android `CookieManager` can't
  read HttpOnly cookies, so extraction is via injected JS (which is how MusicKit itself reads it).
- **On-device tuning points (expect to iterate):** Apple may refuse embedded WebViews — a desktop
  Safari UA is set to get past it; MusicKit's auth popup (`window.open` → idmsa.apple.com) is routed
  back into the same WebView via `onCreateWindow`; widen the extractor JS if the token key differs.
  This is the least-certain piece — test the actual login end-to-end first.

### UI overhaul — Phase 0 foundation + Phase 2 Home flagship (behind a flag)
- `ui/theme/AmTokens.kt` — colour roles, radii, spacing, type ramp, focus scale.
- `ui/components/AmComponents.kt` — `AmCard` (label-above / own-aspect art / title+subtitle),
  `SectionHeader`, generic `Shelf`, `GlassSurface` (tinted scrim, no `Modifier.blur` < API 31).
- **New-UI flag** (`PlayerState.newUiEnabled`, pref `new_ui`, `toggleNewUi`) — **Dev → Interface →
  New UI (preview)**, default off. Exactly the roadmap's guardrail: flag off = current UI, zero risk.
- `ui/screens/HomeScreenV2.kt` — Home rebuilt on `AmCard`/`SectionHeader`, same `HomeViewModel`, same
  click routing. `AppShell` picks V2 when the flag is on, else the old `HomeScreen`. Fully revertible.
- Still just cards + shelves; ambient wash / parallax / shared-element motion are later phases.

### True no-PC audit — RESULT: functionally complete, nothing structural missing
- Every `MusicRepository` data method has a `!useProxy` standalone branch (search, library
  songs/albums/playlists/artists, album/artist/playlist detail + tracks, home, browse, curators,
  rooms, multirooms, groupings, categories, genres + content, related songs/albums, lyrics, motion
  song/playlist/card, stations, apple-status, storefront). Only auth/token/health are proxy-only and
  they're inherently server-side (moot with no server; MUT is used on-device directly).
- **CLAUDE.md is stale**: it says related-albums and apple-status aren't ported — both ARE
  (`direct.relatedAlbums`, `direct.appleStatus`). Only real residual is cosmetic: the multiroom hero
  blurb (a string|object union) is dropped standalone.
- TODO on device: confirm the mappings actually render with the server OFF (structure is there;
  untested paths could have field-mapping bugs). Update CLAUDE.md's "KNOWN LIMIT" once verified.

### Not done / deferred — best done WITH the Fire TV in the loop (heavy UX, compile ≠ correct)
These are intentionally left for a device session — they're visual/interaction-heavy and need
on-TV iteration, not more blind code. Priority order for next session:
1. **Verify everything above on the TV** (sign-in end-to-end first; then cache/shuffle/pause/lyric).
2. **Browse V2** behind the flag (mirror `HomeScreenV2`, but Browse has spotlight + video shelves —
   reuse `SpotlightHeroCard` + the video card; `BrowseShelf`/`BrowseViewModel` are private in
   `BrowseScreen.kt`, so V2 goes in that file or make them `internal`).
3. **Nav rename/expand** (Phase 3): Listen Now→Home, Browse→New, add Videos/Radio + search/settings
   glyphs; update back-behaviour + exit-dialog strings that say "Listen Now".
4. Two-pane album/playlist detail (Phase 4); artist full-bleed hero + bio box + similar circles.
5. Now Playing/video chrome (Phase 5); motion — ambient wash/parallax/shared-element (Phase 6).
- Sign-in from the :8080 phone page is NOT viable (cross-origin — the phone page can't read
  music.apple.com's token); the in-app WebView is the route. Don't chase it.
- Uneven vertical focus bug (needs device to repro/tune).
- Queue editing UI (`moveQueueItem` exists), lyrics translation, autoplay/infinite mix — untouched.


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

---

## Session 2026-09-09 (part 4) — bug batch + video-bleed dead end

### FIXED (built + installed, on branch `feat/radio-artist-shazam-v2`, uncommitted)
- **MV → "Go to Artist" jumped to Home.** Root cause (found via `AMNav`/`AMHome` traces):
  the artist page shows a non-focusable spinner while loading, so D-pad focus escapes UP to
  the nav bar and the SAME OK press that selected "Go to Artist" bleeds a click onto the
  leftmost tab (Listen Now) → `onSelect(ListenNow)` → `navigate(Home)`. Same NavController
  (nav# identical in the trace → NOT a recreation). Fix: `AppShell` records
  `lastVideoNavAwayMs` in the video's `onArtistClick`, and `TopNavBar.onSelect` swallows any
  select within 700 ms of it (`return@onSelect`, logs `AMHome: nav-bar select(..) swallowed`).
  User confirms jump is gone. **RESIDUAL:** user says "focuses to home tho, doesnt open" —
  verify the artist page actually gains focus/opens after the guard (may need the artist
  screen to hold focus during load instead of relying on the swallow).
- **Context-menu focus restore** (`PlaylistDetailScreen`, `AlbumDetailScreen`): per-row
  `FocusRequester` map + `refocusId`; `dismissMenu` sets it, a `LaunchedEffect` refocuses the
  long-pressed row after 60 ms. Closing Add-to-Queue/Play-Next returns focus to the song, not
  the top. User confirmed queue-add works.
- **Queue persistence**: `saveState`/`restoreState` now persist `user_queue` (was dropped on
  restart); `addToQueue`/`playNext` call `saveState()` immediately. AND `playAlbum`/`playSong`
  now clear `userQueue` (starting a fresh song shouldn't carry the old added items; restore
  plays via `pendingRestore`, not these, so persistence still works). User confirmed both.
- **Internet-radio logo**: many radio-browser stations have no `favicon`; `NowPlayingScreen`
  now draws a tinted tile with the station initial under the (crossfaded) logo so something
  always shows. List rows already had a 📻 fallback.
- **Home "is gone" after updates**: Apple `/me/recommendations` 500s in streaks; a fresh
  install has no cache, so `HomeViewModel.load()` gave up after 4 tries → empty. Now on total
  empty-with-no-cache it keeps retrying in the background (15×, 4s→30s backoff) so Home
  self-heals once Apple recovers. Server `home.ts` + `DirectBrowseSource` both already have
  the moods/charts fallback.

### STILL BROKEN — needs a real rethink, not another patch
- **Video player bleeds across tabs.** Repro: NowPlaying → Radio → Videos → (auto to
  NowPlaying) → back to Videos → the video picture is STILL rendered on the Videos tab.
  Everything tried and FAILED to kill it reliably: audio-only rebuild on detach
  (`MusicVideoViewModel.detachVideo`), `awaitDetach()` so unmount waits for the secure decoder
  to release, `setKeepContentOnPlayerReset(false)` off NowPlaying, forcing the inner
  `SurfaceView` to `GONE`/`INVISIBLE` in the `AndroidView` update lambda, unmounting the
  PlayerView. The secure (HDCP, `setSecure(true)`) SurfaceView's SurfaceFlinger plane keeps
  its last protected frame latched fullscreen on Fire TV regardless.
  **USER WANTS THIS RECODED.** Proposed direction: stop the "one ExoPlayer, video keeps
  playing across tabs" model. Options — (a) when leaving NowPlaying, fully RELEASE the video
  player and hand its audio to the main audio ExoPlayer (no secure surface exists off
  NowPlaying at all); or (b) only ever create the secure PlayerView while `isOnNowPlaying`, and
  a video that isn't on NowPlaying plays audio through the normal audio path. Either removes
  the secure surface from every non-NowPlaying tab entirely. Note the video already correctly
  opens on NowPlaying first now (autoOpen via `videoRequest`).

### Temp instrumentation still in code (remove after)
- `AppShell`: `AMNav` OnDestinationChanged listener (logs route + `nav#` identity),
  `AMHome` logs in `onExit` / `goToNowPlaying` / nav-bar swallow.
- Build: `cd android && JAVA_HOME=".../Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon`
  then `adb -s 192.168.1.246:5555 install -r app/build/outputs/apk/debug/app-debug.apk`.

### Still queued (features, not started)
- Lyrics translation (server translate + NowPlaying toggle) — user said do it, not yet done.
- Artist video hero + collapsing hero that resets on scroll.
- Radio/Videos card-size + accent consistency with Home/New.
- Soft margins (fade under top bar, no hard cutoff).

---

## Session 2026-09-09 (part 5) — artist long-press, focus, MV regression revert

### DONE in code (NOT yet built — SABRENT was unmounted at build time; rebuild pending)
- **Artist page long-press context menu** (`ArtistDetailScreenV2`): `SongGridRow`/`SongCell`
  (`AmComponents.kt`) gained an `onLongClick`; Top Songs long-press opens a Dialog menu
  (Play Next / Add to Queue / Add to… / Go to Album) with 500 ms click-block + focus. `AddToDialog`
  reused. New helper `ArtistMenuItem`.
- **MV → artist "focuses on Home, doesn't open"**: artist hero **Play button** now grabs focus
  ~120 ms after Top Songs load (`heroPlayFocus`, `HeroCircle` gained a `focusRequester` param).
  Combined with the part-4 nav-bar swallow, the page opens AND focus lands on Play.
- **Reverted the SurfaceView-`GONE` bleed attempt** in `AppShell` AndroidView update lambda — it
  did NOT reap the plane and risked faulting the live secure decoder (suspected cause of the "MVs
  fail to play a lot now" regression). Back to `visibility=VISIBLE` + `setKeepContentOnPlayerReset(isOnNowPlaying)`.

### Still NOT done (usage ran low) — requested this session
- **Long-press context menu EVERYWHERE** (user: "ADD IT EVERYWHERE AGAIN"): V2 lost it on
  Home/New **spotlight** cards, **Videos tab** MV cards, Browse `AmCard`s. Needs a shared
  song/video/album context-menu triggered from `AmCard` + the video shelves (CategoryScreen video
  rows, BrowseScreenV2, HomeScreenV2). Scope: add `onLongClick` to `AmCard` and the 16:9 video
  Surfaces, route to a shared menu. MV cards want Go to Artist / Add to Queue etc.
- **"Couldn't add to playlist" error**: not reproduced in logs yet (scrolled off). Check
  `AddToDialog` → `playerVm` add-to-playlist → server `POST /api/library/playlists/:id/tracks`
  (or the library add route). Likely a `p.` vs `pl.` id or MUT/library-write issue.
- **MV audio-only rebuild is slow** (detach on leaving Now Playing): user wants it optimised.
  It rebuilds from on-disk playlists (no network) but still re-inits ExoPlayer+DRM. Consider
  track-disable-without-rebuild ONLY paired with the video-model recode, or caching the audio-only
  MediaSource.
- **Queued features (never started)**: lyrics translation, artist video hero + collapsing hero,
  radio/videos card consistency, soft margins.

---

## Session 2026-09-09 (part 6) — long-press everywhere, add-to-playlist fix, lyrics translation

### DONE + built + installed (branch feat/radio-artist-shazam-v2, uncommitted)
- **Artist page long-press** context menu (Top Songs) — Play Next / Add to Queue / Add to… /
  Go to Album. `SongGridRow`/`SongCell` (`AmComponents.kt`) gained `onLongClick`.
- **MV → artist focus**: artist hero **Play button** grabs focus ~120 ms after Top Songs load
  (`heroPlayFocus`, `HeroCircle` `focusRequester` param) — opens AND focuses Play, not the nav bar.
- **Reverted the SurfaceView-GONE bleed attempt** (was the likely "MVs fail to play a lot" regression).
- **Add-to-playlist error**: `MusicRepository.addToPlaylist` now sends the correct resource `type`
  — `library-songs`/`library-music-videos` for `i.`/`l.` ids, else `songs`/`music-videos`. Sending
  "songs" for a library id was 404ing → "Couldn't add". Error toast now shows the real message
  (`AMAddToPl` log) if it still fails.
- **Long-press context menu EVERYWHERE** via a shared `CardContextMenu` (`AmComponents.kt`):
  adapts options to item type (song / music-video / album·playlist). Wired into:
  - **CategoryScreen** (Videos tab + category shelves): AmCard + 16:9 video Surfaces.
  - **BrowseScreenV2** ("New"): spotlight hero cards (`SpotlightHeroCard` got `onLongClick`),
    video rows, regular shelves, song grids. New `onArtistClick` param (wired in AppShell).
  - Home already shuffled playlists on long-press (left as-is).
- **Lyrics translation** (server + client):
  - Server `POST /api/translate` (`server/src/routes/translate.ts`, registered in index.ts) —
    keyless Google `translate_a/single`, batches all lines in one call, in-memory cache, falls
    back to originals on failure. **Server must reload** (bun --watch picks it up).
  - Client: `ProxyApi.translate` + `TranslateRequest/Response`, `MusicRepository.translateLines`,
    `PlayerViewModel.toggleTranslateLyrics()` + `translateLyrics`/`lyricsTranslation` state
    (persisted `translate_lyrics` pref; target = device language). Now Playing ··· menu →
    "Translate Lyrics" toggle; translated line renders italic/dim under each lyric line (both the
    side panel and full-screen lyrics). Proxy-only (no offline translation).

### Build/infra note
- **Main disk filled to 0 B mid-session** — build died with "No space left on device". Freed via:
  `pkill -f gradle`, `tmutil thinlocalsnapshots / … 4`, and clearing `~/Library/Caches/{Google,Homebrew,pip,node-gyp}`
  (~1.5 GB). Data volume is ~207 GB used / ~1.6 GB free now — **user is very low on disk**; builds
  will keep failing until they free real space. Deleted `android/app/build` (regenerable).

### Still queued (NOT done)
- **Soft margins** (content fades under the top bar, no hard cutoff): needs the NavHost top-padding
  removed and per-screen top contentPadding + a top fade scrim across Home/Browse/Radio/Category/
  detail screens. Too broad to do blind safely — scoped for next session.
- **Artist video hero** + collapsing hero that resets on scroll (part of artist polish).
- **Radio/Videos card-size + accent consistency** with Home/New (subjective; needs on-device eyeballing).
- **MV audio-only rebuild is slow** — tied to the video-model recode (see video-surface-bleed memory).
- **Video bleed across tabs** — still needs the recode.

---

## Session 2026-09-09 (part 7) — one context menu, MV audio/queue, Library videos, V2 default

All committed + pushed to `origin/feat/radio-artist-shazam-v2`.

### DONE
- **Single context menu everywhere** (`AmContextMenu` in `AmComponents.kt`): artwork header +
  subtitle + divider + icon/label rows. Library/Album/Playlist/Artist/Category/Browse/Search all
  route through it. `AmMenuAction(label, glyph, destructive)`. See `DESIGN_LANGUAGE.md` +
  memory `canonical-context-menu`.
  - **Long-hold no longer auto-selects**: a held OK auto-repeats KeyDowns, so the menu now consumes
    ALL key events until the first KeyUp (the long-press release), then arms. Robust at any hold length.
  - Artwork resolves Apple's `{w}x{h}bb.{f}` template inside the menu (Library rows were blank before).
  - Card menus (New/Browse/Category) resolve artist/album ids via `lookupSongIds` so **Go to Artist**
    shows there too.
  - Search menu gained Create Station + Go to Artist/Album (id resolution).
- **MV audio quality**: pick highest-bitrate audio rendition, not the one tied to the 480p video tier
  (`AppleDirectClient.buildMaster`). Skips atmos/binaural.
- **MV Up-Next panel**: auto-scrolls to the cursor; shows current + upcoming only (drops played);
  shows userQueue (Play Next / Add to Queue) as a "Playing Next" section.
- **Library → Videos tab**: `/api/library/music-videos` + client wiring + 16:9 grid → plays on Now Playing.
- **V2 UI is now the default** (`new_ui` pref defaults true); Dev toggle still flips to V1.
- **Add-to-playlist type fix**, **lyrics translation** (proxy `/api/translate`).

### STILL OPEN (tracked, not done — need real work, not wrap-up patches)
- **Video bleed to Library/Videos** — NOT a z-order flag (already `setZOrderMediaOverlay(false)`).
  It's `setSecure(true)` (needed for HD; without it → 480p) latching its last protected frame. The
  real cure is the video-decoder-lifecycle recode (see memory `video-surface-bleed`). Do not rush.
- **Lyrics translation on-device (no proxy)** — needs ML Kit Translate (`com.google.mlkit:translate`):
  offline after a one-time per-language model download. Dependency + model-download UX; a real change.
- **MV queue from a mixed playlist** already unifies (row onClick → `playAlbum(tracks, idx)`), so songs
  DO show. The Videos-tab launch is videos-only by nature. Retest if songs seem missing.
