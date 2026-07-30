import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken, ensureBearer, invalidateBearer } from "../auth";
import { spawn } from "child_process";
import path from "path";
import fs from "fs";
import os from "os";

const stream = new Hono();

const PYTHON = process.env.PYTHON_BIN || "python3";
const DECRYPT_SCRIPT = path.join(import.meta.dir, "../../stream_decrypt.py");

const CACHE_DIR = path.join(os.tmpdir(), "am_stream_cache");
fs.mkdirSync(CACHE_DIR, { recursive: true });
// Drop only partial (.tmp) files on startup — those are the ones that cause Bad
// Position errors. Completed .mp4s are atomically renamed and stay valid, so we
// keep them: with --watch, wiping the cache on every code edit would be brutal.
try {
  const stale = fs.readdirSync(CACHE_DIR).filter((f) => !f.endsWith(".mp4"));
  for (const f of stale) { try { fs.unlinkSync(path.join(CACHE_DIR, f)); } catch (_) {} }
  if (stale.length > 0) console.log(`[stream] cleared ${stale.length} partial cache file(s) on startup`);
} catch (_) {}
evictCache();

// In-flight decrypt jobs, keyed by songId, so ExoPlayer's several parallel
// Range connections share one decrypt instead of racing.
/** An in-flight decrypt. `child` is kept so a prefetch can be killed when the
 *  user jumps to a different song and needs the bandwidth now. */
interface InFlightJob {
  promise: Promise<string>;
  child?: import("child_process").ChildProcess;
  background: boolean;
}
const inFlight_ref = new Map<string, InFlightJob>();

function cachePath(songId: string) {
  return path.join(CACHE_DIR, `${songId.replace(/[^a-zA-Z0-9._-]/g, "_")}.mp4`);
}

const CACHE_MAX_BYTES = 500 * 1_048_576; // 500 MB

/**
 * Evict least-recently-used cache files until the directory is under
 * CACHE_MAX_BYTES. Capping by bytes (not file count) keeps the ceiling honest —
 * an AAC track is ~25 MB but a lossless fallback can be 350 MB.
 */
function evictCache() {
  try {
    const files = fs.readdirSync(CACHE_DIR)
      .filter((f) => f.endsWith(".mp4"))
      .map((f) => {
        const p = path.join(CACHE_DIR, f);
        const st = fs.statSync(p);
        return { p, mtime: st.mtimeMs, size: st.size };
      })
      .sort((a, b) => b.mtime - a.mtime); // newest first

    let total = 0;
    let freed = 0;
    for (const f of files) {
      total += f.size;
      if (total <= CACHE_MAX_BYTES) continue;
      try {
        fs.unlinkSync(f.p);
        freed += f.size;
        console.log(`[stream] evicted ${path.basename(f.p)} (${(f.size / 1_048_576).toFixed(1)} MB)`);
      } catch (_) {} // still on disk (in use?) — leave it counted
    }
    if (freed > 0) {
      console.log(`[stream] cache ${((total - freed) / 1_048_576).toFixed(0)}MB / ${CACHE_MAX_BYTES / 1_048_576}MB after eviction`);
    }
  } catch (_) {}
}

/**
 * Kill every running prefetch so a song the user is waiting on gets the whole
 * pipe. Four concurrent decrypts split the WAN download four ways: measured 13-17s
 * each instead of ~6s, and the foreground track finished in 33s. A killed prefetch
 * is cheap — Android re-requests N+1 at the next song boundary anyway.
 */
function abortBackgroundJobs(exceptSongId?: string) {
  for (const [id, job] of inFlight_ref) {
    if (id === exceptSongId || !job.child) continue;
    // Kill stale FOREGROUND jobs too, not just prefetches. Skipping mid-decrypt left
    // the abandoned song still downloading and competing with the one now playing —
    // no prefetch involved, so the old background-only sweep never touched it.
    // ExoPlayer's parallel Range requests share a job by songId, so a different id
    // always means the user moved on.
    const kind = job.background ? "prefetch" : "abandoned decrypt";
    console.log(`[stream] aborting ${kind} ${id} — foreground request needs the bandwidth`);
    try { job.child.kill("SIGKILL"); } catch (_) {}
  }
}

/** Decrypt a song to a seekable cache file (once), returning its path. */
async function ensureDecrypted(songId: string, mut: string, background = false): Promise<string> {
  const out = cachePath(songId);
  if (fs.existsSync(out) && fs.statSync(out).size > 0) {
    console.log(`[stream] cache hit ${songId} (${(fs.statSync(out).size / 1_048_576).toFixed(1)} MB)`);
    const now = new Date();
    try { fs.utimesSync(out, now, now); } catch (_) {}
    return out;
  }

  const existing = inFlight_ref.get(songId);
  if (existing) {
    // A prefetch the user has now caught up to. Promote it so it survives the
    // abort sweep below rather than killing work that's already half done.
    if (!background && existing.background) {
      existing.background = false;
      console.log(`[stream] promoted prefetch ${songId} to foreground`);
    }
    if (!background) abortBackgroundJobs(songId);
    return existing.promise;
  }

  if (!background) abortBackgroundJobs();

  const entry: InFlightJob = { promise: null as any, background };

  entry.promise = (async () => {
    const t0 = Date.now();
    const tmpOut = out + ".tmp";
    try { if (fs.existsSync(tmpOut)) fs.unlinkSync(tmpOut); } catch (_) {}
    console.log(`[stream] decrypt start ${songId}`);

    const { streamUrl, adamId, keyUri } = await getStreamParams(songId, mut);
    const args = JSON.stringify({
      adamId, keyUri, streamUrl, bearer: getBearerToken(), mut, outPath: tmpOut,
    });
    await new Promise<void>((resolve, reject) => {
      const child = spawn(PYTHON, [DECRYPT_SCRIPT, args]);
      entry.child = child;
      let stderr = "";
      child.stdout.on("data", (d) => { console.log("[decrypt]", d.toString().trimEnd()); });
      child.stderr.on("data", (d) => { stderr += d.toString(); });
      child.on("error", reject);
      child.on("close", (code) => {
        if (code === 0) resolve();
        else {
          try { if (fs.existsSync(tmpOut)) fs.unlinkSync(tmpOut); } catch (_) {}
          const err = new Error(`decrypt exited ${code}: ${stderr}`);
          (err as any).exitCode = code;
          reject(err);
        }
      });
    });
    fs.renameSync(tmpOut, out);
    const sizeMb = (fs.statSync(out).size / 1_048_576).toFixed(1);
    console.log(`[stream] decrypt done ${songId} ${sizeMb} MB in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
    evictCache();
    return out;
  })();

  inFlight_ref.set(songId, entry);
  try {
    return await entry.promise;
  } finally {
    inFlight_ref.delete(songId);
  }
}

const playHeaders = (mut: string) => ({
  Authorization: `Bearer ${getBearerToken()}`,
  Cookie: `media-user-token=${mut}`,
  Origin: "https://music.apple.com",
  "Content-Type": "application/json",
});

/** If the playlist is a master playlist, follow it to the best media playlist. */
async function resolveMediaPlaylist(url: string): Promise<{ url: string; text: string }> {
  const res = await axios.get(url);
  const text: string = res.data;

  if (!text.includes("#EXT-X-STREAM-INF")) {
    return { url, text };
  }

  // Master playlist — pick best variant under 500 kbps (avoids ALAC/lossless),
  // falling back to lowest available if everything is above the cap.
  let bestBw = -1;
  let bestUrl = "";
  let fallbackBw = Infinity;
  let fallbackUrl = "";
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.startsWith("#EXT-X-STREAM-INF")) {
      const bwMatch = line.match(/BANDWIDTH=(\d+)/);
      const bw = bwMatch ? parseInt(bwMatch[1]) : 0;
      const nextUrl = lines[i + 1]?.trim();
      if (!nextUrl || nextUrl.startsWith("#")) continue;
      const resolved = nextUrl.startsWith("http") ? nextUrl : url.substring(0, url.lastIndexOf("/") + 1) + nextUrl;
      console.log(`[stream] variant bw=${bw} url=${resolved.substring(0, 60)}…`);
      if (bw <= 500_000) {
        if (bw >= bestBw) { bestBw = bw; bestUrl = resolved; }
      } else {
        if (bw < fallbackBw) { fallbackBw = bw; fallbackUrl = resolved; }
      }
    }
  }
  console.log(`[stream] picked bw=${bestBw >= 0 ? bestBw : fallbackBw} (cap=500kbps, had_under=${bestBw >= 0})`);
  if (!bestUrl) bestUrl = fallbackUrl;
  if (!bestUrl) throw new Error("No variant in master playlist");

  const mediaRes = await axios.get(bestUrl);
  return { url: bestUrl, text: mediaRes.data };
}

async function getStreamParams(songId: string, mut: string) {
  await ensureBearer();

  const isLibrary = songId.startsWith("i.");
  const numericId = songId.replace(/^[a-z]+\./, "");

  const WEB_PLAYBACK = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback";

  const bodies = isLibrary
    ? [{ universalLibraryId: songId }, { salableAdamId: numericId }]
    : [{ salableAdamId: numericId }, { universalLibraryId: songId }];

  let entry: any;
  for (const bodyBase of bodies) {
    console.log(`[stream] webPlayback body:`, JSON.stringify(bodyBase));
    const res = await axios.post(WEB_PLAYBACK, { ...bodyBase, language: "en-US" }, { headers: playHeaders(mut) });
    console.log(`[stream] webPlayback status=${res.status} songList.length=${res.data?.songList?.length ?? 0}`);
    entry = res.data?.songList?.[0];
    if (entry) break;
  }
  if (!entry) throw new Error("No songList in webPlayback — MUT may be invalid or expired");

  const adamId = String(entry.songId);
  const assets: any[] = entry.assets ?? [];
  console.log(`[stream] adamId=${adamId} assets=${assets.map((a: any) => a.flavor).join(", ")}`);

  // Prefer ctrp64 (smaller/compressed) over ctrp256 (likely lossless/hi-res).
  // ctrp = CTR-mode protected; 64 vs 256 suffix appears to be quality tier, not key size.
  const asset =
    assets.find((a: any) => a.flavor === "32:ctrp64") ||
    assets.find((a: any) => a.flavor === "28:ctrp256") ||
    assets.find((a: any) => a.flavor?.includes("ctrp")) ||
    assets.find((a: any) => typeof a.URL === "string" && a.URL);

  if (!asset?.URL) {
    const flavors = assets.map((a: any) => a.flavor).join(", ") || "none";
    throw new Error(`No playable asset (flavors: ${flavors})`);
  }

  console.log(`[stream] asset flavor=${asset.flavor} url=${asset.URL.substring(0, 80)}…`);

  const { url: mediaUrl, text: hlsText } = await resolveMediaPlaylist(asset.URL);
  console.log(`[stream] mediaUrl=${mediaUrl.substring(0, 80)}… hlsLen=${hlsText.length}`);

  // Try data: URI first (CENC/Widevine), then any URI="..." key
  const keyMatch = hlsText.match(/URI="(data:[^"]+)"/) ?? hlsText.match(/URI="([^"]+)"/);
  const keyUri = keyMatch?.[1];
  if (!keyUri) {
    console.error(`[stream] No key URI — first 300 chars of manifest:\n${hlsText.substring(0, 300)}`);
    throw new Error(`No key URI in HLS manifest`);
  }
  console.log(`[stream] keyUri type=${keyUri.startsWith("data:") ? "data" : "url"} len=${keyUri.length}`);

  return { streamUrl: mediaUrl, adamId, keyUri };
}

// GET /api/stream/cached/:songId — quick check: is this song already in the cache file?
stream.get("/cached/:songId", (c) => {
  const out = cachePath(c.req.param("songId"));
  const cached = fs.existsSync(out) && fs.statSync(out).size > 0;
  const inFlight = inFlight_ref.has(c.req.param("songId"));
  return c.json({ cached, inFlight });
});

// GET /api/prefetch/:songId — kick off decrypt in background, return 200 immediately.
stream.get("/prefetch/:songId", async (c) => {
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  if (!mut) { console.warn(`[prefetch] no MUT for ${c.req.param("songId")}`); return c.json({ ok: false, reason: "no_mut" }, 200); }
  const songId = c.req.param("songId");
  const out = cachePath(songId);
  if (fs.existsSync(out) && fs.statSync(out).size > 0) return c.json({ ok: true, cached: true });
  if (inFlight_ref.has(songId)) return c.json({ ok: true, inFlight: true });
  // One prefetch at a time, and never alongside a song the user is waiting on.
  // Dropping it is fine: the next song boundary re-requests N+1.
  for (const job of inFlight_ref.values()) {
    if (!job.background) {
      console.log(`[prefetch] deferred ${songId} — a foreground decrypt is running`);
      return c.json({ ok: true, deferred: "foreground_busy" });
    }
  }
  if ([...inFlight_ref.values()].some((j) => j.background)) {
    console.log(`[prefetch] deferred ${songId} — another prefetch is already running`);
    return c.json({ ok: true, deferred: "prefetch_busy" });
  }
  ensureDecrypted(songId, mut, true).catch((e) => console.error(`[prefetch] FAILED ${songId}:`, e.message));
  return c.json({ ok: true, started: true });
});

// GET /api/stream/:songId — decrypted audio, served from a seekable cache
// file with HTTP Range support so ExoPlayer can scrub instantly (clicking a
// lyric line seeks instead of restarting the track).
stream.get("/:songId", async (c) => {
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  if (!mut) return c.json({ error: "Music-User-Token not set" }, 401);

  const songId = c.req.param("songId");

  try {
    const filePath = await ensureDecrypted(songId, mut);
    const size = fs.statSync(filePath).size;
    const rangeHeader = c.req.header("range");

    const commonHeaders: Record<string, string> = {
      "Content-Type": "audio/mp4",
      "Accept-Ranges": "bytes",
      "Cache-Control": "no-store",
    };

    const file = Bun.file(filePath);
    console.log(`[stream] serve ${songId} size=${(size / 1_048_576).toFixed(1)}MB range=${rangeHeader ?? "full"}`);

    if (rangeHeader) {
      const m = rangeHeader.match(/bytes=(\d*)-(\d*)/);
      let start = m && m[1] ? parseInt(m[1], 10) : 0;
      let end = m && m[2] ? parseInt(m[2], 10) : size - 1;
      if (isNaN(start) || start < 0) start = 0;
      if (isNaN(end) || end >= size) end = size - 1;
      if (start > end) {
        return new Response(null, {
          status: 416,
          headers: { ...commonHeaders, "Content-Range": `bytes */${size}` },
        });
      }
      // Bun.file slice end is exclusive.
      return new Response(file.slice(start, end + 1), {
        status: 206,
        headers: {
          ...commonHeaders,
          "Content-Range": `bytes ${start}-${end}/${size}`,
          "Content-Length": String(end - start + 1),
        },
      });
    }

    return new Response(file, {
      status: 200,
      headers: { ...commonHeaders, "Content-Length": String(size) },
    });
  } catch (e: any) {
    console.error(`[stream] ${songId}:`, e.message);
    // Exit code 2 = Apple DRM refusal (failureType 3077). Return 404 so
    // ExoPlayer treats it as permanent and skips without retrying.
    const status = (e as any).exitCode === 2 ? 404 : 500;
    return c.json({ error: e.message }, status);
  }
});

export default stream;
