<div align="center">

# Apple Music TV

**A native Apple Music client for Android TV & Fire TV.**
Browse, search, your full library, word-synced lyrics, music videos, live radio — on the big screen.
Runs **fully standalone on-device**. No computer required.

[![Download APK](https://img.shields.io/badge/Download-APK-FA233B?style=for-the-badge&logo=android&logoColor=white)](https://github.com/JObersi10/apple-music-tv/releases/download/dev/app-debug.apk)
[![Releases](https://img.shields.io/badge/All-Releases-333?style=for-the-badge&logo=github&logoColor=white)](https://github.com/JObersi10/apple-music-tv/releases)

![status](https://img.shields.io/badge/status-active-brightgreen) ![platform](https://img.shields.io/badge/platform-Fire%20TV%20%7C%20Android%20TV-blue) ![license](https://img.shields.io/badge/use-personal%20%2F%20educational-lightgrey)

</div>

> **Personal / educational project.** You bring your own Apple Music subscription and Music-User-Token. Streams are decrypted locally for playback only. Not affiliated with Apple.

---

## What it does

A proper 10-foot Apple Music experience built with Jetpack Compose for TV — the same content you get on the web player, laid out for a remote:

- **Listen Now, Browse, Library, Search** with Apple's real editorial shelves.
- **Full-screen Now Playing** with a live album-colour background and **word-by-word synced lyrics**.
- **Music videos, live radio, and stations** — all playable.
- **On-device playback** — a pure-Kotlin Widevine CDM decrypts each song right on the TV. No PC, no sidecar server.

---

## Screenshots

<!-- TODO: drop 4–6 real Fire TV screenshots into docs/screenshots/ and update the paths below.
     Suggested set: Listen Now, Browse, Now Playing (lyrics), Now Playing (dynamic background),
     Library, Music Video. A short demo GIF (browse → lyrics → Now Playing) placed right here,
     above the table, helps enormously — especially for AFTVnews-style coverage. -->

| Listen Now | Now Playing — Lyrics | Dynamic Background |
|---|---|---|
| _add `docs/screenshots/listen-now.png`_ | _add `docs/screenshots/lyrics.png`_ | _add `docs/screenshots/now-playing.png`_ |

| Library | Browse | Music Video |
|---|---|---|
| _add `docs/screenshots/library.png`_ | _add `docs/screenshots/browse.png`_ | _add `docs/screenshots/music-video.png`_ |

---

## Features

**Playback**
- Fully standalone on-device decryption (no computer needed).
- Live Apple Music Radio (Apple Music 1 / Hits / Country), stations, and per-song **Create Station**.
- Gapless playback, crossfade (1–15 s), and a **sleep timer** that fades out over 5 s.
- Dead / withdrawn library tracks fall back to their catalog copy automatically.

**Now Playing**
- Word-by-word synced lyrics (Apple TTML, `lrclib.net` fallback) with a full-screen lyrics mode.
- Three background looks — **Dynamic** (album-colour pools), **Projector** (beat-reactive orbs), **Black** — plus a beat pulse and motion (animated) artwork.
- Ambient **screensaver**, background play, scrub bar, and sleep timer.

**Browsing & Library**
- Real editorial Home (Top Picks, Featured, Find Your Mood, genre/mood/decade tiles).
- Music videos & interviews, grouped and on artist pages.
- Library with left-sidebar categories, per-playlist sort memory, and pinned playlists.
- Per-tab navigation memory — each tab returns to where you left it.

**Quality of life**
- In-app updater (checks GitHub Releases; optional beta channel).
- Low Power Mode, adjustable lyrics size, rounded/square artwork, reduce motion.
- Volume leveling (experimental), on-device caching, remote/controller media keys.

---

## Installation

1. **Download the APK** — [latest build](https://github.com/JObersi10/apple-music-tv/releases/download/dev/app-debug.apk) (or pick one from [Releases](https://github.com/JObersi10/apple-music-tv/releases)).
2. **Sideload it** onto your Fire TV / Android TV:
   ```bash
   adb connect <TV_IP>
   adb install -r app-debug.apk
   ```
   (Or use a sideload app like Downloader with the release URL.)
3. **Add your Music-User-Token** — open `http://<TV_IP>:8080` on your phone and paste it in. The app runs a tiny setup page there. [How to find your token →](docs/TECHNICAL.md#getting-your-music-user-token)

That's it — standalone mode needs no server. To use the optional PC proxy path instead, see the [technical guide](docs/TECHNICAL.md#optional-pc-proxy-server).

---

## Technical details

Architecture, the optional PC proxy server, the on-device Widevine/CENC pipeline, auth flow, CI, and build notes live in **[docs/TECHNICAL.md](docs/TECHNICAL.md)**.

Deeper dives: [Apple Music API notes](docs/apple-music-api.md) · [MUT flow](docs/mut-flow.md) · [Projector mode](docs/PROJECTOR_MODE_NOTES.md).

## Contributing & security

- [CONTRIBUTING.md](CONTRIBUTING.md) — how to build and where things live.
- [SECURITY.md](SECURITY.md) — token handling and how to report issues.
- [ROADMAP.md](ROADMAP.md) — what's planned next.

## License

For personal and educational use. Not affiliated with, or endorsed by, Apple.
