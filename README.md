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

### 1. Server

```bash
cd server
cp .env.example .env      # then edit .env with your gamdl venv + binary paths
bun install
bun run src/index.ts      # http://0.0.0.0:3000
```

`.env` (gitignored) holds machine-specific paths:

```
GAMDL_SITE=/path/to/pipx/venvs/gamdl/lib/pythonX.Y/site-packages
PYTHON_BIN=/path/to/pipx/venvs/gamdl/bin/python3
MP4DECRYPT_BIN=mp4decrypt
FFMPEG_BIN=ffmpeg
```

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
stored on-device and synced to the proxy.

---

## Building the APK via GitHub Actions

Every push to `main` (and every PR) builds a debug APK. Download it from the
**Actions** run's artifacts. See [`.github/workflows/android.yml`](.github/workflows/android.yml).

---

## Security notes

- `server/auth-state.json`, `server/.env`, and `android/local.properties` are
  **gitignored** — they hold your token/paths and must never be committed.
- The proxy binds to `0.0.0.0`; run it only on a trusted LAN.

## License

For personal and educational use. Not affiliated with Apple.
