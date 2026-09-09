import { Hono } from "hono";
import { spawn } from "child_process";
import path from "path";

/**
 * Song identification for internet radio (radio-browser streams carry no track metadata).
 * `GET /api/identify?url=<streamUrl>` grabs a few seconds with ffmpeg and runs it through Shazam
 * (`shazam_identify.py` → shazamio). Returns `{title, artist, artwork}` or `{title:null}` on a miss.
 * Requires `pip install shazamio` on the server host; without it the route returns null cleanly.
 */
export const identifyRoutes = new Hono();

import fs from "fs";

// Shazam runs in its OWN venv (`server/.shazam-venv`) — shazamio-core needs a Python with a prebuilt
// wheel (3.13), separate from gamdl's PYTHON_BIN (3.14, no wheel → Rust build fails). Override with
// SHAZAM_PYTHON if needed; else fall back to the venv, then PYTHON_BIN.
const VENV_PY = path.join(process.cwd(), ".shazam-venv", "bin", "python3");
const PYTHON = process.env.SHAZAM_PYTHON || (fs.existsSync(VENV_PY) ? VENV_PY : (process.env.PYTHON_BIN || "python3"));
const FFMPEG = process.env.FFMPEG_BIN || "ffmpeg";
const SCRIPT = path.join(process.cwd(), "shazam_identify.py");

// Cache the last identification per stream for 25s — Shazam is slow (~8s) and Android polls.
const cache = new Map<string, { at: number; data: any }>();
const TTL = 25_000;
// Only one recognize at a time per stream (they're heavy).
const inFlight = new Map<string, Promise<any>>();

function runShazam(url: string): Promise<any> {
  return new Promise((resolve) => {
    let out = "";
    const child = spawn(PYTHON, [SCRIPT, url, FFMPEG], { timeout: 40_000 });
    child.stdout.on("data", (d) => (out += d.toString()));
    child.on("error", () => resolve({ title: null }));
    child.on("close", () => {
      try {
        const line = out.trim().split("\n").filter(Boolean).pop() || "{}";
        resolve(JSON.parse(line));
      } catch {
        resolve({ title: null });
      }
    });
  });
}

identifyRoutes.get("/", async (c) => {
  const url = c.req.query("url");
  if (!url) return c.json({ title: null, error: "missing url" }, 400);

  const hit = cache.get(url);
  if (hit && Date.now() - hit.at < TTL) return c.json(hit.data);

  let p = inFlight.get(url);
  if (!p) {
    p = runShazam(url).finally(() => inFlight.delete(url));
    inFlight.set(url, p);
  }
  const data = await p;
  cache.set(url, { at: Date.now(), data });
  return c.json(data);
});
