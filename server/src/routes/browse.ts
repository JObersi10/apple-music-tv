import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken, getStorefront } from "../auth";
import { normaliseAlbum, normalisePlaylist } from "./search";

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
    title: attr.name ?? "Unknown",
    artistName: attr.artistName ?? attr.curatorName ?? "",
    artworkUrl: url,
    artworkBgColor: attr.artwork?.bgColor ?? null,
    releaseDate: attr.releaseDate ?? null,
    trackCount: attr.trackCount ?? 0,
    genreNames: attr.genreNames ?? [],
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

browse.get("/", async (c) => {
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  const sf = getStorefront() || "us";
  const h = hdrs(mut);
  const sections: Array<{ title: string; albums: any[] }> = [];

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
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/genres`, { headers: hdrs(), params: { limit: 50 } })
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
  const sections: Array<{ title: string; albums: any[] }> = []
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      headers: h, params: { genre: id, types: "playlists,albums", limit: 20 },
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
    if (playlists.length) sections.push({ title: "Top Playlists", albums: playlists })
    if (albums.length) sections.push({ title: "Top Albums", albums })
  } catch {}
  return c.json({ sections })
})

export default browse;
