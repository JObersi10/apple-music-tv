# Apple Music TV

A native **Android TV / Fire TV** Apple Music client, plus a lightweight proxy
server that wraps Apple's private Music APIs (using a scraped web bearer token —
no Apple Developer account required).

> **Personal / educational project.** You need your own Apple Music subscription
> and Music-User-Token. Streams are decrypted locally for playback only.

---

## Architecture

| Part | Stack |
|------|-------|
| **Android app** | Jetpack Compose for TV, Media3 ExoPlayer, Hilt, Retrofit + Moshi, Coil |
| **Proxy server** | Bun + Hono, wrapping `amp-api-edge.music.apple.com` + `play.itunes.apple.com` |
| **Decryption** | `gamdl` + `pywidevine` (Widevine license) + `mp4decrypt` + `ffmpeg` remux |

The app talks to the proxy for catalog/library data, lyrics, artwork, and audio.
The proxy fetches CENC audio from Apple, decrypts it, remuxes it to a seekable
progressive MP4, and serves it with HTTP Range support so ExoPlayer can scrub.

---

## Features

- Listen Now, Browse, Library (playlists / albums / artists / songs), Search
- Full-screen **Now Playing** with an animated color-pool background from the cover
- **Word-by-word synced lyrics** (Apple TTML, with `lrclib.net` fallback)
- Animated (motion) album artwork where Apple provides it
- Artist pages: top songs, latest release, albums, featured, similar artists
- Long-press context menu (Play Next / Add to Queue)
- Library sort (field + asc/desc) with on-device caching
- Remote/controller media keys via a Media3 `MediaSession`

---

## Prerequisites

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

Every push to `main` (and every PR) builds a debug APK. Download it from the
**Actions** run's artifacts. See [`.github/workflows/android.yml`](.github/workflows/android.yml).

---

## Security notes

- `server/auth-state.json`, `server/.env`, and `android/local.properties` are
  **gitignored** — they hold your token/paths and must never be committed.
- The proxy binds to `0.0.0.0`; run it only on a trusted LAN.
- Your MUT grants full access to your Apple Music library and account — treat it
  like a password. Never share it or commit it.

## License

For personal and educational use. Not affiliated with Apple.
