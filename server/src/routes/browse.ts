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

/** Fill a wide editorial-art template at the artwork's OWN aspect ratio (no letterbox padding —
 *  forcing 16:9 on a ~2:1 hero pads it with bgColor, which reads as "off-centre"). */
function wideArtFrom(ea: any): string | null {
  const obj = ea?.superHeroWide ?? ea?.subscriptionHero ?? ea?.storeFlowcase;
  const raw: string | undefined = obj?.url;
  if (!raw) return null;
  const w = 1000;
  const ratio = obj.width && obj.height ? obj.height / obj.width : 9 / 16;
  const h = Math.round(w * ratio);
  return raw.replace("{w}", String(w)).replace("{h}", String(h)).replace("{f}", "jpg");
}

/** Short editorial blurb ("A decade on, his full-length introduction…") for a spotlight caption. */
function shortNote(attr: any): string | null {
  const n = attr?.editorialNotes;
  if (!n) return null;
  return (typeof n === "string" ? n : (n.short ?? n.standard ?? null)) || null;
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
    // Spotlight card: caption = the short editorial blurb; the small label above is derived on the
    // client from `type` (NEW ALBUM / UPDATED PLAYLIST / NEW RADIO SHOW).
    editorialNotes: shortNote(attr),
    wideArtworkUrl: wideArtFrom(attr.editorialArtwork),
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
// A plain editorial ROOM — the "see all" page behind a Browse shelf's "More" card. Contents are a
// flat list (room 6503108310 "Daily Top 100" = 100 country playlists), so it returns one section.
browse.get("/room/:id", async (c) => {
  const id = c.req.param("id");
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  const sf = getStorefront() || "us";
  try {
    const res = await axios.get(`${APPLE}/v1/editorial/${sf}/rooms/${id}`, {
      headers: hdrs(mut),
      params: { include: "contents", extend: "editorialArtwork", "limit[contents]": 200, l: "en-US", platform: "web", "art[url]": "f" },
    });
    const room = res.data?.data?.[0];
    const title: string = room?.attributes?.title ?? room?.attributes?.name ?? "";
    const items = (room?.relationships?.contents?.data ?? []).map((it: any) =>
      (it.type === "apple-curators" || it.type === "curators") ? curatorCard(it)
        : it.type === "playlists" ? playlistCard(it)
        : itemFromRaw(it)).filter(Boolean);
    return c.json({ id, title, description: null, artworkUrl: null,
      sections: items.length ? [{ title, albums: items }] : [] });
  } catch (e: any) {
    console.warn("[browse] room failed:", e?.response?.status, e?.message);
    return c.json({ id, title: "", sections: [] });
  }
});

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

// A single song rendered as a card. Placed in the `albums` array with type "songs" — the Android
// BrowseRow routes type=="songs" cards to playback (not album detail).
function songCard(item: any): any | null {
  const attr = item.attributes ?? {};
  const url = artUrl(attr.artwork?.url);
  if (!url) return null;
  return {
    id: item.id,
    type: "songs",
    title: attr.name ?? "Unknown",
    artistName: attr.artistName ?? "",
    artworkUrl: url,
    artworkBgColor: attr.artwork?.bgColor ?? null,
    albumId: item.relationships?.albums?.data?.[0]?.id ?? null,
    artistId: item.relationships?.artists?.data?.[0]?.id ?? null,
  };
}

/** A curator/apple-curator tile inside a room (Genres/Moods/Decades). The id is prefixed so the
 *  Category screen re-opens it as a nested category page instead of trying an album lookup. */
function curatorCard(item: any): any | null {
  const attr = item.attributes ?? {};
  const ea = attr.editorialArtwork ?? {};
  const url = artUrl((ea.subscriptionCover ?? ea.brandLogo ?? attr.artwork)?.url, 600);
  if (!url) return null;
  const isApple = item.type === "apple-curators";
  return {
    id: (isApple ? "ac-" : "c-") + item.id,
    type: "curators",
    title: (attr.name ?? "Unknown").replace(/^Apple Music (?=\S)/, "").replace(/^Apple (?=\S)/, ""),
    artistName: "",
    artworkUrl: url,
    artworkBgColor: null,
  };
}

function playlistCard(item: any): any | null {
  const p = normalisePlaylist(item);
  const url = artUrl(p.artworkUrl) ?? p.artworkUrl;
  if (!url) return null;
  const attr = item.attributes ?? {};
  return { ...p, artworkUrl: url, title: p.name, artistName: p.curatorName, type: "playlists",
    editorialNotes: shortNote(attr), wideArtworkUrl: wideArtFrom(attr.editorialArtwork) };
}

// The real music.apple.com "Browse"/New page. It's a single editorial GROUPING (name="music")
// whose default tab holds every shelf — Best New Songs, New This Week, Updated Playlists, the
// personalized "Your … Soundtrack", Daily Top 100, City Charts, Coming Soon, … — IN ORDER. Passing
// the MUT makes Apple personalize it exactly like the signed-in web page. We map each editorial
// child to a section, preserving Apple's titles and order. Skipped for now: radio/stations shelves
// (Live Radio, New Radio Episodes) and Watch Interviews (uploaded-videos we can't play).
browse.get("/", async (c) => {
  const mut = c.req.header("X-Music-User-Token") || getMUT();
  const sf = getStorefront() || "us";
  const h = hdrs(mut);
  const sections: Array<{ title: string; albums?: any[]; videos?: any[] }> = [];

  try {
    const res = await axios.get(`${APPLE}/v1/editorial/${sf}/groupings`, {
      headers: h,
      params: {
        name: "music", l: "en-US", platform: "web", include: "tabs",
        extend: "editorialArtwork", "art[url]": "f", "limit[contents]": 24,
      },
    });
    const tab = res.data?.data?.[0]?.relationships?.tabs?.data?.[0];
    const kids: any[] = tab?.relationships?.children?.data ?? [];

    for (const k of kids) {
      const attr = k.attributes ?? {};
      const kind = attr.editorialElementKind;
      // 326 = album/playlist shelf, 327 = song shelf. Others are heros/links/tiles — handled elsewhere.
      if (kind !== "326" && kind !== "327") continue;
      const title: string = attr.name ?? attr.title ?? "";
      if (!title) continue;

      const contents: any[] = k.relationships?.contents?.data ?? [];
      if (!contents.length) continue;

      // A shelf is homogeneous by intent but "Everyone's Listening To..." mixes albums+playlists.
      // Live Radio, Watch Interviews and New Radio Episodes all surface now.
      const types = new Set(contents.map((it: any) => it.type));

      // The editorial-element's own id IS its room id (verified: "Daily Top 100" -> 6503108310,
      // matching music.apple.com/us/room/6503108310). Sent so the row can end in a "More" card.
      const roomId: string | undefined = k.id;

      if ([...types].every((t) => t === "music-videos")) {
        const videos = contents.map(normaliseSong).filter((v: any) => v.artworkUrl);
        if (videos.length) sections.push({ title, videos, roomId });
        continue;
      }

      const albums = contents.map((it: any) => {
        if (it.type === "songs" || it.type === "music-videos") return songCard(it);
        if (it.type === "playlists") return playlistCard(it);
        return itemFromRaw(it);
      }).filter(Boolean);
      if (albums.length) sections.push({ title, albums, roomId });
    }
  } catch (e: any) {
    console.warn("[browse] editorial grouping failed:", e?.response?.status, e?.message);
  }

  // "New" spotlight: a leading row of big landscape editorial cards. We take the lead item from
  // each of the first few content shelves that ships wide editorial art + a tagline — that yields
  // the mixed-label hero row Apple shows atop Browse (New Release / New Music Daily / Apple Music 1…)
  // without reordering or hiding the normal shelves below.
  if (sections.length) {
    const seen = new Set<string>();
    const spotlight: any[] = [];
    for (const s of sections) {
      const lead = (s.albums ?? []).find((a: any) => a?.wideArtworkUrl && !seen.has(a.id));
      if (lead) { seen.add(lead.id); spotlight.push(lead); }
      if (spotlight.length >= 8) break;
    }
    if (spotlight.length >= 3) {
      sections.unshift({ title: "New", albums: spotlight, style: "spotlight" } as any);
    }
  }

  // Fallback: if the editorial page returned nothing (no MUT / Apple hiccup), show charts so the
  // tab is never empty.
  if (!sections.length) {
    try {
      const res = await axios.get(`${APPLE}/v1/catalog/${sf}/charts`, { params: { types: "albums,playlists,songs", limit: 20 }, headers: h });
      const alb = (res.data?.results?.albums?.[0]?.data ?? []).map(itemFromRaw).filter(Boolean);
      const pl = (res.data?.results?.playlists?.[0]?.data ?? []).map(playlistCard).filter(Boolean);
      const sg = (res.data?.results?.songs?.[0]?.data ?? []).map(songCard).filter(Boolean);
      if (sg.length) sections.push({ title: "Trending Songs", albums: sg });
      if (alb.length) sections.push({ title: "New Releases", albums: alb });
      if (pl.length) sections.push({ title: "Top Playlists", albums: pl });
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
