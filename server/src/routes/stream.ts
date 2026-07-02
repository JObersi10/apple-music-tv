import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken, ensureBearer } from "../auth";
import { spawn } from "child_process";
import path from "path";
import fs from "fs";
import os from "os";

const stream = new Hono();

const PYTHON = process.env.PYTHON_BIN || "python3";
const DECRYPT_SCRIPT = path.join(import.meta.dir, "../../stream_decrypt.py");

const CACHE_DIR = path.join(os.tmpdir(), "am_stream_cache");
fs.mkdirSync(CACHE_DIR, { recursive: true });

// In-flight decrypt jobs, keyed by songId, so ExoPlayer's several parallel
// Range connections share one decrypt instead of racing.
const inFlight = new Map<string, Promise<string>>();

function cachePath(songId: string) {
  return path.join(CACHE_DIR, `${songId.replace(/[^a-zA-Z0-9._-]/g, "_")}.mp4`);
}

/** Decrypt a song to a seekable cache file (once), returning its path. */
async function ensureDecrypted(songId: string, mut: string): Promise<string> {
  const out = cachePath(songId);
  if (fs.existsSync(out) && fs.statSync(out).size > 0) return out;

  const existing = inFlight.get(songId);
  if (existing) return existing;

  const job = (async () => {
    const { streamUrl, adamId, keyUri } = await getStreamParams(songId, mut);
    const args = JSON.stringify({
      adamId, keyUri, streamUrl, bearer: getBearerToken(), mut, outPath: out,
    });
    await new Promise<void>((resolve, reject) => {
      const child = spawn(PYTHON, [DECRYPT_SCRIPT, args]);
      let stderr = "";
      child.stdout.on("data", (d) => { console.log("[decrypt]", d.toString().trimEnd()); });
      child.stderr.on("data", (d) => { stderr += d.toString(); });
      child.on("error", reject);
      child.on("close", (code) => {
        if (code === 0) resolve();
        else reject(new Error(`decrypt exited ${code}: ${stderr}`));
      });
    });
    return out;
  })();

  inFlight.set(songId, job);
  try {
    return await job;
  } finally {
    inFlight.delete(songId);
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
      if (bw <= 500_000) {
        if (bw >= bestBw) { bestBw = bw; bestUrl = resolved; }
      } else {
        if (bw < fallbackBw) { fallbackBw = bw; fallbackUrl = resolved; }
      }
    }
  }
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

  const asset =
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
    return c.json({ error: e.message }, 500);
  }
});

export default stream;
