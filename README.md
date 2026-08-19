# Apple Music TV

A native **Android TV / Fire TV** Apple Music client. It runs **fully standalone**
— catalog, library, lyrics, artwork **and audio decryption all happen on-device**,
no computer required. An optional **PC proxy server** is still supported as an
alternate audio path (useful for debugging or offloading decryption).

> **Personal / educational project.** You need your own Apple Music subscription
> and Music-User-Token. Streams are decrypted locally for playback only.

## Modes

| Mode | What runs | When to use |
|------|-----------|-------------|
| **Standalone** (default) | A pure-Kotlin software **Widevine CDM** + CENC decryptor on the Fire TV decrypts each song in-app. No PC. | Normal use — nothing else to run. |
| **Proxy** (optional) | The PC server fetches + decrypts audio and serves seekable MP4 over your LAN. | Debugging, or devices where in-app decrypt struggles. |

Toggle in **⚙ Settings → Playback → Standalone**, or point the app at a PC in
**Settings → Open Dev Menu → PC Server**.

---

## Architecture

| Part | Stack |
|------|-------|
| **Android app** | Jetpack Compose for TV, Media3 ExoPlayer, Hilt, Retrofit + Moshi, Coil |
| **On-device decrypt** (standalone) | Pure-Kotlin software **Widevine L3 CDM** + `cenc` AES-CTR fMP4 decryptor, feeding clear AAC straight to ExoPlayer |
| **Proxy server** (optional) | Bun + Hono, wrapping `amp-api-edge.music.apple.com` + `play.itunes.apple.com` |
| **Proxy decryption** | `gamdl` + `pywidevine` (Widevine license) + `mp4decrypt` + `ffmpeg` remux |

**Standalone:** the app talks directly to `amp-api-edge.music.apple.com`, obtains a
Widevine license, derives the content key on-device, decrypts the CENC audio, and
plays it — no PC involved. **Proxy (optional):** the server does the fetch/decrypt/
remux instead and serves a seekable MP4 over HTTP Range.

---

## Features

- Listen Now, Browse, Library (playlists / albums / artists / songs), Search
- Full-screen **Now Playing** with an animated color-pool background from the cover
- **Word-by-word synced lyrics** (Apple TTML, with `lrclib.net` fallback)
- Animated (motion) album artwork where Apple provides it
- Artist pages: top songs, latest release, albums, featured, similar artists
- Long-press context menu (Play Next / Add to Queue / Go to Artist / Go to Album)
- Word-synced lyrics with **prefetch** (next song's lyrics warm before it starts), a
  soft karaoke wipe, and edge-faded scrolling
- **Full-Screen Lyrics** mode (··· menu) — enlarged words, corner transport controls
  that fade on idle, lands on the current line, auto-returns to the play button
- **Ambient screensaver** — after an idle timeout (set in Settings, off…2h) the screen
  cross-dissolves to a dimmed drifting background + a small now-playing chip
- **Background play** — audio keeps playing when you leave the app (toggle in Settings);
  when paused, the screen is released so Fire TV's own screensaver/sleep can take over
- **Picture-in-Picture** (··· menu) — shrinks to a corner card with darkened album art +
  title (may not be supported on all Fire TV hardware)
- **"Next: …" toast** ~15 s before the track switches
- Search also returns **playlists** (Apple editorial ranked first)
- **24-hour cache expiry** for lyrics and artwork, plus a hard **100 MB disk cap** on the
  artwork cache (LRU-evicted) so it can't grow unbounded on large drives
- **In-app updater** — checks GitHub Releases on launch, shows a red dot on the ⚙ tab and a
  Settings → Software card to download + install the new APK. A **Beta updates** toggle opts
  into prerelease builds
- **Crash log + bug report** — a global crash handler records the last crash on-device; the
  phone web server (port 8080) serves a one-file bug report (app version, device, last crash,
  recent app + network logs) via **Download Bug Report**
- **Artist Stations** — a generated, shuffle mix from an artist + similar artists
- **Internet Radio** tab — geo-detected local stations (radio-browser.info), add any
  country by name (with spell-correction), plus "now playing" song ID from ICY stream
  metadata (pulls Apple artwork + lyrics for the current track)
- **Now Playing background** — three looks: **Dynamic** (drifting album-colour pools,
  each orb pinned to a distinct palette colour for an oil-painting spread), **Projector**
  (three beat-reactive band orbs — bass / vocal / treble), and plain **Black**. The beat
  pulse is critically damped so each hit lands once and decays cleanly
- **Now Playing customization** (Settings → dev tools) — **Intensity** (Calm…Crazy,
  remembered *per background mode*), **Orb speed** (Projector), **Lyrics size**
  (Small/Normal/Large), **Rounded vs square artwork**, **Motion artwork** toggle,
  **Reduce motion** (holds the orbs still), and **Low Power Mode** (fewer/simpler orbs
  for weaker hardware)
- **Volume leveling** (experimental, off by default) — RMS loudness leveling in the audio
  chain
- **⚙ Settings** screen — Crossfade, Standalone toggle, Screensaver timeout, Background
  play, Lyrics offset, Beat latency, live network log, and Reset App (replaces the old
  dev menu; dev tools tucked inside)
- Library sort (field + asc/desc) with on-device caching
- Remote/controller media keys via a Media3 `MediaSession`

> **Not standalone:** live Apple Music radio (`ra.*`, Apple Music 1) is FairPlay-
> protected live HLS and can't be played on-device; personalized `ra.*` mixes need a
> radio-tuner endpoint not yet wired. Internet radio above is DRM-free and works.

---

## Prerequisites

**Server — OPTIONAL** (standalone mode needs none of this; only set it up if you
want the proxy audio path):

**Server (macOS/Linux):**
- [Bun](https://bun.sh)
- Python 3 with [`gamdl`](https://github.com/glomatico/gamdl) + `pywidevine`
  installed (a valid `.wvd` Widevine device is required by gamdl)
- `mp4decrypt` (Bento4) and `ffmpeg` on your `PATH`

**Android:**
- Android Studio / Android SDK (compileSdk 35), JDK 17
- An Android TV or Fire TV device with ADB enabled

---

## Setup

### 1. Server — one-shot setup

The setup script installs everything (Bun, Python + gamdl + pywidevine, ffmpeg,
Bento4 mp4decrypt) into normal locations and writes `server/.env` for you.
gamdl ships its own embedded Widevine device, so no `.wvd` file is needed.

**macOS:**
```bash
cd server
./setup-mac.sh
bun run src/index.ts      # http://0.0.0.0:3000
```

**Windows (PowerShell):**
```powershell
cd server
powershell -ExecutionPolicy Bypass -File .\setup-windows.ps1
bun run src/index.ts
```

<details>
<summary>Manual setup / what the script does</summary>

Install `bun`, `ffmpeg`, `mp4decrypt` (Bento4), and Python; create a venv and
`pip install gamdl pywidevine httpx`; then copy `.env.example` to `.env` and set
the paths. `.env` (gitignored) holds machine-specific values:

```
GAMDL_SITE=              # empty when PYTHON_BIN is a venv that already has gamdl
PYTHON_BIN=/path/to/.venv/bin/python
MP4DECRYPT_BIN=mp4decrypt
FFMPEG_BIN=ffmpeg
```
</details>

### 2. Android

Add your machine's LAN IP to `android/local.properties` (gitignored):

```
proxyBaseUrl=http://192.168.1.50:3000/
```

Build & install:

```bash
cd android
./gradlew assembleDebug
adb connect <FIRE_TV_IP>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Set your Music-User-Token

The Fire TV runs a small web server on port **8080**. Open
`http://<FIRE_TV_IP>:8080` on your phone and paste your Music-User-Token. It's
stored on-device and synced to the proxy server (`server/auth-state.json`).

**How to find your Music-User-Token:**
1. Open `music.apple.com` in a browser
2. DevTools → Network tab
3. Click anything that loads music content
4. Find any request to `amp-api-edge.music.apple.com`
5. Copy the `Music-User-Token` request header value

**Bearer token** is scraped automatically from `music.apple.com`'s JS bundle on
server startup — you don't need to find or set it manually.

### Auth state storage

Both tokens are persisted in `server/auth-state.json` (gitignored):

```json
{
  "mut": "...",
  "bearerToken": "eyJ...",
  "mutSetAt": 1234567890000
}
```

This file is **never committed** (listed in `.gitignore`). If you need to reset:
delete `auth-state.json` and restart the server — bearer is re-scraped, MUT must
be re-entered via the phone web server.

---

## Building the APK via GitHub Actions

The **Android APK** workflow ([`.github/workflows/android.yml`](.github/workflows/android.yml))
runs on every push to `main`, every PR to `main`, and on manual dispatch. Each run:

1. Sets up JDK 17 + the Android SDK.
2. Writes a default `local.properties` (`proxyBaseUrl=http://10.0.2.2:3000/`) so the
   build compiles without your local config.
3. Runs unit tests (`./gradlew :app:test`).
4. Builds the debug APK (`./gradlew assembleDebug`).
5. Uploads it as the **`app-debug`** artifact on the run.
6. On pushes to `main`, publishes/updates the rolling **`dev`** pre-release with the APK.

**Getting the APK:**
- Latest `main` build — repo **Releases → "Latest build"** → `app-debug.apk`
  (no login required), or
- A specific run — that run's **Actions → Artifacts → `app-debug`**.

Sideload it: `adb install -r app-debug.apk`.

### Releasing (and the in-app updater)

The app polls **`releases/latest`** on launch. For it to detect a new version, bump both
`versionCode` and `versionName` in `android/app/build.gradle.kts` **and** tag the GitHub
release with a higher number than the running build (e.g. current build is `1.1`, so tag the
next release `v1.2`). The updater compares numerically after stripping a leading `v`, so
`v1.2` > `1.1`; an equal version is not offered. Mark a release **prerelease** to reach only
users who've enabled **Beta updates** in Settings → Software.

### Committing / pushing

`main` is the working branch; a push there triggers the workflow and refreshes the `dev`
release. Native Widevine libs live in `android/app/src/main/jniLibs/` and the FFmpeg
Media3 decoder in `android/app/libs/` — both are committed (the build needs them). Secrets
(`auth-state.json`, `*.keystore`) are git-ignored and must never be committed.

---

## Security notes

- `server/auth-state.json`, `server/.env`, and `android/local.properties` are
  **gitignored** — they hold your token/paths and must never be committed.
- The proxy binds to `0.0.0.0`; run it only on a trusted LAN.
- Your MUT grants full access to your Apple Music library and account — treat it
  like a password. Never share it or commit it.

## License

For personal and educational use. Not affiliated with Apple.
