# Third-Party Notices

Apple Music TV is licensed under **GPL-3.0** (see [LICENSE](LICENSE)). It uses the
third-party projects listed below. Each retains its own license; those licenses
apply to those components, not to the rest of this project.

## Adapted code

- **gamdl** — MIT — https://github.com/glomatico/gamdl
  The server-side Apple Music CENC decryption workflow (Widevine license
  acquisition via Apple's `acquireWebPlaybackLicense` endpoint, PSSH
  reconstruction, and `mp4decrypt` handoff) was adapted from gamdl. See
  `server/get_key.py`, `server/stream_decrypt.py`, and `server/ref_key.py`;
  those scripts also import gamdl's `WVD` device at runtime. The on-device Kotlin
  port (`AppleMusicDrmCallback`, `AppleDirectClient`) follows the same license
  approach using Android's native MediaDrm.

## Runtime dependencies

- **pywidevine** — GPL-3.0 — https://github.com/devine-dl/pywidevine
  Widevine CDM used by the Python decryption scripts to obtain content keys. Its
  copyleft license is the reason this project is distributed under GPL-3.0.
- **Bento4 / mp4decrypt** — GPL-2.0 or commercial — https://github.com/axiomatic-systems/Bento4
  External binary invoked to remove CENC encryption from decrypted audio.
- **FFmpeg** — LGPL-2.1+/GPL — https://ffmpeg.org
  External binary used to remux/transcode audio on the server, and via the Media3
  FFmpeg decoder extension on-device.
- **AndroidX, Jetpack Compose, Media3 (ExoPlayer)** — Apache-2.0 — https://developer.android.com/jetpack
  UI toolkit (Compose for TV) and media playback stack for the Android/Fire TV app.
- **Hilt / Dagger** — Apache-2.0 — https://dagger.dev/hilt
  Dependency injection for the Android app.
- **OkHttp, Retrofit, Moshi** — Apache-2.0 — https://square.github.io/okhttp
  HTTP client, REST layer, and JSON parsing for the Android app.
- **Coil** — Apache-2.0 — https://coil-kt.github.io/coil
  Image loading for artwork on the Android app.
- **Hono** — MIT — https://hono.dev
  Web framework for the optional PC proxy server.

## Inspiration / reference

- **Spicy Lyrics** — https://github.com/Spikerko/spicy-lyrics
  Referenced only as inspiration for the concept of dynamic, word-synchronized
  lyrics. The word-synced lyrics implementation in this project was written
  independently; no Spicy Lyrics code was copied or adapted.

## Data source

Apple Music content, artwork, editorial data, and lyrics are provided by Apple.
This project is not affiliated with or endorsed by Apple. You must bring your own
Apple Music subscription; streams are decrypted locally for personal playback only.
