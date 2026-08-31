# Technical details

Everything below used to live in the README. It's here so the front page stays about *using* the app; this page is for building and understanding it.

## Modes

| Mode | What runs | When to use |
|------|-----------|-------------|
| **Standalone** (default) | A pure-Kotlin software **Widevine CDM** + CENC decryptor on the TV decrypts each song in-app. No PC. | Normal use — nothing else to run. |
| **Proxy** (optional) | A PC server fetches + decrypts audio and serves seekable MP4 over your LAN. | Debugging, or devices where in-app decrypt struggles. |

Toggle in **⚙ Settings → Playback → Standalone**, or point the app at a PC in **Settings → Open Dev Menu → PC Server**.

## Architecture

| Part | Stack |
|------|-------|
| **Android app** | Jetpack Compose for TV, Media3 ExoPlayer, Hilt, Retrofit + Moshi, Coil |
| **On-device decrypt** (standalone) | Pure-Kotlin software **Widevine L3 CDM** + `cenc` AES-CTR fMP4 decryptor, feeding clear AAC straight to ExoPlayer |
| **Proxy server** (optional) | Bun + Hono, wrapping `amp-api-edge.music.apple.com` + `play.itunes.apple.com` |
| **Proxy decryption** | `gamdl` + `pywidevine` (Widevine license) + `mp4decrypt` + `ffmpeg` remux |

**Standalone:** the app talks directly to `amp-api-edge.music.apple.com`, obtains a Widevine license, derives the content key on-device, decrypts the CENC audio, and plays it — no PC involved. **Proxy (optional):** the server does the fetch/decrypt/remux instead and serves a seekable MP4 over HTTP Range.

## Getting your Music-User-Token

The TV runs a small web server on port **8080**. Open `http://<TV_IP>:8080` on your phone and paste your Music-User-Token. It's stored on-device (and synced to the proxy server if you use one).

To find the token:
1. Open `music.apple.com` in a browser.
2. DevTools → Network tab.
3. Click anything that loads music content.
4. Find any request to `amp-api-edge.music.apple.com`.
5. Copy the `Music-User-Token` request header value.

The **bearer token** is scraped automatically from `music.apple.com`'s JS bundle — you never set it by hand.

> Your MUT grants full access to your Apple Music library and account — treat it like a password. See [SECURITY.md](../SECURITY.md).

## Optional PC proxy server

Standalone needs none of this. Only set up the server if you want the proxy audio path.

**Prerequisites (macOS/Linux):** [Bun](https://bun.sh); Python 3 with [`gamdl`](https://github.com/glomatico/gamdl) + `pywidevine`; `mp4decrypt` (Bento4) and `ffmpeg` on `PATH`.

### One-shot setup

The setup script installs everything (Bun, Python + gamdl + pywidevine, ffmpeg, Bento4 mp4decrypt) and writes `server/.env`. gamdl ships its own embedded Widevine device, so no `.wvd` file is needed.

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

Install `bun`, `ffmpeg`, `mp4decrypt` (Bento4), and Python; create a venv and `pip install gamdl pywidevine httpx`; then copy `.env.example` to `.env` and set the paths. `.env` (gitignored) holds machine-specific values:

```
GAMDL_SITE=              # empty when PYTHON_BIN is a venv that already has gamdl
PYTHON_BIN=/path/to/.venv/bin/python
MP4DECRYPT_BIN=mp4decrypt
FFMPEG_BIN=ffmpeg
```
</details>

### Point the app at the server

Add your machine's LAN IP to `android/local.properties` (gitignored), or set it at runtime in the Dev menu:

```
proxyBaseUrl=http://192.168.1.50:3000/
```

### Auth state storage

Both tokens are persisted in `server/auth-state.json` (gitignored):

```json
{ "mut": "...", "bearerToken": "eyJ...", "mutSetAt": 1234567890000 }
```

Never committed. To reset: delete `auth-state.json` and restart — the bearer is re-scraped, the MUT is re-entered via the phone web server.

## Building from source

**Android:**
```bash
cd android
./gradlew assembleDebug
adb connect <TV_IP>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android Studio / SDK (compileSdk 35), JDK 17, a TV device with ADB enabled.

## CI & releases

The **Android APK** workflow ([`.github/workflows/android.yml`](../.github/workflows/android.yml)) runs on every push/PR to `main` and on manual dispatch. Each run sets up JDK 17 + the SDK, writes a default `local.properties`, runs unit tests, builds the debug APK, and uploads it as the **`app-debug`** artifact. On pushes to `main` it also refreshes the rolling **`dev`** pre-release (deleted + re-created each push so it sorts to the top of the Releases list).

**Getting the APK:** repo **Releases** → latest build → `app-debug.apk` (no login), or a specific run's **Actions → Artifacts → `app-debug`**. Sideload with `adb install -r app-debug.apk`.

### Cutting a versioned release (and the in-app updater)

The app polls `releases/latest` on launch. To ship an update, bump **both** `versionCode` and `versionName` in `android/app/build.gradle.kts`, then tag a GitHub release with a higher number than the running build (e.g. `v1.2`). The updater compares numerically after stripping a leading `v`; equal versions aren't offered. Mark a release **prerelease** to reach only users who enabled **Beta updates** in Settings → Software.

### Committing / pushing

`main` is the working branch; pushing there triggers the workflow and refreshes the `dev` release. Native Widevine libs live in `android/app/src/main/jniLibs/` and the FFmpeg Media3 decoder in `android/app/libs/` — both are committed (the build needs them). Secrets (`auth-state.json`, `*.keystore`, `local.properties`, `.env`) are git-ignored and must never be committed.
