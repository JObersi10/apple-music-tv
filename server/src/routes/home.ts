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

  // Personalized recommendations feed — this IS the signed-in music.apple.com "Listen Now"/Home
  //    page. Each recommendation is a titled shelf ("Playlists Made for You", "Recently Played",
  //    genre essentials, "More from <artist>", "New Releases for You", …). We emit each as its own
  //    section in Apple's order, exactly like the web. Stations shelves are skipped until station
  //    playback is wired (a station card that can't play would look broken).
  if (mut) {
    try {
      // Apple's recommendations upstream is flaky: ~1 in 10 calls returns 500 "Upstream Service
      // Error" (50001). Worse, `art[url]=f` on the edge host makes that 500 near-certain — drop it.
      // Retry a few times so a single unlucky call doesn't collapse Home to the charts fallback.
      let res: any = null;
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          res = await axios.get(`${APPLE}/v1/me/recommendations`, {
            params: { limit: 25, "include[personal-recommendation]": "contents" },
            headers: h,
          });
          break;
        } catch (err: any) {
          if (attempt === 2) throw err;
          await new Promise((r) => setTimeout(r, 300 * (attempt + 1)));
        }
      }
      for (const rec of (res.data?.data ?? [])) {
        const title: string = rec.attributes?.title?.stringForDisplay
          ?? rec.attributes?.reason?.stringForDisplay
          ?? "For You";
        const contents: any[] = rec.relationships?.contents?.data ?? [];
        const items = contents.map((item: any) => {
          if (item.type === "albums") { const a = normaliseAlbum(item); return a.artworkUrl ? a : null; }
          if (item.type === "playlists") { const p = normalisePlaylist(item); return p.artworkUrl ? { ...p, title: p.name, artistName: p.curatorName, type: "playlists" } : null; }
          return itemFromRaw(item);
        }).filter(Boolean);
        if (items.length > 0) {
          // ONLY "Playlists Made for You" animates (Get Up!/Chill/Your Essentials…) — the one row
          // Apple animates on the web Home. Every animated card costs the TV a video decoder.
          if (/^Playlists Made for You/i.test(title)) {
            await Promise.all(items.map(async (it: any) => {
              try {
                const r = await axios.get(`${APPLE}/v1/catalog/${sf}/playlists/${it.id}`, {
                  params: { extend: "editorialVideo" }, headers: h,
                });
                const ev = r.data?.data?.[0]?.attributes?.editorialVideo;
                it.motionUrl = (ev?.motionSquareVideo1x1 ?? ev?.motionDetailSquare)?.video ?? null;
              } catch { /* motion art is optional polish */ }
            }));
          }
          sections.push({ title, albums: items });
        }
      }
    } catch (e: any) {
      console.warn("[home] recommendations failed:", e?.response?.status, e?.message);
    }
  }

  // "Find Your Mood" — Apple's Moods & Activities editorial room, same shelf the web Home shows
  // under the personalized feed. Cards carry the CategoryScreen id prefix so tapping opens that page.
  try {
    const res = await axios.get(`${APPLE}/v1/editorial/${sf}/rooms/6456176472`, {
      headers: h,
      params: { include: "contents", extend: "editorialArtwork", l: "en-US", platform: "web", "art[url]": "f" },
    });
    const drop = /rewind|replay|year in|wrapped/i;
    const items = (res.data?.data?.[0]?.relationships?.contents?.data ?? [])
      .filter((it: any) => it.type === "apple-curators" || it.type === "curators")
      .filter((it: any) => !drop.test(it.attributes?.name ?? ""))
      .map((it: any) => {
        const a = it.attributes ?? {};
        const ea = a.editorialArtwork ?? {};
        const url = artUrl(ea.subscriptionCover?.url ?? ea.brandLogo?.url ?? a.artwork?.url, 600);
        if (!url) return null;
        return {
          id: (it.type === "apple-curators" ? "ac-" : "c-") + it.id,
          title: (a.name ?? "Unknown").replace(/^Apple Music (?=\S)/, "").replace(/^Apple (?=\S)/, ""),
          artistName: "", artworkUrl: url, type: "curators",
          artworkBgColor: null, releaseDate: null, trackCount: 0,
        };
      }).filter(Boolean);
    if (items.length) sections.push({ title: "Find Your Mood", albums: items });
  } catch (e: any) {
    console.warn("[home] moods failed:", e?.response?.status, e?.message);
  }

  // Charts + new releases fill in when there's no MUT (logged-out) or the rec feed was thin.
  if (sections.length < 2) {
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
  } // end fallback

  // Apple leads with "Playlists Made for You"; the user wants it lower. Pull any such shelf down to
  // ~4th so the fresher personalized rows lead.
  const isMade = (s: { title: string }) => /^Playlists Made for You/i.test(s.title);
  const made = sections.filter(isMade);
  if (made.length) {
    const rest = sections.filter((s) => !isMade(s));
    rest.splice(Math.min(3, rest.length), 0, ...made);
    return c.json({ sections: rest });
  }

  return c.json({ sections });
});

export default home;
