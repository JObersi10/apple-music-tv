# Roadmap

Rough direction, not a promise. Roughly ordered by impact.

## Planned / exploring

- **UI glow-up (v2)** — another full visual pass to bring the interface in line with Apple's own Apple Music app on Roku: cleaner shelves, larger editorial art, refined spacing/typography, and more true-to-Apple screen layouts.
- **True no-PC mode** — port the remaining proxy routes to on-device Kotlin so browse/library/search/lyrics/artwork work with zero computer (playback already does). The hard parts (bearer scrape, webPlayback, Widevine) are done; what's left is data-route + JSON mapping.
- **Queue editing** — reorder and remove from Up Next with the D-pad (list-mutation only, no decoder juggling).
- **Lyrics translation** — Apple-style translated line under each original; probe Apple's endpoint first, else on-device ML Kit translation.
- **Offline / downloads** — cache decrypted songs on-device for playback without a network.
- **Autoplay / infinite mix** — station-style continuation when the queue ends.
- **Sound Check** — proper per-track loudness normalization (current volume leveling is RMS only).

## Known issues

- **Uneven vertical focus** in Listen Now / Browse — focus movement scrolls the list and lands rows slightly off-centre; the LazyColumn shouldn't re-anchor on focus.
- **Radio shows** (non-live episodes) are hidden until playback is wired; live Apple Music Radio works.
- **Volume leveling** is experimental and off by default.

## Done recently

See [RELEASE_NOTES.md](RELEASE_NOTES.md) and [HANDOFF.md](HANDOFF.md).
