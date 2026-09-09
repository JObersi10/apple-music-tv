# Roadmap

Rough direction, not a promise. Roughly ordered by impact.

## Planned / exploring

- **UI overhaul (v2)** — a full presentation-layer rebuild to match Apple's own Apple Music app (Home / New / Videos / Radio / Library). This is the headline effort — see **[UI Overhaul](#ui-overhaul--match-the-real-apple-music-app)** below for the design analysis and the phased build/transition plan.
- **True no-PC mode** — port the remaining proxy routes to on-device Kotlin so browse/library/search/lyrics/artwork work with zero computer (playback already does). The hard parts (bearer scrape, webPlayback, Widevine) are done; what's left is data-route + JSON mapping.
- **Queue editing** — reorder and remove from Up Next with the D-pad (list-mutation only, no decoder juggling).
- **Lyrics translation** — Apple-style translated line under each original; probe Apple's endpoint first, else on-device ML Kit translation.
- **Autoplay / infinite mix** — station-style continuation when the queue ends.
- **Automatic MUT capture (sign-in, no copy-paste)** — **RESOLVED 2026-09-08 (on-device).** The
  **on-TV WebView is the answer** (`AppleSignInScreen`, Dev → Account → Sign In): it loads the real
  `music.apple.com`, so it's same-origin and auto-reads `media-user-token`. Confirmed Apple's login
  renders in it. To avoid D-pad typing, use the **Amazon Fire TV phone app as a keyboard**.
  - **DEAD END — phone web sign-in (do not retry).** A `:8080`/HTTPS `:8443` page loading MusicKit JS
    with the *scraped* bearer **cannot work**: Apple **origin-locks** the anonymous bearer to
    `music.apple.com`. MusicKit loads + Apple login popup succeeds, but `authorize()` →
    `GET /v1/me/storefront` = **401** (our Origin ≠ Apple's) → `webPlayerLogout` 403. Reproduced on
    Chrome desktop (not iOS/HTTPS-specific). Confirmed via console + HAR. MusicKit-web needs a **paid
    developer token with registered origins** — off the table for this project. All that web/HTTPS/
    keystore code was removed. (Music Assistant's "free" button uses a maintainer's bundled *paid*
    token.) A phone-native capture without a paid token needs a **native companion app** with a WebView
    on `music.apple.com` (user rejected apps) — a pure web page can't (cross-origin/CSP).
  - Possible future: an in-app **phone→TV keyboard relay** (type on phone, inject key events into the
    on-TV WebView via its `InputConnection`) to replace the Amazon app dependency.
  - Historical analysis of the (non-viable) phone routes kept below.
  Two routes, in order of realism:
  - **Phone companion sign-in (preferred).** The existing `:8080` phone page gains a "Sign in to
    Apple Music" button that opens `music.apple.com` in the phone's own browser/WebView. Once the
    user logs in there (2FA is native and painless on a phone, unlike a TV remote), the session's
    **`media-user-token`** lives in the site's cookies / `localStorage` — read it and POST it to the
    TV automatically. Same endpoint the manual paste already hits; we're just filling the box for them.
    The current QR/8080 pairing is the skeleton — this replaces the paste with an extract.
  - **On-TV WebView sign-in.** An in-app `WebView` pointed at `music.apple.com`; after auth, pull the
    same `media-user-token`. Cleanest UX (never leave the couch) but riskier: typing an Apple ID +
    2FA on a D-pad is rough, and Apple may block embedded/WebView logins or throw a captcha.
  - **Feasibility / caveats.** There is no official token API — both routes read the token the web
    player itself stores after a real login, so this is scraping our own authenticated session, not
    Apple's servers. Watch for: 2FA, captchas, and login-flow HTML changes (same fragility as the
    bearer scrape). Bearer + storefront detection already exist server-side, so only the MUT hand-off
    is new. Start with the phone route; treat the on-TV WebView as a stretch.
  - **How the extract actually has to work (the constraint).** You can't read the token out of a
    normal external browser tab — the session lives in *its* origin, not ours. So either: (a) the user
    authenticates **inside an embedded WebView / iframe we control** (phone companion or on-TV), where
    after login we can read `media-user-token` from that WebView's cookies/`localStorage`; or (b) the
    **server sits in the auth handshake** (proxying `music.apple.com`) and intercepts the response where
    Apple returns the session tokens. Both mean *we host the login surface* — a plain "open Apple in
    your browser" link can't hand the token back. This is the real design decision to make next time.
	
	~~Have a hard cache limit of 150MB, even tho we did implement such thing, it does not take into effect since i see it go above of 300MB, maybe cuz of Music Video cache? you check.~~
	**DONE 2026-09-08 (unbuilt).** Cause was the standalone decrypt `clear_*.mp4` scratch (un-evicted, not Coil). `MediaCacheManager.trim` now caps it; verify on device.
	~~Also cache like limit changer, user can cap the cache size from like no cache to 50MB to 100MB .etc till 2GB.~~
	**DONE (unbuilt).** `CachePreferences` + Dev → Storage → Media cache (Off … 2 GB).
	
	~~the app ALWAYS remembers if u turned on shuffle or repeat on or off, applies to all playlists & albums, and also music videos.~~
	**DONE (unbuilt).** Persisted globally; `playAlbum`/`playVideos` open in the remembered shuffle.
	
	~~when a word grows, it should move the rest of the lyrics IN ITS specific line to the right, to make space for when it grows and goes back when it shrinks, but more subtle,~~
	**DONE (unbuilt).** Word growth is now real `fontSize` so the FlowRow line reflows; tune the ~5.5% factor on device.
	
	~~when pausing song in the middle of a crossfade it doesnt stop.~~
	**DONE (unbuilt).** `collapseCrossfade` folds to the incoming player on pause so audio stops.
	

## UI Overhaul — match the real Apple Music app

The target is Apple's own Apple Music app (the Home / New / Videos / Radio / Library / Now Playing
layout). This is a **presentation-layer rebuild only** — the data layer, view models, proxy routes
and standalone path stay exactly as they are; every screen is already fed by a view model, so we are
swapping how things *look*, not how they load. Ship it screen-by-screen behind a flag so old and new
coexist and each step is revertible.

### What actually makes Apple's UI look like Apple's

Studied from the live app. The look is not one thing — it's ~8 systems used consistently:

1. **Materials & depth.** Translucent, blurred surfaces (nav pill, cards, bio/metadata boxes) layered
   over a soft, content-derived **ambient background wash** (a muted gradient pulled from the current
   artwork/section, not flat black). Everything reads as glass floating over that wash.
2. **One card system, used everywhere.** Rounded ~14 corners, the card's **own** aspect ratio (never
   letterboxed), an UPPERCASE micro-label *above* the art, title + grey subtitle *below or inside*.
   Focus = scale up ~1.06 + brighten + white title; unfocused sits dimmer. Every shelf is the same card.
3. **Typography ramp.** SF-style. Tiny UPPERCASE tracking-wide labels ("UPDATED PLAYLIST"), bold
   titles, medium-grey subtitles, generous line-height. Three or four sizes, reused — not ad-hoc.
4. **Richer top nav.** Home · New · Videos · Radio · Library · Now Playing, plus a search glyph and a
   **Settings** (gear) glyph, in a translucent pill. (We currently have Listen Now / Browse / Library /
   Search / Now Playing.)
5. **Shelf grammar.** Horizontal shelves with a title and "see all"; cards carry a subtitle underneath.
   Songs render as a **3-column grid of rows** (art + title + artist), not a single list. Big
   **hero spotlights** (two wide cards side by side) lead a page.
6. **Detail layouts.** Album/playlist = **two-pane** (large art left; title, editorial quote + MORE,
   Play/Shuffle/+/···, numbered tracklist right). Artist = **full-bleed hero** image with Play + name
   overlaid, a "Top Songs" grid over it, a translucent **bio box** with FROM / BORN / GENRE metadata,
   and a **Similar Artists** row of circular avatars.
7. **Video.** A preview **overlay card** (thumbnail + title + artist + duration + HD + "From Beginning")
   over a paused still, then a full player with title/artist bottom-left, scrubber, Info, ···, list.
8. **Motion.** Shared-element transitions into detail, gentle parallax on hero art, a spring on focus.

### Phase 0 — design-system foundation (no visible change yet)

**IN PROGRESS (2026-09-08, on-device):** Phase 0 kit done (`ui/theme/AmTokens.kt`,
`ui/components/AmComponents.kt`), New-UI flag (Dev → Interface, default off). **Phase 2:** `HomeScreenV2`
+ `BrowseScreenV2` live behind the flag (branched in `AppShell` on `newUiEnabled`), type sizes tuned
smaller. **Phase 3 (partial):** nav rename Listen Now→Home / Browse→New + **Videos + Radio tabs** done
(flag-gated in `TopNavBar`). **Still TODO:** Library V2, Search V2, detail two-pane (Phase 4), Artist
hero, Now Playing/video (Phase 5), motion (Phase 6) — the user wants ALL pages consistent with Home.
Also queue editing (Now Playing panel) and the MV→artist nav bug + Radio redesign (see HANDOFF).

Build the kit first so every screen swap is cheap and consistent:

- **Tokens** — a single `Tokens`/`AmTheme` object: color roles (surface, surfaceGlass, textPrimary,
  textSecondary, labelUppercase, accent `#FA233B`), radii, spacing scale, elevation, and the type ramp.
- **Ambient background** — reuse `rememberArtworkPalette` to drive a low-cost gradient wash behind the
  whole shell (one draw, no blur on Fire TV).
- **Core components** — `AmCard`, `Shelf`, `SectionHeader`, `SongGridRow` (3-col), `HeroSpotlight`,
  `TwoPaneDetail`, `ArtistHero`, `MetadataBox`, `CircleAvatar`, `PillNav`, `GlassSurface`
  (translucent scrim; real blur only where `Build.VERSION >= 31`, tinted scrim fallback otherwise).

### Phase 1 → 6 — the transition (screen by screen, behind a `Dev → New UI` flag)

1. **Foundation** — land tokens + component library; no screen wired to them yet.
2. **Cards & shelves** — repoint Home + Browse to `AmCard`/`Shelf`; add the 3-col song grid; unify the
   focus state. Highest visual payoff, lowest risk.
3. **Navigation** — rename + expand the top nav: **Listen Now → Home, Browse → New**, add **Videos**
   and **Radio** tabs (wire to the existing music-video grouping + station/radio endpoints), keep
   Library / Now Playing, add the search + settings glyphs. Update back-behaviour + exit-dialog strings
   that currently say "Listen Now".
4. **Detail screens** — two-pane album/playlist; artist full-bleed hero + top-songs grid + bio box +
   similar-artist circles.
5. **Now Playing + Video** — align player chrome to Apple's; add the video preview overlay card and the
   full-player controls.
6. **Motion** — shared-element/parallax/focus-spring; ambient wash everywhere.

### Guardrails (don't regress Fire TV perf — hard-won)

- **No `Modifier.blur`** below API 31 (no-op / slow on Fire TV) — use tinted translucent scrims.
- Keep the draw-call budget low, RGB_565 bitmaps, the shared Coil loader, no multi-pass composites
  (same lessons as `DynamicBackground` and the motion-card debounce).
- Presentation-only: do **not** touch data sources, view models, or the standalone path.
- One screen per PR so every step is reviewable and revertible; the flag lets both UIs ship at once.

## Known issues

- **Uneven vertical focus** in Listen Now / Browse — focus movement scrolls the list and lands rows slightly off-centre; the LazyColumn shouldn't re-anchor on focus.
- **Radio shows** (non-live episodes) are hidden until playback is wired; live Apple Music Radio works.
- **Volume leveling** is experimental and off by default.

## Done recently

See [RELEASE_NOTES.md](RELEASE_NOTES.md) and [HANDOFF.md](HANDOFF.md).
