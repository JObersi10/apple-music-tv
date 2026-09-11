# Roadmap — deferred work

## Artist page (V2) revamp — DO FIRST
`ArtistDetailScreenV2.kt`. Two things:
1. **Collapsing hero, not a fixed block.** Today the artist photo is LazyColumn item 0 (480dp) that
   scrolls away entirely. Wanted: the photo stays as a **backdrop** and fades *behind/into* the Top
   Songs (Apple style) — content scrolls up over the gradient, the picture is still visible mid-page,
   not pinned on top. Likely: pin the image in the `Box` background, overlay the scrolling content, and
   drive its alpha/parallax from the LazyColumn scroll offset; reset cleanly on scroll back to top.
2. **Focus-up trap.** Once focus goes DOWN into Top Songs / lower shelves you can't come back UP. Cause:
   the hero controls scroll off-screen and get disposed, so there's no focusable target above. Fix with a
   focus restorer / keeping the hero controls reachable (e.g. `focusRestorer`, a sticky control row, or
   scroll-to-top on Up from the first shelf). Verify on-device — TV focus can't be checked blind.


## Lyrics translation without the proxy
Today `/api/translate` (proxy) does a keyless Google `translate_a/single` call and the app calls the
proxy. To drop the server dependence, move the call **into the app** — either:
- Direct keyless call to `https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=<lang>&dt=t&q=<lines>`
  and parse the nested JSON array (batch all lines with a newline join, like the server does), or
- ML Kit Translate (`com.google.mlkit:translate`) for true offline (one-time per-language model download).

Kept the working proxy version for now (per "use an API … else remove it for now, add to roadmap").

## Music-video picture bleed — full recode (if v3 doesn't hold)
Three teardown strategies tried against the secure (`setSecure(true)`, needed for HD) SurfaceView:
1. 1px behind the window — bled through the Videos tab.
2. Destroy on leave — orphaned the SurfaceFlinger layer (Fire TV latches the last protected buffer).
3. **(current)** Keep mounted, drop to audio-only (no protected frame), move far off-screen.

If v3 still bleeds, the real cure is a video-model recode: render the MV in its own dedicated
window/`Presentation` (or a separate secure surface with explicit `SurfaceHolder` destroy/recreate),
fully dismissed when leaving Now Playing. See memory `video-surface-bleed`.

## Music-video: ~1s video reload when returning to Now Playing
Leaving Now Playing disables the video track (frees the secure decoder so BT audio doesn't stutter);
returning re-enables it, and the decoder + surface re-acquire takes ~1s before the picture is back.
Keeping the decoder alive off-screen (v6) removes the delay but churns the secure decoder and
**stutters Bluetooth audio**, so track-disable stays. Options to try on-device: (a) pre-warm the video
decoder a beat before the nav transition completes; (b) hold the last frame (`setKeepContentOnPlayerReset`)
so the reload isn't visible; (c) accept it. Alternatively, a **hard "stop video on leave"** — close the
MV entirely when navigating away instead of continuing its audio — kills the delay, the bleed, and the
stutter in one go, at the cost of the "audio keeps playing like a song across tabs" behaviour.

## Dynamic Now Playing background — Apple's ambient-video approach
Confirmed from music.apple.com's lyrics fullscreen DOM: Apple layers two things behind the lyrics —
1. a `<canvas data-testid="now-playing-backdrop">` painting the animated color wash (we already do this
   in `DynamicBackground` — canvas gradient blobs from the artwork palette), and
2. an `<amp-ambient-video>` — the album's editorial **motion loop** — as a full backdrop behind the
   artwork when one exists.
We ALREADY fetch that motion loop: `GET /api/motion/:songId` returns the square motion-art HLS, and
`MotionCover` plays it over the static cover on Now Playing. The remaining work is to also render that
same motion URL FULLSCREEN behind the lyrics/queue (under the color wash + darkening overlays), muted +
looping, only when `motionUrl != null`; fall back to the pure color wash otherwise. Low risk — reuses
the URL and the ExoPlayer/MotionCover plumbing already in place. Keep the 4-blob perf budget; the video
is one decoder, so gate it behind Low Power off.

## Lyrics translation — upgrade to Apple's native translations
Today translation uses the proxy's keyless Google endpoint (`/api/translate`) — works but unofficial
and occasionally rate-limited. Apple's own lyrics API returns translations inline (seen in the web DOM:
`enable-translations="true"` → a `secondary` line per lyric). Upgrade `/api/lyrics/:id` to request and
parse Apple's own translation track (the `secondary`/localization spans) so translations are official
and don't depend on Google. Keep the Google path as the fallback for songs Apple hasn't translated.

## Other queued polish
- Soft margins (content fades under the top bar).
- Artist video hero + collapsing hero.
- Radio/Videos card-size + accent fine-tuning vs Home/New.
- MV audio-only rebuild speed (tied to the video-model recode).

## Album page: Music Videos section
Albums rarely carry MVs in the tracklist, but the artist often has related MVs. Add a "Music Videos"
shelf at the BOTTOM of `AlbumDetailScreen` — pull the artist feed (`/api/artists/:id/full` already
returns `musicVideos`) using the album's `artistId`, filter to videos matching this album/era, render
as 16:9 thumbnails that play via `playerVm.playAlbum(videos, i)`. Also consider MV badges on search
results.
