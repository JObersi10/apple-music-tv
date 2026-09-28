import { Hono } from "hono";
import axios from "axios";

export const translateRoutes = new Hono();

// Simple in-memory cache: `${to}\n${text}` → translated. Lyrics repeat (choruses) and the user may
// toggle translation on/off, so caching avoids re-hitting the endpoint for the same lines.
const cache = new Map<string, string>();

/**
 * Translate lyric lines. Free, keyless Google endpoint (`translate_a/single`) — the same one the web
 * widget uses. Body: `{ lines: string[], to: string }` → `{ lines: string[], to }` (same length,
 * order preserved). Lines are joined with a sentinel so one request covers the whole song; on any
 * failure a line falls back to its original text so the UI never shows blanks.
 */
translateRoutes.post("/", async (c) => {
  const { lines, to } = await c.req.json().catch(() => ({} as any));
  if (!Array.isArray(lines) || lines.length === 0) return c.json({ error: "lines[] required" }, 400);
  const target = (typeof to === "string" && to.trim()) ? to.trim() : "en";

  // Split into cached / uncached. A blank line translates to blank.
  const out: string[] = new Array(lines.length);
  const need: { i: number; text: string }[] = [];
  lines.forEach((raw: any, i: number) => {
    const text = typeof raw === "string" ? raw : "";
    if (!text.trim()) { out[i] = text; return; }
    const key = `${target}\n${text}`;
    const hit = cache.get(key);
    if (hit !== undefined) out[i] = hit; else need.push({ i, text });
  });

  if (need.length > 0) {
    // A rare unicode sentinel Google won't translate away, so we can split the batch back apart.
    const SENT = "\n␞\n";
    const joined = need.map((n) => n.text).join(SENT);
    try {
      const res = await axios.get("https://translate.googleapis.com/translate_a/single", {
        params: { client: "gtx", sl: "auto", tl: target, dt: "t", q: joined },
        timeout: 12000,
        headers: { "User-Agent": "Mozilla/5.0" },
      });
      // Response: [[[translatedChunk, originalChunk, ...], ...], ...]. Concatenate chunk[0] then split.
      const chunks: string = (res.data?.[0] ?? []).map((seg: any[]) => seg?.[0] ?? "").join("");
      const parts = chunks.split("␞").map((s) => s.trim());
      need.forEach((n, k) => {
        const t = (parts[k] ?? n.text).trim() || n.text;
        out[n.i] = t;
        cache.set(`${target}\n${n.text}`, t);
      });
    } catch (e: any) {
      console.warn("[translate] failed:", e?.response?.status, e?.message);
      // Fall back to originals for the uncached lines.
      need.forEach((n) => { if (out[n.i] === undefined) out[n.i] = n.text; });
    }
  }

  // Cap cache growth.
  if (cache.size > 4000) cache.clear();
  return c.json({ lines: out, to: target });
});

export default translateRoutes;
