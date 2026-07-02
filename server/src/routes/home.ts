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

const resolveMUT = (c: any): string => c.req.header("X-Music-User-Token") || getMUT();

home.get("/", async (c) => {
  const mut = resolveMUT(c);
  if (!mut) return c.json({ sections: [] });

  const headers = appleHeaders(mut);
  const sections: Array<{ title: string; albums: any[] }> = [];

  // 1. Personalized recommendations (Listen Now, Made For You, etc.)
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

  // 2. Recently Added from library
  try {
    const res = await axios.get(`${APPLE}/v1/me/library/recently-added`, {
      params: { limit: 20 },
      headers,
    });
    const items = (res.data?.data ?? [])
      .map((item: any) => {
        const attr = item.attributes ?? {};
        const rawUrl: string | undefined = attr.artwork?.url;
        const artworkUrl = rawUrl
          ?.replace("{w}", "500").replace("{h}", "500").replace("{f}", "jpg") ?? null;
        if (!artworkUrl) return null;
        return {
          id: item.id,
          title: attr.name ?? "Unknown",
          artistName: attr.artistName ?? attr.curatorName ?? "",
          artworkUrl,
          artworkBgColor: attr.artwork?.bgColor ?? null,
          releaseDate: attr.releaseDate ?? null,
          trackCount: attr.trackCount ?? 0,
          genreNames: attr.genreNames ?? [],
        };
      })
      .filter(Boolean);
    if (items.length > 0) sections.push({ title: "Recently Added", albums: items });
  } catch (e: any) {
    console.warn("[home] recently-added failed:", e.message);
  }

  return c.json({ sections });
});

export default home;
