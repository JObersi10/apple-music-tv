# Roadmap — deferred work

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

## Other queued polish
- Soft margins (content fades under the top bar).
- Artist video hero + collapsing hero.
- Radio/Videos card-size + accent fine-tuning vs Home/New.
- MV audio-only rebuild speed (tied to the video-model recode).
