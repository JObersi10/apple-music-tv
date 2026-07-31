import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken, hasMUT, getStorefront } from "../auth";

const lyrics = new Hono();

lyrics.get("/:songId", async (c) => {
  if (!hasMUT()) return c.json({ error: "Music-User-Token not set" }, 401);
  const songId = c.req.param("songId");
  const t0 = Date.now();

  const headers = {
    Authorization: `Bearer ${getBearerToken()}`,
    "Music-User-Token": getMUT(),
    Origin: "https://music.apple.com",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
  };

  // 1) Apple Music is the primary source (word-by-word timed TTML).
  try {
    const appleLines = await fetchAppleLyrics(songId, headers);
    if (appleLines && appleLines.length > 0) {
      const wordCount = appleLines.reduce((n, l) => n + l.words.length, 0);
      const lineCount = appleLines.length;
      console.log(`[lyrics] ${songId} apple ${wordCount > 0 ? `word-by-word (${wordCount} words, ${lineCount} lines)` : `line-synced (${lineCount} lines)`} ${Date.now() - t0}ms`);
      return c.json({ lines: appleLines, source: "apple" });
    }
  } catch (e: any) { console.warn(`[lyrics] ${songId} apple failed: ${e.message}`); }

  // 2) Fallback: lrclib.net
  try {
    const meta = await fetchSongMeta(songId, headers);
    if (meta) {
      const fallbackLines = await fetchLrclibLyrics(meta);
      if (fallbackLines && fallbackLines.length > 0) {
        console.log(`[lyrics] ${songId} lrclib (${fallbackLines.length} lines) ${Date.now() - t0}ms`);
        return c.json({ lines: fallbackLines, source: "lrclib" });
      }
    }
  } catch (e: any) { console.warn(`[lyrics] ${songId} lrclib failed: ${e.message}`); }

  console.log(`[lyrics] ${songId} none ${Date.now() - t0}ms`);
  return c.json({ lines: [], source: "none" });
});

/** Fetch TTML from an endpoint, trying syllable-lyrics then lyrics. */
async function fetchTTML(
  baseUrl: string,
  headers: Record<string, string>
): Promise<LyricLine[] | null> {
  // Try word-level syllable endpoint first, fall back to line-level.
  for (const suffix of ["syllable-lyrics", "lyrics"]) {
    try {
      const res = await axios.get(`${baseUrl}/${suffix}`, { headers });
      const ttml = res.data?.data?.[0]?.attributes?.ttml ?? null;
      if (ttml) {
        const lines = parseTTML(ttml);
        if (lines.length > 0) return lines;
      }
    } catch (_) {}
  }
  return null;
}

/** Apple Music TTML lyrics (library id resolves to catalog if needed). */
async function fetchAppleLyrics(
  songId: string,
  headers: Record<string, string>
): Promise<LyricLine[] | null> {
  const isLibrary = songId.startsWith("i.");
  const sf = getStorefront();

  if (isLibrary) {
    const libBase = `https://amp-api-edge.music.apple.com/v1/me/library/songs/${songId}`;
    const libLines = await fetchTTML(libBase, headers);
    if (libLines) return libLines;

    const catalogId = await resolveCatalogId(songId, headers);
    if (catalogId) {
      const catBase = `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/songs/${catalogId}`;
      return fetchTTML(catBase, headers);
    }
    return null;
  }

  const catBase = `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/songs/${songId}`;
  return fetchTTML(catBase, headers);
}

async function resolveCatalogId(
  libraryId: string,
  headers: Record<string, string>
): Promise<string | null> {
  try {
    const rel = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/me/library/songs/${libraryId}?include=catalog`,
      { headers }
    );
    return rel.data?.data?.[0]?.relationships?.catalog?.data?.[0]?.id ?? null;
  } catch (_) {
    return null;
  }
}

interface SongMeta {
  title: string;
  artist: string;
  album: string;
  durationSec: number;
}

/** Fetch song attributes (title/artist/album/duration) for lrclib lookup. */
async function fetchSongMeta(
  songId: string,
  headers: Record<string, string>
): Promise<SongMeta | null> {
  const sf = getStorefront();
  let catalogId = songId;
  if (songId.startsWith("i.")) {
    catalogId = (await resolveCatalogId(songId, headers)) ?? "";
    if (!catalogId) return null;
  }
  try {
    const res = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/songs/${catalogId}`,
      { headers }
    );
    const a = res.data?.data?.[0]?.attributes;
    if (!a?.name || !a?.artistName) return null;
    return {
      title: a.name,
      artist: a.artistName,
      album: a.albumName ?? "",
      durationSec: Math.round((a.durationInMillis ?? 0) / 1000),
    };
  } catch (_) {
    return null;
  }
}

function normalizeArtist(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function isGoodLrclibMatch(data: any, meta: SongMeta): boolean {
  // Artist must loosely match — reject clear mismatches.
  const got  = normalizeArtist(data.artistName ?? "");
  const want = normalizeArtist(meta.artist);
  if (got && want && !got.includes(want) && !want.includes(got)) return false;
  // Duration must be within 10 seconds.
  if (meta.durationSec > 0 && typeof data.duration === "number") {
    if (Math.abs(data.duration - meta.durationSec) > 10) return false;
  }
  return true;
}

/** lrclib.net — line-synced LRC lyrics, converted to our LyricLine shape. */
async function fetchLrclibLyrics(meta: SongMeta): Promise<LyricLine[] | null> {
  const params = new URLSearchParams({
    track_name: meta.title,
    artist_name: meta.artist,
  });
  if (meta.durationSec > 0) params.set("duration", String(meta.durationSec));

  const lrcHeaders = { "User-Agent": "AppleMusicTV (github.com/applemusicktv)" };

  // Prefer the exact /get match; validate it's actually the right song.
  let synced: string | null = null;
  try {
    const res = await axios.get(`https://lrclib.net/api/get?${params.toString()}`, {
      headers: lrcHeaders,
    });
    if (res.data?.syncedLyrics && isGoodLrclibMatch(res.data, meta)) {
      synced = res.data.syncedLyrics;
    }
  } catch (_) {}

  // Fall back to /search — pick only a hit that passes artist+duration check.
  if (!synced) {
    try {
      const q = new URLSearchParams({ track_name: meta.title, artist_name: meta.artist });
      const res = await axios.get(`https://lrclib.net/api/search?${q.toString()}`, {
        headers: lrcHeaders,
      });
      const hit = Array.isArray(res.data)
        ? res.data.find((r: any) => r?.syncedLyrics && isGoodLrclibMatch(r, meta))
        : null;
      synced = hit?.syncedLyrics ?? null;
    } catch (_) {}
  }

  if (!synced) return null;
  return parseLRC(synced);
}

/** Parse standard `[mm:ss.xx] text` LRC into line-synced LyricLines. */
function parseLRC(lrc: string): LyricLine[] {
  const out: { startMs: number; text: string }[] = [];
  const tagRe = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g;
  for (const raw of lrc.split(/\r?\n/)) {
    const text = decodeEntities(raw.replace(tagRe, "").trim());
    tagRe.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = tagRe.exec(raw)) !== null) {
      const min = parseInt(m[1], 10);
      const sec = parseInt(m[2], 10);
      const frac = m[3] ? parseInt(m[3].padEnd(3, "0"), 10) : 0;
      const startMs = min * 60000 + sec * 1000 + frac;
      if (text) out.push({ startMs, text });
    }
  }
  out.sort((a, b) => a.startMs - b.startMs);
  return out.map((l, i) => ({
    startMs: l.startMs,
    endMs: i + 1 < out.length ? out[i + 1].startMs : l.startMs + 5000,
    text: l.text,
    words: [],
    background: null,
  }));
}

interface LyricWord {
  startMs: number;
  endMs: number;
  text: string;
}

interface LyricBackground {
  startMs: number;
  endMs: number;
  text: string;
  words: LyricWord[];
}

interface LyricLine {
  startMs: number;
  endMs: number;
  text: string;
  words: LyricWord[];
  background: LyricBackground | null;
}

// ── Minimal tag-tree parser ──────────────────────────────────────────────
// TTML nests background-vocal words inside a <span ttm:role="x-bg"> that is
// itself a child of the line <p>. A regex can't reliably tell that span's
// closing tag apart from its children's, so we tokenize + build a real tree.

type Token =
  | { type: "open"; tag: string; attrs: string; selfClose: boolean }
  | { type: "close"; tag: string }
  | { type: "text"; text: string };

interface TagNode {
  tag: string;
  attrs: string;
  children: (TagNode | string)[];
}

function tokenize(xml: string): Token[] {
  const tokens: Token[] = [];
  const re = /<(\/?)([a-zA-Z0-9:_-]+)([^<>]*?)(\/?)>|([^<]+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(xml)) !== null) {
    if (m[5] !== undefined) {
      tokens.push({ type: "text", text: m[5] });
    } else {
      const closing = m[1] === "/";
      const tag = m[2];
      const attrs = m[3] ?? "";
      const selfClose = m[4] === "/" || /\/\s*$/.test(attrs);
      if (closing) tokens.push({ type: "close", tag });
      else tokens.push({ type: "open", tag, attrs, selfClose });
    }
  }
  return tokens;
}

/**
 * Apple's TTML is XML, so "Rae Sremmurd & Friends" arrives as "&amp;". Decoding at
 * tree-build time covers every downstream consumer (per-word and flattened text).
 */
function decodeEntities(s: string): string {
  return s
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&"); // last: otherwise "&amp;lt;" would become "<"
}

function buildTree(tokens: Token[]): TagNode {
  const root: TagNode = { tag: "root", attrs: "", children: [] };
  const stack: TagNode[] = [root];
  for (const t of tokens) {
    const top = stack[stack.length - 1];
    if (t.type === "text") {
      top.children.push(decodeEntities(t.text));
    } else if (t.type === "open") {
      const node: TagNode = { tag: t.tag, attrs: t.attrs, children: [] };
      top.children.push(node);
      if (!t.selfClose) stack.push(node);
    } else {
      for (let i = stack.length - 1; i > 0; i--) {
        if (stack[i].tag === t.tag) {
          stack.length = i;
          break;
        }
      }
    }
  }
  return root;
}

function findAll(node: TagNode, tag: string, out: TagNode[] = []): TagNode[] {
  for (const child of node.children) {
    if (typeof child === "string") continue;
    if (child.tag === tag) out.push(child);
    findAll(child, tag, out);
  }
  return out;
}

function attr(attrs: string, name: string): string | undefined {
  return attrs.match(new RegExp(`(?:^|[\\s:])${name}="([^"]+)"`))?.[1];
}

function flattenText(node: TagNode): string {
  return rawText(node).trim();
}

/** Flattened text with whitespace intact — needed to spot word boundaries. */
function rawText(node: TagNode): string {
  return node.children.map((c) => (typeof c === "string" ? c : rawText(c))).join("");
}

function isBackgroundSpan(attrs: string): boolean {
  return /ttm:role="x-bg"|(?:^|\s)role="x-bg"/.test(attrs);
}

const isSpan = (c: TagNode | string): c is TagNode =>
  typeof c !== "string" && (c.tag === "span" || c.tag.endsWith(":span"));

// Direct child <span> elements of a node, in document order.
function childSpans(node: TagNode): TagNode[] {
  return node.children.filter(isSpan);
}

/** Direct child spans plus whether whitespace follows each one (a word boundary).
 *  Apple separates syllables of one word with adjacent spans and no whitespace;
 *  real word breaks have a space inside the span or as a sibling text node. */
function childSpanEntries(node: TagNode): { span: TagNode; endsWord: boolean }[] {
  const kids = node.children;
  const out: { span: TagNode; endsWord: boolean }[] = [];
  for (let i = 0; i < kids.length; i++) {
    const c = kids[i];
    if (!isSpan(c)) continue;
    let endsWord = /\s$/.test(rawText(c));
    for (let j = i + 1; j < kids.length && !isSpan(kids[j]); j++) {
      if (/\s/.test(kids[j] as string)) { endsWord = true; break; }
    }
    out.push({ span: c, endsWord });
  }
  return out;
}

function spanToWord(span: TagNode, endsWord = true): LyricWord | null {
  const begin = attr(span.attrs, "begin");
  const raw = flattenText(span);
  if (!begin || !raw) return null;
  const end = attr(span.attrs, "end");
  return {
    startMs: parseTime(begin),
    endMs: end ? parseTime(end) : parseTime(begin) + 500,
    text: raw,
    _endsWord: endsWord,
  } as LyricWord & { _endsWord: boolean };
}

/** Merge consecutive syllable spans into whole words.
 *  Apple sends word-boundary spans with a trailing space; syllables have none.
 *  e.g. "happ" + "ening " → "happening" */
function mergeSyllables(raw: (LyricWord & { _endsWord?: boolean })[]): LyricWord[] {
  const out: LyricWord[] = [];
  let i = 0;
  while (i < raw.length) {
    let cur = raw[i];
    while (!(cur as any)._endsWord && i + 1 < raw.length) {
      i++;
      const next = raw[i];
      // Carry next's boundary flag — without it the merged object has no
      // _endsWord and the loop runs away to the end of the line.
      cur = {
        startMs: cur.startMs, endMs: next.endMs,
        text: cur.text + next.text, _endsWord: next._endsWord,
      } as any;
    }
    out.push({ startMs: cur.startMs, endMs: cur.endMs, text: cur.text.trim() });
    i++;
  }
  // Safety net: if boundary detection failed the whole line collapses into one
  // "word" — that's worse than no merging, so hand back the original spans.
  if (out.length === 1 && raw.length > 3) {
    return raw.map((w) => ({ startMs: w.startMs, endMs: w.endMs, text: w.text.trim() }));
  }
  return out;
}

function parseTTML(ttml: string): LyricLine[] {
  const timing = ttml.match(/itunes:timing="([^"]+)"/)?.[1] ?? "unknown"
  console.log("[ttml] timing:", timing)
  const tree = buildTree(tokenize(ttml));
  const pNodes = findAll(tree, "p");
  const lines: LyricLine[] = [];

  for (const p of pNodes) {
    const beginAttr = attr(p.attrs, "begin");
    if (!beginAttr) continue;
    const startMs = parseTime(beginAttr);
    const endAttr = attr(p.attrs, "end");
    const endMs = endAttr ? parseTime(endAttr) : startMs + 5000;

    const rawWords: (LyricWord & { _endsWord?: boolean })[] = [];
    let background: LyricBackground | null = null;

    for (const { span, endsWord } of childSpanEntries(p)) {
      if (isBackgroundSpan(span.attrs)) {
        const rawBg: (LyricWord & { _endsWord?: boolean })[] = [];
        for (const inner of childSpanEntries(span)) {
          const w = spanToWord(inner.span, inner.endsWord);
          if (w) rawBg.push(w as any);
        }
        if (rawBg.length === 0) {
          const w = spanToWord(span);
          if (w) rawBg.push(w as any);
        }
        if (rawBg.length > 0) {
          const bgWords = mergeSyllables(rawBg);
          const bgBeginAttr = attr(span.attrs, "begin");
          const bgEndAttr = attr(span.attrs, "end");
          background = {
            startMs: bgBeginAttr ? parseTime(bgBeginAttr) : bgWords[0].startMs,
            endMs: bgEndAttr ? parseTime(bgEndAttr) : bgWords[bgWords.length - 1].endMs,
            text: bgWords.map((w) => w.text).join(" "),
            words: bgWords,
          };
        }
      } else {
        const w = spanToWord(span, endsWord);
        if (w) rawWords.push(w as any);
      }
    }

    const words = mergeSyllables(rawWords);
    const lineText = words.length > 0 ? words.map((w) => w.text).join(" ") : flattenText(p);
    if (!lineText) continue;

    lines.push({ startMs, endMs, text: lineText, words, background });
  }

  return lines;
}

function parseTime(t: string): number {
  const clean = t.replace(/s$/, "");
  const parts = clean.split(":").map(Number);
  if (parts.length === 3) return Math.round((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000);
  if (parts.length === 2) return Math.round((parts[0] * 60 + parts[1]) * 1000);
  return Math.round(parts[0] * 1000);
}

export default lyrics;
