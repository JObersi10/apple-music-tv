# Apple Music TV — v1.2

- A big one. Standalone on-device playback is now the default, radio plays, and the whole Now Playing / Browse experience got a facelift.

*🎵 Playback & Audio*
Fully standalone by default — catalog, library, search, lyrics, artwork and on-device audio decryption, no computer needed.
Dead library songs now play — obscure/matched tracks that used to skip instantly fall back to the catalog copy on-device automatically.
Live radio — Apple Music 1 / Hits / Country and station shows play, with live in-band track metadata, a LIVE badge, and per-station lyric offset.
Radio stations — station cards play their rolling queue (cracked Apple's next-tracks endpoint).
Volume leveling — optional RMS auto-gain so quiet masters aren't buried.
Gapless & crossfade — same-album tracks stay gapless; everything else crossfades (tunable 1–15s).
Add to Library / Add to Playlist from the song menu.

*📺 Now Playing*
Dynamic background — fluid color backdrop extracted live from the album art, beat-reactive (Normal / Strong / Insane), with motion (animated) covers.
Projector mode — a calmer, slower ambient backdrop for the big screen.
Screensaver — kicks in when idle so nothing burns in.
Big-screen lyrics — full-screen, word-by-word synced lyrics with adjustable size.
Scrub bar, sleep timer, shuffle/repeat, go-to-artist/album in the transport menu.

*🎬 Music Videos*
Music videos and interviews play, grouped ("Music Videos", "Behind the Songs"), and now show on artist pages.
Fixed the secure-surface bleed, HDCP quality traps, and A/V lip-sync.

*🗂️ Library — revamped*
Left sidebar categories (Playlists / Albums / Artists / Songs), modernized.
Sort via a round popup button — by name / artist / date, ascending/descending, remembered per playlist.
Pin playlists to the top; instant load from cache.

*🔎 Browse & Listen Now*
Menu revamp — real Apple editorial shelves: Top Picks for You hero, the Featured "New" spotlight, Find Your Mood, genre/mood/decade tiles, "More to Explore".
Personalization no longer vanishes during Apple's recommendation outages.
Search — songs, categories, curators, multirooms, and editorial links.
Loading skeletons that match the real layout.

*⚙️ Settings — revamped*
Reorganized Dev/Settings: background mode, orb speed, lyrics size, rounded/square art, motion toggle, reduce motion, Low Power Mode, beat intensity, crossfade, standalone toggle, PC server IP, and the in-app updater.

*⚡ Performance*
Smoother scrolling — motion covers wait before animating; background logging no longer stalls menus.
Off-main-thread Home cache, capped bitmap memory, RGB_565 palette.

*🐛 Fixes*
Grey Now Playing background on colorful covers; missing-artwork songs get their own color.
Editorial playlists showing "no songs"; double failure toasts; per-tab back-stack memory (every tab returns to its last page).
Dev → Re-check Server / Refresh now actually reload Listen Now.
Removed non-playable radio-show cards from Browse.
