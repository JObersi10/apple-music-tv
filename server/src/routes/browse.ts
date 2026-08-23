import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken, getStorefront } from "../auth";
import { normaliseAlbum, normalisePlaylist, normaliseSong } from "./search";

const browse = new Hono();
const APPLE = "https://amp-api-edge.music.apple.com";

const hdrs = (mut?: string) => ({
  Authorization: `Bearer ${getBearerToken()}`,
  ...(mut ? { "Media-User-Token": mut, "Music-User-Token": mut } : {}),
  Origin: "https://music.apple.com",
  "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
});

function artUrl(raw: string | undefined, size = 500): string | null {
  if (!raw) return null;
  return raw.replace("{w}", String(size)).replace("{h}", String(size)).replace("{f}", "jpg");
}

function itemFromRaw(item: any): any | null {
  const attr = item.attributes ?? {};
  const url = artUrl(attr.artwork?.url);
  if (!url) return null;
  return {
    id: item.id,
    type: item.type ?? "albums",
    title: attr.name ?? "Unknown",
    artistName: attr.artistName ?? attr.curatorName ?? "",
    artworkUrl: url,
    artworkBgColor: attr.artwork?.bgColor ?? null,
    releaseDate: attr.releaseDate ?? null,
    trackCount: attr.trackCount ?? 0,
    genreNames: attr.genreNames ?? [],
    durationMs: attr.durationInMillis ?? 0,
    previewUrl: attr.previews?.[0]?.url ?? null,
  };
}

async function fetchPlaylist(sf: string, id: string, mut?: string): Promise<any | null> {
  try {
    const url = id.startsWith("pl.")
      ? `${APPLE}/v1/catalog/${sf}/playlists/${id}`
      : `${APPLE}/v1/me/library/playlists/${id}`;
    const res = await axios.get(url, { headers: hdrs(mut) });
    const item = res.data?.data?.[0];
    if (!item) return null;
    return itemFromRaw(item);
  } catch { return null; }
}

// A "multi-room" is an Apple editorial category page (e.g. The Sounds of Formula 1):
// a title, a hero description, and several titled shelves of playlists/albums.
// The web app fetches it from /v1/editorial/{sf}/multirooms/{id}; children are
// "editorial-elements" — kind 404 = hero (title+description), 345 = a content shelf,
// 405 = an external link (skipped).
browse.get("/multiroom/:id", async (c) => {
  const id = c.req.param("id");
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  const sf = getStorefront() || "us";
  try {
    const res = await axios.get(`${APPLE}/v1/editorial/${sf}/multirooms/${id}`, {
      headers: hdrs(mut),
      params: { l: "en-US", platform: "web", extend: "editorialArtwork", "include[albums]": "artists", "art[url]": "f" },
    });
    const room = res.data?.data?.[0];
    if (!room) return c.json({ error: "not found" }, 404);
    const kids: any[] = room.relationships?.children?.data ?? [];

    let description: string | null = null;
    // Key must be `albums` — that's what the Android HomeSection model reads.
    const sections: Array<{ title: string; albums: any[] }> = [];
    for (const k of kids) {
      const attr = k.attributes ?? {};
      const kind = attr.editorialElementKind;
      if (kind === "404" && attr.description) {
        // Hero — may be a plain string or {short,standard}.
        description = typeof attr.description === "string"
          ? attr.description
          : (attr.description?.standard ?? attr.description?.short ?? null);
      } else if (kind === "345") {
        const items = (k.relationships?.contents?.data ?? []).map(itemFromRaw).filter(Boolean);
        if (items.length && attr.title) sections.push({ title: attr.title, albums: items });
      }
    }
    // Wide editorial hero for the page header.
    const ea = room.attributes?.editorialArtwork ?? {};
    const artworkUrl = artUrl((ea.superHeroWide ?? ea.subscriptionHero ?? ea.storeFlowcase ?? ea.subscriptionCover)?.url, 1600);
    return c.json({ id, title: room.attributes?.title ?? "", description, artworkUrl, sections });
  } catch (e: any) {
    return c.json({ error: e?.response?.data?.errors?.[0]?.detail ?? e?.message ?? "failed" }, 502);
  }
});

// A curator page — the normal, searchable editorial entity behind things like
// "Formula 1" or "Tomorrowland". Two flavours share the same shape: `curators`
// (label/brand curators) and `apple-curators` (Apple's own). Pass ?apple=1 for the
// latter. We flatten the curator's playlists into a single shelf; same response shape
// as /multiroom so the Category screen renders it unchanged.
browse.get("/curator/:id", async (c) => {
  const id = c.req.param("id");
  const isApple = c.req.query("apple") === "1";
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  const sf = getStorefront() || "us";
  const kind = isApple ? "apple-curators" : "curators";
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/${kind}/${id}`, {
      headers: hdrs(mut),
      params: { include: "grouping,playlists", extend: "editorialArtwork", "limit[curators:playlists]": 10, l: "en-US", platform: "web" },
    });
    const cur = res.data?.data?.[0];
    if (!cur) return c.json({ error: "not found" }, 404);
    const attr = cur.attributes ?? {};
    const description = typeof attr.description === "string"
      ? attr.description
      : (attr.description?.standard ?? attr.description?.short ?? null);

    // Rich curators (e.g. Tomorrowland Live Sets) hang their content off an editorial
    // "grouping" — a set of named shelves (2026, 2025, …). Expand that when present;
    // otherwise fall back to the curator's flat playlists relationship (e.g. Formula 1).
    let sections: Array<{ title: string; albums: any[] }> = [];
    const groupingId = cur.relationships?.grouping?.data?.[0]?.id;
    if (groupingId) {
      sections = await groupingSections(sf, groupingId, mut);
    }
    if (!sections.length) {
      const items = (cur.relationships?.playlists?.data ?? []).map(itemFromRaw).filter(Boolean);
      // Key must be `albums` — that's what the Android HomeSection model reads.
      if (items.length) sections = [{ title: "Playlists", albums: items }];
    }
    const ea = attr.editorialArtwork ?? {};
    const artworkUrl = artUrl(
      (ea.superHeroWide ?? ea.subscriptionHero ?? ea.storeFlowcase)?.url ?? attr.artwork?.url, 1600)
    return c.json({ id, title: attr.name ?? "", description, artworkUrl, sections });
  } catch (e: any) {
    return c.json({ error: e?.response?.data?.errors?.[0]?.detail ?? e?.message ?? "failed" }, 502);
  }
});

// The genre/mood/decade tile grid, like Apple's "Browse by Genre". Three sibling
// editorial rooms, each a list of apple-curators with editorial artwork. Tapping a tile
// opens that apple-curator as a category page. Rewind/Replay year-in-review curators are
// filtered out. Rooms are stable ids pulled from music.apple.com's browse feed.
const CATEGORY_ROOMS: Array<{ title: string; room: string }> = [
  { title: "Genres",            room: "6456176470" },
  { title: "Moods & Activities", room: "6456176472" },
  { title: "Decades",           room: "6456176471" },
]
browse.get("/categories", async (c) => {
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  const sf = getStorefront() || "us";
  const drop = /rewind|replay|year in|wrapped/i;
  try {
    const sections = await Promise.all(CATEGORY_ROOMS.map(async ({ title, room }) => {
      const res = await axios.get(`${APPLE}/v1/editorial/${sf}/rooms/${room}`, {
        headers: hdrs(mut),
        params: { include: "contents", extend: "editorialArtwork", l: "en-US", platform: "web", "limit[contents]": 200 },
      });
      const items = (res.data?.data?.[0]?.relationships?.contents?.data ?? [])
        .filter((it: any) => it.type === "apple-curators" || it.type === "curators")
        .filter((it: any) => !drop.test(it.attributes?.name ?? ""))
        .map((it: any) => {
          const a = it.attributes ?? {};
          const ea = a.editorialArtwork ?? {};
          return {
            id: it.id,
            // Strip the "Apple Music " brand prefix so tiles read "Acoustic", "Chill", etc.
            name: (a.name ?? "Unknown").replace(/^Apple Music (?=\S)/, "").replace(/^Apple (?=\S)/, ""),
            kind: it.type === "apple-curators" ? "apple-curator" : "curator",
            isApple: it.type === "apple-curators",
            artworkUrl: artUrl((ea.subscriptionCover ?? ea.brandLogo ?? a.artwork)?.url, 600),
          };
        });
      return { title, items };
    }));
    return c.json({ sections: sections.filter((s) => s.items.length) });
  } catch (e: any) {
    return c.json({ error: e?.response?.data?.errors?.[0]?.detail ?? e?.message ?? "failed", sections: [] }, 200);
  }
});

// An editorial grouping → its default tab's children are named content shelves.
// Shelf title is `attributes.name` (NOT `title` — that field is empty here).
async function groupingSections(sf: string, groupingId: string, mut?: string): Promise<Array<{ title: string; albums: any[] }>> {
  try {
    const res = await axios.get(`${APPLE}/v1/editorial/${sf}/groupings/${groupingId}`, {
      headers: hdrs(mut),
      params: { include: "tabs", extend: "editorialArtwork", l: "en-US", platform: "web" },
    });
    const tab = res.data?.data?.[0]?.relationships?.tabs?.data?.[0];
    const kids: any[] = tab?.relationships?.children?.data ?? [];
    const out: Array<{ title: string; albums: any[] }> = [];
    for (const k of kids) {
      const a = k.attributes ?? {};
      const kind = a.editorialElementKind;
      if (kind !== "326" && kind !== "345") continue;
      const title = a.name ?? a.title;
      const albums = (k.relationships?.contents?.data ?? []).map(itemFromRaw).filter(Boolean);
      if (title && albums.length) out.push({ title, albums });
    }
    return out;
  } catch { return []; }
}

browse.get("/", async (c) => {
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  const sf = getStorefront() || "us";
  const h = hdrs(mut);
  const sections: Array<{ title: string; albums?: any[]; videos?: any[] }> = [];

  // 1. Charts: trending songs
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      params: { types: "songs", limit: 20 },
      headers: h,
    });
    const chart = res.data?.results?.songs?.[0];
    if (chart?.data?.length) {
      sections.push({
        title: chart.name ?? "Trending Songs",
        albums: chart.data.map(itemFromRaw).filter(Boolean),
      });
    }
  } catch {}

  // 2. Daily Top 100 + other chart playlists
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      params: { types: "playlists", limit: 30 },
      headers: h,
    });
    const chart = res.data?.results?.playlists?.[0];
    if (chart?.data?.length) {
      // Separate "Daily Top 100" from other chart playlists
      const daily: any[] = [];
      const other: any[] = [];
      for (const item of chart.data) {
        const name: string = item.attributes?.name ?? "";
        const p = normalisePlaylist(item);
        if (!p.artworkUrl) continue;
        const obj = { ...p, artworkUrl: artUrl(p.artworkUrl) ?? p.artworkUrl, title: p.name, artistName: p.curatorName };
        if (name.toLowerCase().includes("daily top 100") || name.toLowerCase().includes("top 100")) {
          daily.push(obj);
        } else {
          other.push(obj);
        }
      }
      if (daily.length > 0)  sections.push({ title: "Daily Top 100", albums: daily });
      if (other.length > 0)  sections.push({ title: chart.name ?? "Top Playlists", albums: other });
    }
  } catch {}

  // 3. New album releases
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      params: { types: "albums", limit: 20 },
      headers: h,
    });
    const chart = res.data?.results?.albums?.[0];
    if (chart?.data?.length) {
      sections.push({
        title: "New Releases",
        albums: chart.data.map((item: any) => { const a = normaliseAlbum(item); return a.artworkUrl ? a : null; }).filter(Boolean),
      });
    }
  } catch {}

  // 3b. Music Videos — the charted music-video shelf (routes into the fullscreen video player).
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      params: { types: "music-videos", limit: 24 },
      headers: h,
    });
    const chart = res.data?.results?.["music-videos"]?.[0];
    const videos = (chart?.data ?? []).map(normaliseSong).filter((v: any) => v.artworkUrl);
    if (videos.length) sections.push({ title: chart?.name || "Music Videos", videos });
  } catch {}

  // 4. Editorial playlists by category keyword search
  const editorialQueries: Array<{ title: string; term: string }> = [
    { title: "Apple Music Live",          term: "apple music live concert" },
    { title: "Artists Take Over",         term: "artists take over apple music" },
    { title: "In Studio Performances",    term: "in studio performance apple music" },
    { title: "Best Club DJ Mixes",        term: "club dj mix apple music" },
    { title: "Updated Playlists",         term: "apple music editors playlist updated" },
  ];

  // Try the editorial sections endpoint first (richer results)
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/groupings`, {
      params: { ids: "music-browse", include: "contents", limit: 8 },
      headers: h,
    });
    const grouping = res.data?.data?.[0];
    const contents: any[] = grouping?.relationships?.contents?.data ?? [];
    if (contents.length > 0) {
      const items = contents.map((item: any) => {
        if (item.type === "playlists") {
          const p = normalisePlaylist(item);
          const fixedUrl = artUrl(p.artworkUrl) ?? p.artworkUrl;
          return fixedUrl ? { ...p, artworkUrl: fixedUrl, title: p.name, artistName: p.curatorName } : null;
        }
        return itemFromRaw(item);
      }).filter(Boolean);
      if (items.length > 0) sections.push({ title: "Featured on Apple Music", albums: items });
    }
  } catch {}

  // Fallback: search for each editorial category
  for (const { title, term } of editorialQueries) {
    try {
      const res = await axios.get(`${APPLE}/v1/catalog/${sf}/search`, {
        params: { term, types: "playlists", limit: 10 },
        headers: h,
      });
      const playlists: any[] = res.data?.results?.playlists?.data ?? [];
      const items = playlists
        .filter((p: any) => {
          const name: string = (p.attributes?.name ?? "").toLowerCase();
          const curator: string = (p.attributes?.curatorName ?? "").toLowerCase();
          return curator.includes("apple music") || name.includes("apple music");
        })
        .map((p: any) => {
          const pl = normalisePlaylist(p);
          const fixedUrl = artUrl(pl.artworkUrl) ?? pl.artworkUrl;
          return fixedUrl ? { ...pl, artworkUrl: fixedUrl, title: pl.name, artistName: pl.curatorName } : null;
        })
        .filter(Boolean)
        .slice(0, 8);
      if (items.length > 0) sections.push({ title, albums: items });
    } catch {}
  }

  return c.json({ sections });
});

browse.get("/genres", async (c) => {
  const sf = getStorefront() || "us"
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/genres`, { headers: hdrs(), params: { limit: 200 } })
    const genres = (res.data?.data ?? [])
      .map((g: any) => ({ id: g.id, name: g.attributes?.name ?? "" }))
      .filter((g: any) => g.name && g.id !== "34") // 34 = Podcasts
    return c.json({ genres })
  } catch { return c.json({ genres: [] }) }
})

browse.get("/genres/:id", async (c) => {
  const sf = getStorefront() || "us"
  const id = c.req.param("id")
  const h = hdrs()
  const sections: Array<{ title: string; albums?: any[]; videos?: any[] }> = []
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      headers: h, params: { genre: id, types: "playlists,albums,songs,music-videos", limit: 20 },
    })
    const playlists = (res.data?.results?.playlists?.[0]?.data ?? []).map((p: any) => {
      const pl = normalisePlaylist(p)
      const url = artUrl(pl.artworkUrl) ?? pl.artworkUrl
      return url ? { ...pl, artworkUrl: url, title: pl.name, artistName: pl.curatorName } : null
    }).filter(Boolean)
    const albums = (res.data?.results?.albums?.[0]?.data ?? []).map((a: any) => {
      const al = normaliseAlbum(a)
      return al.artworkUrl ? al : null
    }).filter(Boolean)
    // Music videos: type "music-video" is what the app routes into the video player.
    const videos = (res.data?.results?.["music-videos"]?.[0]?.data ?? []).map(normaliseSong).filter((v: any) => v.artworkUrl)
    if (videos.length) sections.push({ title: "Top Music Videos", videos })
    if (playlists.length) sections.push({ title: "Top Playlists", albums: playlists })
    if (albums.length) sections.push({ title: "Top Albums", albums })
  } catch {}
  return c.json({ sections })
})

export default browse;
