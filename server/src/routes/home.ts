import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken, getStorefront } from "../auth";
import { normaliseAlbum, normalisePlaylist } from "./search";

const home = new Hono();

const APPLE = "https://amp-api-edge.music.apple.com";

const appleHeaders = (mut: string) => ({
  Authorization: `Bearer ${getBearerToken()}`,
  "Media-User-Token": mut,
  "Music-User-Token": mut,
  Origin: "https://music.apple.com",
  "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
});

const bearerOnly = () => ({
  Authorization: `Bearer ${getBearerToken()}`,
  Origin: "https://music.apple.com",
  "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
});

const resolveMUT = (c: any): string => c.req.header("X-Music-User-Token") || getMUT();

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
    title: attr.name ?? attr.editorialNotes?.name ?? "Unknown",
    artistName: attr.artistName ?? attr.curatorName ?? "",
    artworkUrl: url,
    artworkBgColor: attr.artwork?.bgColor ?? null,
    releaseDate: attr.releaseDate ?? null,
    trackCount: attr.trackCount ?? 0,
    genreNames: attr.genreNames ?? [],
  };
}

home.get("/", async (c) => {
  const mut = resolveMUT(c);
  const sf = getStorefront() || "us";
  const h = mut ? appleHeaders(mut) : bearerOnly();
  const sections: Array<{ title: string; albums: any[] }> = [];

  // 1. Personalized recommendations — "Top Picks for You"
  //    Contains new releases, Made For You, artist stations, etc.
  if (mut) {
    try {
      const res = await axios.get(`${APPLE}/v1/me/recommendations`, {
        params: {
          limit: 20,
          "include[personal-recommendation]": "contents",
        },
        headers: h,
      });
      const topPicks: any[] = [];
      const genreSections: Map<string, any[]> = new Map();

      const recs = res.data?.data ?? [];
      for (const rec of recs) {
        const title: string = rec.attributes?.title?.stringForDisplay ?? "For You";
        const contents: any[] = rec.relationships?.contents?.data ?? [];
        const recType: string = rec.attributes?.resourceTypes?.[0] ?? "";
        if (recType === "stations" || title.toLowerCase().includes("station")) continue;
        const items: any[] = contents
          .filter((item: any) => item.type !== "stations")
          .map((item: any) => {
            if (item.type === "albums") { const a = normaliseAlbum(item); return a.artworkUrl ? a : null; }
            if (item.type === "playlists") { const p = normalisePlaylist(item); return p.artworkUrl ? { ...p, title: p.name, artistName: p.curatorName } : null; }
            return itemFromRaw(item);
          }).filter(Boolean);
        if (items.length === 0) continue;

        if (title.toLowerCase().includes("genre") || rec.attributes?.kind === "genre-mix") {
          genreSections.set(title, items);
        } else {
          topPicks.push(...items);
        }
      }

      if (topPicks.length > 0) sections.push({ title: "Top Picks for You", albums: topPicks });
      for (const [title, items] of genreSections) {
        sections.push({ title, albums: items });
      }
    } catch (e: any) {
      // Apple's rec endpoint intermittently 500s — retry below
      // retry once — Apple's rec endpoint intermittently 500s
      try {
        const retry = await axios.get(`${APPLE}/v1/me/recommendations`, {
          params: { limit: 20, "include[personal-recommendation]": "contents" },
          headers: h,
        });
        const items = (retry.data?.data ?? []).flatMap((rec: any) => {
          const contents: any[] = rec.relationships?.contents?.data ?? [];
          return contents.map((item: any) => {
            if (item.type === "albums") { const a = normaliseAlbum(item); return a.artworkUrl ? a : null; }
            if (item.type === "playlists") { const p = normalisePlaylist(item); return p.artworkUrl ? { ...p, title: p.name, artistName: p.curatorName } : null; }
            return itemFromRaw(item);
          }).filter(Boolean);
        });
        if (items.length > 0) sections.push({ title: "Top Picks for You", albums: items });
      } catch {}
    }
  }

  // 2. Recently Played
  if (mut) {
    try {
      const res = await axios.get(`${APPLE}/v1/me/recent/played`, {
        params: { limit: 20, types: "albums,playlists" },
        headers: h,
      });
      const items = (res.data?.data ?? []).map(itemFromRaw).filter(Boolean);
      if (items.length > 0) sections.push({ title: "Recently Played", albums: items });
    } catch (e: any) {
      console.warn("[home] recently-played failed:", e.message);
    }
  }

  // 3. New in catalog — top albums chart (new releases)
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      params: { types: "albums", limit: 20 },
      headers: h,
    });
    const chart = res.data?.results?.albums?.[0];
    if (chart) {
      const items = (chart.data ?? []).map((item: any) => {
        const a = normaliseAlbum(item);
        return a.artworkUrl ? a : null;
      }).filter(Boolean);
      if (items.length > 0) sections.push({ title: chart.name ?? "Top Albums", albums: items });
    }
  } catch (e: any) {
    console.warn("[home] charts/albums failed:", e.message);
  }

  // 4. Top Playlists
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      params: { types: "playlists", limit: 20 },
      headers: h,
    });
    const chart = res.data?.results?.playlists?.[0];
    if (chart) {
      const items = (chart.data ?? []).map((item: any) => {
        const p = normalisePlaylist(item);
        return p.artworkUrl ? { ...p, title: p.name, artistName: p.curatorName } : null;
      }).filter(Boolean);
      if (items.length > 0) sections.push({ title: chart.name ?? "Top Playlists", albums: items });
    }
  } catch (e: any) {
    console.warn("[home] charts/playlists failed:", e.message);
  }

  // 5. Recently Added from library (fallback / extra)
  if (mut) {
    try {
      const res = await axios.get(`${APPLE}/v1/me/library/recently-added`, {
        params: { limit: 20 },
        headers: h,
      });
      const items = (res.data?.data ?? []).map(itemFromRaw).filter(Boolean);
      if (items.length > 0) sections.push({ title: "Recently Added", albums: items });
    } catch (e: any) {
      console.warn("[home] recently-added failed:", e.message);
    }
  }



  return c.json({ sections });
});

export default home;
