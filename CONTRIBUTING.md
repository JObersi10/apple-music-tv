# Contributing

Thanks for your interest. This is a personal / educational project, but PRs and issues are welcome.

## Getting set up

- **Android app:** Android Studio, JDK 17, SDK compileSdk 35. Build with `cd android && ./gradlew assembleDebug`, install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Optional proxy server:** see [docs/TECHNICAL.md](docs/TECHNICAL.md#optional-pc-proxy-server).
- You'll need your own Apple Music subscription and Music-User-Token to run anything end-to-end.

## Where things live

| Area | Path |
|------|------|
| Android app | `android/app/src/main/java/com/applemusicktv/` |
| Compose screens | `.../ui/screens/`, shared UI in `.../ui/components/` |
| Playback / ExoPlayer | `.../ui/viewmodel/PlayerViewModel.kt`, `.../media/` |
| On-device decrypt (standalone) | `.../media/` (Widevine CDM, CENC decryptor, `AppleDirectClient`) |
| Proxy server routes | `server/src/routes/` |

See [CLAUDE.md](CLAUDE.md) for a dense, up-to-date map of how the pieces fit together and the non-obvious gotchas (it's the most detailed doc in the repo).

## Guidelines

- Match the surrounding style — this codebase favours small, well-commented changes over broad rewrites.
- Keep the standalone (on-device) and proxy paths at parity when you touch data fetching; most data has a mirror in `DirectMusicDataSource` / `DirectBrowseSource`.
- Never commit secrets: `auth-state.json`, `.env`, `local.properties`, and `*.keystore` are gitignored for a reason.
- Test on a real Fire TV / Android TV device when you can — the emulator misses focus, HDCP, and decoder behaviour that matter here.

## Pull requests

- Branch off `main`, keep the PR focused, and describe what you changed and how you verified it.
- CI builds the APK on every PR; make sure it's green.
