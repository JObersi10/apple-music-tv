/**
 * Spicy Lyrics source — the primary lyrics provider.
 *
 * Spicy Lyrics (api.spicylyrics.org) aggregates word-synced lyrics from Apple Music, Spotify and a
 * community upload pool, keyed by **Spotify** track id. We only have Apple metadata, so the lookup
 * mirrors what the Spicy Player Android app does: resolve the song to a Spotify id via Spotify's
 * ANONYMOUS web-player search (no user login — a token scraped from the public embed page), then ask
 * Spicy for that id's lyrics. The response is syllable/line timed; we map it straight to our
 * LyricLine model (no TTML round-trip).
 *
 * Because Spicy already serves Apple Music's own lyrics when it has them, this sits AHEAD of our
 * direct Apple TTML fetch — Apple + lrclib are the fallback for when Spicy is down or misses.
 *
 * Ported from TheX24/spicy-player (AnonymousSpotifyCatalogSearch / SpicyLyricsProvider).
 * Everything here rides Spotify's undocumented web contract — fail SOFT on any shape change so the
 * caller just falls through to Apple.
 */
import axios from "axios";

export interface SpicyMeta {
  title: string;
  artist: string;
  album: string;
  durationSec: number;
}

interface LyricWord { startMs: number; endMs: number; text: string }
interface LyricBackground { startMs: number; endMs: number; text: string; words: LyricWord[] }
interface LyricLine { startMs: number; endMs: number; text: string; words: LyricWord[]; background: LyricBackground | null }

const KEY = () => (process.env.SPICY_LYRICS_KEY ?? "").trim();
export const spicyEnabled = () => KEY().length > 0;

// ── Anonymous Spotify session (shared, cached) ───────────────────────────────
const BOOTSTRAP_URL = "https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC";
const PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query";
// Spotify persisted-query hashes are NOT stable API. A "PersistedQueryNotFound" response means this
// must be refreshed (from the web player's network tab). We fail soft when that happens.
const SEARCH_SHA256 = "eff59fa0a3d026b88b56fddbcf4bdfa16a186b8175a5c1a358c072e053c2e5b0";
const EXPIRY_SKEW_MS = 60_000;

let cachedToken: { token: string; expiresAtMs: number } | null = null;

async function spotifyToken(force = false): Promise<string> {
  const now = Date.now();
  if (!force && cachedToken && cachedToken.expiresAtMs - EXPIRY_SKEW_MS > now) return cachedToken.token;
  const res = await axios.get(BOOTSTRAP_URL, {
    timeout: 10000,
    headers: { "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15" },
  });
  const html: string = res.data ?? "";
  const m = html.match(/<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)<\/script>/is);
  if (!m) throw new Error("Spotify embed page has no __NEXT_DATA__");
  const session = JSON.parse(m[1])?.props?.pageProps?.state?.settings?.session;
  const token = session?.accessToken;
  const exp = session?.accessTokenExpirationTimestampMs;
  if (!token) throw new Error("Spotify embed session omitted its access token");
  cachedToken = { token, expiresAtMs: typeof exp === "number" ? exp : now + 30 * 60_000 };
  return token;
}

interface SpotifyCandidate { id: string; title: string; artists: string[]; album: string; durationMs: number }

async function spotifySearch(query: string): Promise<SpotifyCandidate[]> {
  const run = async (token: string) => {
    const variables = JSON.stringify({
      searchTerm: query, offset: 0, limit: 20, numberOfTopResults: 5,
      includeAudiobooks: false, includePreReleases: true, includeAlbumPreReleases: false,
      includeAuthors: false, includeEpisodeContentRatingsV2: false,
    });
    const extensions = JSON.stringify({ persistedQuery: { version: 1, sha256Hash: SEARCH_SHA256 } });
    return axios.get(PATHFINDER_URL, {
      timeout: 12000,
      params: { operationName: "searchDesktop", variables, extensions },
      headers: { Authorization: `Bearer ${token}`, "app-platform": "WebPlayer" },
      validateStatus: () => true,
    });
  };

  let token = await spotifyToken();
  let res = await run(token);
  if (res.status === 401) { token = await spotifyToken(true); res = await run(token); }
  if (res.status !== 200) throw new Error(`Spotify search HTTP ${res.status}`);

  const root = res.data;
  if (Array.isArray(root?.errors) && root.errors.some((e: any) => e?.message === "PersistedQueryNotFound")) {
    throw new Error("Spotify rotated the persisted search query");
  }
  const items = root?.data?.searchV2?.tracksV2?.items;
  if (!Array.isArray(items)) throw new Error("Spotify search response shape changed");

  const out: SpotifyCandidate[] = [];
  for (const it of items) {
    const d = it?.item?.data;
    if (!d) continue;
    const id = d.id ?? (typeof d.uri === "string" ? d.uri.split(":").pop() : null);
    const title = d.name;
    const durationMs = d.duration?.totalMilliseconds;
    if (!id || !title || typeof durationMs !== "number") continue;
    const artists = (d.artists?.items ?? []).map((a: any) => a?.profile?.name).filter(Boolean);
    if (artists.length === 0) continue;
    out.push({ id, title, artists, album: d.albumOfTrack?.name ?? "", durationMs });
  }
  return out;
}

// ── Matching ─────────────────────────────────────────────────────────────────
const norm = (s: string) => (s ?? "").toLowerCase().replace(/[^a-z0-9]/g, "");

/** Best Spotify ids for this track, closest duration first (up to 3 to try against Spicy). */
async function resolveSpotifyIds(meta: SpicyMeta): Promise<string[]> {
  const wantTitle = norm(meta.title);
  const wantArtist = norm(meta.artist);
  const queries = [`${meta.title} ${meta.artist}`.trim()];
  if (meta.album) queries.push(`${meta.title} ${meta.album}`.trim());

  const seen = new Map<string, SpotifyCandidate>();
  for (const q of queries) {
    let cands: SpotifyCandidate[] = [];
    try { cands = await spotifySearch(q); } catch (e: any) {
      if (q === queries[0]) throw e; // first query's failure is the real one
      continue;
    }
    for (const c of cands) if (!seen.has(c.id)) seen.set(c.id, c);
    // The album query is only worth running if the title/artist one didn't land a solid match.
    if (q === queries[0] && [...seen.values()].some((c) => isMatch(meta, c))) break;
  }

  return [...seen.values()]
    .filter((c) => isMatch(meta, c))
    .sort((a, b) => durationDelta(meta, a) - durationDelta(meta, b))
    .slice(0, 3)
    .map((c) => c.id)
    .filter((id, i, arr) => arr.indexOf(id) === i);

  function isMatch(m: SpicyMeta, c: SpotifyCandidate): boolean {
    const t = norm(c.title);
    if (!(t === wantTitle || t.includes(wantTitle) || wantTitle.includes(t))) return false;
    const artistOk = c.artists.some((a) => {
      const n = norm(a);
      return n && (n.includes(wantArtist) || wantArtist.includes(n));
    });
    if (!artistOk) return false;
    // Duration within 6s when both known — guards remixes/live/sped-up versions.
    if (m.durationSec > 0 && durationDelta(m, c) > 6) return false;
    return true;
  }
}

function durationDelta(m: SpicyMeta, c: SpotifyCandidate): number {
  if (m.durationSec <= 0) return 0;
  return Math.abs(c.durationMs / 1000 - m.durationSec);
}

// ── Spicy fetch + parse ──────────────────────────────────────────────────────
interface SpicyHit { lines: LyricLine[]; wordSynced: boolean; songwriters: string[] }

async function fetchSpicyId(spotifyId: string): Promise<SpicyHit | null> {
  const res = await axios.get(`https://api.spicylyrics.org/v1/lyrics/${encodeURIComponent(spotifyId)}`, {
    timeout: 15000,
    headers: { Authorization: `Bearer ${KEY()}` },
    validateStatus: () => true,
  });
  if (res.status === 404) return null;
  if (res.status !== 200) throw new Error(`Spicy HTTP ${res.status}`);
  return parseSpicy(res.data);
}

/** Merge Spicy syllables into whole words. `IsPartOfWord` on a syllable means it joins the NEXT one. */
function mergeSpicy(syllables: any[] | undefined): LyricWord[] {
  const words: LyricWord[] = [];
  let cur: LyricWord | null = null;
  for (const s of syllables ?? []) {
    const text = s?.Text;
    const start = s?.StartTime;
    const end = s?.EndTime;
    if (typeof text !== "string" || typeof start !== "number" || typeof end !== "number") continue;
    if (!cur) cur = { startMs: Math.round(start * 1000), endMs: Math.round(end * 1000), text };
    else { cur.text += text; cur.endMs = Math.round(end * 1000); }
    if (s?.IsPartOfWord !== true) { cur.text = cur.text.trim(); if (cur.text) words.push(cur); cur = null; }
  }
  if (cur) { cur.text = cur.text.trim(); if (cur.text) words.push(cur); }
  return words;
}

function parseSpicy(root: any): SpicyHit | null {
  if (root?.Status !== 200) return null;
  const body = root?.Body;
  if (!body) return null;
  const songwriters = (Array.isArray(body.SongWriters) ? body.SongWriters : [])
    .filter((s: any) => typeof s === "string" && s.trim())
    .map((s: string) => s.trim());
  const content = body.Content;
  const lines: LyricLine[] = [];
  let wordSynced = false;

  if (Array.isArray(content)) {
    for (const item of content) {
      if (typeof item?.Type === "string" && item.Type.toLowerCase() === "interlude") continue;
      const lead = item?.Lead;
      if (!lead) {
        // Line-type: text + timing on the item itself.
        const text = typeof item?.Text === "string" ? item.Text.trim() : "";
        const start = item?.StartTime, end = item?.EndTime;
        if (!text || typeof start !== "number") continue;
        lines.push({ startMs: Math.round(start * 1000), endMs: Math.round((end ?? start) * 1000), text, words: [], background: null });
        continue;
      }
      wordSynced = true;
      const words = mergeSpicy(lead.Syllables);
      // Flatten every background group into one background phrase (our model carries one per line).
      const bgWords: LyricWord[] = [];
      for (const g of (Array.isArray(item?.Background) ? item.Background : [])) bgWords.push(...mergeSpicy(g?.Syllables));
      if (words.length === 0 && bgWords.length === 0) continue;
      const all = [...words, ...bgWords];
      const startMs = typeof lead.StartTime === "number" ? Math.round(lead.StartTime * 1000) : Math.min(...all.map((w) => w.startMs));
      const endMs = typeof lead.EndTime === "number" ? Math.round(lead.EndTime * 1000) : Math.max(...all.map((w) => w.endMs));
      const background: LyricBackground | null = bgWords.length > 0 ? {
        startMs: Math.min(...bgWords.map((w) => w.startMs)),
        endMs: Math.max(...bgWords.map((w) => w.endMs)),
        text: bgWords.map((w) => w.text).join(" "),
        words: bgWords,
      } : null;
      lines.push({ startMs, endMs, text: words.map((w) => w.text).join(" ") || background?.text || "", words, background });
    }
  }

  // Static (unsynced) response: only Lines[].Text, no timing.
  if (lines.length === 0 && Array.isArray(body.Lines)) {
    for (const l of body.Lines) {
      const text = typeof l?.Text === "string" ? l.Text.trim() : "";
      if (text) lines.push({ startMs: -1, endMs: -1, text, words: [], background: null });
    }
  }

  return lines.length > 0 ? { lines, wordSynced, songwriters } : null;
}

/**
 * Resolve [meta] to a Spotify id and fetch its Spicy lyrics. Tries the best few candidate ids (a
 * community upload lives on one specific id), preferring a word-synced hit. Returns null on no key,
 * no match, or any soft failure — the caller then falls through to Apple.
 */
export async function fetchSpicyLyrics(
  meta: SpicyMeta,
): Promise<{ lines: LyricLine[]; synced: boolean; songwriters: string[] } | null> {
  if (!spicyEnabled()) return null;
  if (!meta.title || !meta.artist) return null;

  const ids = await resolveSpotifyIds(meta);
  if (ids.length === 0) return null;

  let plainFallback: SpicyHit | null = null;
  for (const id of ids) {
    let hit: SpicyHit | null = null;
    try { hit = await fetchSpicyId(id); } catch (_) { continue; }
    if (!hit) continue;
    const timed = hit.lines.some((l) => l.startMs >= 0);
    if (hit.wordSynced || timed) return { lines: hit.lines, synced: true, songwriters: hit.songwriters };
    plainFallback = plainFallback ?? hit; // keep plain, keep looking for a timed id
  }
  return plainFallback ? { lines: plainFallback.lines, synced: false, songwriters: plainFallback.songwriters } : null;
}
