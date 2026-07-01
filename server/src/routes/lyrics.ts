import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken, hasMUT, getStorefront } from "../auth";

const lyrics = new Hono();

lyrics.get("/:songId", async (c) => {
  if (!hasMUT()) return c.json({ error: "Music-User-Token not set" }, 401);
  const songId = c.req.param("songId");

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
      return c.json({ lines: appleLines, source: "apple" });
    }
  } catch (_) {}

  // 2) Fallback: lrclib.net (line-synced, no word timing, no auth needed) —
  // the same free community source ecosystem Spicy Lyrics leans on when the
  // primary provider has nothing.
  try {
    const meta = await fetchSongMeta(songId, headers);
    if (meta) {
      const fallbackLines = await fetchLrclibLyrics(meta);
      if (fallbackLines && fallbackLines.length > 0) {
        return c.json({ lines: fallbackLines, source: "lrclib" });
      }
    }
  } catch (_) {}

  return c.json({ lines: [], source: "none" });
});

/** Apple Music TTML lyrics (library id resolves to catalog if needed). */
async function fetchAppleLyrics(
  songId: string,
  headers: Record<string, string>
): Promise<LyricLine[] | null> {
  const isLibrary = songId.startsWith("i.");
  const sf = getStorefront();

  if (isLibrary) {
    try {
      const res = await axios.get(
        `https://amp-api-edge.music.apple.com/v1/me/library/songs/${songId}/lyrics`,
        { headers }
      );
      const ttml = res.data?.data?.[0]?.attributes?.ttml ?? null;
      if (ttml) return parseTTML(ttml);
    } catch (_) {}

    const catalogId = await resolveCatalogId(songId, headers);
    if (catalogId) {
      try {
        const res = await axios.get(
          `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/songs/${catalogId}/lyrics`,
          { headers }
        );
        const ttml = res.data?.data?.[0]?.attributes?.ttml ?? null;
        if (ttml) return parseTTML(ttml);
      } catch (_) {}
    }
    return null;
  }

  const res = await axios.get(
    `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/songs/${songId}/lyrics`,
    { headers }
  );
  const ttml = res.data?.data?.[0]?.attributes?.ttml ?? null;
  return ttml ? parseTTML(ttml) : null;
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

/** lrclib.net — line-synced LRC lyrics, converted to our LyricLine shape. */
async function fetchLrclibLyrics(meta: SongMeta): Promise<LyricLine[] | null> {
  const params = new URLSearchParams({
    track_name: meta.title,
    artist_name: meta.artist,
  });
  if (meta.album) params.set("album_name", meta.album);
  if (meta.durationSec > 0) params.set("duration", String(meta.durationSec));

  const lrcHeaders = { "User-Agent": "AppleMusicTV (github.com/applemusicktv)" };

  // Prefer the exact /get match; fall back to /search's best hit.
  let synced: string | null = null;
  try {
    const res = await axios.get(`https://lrclib.net/api/get?${params.toString()}`, {
      headers: lrcHeaders,
    });
    synced = res.data?.syncedLyrics ?? null;
  } catch (_) {}

  if (!synced) {
    try {
      const q = new URLSearchParams({ track_name: meta.title, artist_name: meta.artist });
      const res = await axios.get(`https://lrclib.net/api/search?${q.toString()}`, {
        headers: lrcHeaders,
      });
      const hit = Array.isArray(res.data)
        ? res.data.find((r: any) => r?.syncedLyrics)
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
    const text = raw.replace(tagRe, "").trim();
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

function buildTree(tokens: Token[]): TagNode {
  const root: TagNode = { tag: "root", attrs: "", children: [] };
  const stack: TagNode[] = [root];
  for (const t of tokens) {
    const top = stack[stack.length - 1];
    if (t.type === "text") {
      top.children.push(t.text);
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
  return node.children
    .map((c) => (typeof c === "string" ? c : flattenText(c)))
    .join("")
    .trim();
}

function isBackgroundSpan(attrs: string): boolean {
  return /ttm:role="x-bg"|(?:^|\s)role="x-bg"/.test(attrs);
}

// Direct child <span> elements of a node, in document order.
function childSpans(node: TagNode): TagNode[] {
  return node.children.filter(
    (c): c is TagNode => typeof c !== "string" && c.tag === "span"
  );
}

function spanToWord(span: TagNode): LyricWord | null {
  const begin = attr(span.attrs, "begin");
  const text = flattenText(span);
  if (!begin || !text) return null;
  const end = attr(span.attrs, "end");
  return {
    startMs: parseTime(begin),
    endMs: end ? parseTime(end) : parseTime(begin) + 500,
    text,
  };
}

function parseTTML(ttml: string): LyricLine[] {
  const tree = buildTree(tokenize(ttml));
  const pNodes = findAll(tree, "p");
  const lines: LyricLine[] = [];

  for (const p of pNodes) {
    const beginAttr = attr(p.attrs, "begin");
    if (!beginAttr) continue;
    const startMs = parseTime(beginAttr);
    const endAttr = attr(p.attrs, "end");
    const endMs = endAttr ? parseTime(endAttr) : startMs + 5000;

    const words: LyricWord[] = [];
    let background: LyricBackground | null = null;

    for (const span of childSpans(p)) {
      if (isBackgroundSpan(span.attrs)) {
        const bgWords: LyricWord[] = [];
        for (const inner of childSpans(span)) {
          const w = spanToWord(inner);
          if (w) bgWords.push(w);
        }
        // Some TTML puts word-level spans directly, others put the text
        // straight on the x-bg span with no nested spans.
        if (bgWords.length === 0) {
          const w = spanToWord(span);
          if (w) bgWords.push(w);
        }
        if (bgWords.length > 0) {
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
        const w = spanToWord(span);
        if (w) words.push(w);
      }
    }

    const lineText = words.length > 0 ? words.map((w) => w.text).join(" ") : flattenText(p);
    if (!lineText) continue;

    lines.push({ startMs, endMs, text: lineText, words, background });
  }

  return lines;
}

function parseTime(t: string): number {
  const parts = t.split(":").map(Number);
  if (parts.length === 3) return Math.round((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000);
  if (parts.length === 2) return Math.round((parts[0] * 60 + parts[1]) * 1000);
  return Math.round(parts[0] * 1000);
}

export default lyrics;
