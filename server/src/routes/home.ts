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

const bearerHeaders = () => ({
  Authorization: `Bearer ${getBearerToken()}`,
  Origin: "https://music.apple.com",
  "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
});

const resolveMUT = (c: any): string => c.req.header("X-Music-User-Token") || getMUT();

function artUrl(raw: string | undefined): string | null {
  return raw?.replace("{w}", "500").replace("{h}", "500").replace("{f}", "jpg") ?? null;
}

home.get("/", async (c) => {
  const mut = resolveMUT(c);
  const sf = getStorefront() || "us";
  const headers = mut ? appleHeaders(mut) : bearerHeaders();
  const sections: Array<{ title: string; albums: any[] }> = [];

  // 1. Personalized recommendations (Listen Now, Made For You, etc.)
  if (mut) {
    try {
      const res = await axios.get(`${APPLE}/v1/me/recommendations`, {
        params: {
          limit: 20,
          "include[personal-recommendation]": "contents",
          "extend[albums]": "editorialNotes",
        },
        headers,
      });
      for (const rec of res.data?.data ?? []) {
        const title: string = rec.attributes?.title?.stringForDisplay ?? "For You";
        const contents: any[] = rec.relationships?.contents?.data ?? [];
        const items: any[] = [];
        for (const item of contents) {
          if (!item.attributes) continue;
          if (item.type === "albums") {
            const a = normaliseAlbum(item);
            if (a.artworkUrl) items.push(a);
          } else if (item.type === "playlists") {
            const p = normalisePlaylist(item);
            if (p.artworkUrl) items.push({ ...p, title: p.name, artistName: p.curatorName });
          }
        }
        if (items.length > 0) sections.push({ title, albums: items });
      }
    } catch (e: any) {
      console.warn("[home] recommendations failed:", e.message);
    }
  }

  // 2. Editorial groupings — same source as Apple Music's Listen Now page
  // (new releases, genre playlists, featured, etc.)
  try {
    const res = await axios.get(`${APPLE}/v1/editorial/${sf}/groupings`, {
      params: { schema: "whats-new", limit: 10, "fields[editorial-elements]": "title,items" },
      headers,
    });
    for (const group of res.data?.data ?? []) {
      const title: string =
        group.attributes?.title?.stringForDisplay ??
        group.relationships?.contents?.attributes?.title?.stringForDisplay ??
        "New";
      const contents: any[] =
        group.relationships?.items?.data ??
        group.relationships?.contents?.data ?? [];
      const items: any[] = [];
      for (const item of contents) {
        const attr = item.attributes ?? {};
        const url = artUrl(attr.artwork?.url);
        if (!url) continue;
        items.push({
          id: item.id,
          title: attr.name ?? attr.title?.stringForDisplay ?? "Unknown",
          artistName: attr.artistName ?? attr.curatorName ?? "",
          artworkUrl: url,
          artworkBgColor: attr.artwork?.bgColor ?? null,
          releaseDate: attr.releaseDate ?? null,
          trackCount: attr.trackCount ?? 0,
          genreNames: attr.genreNames ?? [],
        });
      }
      if (items.length > 1) sections.push({ title, albums: items });
    }
  } catch (e: any) {
    console.warn("[home] editorial groupings failed:", e.message);
  }

  // 3. New releases — top albums chart
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      params: { types: "albums", limit: 20, genre: "34" }, // 34 = Pop (broad)
      headers,
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
    console.warn("[home] charts failed:", e.message);
  }

  // 4. Featured playlists — Apple curated
  try {
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, {
      params: { types: "playlists", limit: 20 },
      headers,
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
    console.warn("[home] playlist charts failed:", e.message);
  }

  // 5. Recently Added from library
  if (mut) {
    try {
      const res = await axios.get(`${APPLE}/v1/me/library/recently-added`, {
        params: { limit: 20 },
        headers,
      });
      const items = (res.data?.data ?? []).map((item: any) => {
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
      }).filter(Boolean);
      if (items.length > 0) sections.push({ title: "Recently Added", albums: items });
    } catch (e: any) {
      console.warn("[home] recently-added failed:", e.message);
    }
  }

  return c.json({ sections });
});

export default home;
