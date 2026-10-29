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
    // Translate ONE LINE PER REQUEST (bounded parallelism). A previous version joined every line
    // with a sentinel and split the single response back apart — but Google drops/merges the
    // sentinel and re-segments on its own sentence boundaries, so the split misaligned and each
    // line got a DIFFERENT line's translation ("translations wrong"). Per-line is unambiguous, and
    // the cache keeps repeated lines (choruses, toggling on/off) cheap.
    async function one(text: string): Promise<string> {
      try {
        const res = await axios.get("https://translate.googleapis.com/translate_a/single", {
          params: { client: "gtx", sl: "auto", tl: target, dt: "t", q: text },
          timeout: 12000,
          headers: { "User-Agent": "Mozilla/5.0" },
        });
        // Response: [[[translatedChunk, originalChunk, ...], ...], ...]. One line may still come back
        // as several chunks (long line) — concatenate them, which stays aligned to THIS line.
        const t = (res.data?.[0] ?? []).map((seg: any[]) => seg?.[0] ?? "").join("").trim();
        return t || text;
      } catch (e: any) {
        console.warn("[translate] failed:", e?.response?.status, e?.message);
        return text; // never blank
      }
    }
    const LIMIT = 8;
    for (let start = 0; start < need.length; start += LIMIT) {
      const batch = need.slice(start, start + LIMIT);
      const results = await Promise.all(batch.map((n) => one(n.text)));
      batch.forEach((n, k) => {
        out[n.i] = results[k];
        cache.set(`${target}\n${n.text}`, results[k]);
      });
    }
  }

  // Cap cache growth.
  if (cache.size > 4000) cache.clear();
  return c.json({ lines: out, to: target });
});

export default translateRoutes;
