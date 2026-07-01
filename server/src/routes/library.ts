import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken } from "../auth";
import { normaliseAlbum, normaliseArtist, normaliseSong, normalisePlaylist } from "./search";

function normaliseLibraryPlaylist(p: any) {
  const attr = p.attributes ?? {};
  // Artwork: prefer direct, then catalog relationship, then catalog attributes
  const cat = p.relationships?.catalog?.data?.[0];
  const artworkUrl =
    attr.artwork?.url?.replace("{w}", "500").replace("{h}", "500").replace("{f}", "jpg")
    ?? cat?.attributes?.artwork?.url?.replace("{w}", "500").replace("{h}", "500").replace("{f}", "jpg")
    ?? null;
  return {
    id:          p.id,
    name:        attr.name ?? cat?.attributes?.name ?? "Unknown",
    curatorName: attr.curatorName ?? cat?.attributes?.curatorName ?? "",
    artworkUrl,
    artworkBgColor: attr.artwork?.bgColor ?? cat?.attributes?.artwork?.bgColor ?? null,
    description: attr.description?.short ?? cat?.attributes?.description?.short ?? null,
    playlistType: attr.playlistType ?? null,
  };
}

const library = new Hono();

const appleHeaders = (mut: string) => ({
  Authorization: `Bearer ${getBearerToken()}`,
  "Media-User-Token": mut,
  Origin: "https://music.apple.com",
  "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
});

// Prefer the per-request header sent by the Android app, fall back to stored MUT
const resolveMUT = (c: any): string => c.req.header("X-Music-User-Token") || getMUT();

const guard = (c: any): string | null => {
  const mut = resolveMUT(c);
  if (!mut) { c.json({ error: "Music-User-Token not set" }, 401); return null; }
  return mut;
};

library.get("/songs", async (c) => {
  const mut = guard(c); if (!mut) return;
  try {
    const all: any[] = [];
    let url: string | null = "https://amp-api-edge.music.apple.com/v1/me/library/songs";
    while (url && all.length < 2000) {
      const res = await axios.get(url, {
        params: all.length === 0 ? { limit: 100, include: "catalog" } : undefined,
        headers: appleHeaders(mut),
      });
      all.push(...(res.data?.data ?? []));
      url = res.data?.next ? `https://amp-api-edge.music.apple.com${res.data.next}` : null;
    }
    return c.json({ songs: all.map((s: any) => normaliseSong(s)) });
  } catch (e: any) { return c.json({ error: e.message }, 500); }
});

library.get("/albums", async (c) => {
  const mut = guard(c); if (!mut) return;
  try {
    const all: any[] = [];
    let url: string | null = "https://amp-api-edge.music.apple.com/v1/me/library/albums";
    while (url && all.length < 2000) {
      const res = await axios.get(url, {
        params: all.length === 0 ? { limit: 100, include: "catalog" } : undefined,
        headers: appleHeaders(mut),
      });
      all.push(...(res.data?.data ?? []));
      url = res.data?.next ? `https://amp-api-edge.music.apple.com${res.data.next}` : null;
    }
    return c.json({ albums: all.map((a: any) => normaliseAlbum(a)) });
  } catch (e: any) { return c.json({ error: e.message }, 500); }
});

library.get("/playlists", async (c) => {
  const mut = guard(c); if (!mut) return;
  const limit = Number(c.req.query("limit") ?? "100");
  try {
    // Paginate to get all playlists (Apple caps at 100 per page)
    const all: any[] = [];
    let url: string | null = "https://amp-api-edge.music.apple.com/v1/me/library/playlists";
    while (url && all.length < 500) {
      const res = await axios.get(url, {
        params: all.length === 0 ? { limit: Math.min(limit, 100), include: "catalog" } : undefined,
        headers: appleHeaders(mut),
      });
      all.push(...(res.data?.data ?? []));
      url = res.data?.next ? `https://amp-api-edge.music.apple.com${res.data.next}` : null;
    }
    const playlists = all.map((p: any) => normaliseLibraryPlaylist(p));
    return c.json({ playlists });
  } catch (e: any) { return c.json({ error: e.message }, 500); }
});

library.get("/artists", async (c) => {
  const mut = guard(c); if (!mut) return;
  try {
    const all: any[] = [];
    let url: string | null = "https://amp-api-edge.music.apple.com/v1/me/library/artists";
    while (url && all.length < 2000) {
      const res = await axios.get(url, {
        params: all.length === 0 ? { limit: 100 } : undefined,
        headers: appleHeaders(mut),
      });
      all.push(...(res.data?.data ?? []));
      url = res.data?.next ? `https://amp-api-edge.music.apple.com${res.data.next}` : null;
    }
    return c.json({ artists: all.map((a: any) => normaliseArtist(a)) });
  } catch (e: any) { return c.json({ error: e.message }, 500); }
});

library.get("/recent", async (c) => {
  const mut = guard(c); if (!mut) return;
  try {
    const res = await axios.get(
      "https://amp-api-edge.music.apple.com/v1/me/recent/played",
      { params: { limit: 20 }, headers: appleHeaders(mut) }
    );
    const items = (res.data?.data ?? []).map((item: any) => {
      if (item.type === "albums") return { type: "album", ...normaliseAlbum(item) };
      if (item.type === "playlists") return { type: "playlist", ...normalisePlaylist(item) };
      return { type: "song", ...normaliseSong(item) };
    });
    return c.json({ items });
  } catch (e: any) { return c.json({ error: e.message }, 500); }
});

library.get("/playlists/:id/tracks", async (c) => {
  const mut = guard(c); if (!mut) return;
  const id = c.req.param("id");
  try {
    const { normaliseSong } = await import("./search");
    const { getStorefront } = await import("../auth");
    const songs: any[] = [];

    if (id.startsWith("pl.")) {
      // Catalog/editorial/shared/generated playlists — use catalog endpoint
      const sf = getStorefront() || "us";
      let url: string | null = `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/playlists/${id}/tracks`;
      while (url && songs.length < 2000) {
        const res = await axios.get(url, { params: songs.length === 0 ? { limit: 100 } : undefined, headers: appleHeaders(mut) });
        songs.push(...(res.data?.data ?? []).map((s: any) => normaliseSong(s)));
        url = res.data?.next ? `https://amp-api-edge.music.apple.com${res.data.next}` : null;
      }
    } else {
      // User library playlists — use library endpoint
      let url: string | null = `https://amp-api-edge.music.apple.com/v1/me/library/playlists/${id}/tracks`;
      while (url && songs.length < 2000) {
        const res = await axios.get(url, { params: songs.length === 0 ? { limit: 100, include: "catalog" } : undefined, headers: appleHeaders(mut) });
        songs.push(...(res.data?.data ?? []).map((s: any) => normaliseSong(s)));
        url = res.data?.next ? `https://amp-api-edge.music.apple.com${res.data.next}` : null;
      }
    }

    return c.json({ songs });
  } catch (e: any) { return c.json({ error: e.message }, 500); }
});

export default library;
