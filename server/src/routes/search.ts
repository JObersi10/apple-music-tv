import { Hono } from "hono"
import axios from "axios"
import { music } from "../index"
import { ResourceType } from "@syncfm/applemusic-api"
import { getMUT, getBearerToken, getStorefront } from "../auth"

export const searchRoutes = new Hono()

const APPLE = "https://amp-api-edge.music.apple.com"

searchRoutes.get("/", async (c) => {
  const term  = c.req.query("term") ?? ""
  const limit = Number(c.req.query("limit") ?? 20)
  const types = (c.req.query("types") ?? "songs,albums,artists")
    .split(",").map((t) => t.trim() as ResourceType)
  if (!term) return c.json({ error: "term is required" }, 400)
  const results = await music.Search.search({ term, limit, types })
  const wantCurators = types.some((t) => String(t).includes("curator"))
  return c.json({
    songs:     results.results.songs?.data.map(normaliseSong) ?? [],
    albums:    results.results.albums?.data.map(normaliseAlbum) ?? [],
    artists:   results.results.artists?.data.map(normaliseArtist) ?? [],
    playlists: results.results.playlists?.data.map(normalisePlaylist) ?? [],
    curators:  wantCurators ? await searchCurators(term) : [],
  })
})

// Editorial "categories" behind a search — two shapes surfaced under one row:
//  • multirooms (e.g. "The Sounds of Formula 1") — hand-built editorial pages. They only
//    appear when `editorial-items` rides along in `types` (alone it 400s) + `with=topResults`;
//    the item's link.url carries the real /multi-room/<id>.
//  • curators (e.g. "Formula 1", "Tomorrowland") — playlist collections; `curators`
//    (brand) and `apple-curators` (Apple's own).
// Each item is tagged with `kind` so the client picks the right page route.
function art(url: string | undefined, size = 400): string | null {
  return url ? String(url).replace("{w}", String(size)).replace("{h}", String(size)).replace("{f}", "jpg") : null
}

async function searchCurators(term: string): Promise<any[]> {
  const sf = getStorefront() || "us"
  const hdrs = {
    Authorization: `Bearer ${getBearerToken()}`,
    "Music-User-Token": getMUT() ?? "",
    Origin: "https://music.apple.com",
  }
  try {
    // artists rides along because Apple only fills the editorial groups when other
    // "bubble" types are present; the group keys are inconsistent (curator/category/…),
    // so scan every group by item type rather than guessing keys.
    const res = await axios.get(`${APPLE}/v1/catalog/${sf}/search`, {
      headers: hdrs,
      params: {
        term,
        // editorial-items (multirooms) is only accepted alongside a broad type set —
        // a short list 400s. We still only read the curator + category groups below.
        types: "artists,albums,songs,playlists,curators,apple-curators,music-videos,stations,editorial-items",
        with: "serverBubbles,topResults",
        limit: 6, platform: "web", l: "en-US",
      },
    })
    const groups = res.data?.results ?? {}
    const multirooms: any[] = []
    const curators: any[] = []
    const seen = new Set<string>()
    for (const key of Object.keys(groups)) {
      for (const item of groups[key]?.data ?? []) {
        const a = item.attributes ?? {}
        if (item.type === "editorial-items") {
          // Only multiroom editorial items map to a page we can open.
          if (a.link?.feature !== "multirooms") continue
          const m = String(a.link?.url ?? a.url ?? "").match(/multi-?room[s]?\/(\d+)/)
          if (!m) continue
          const id = m[1]
          if (seen.has("mr" + id)) continue
          seen.add("mr" + id)
          multirooms.push({
            id, kind: "multiroom", isApple: false,
            name: a.editorialNotes?.name ?? a.name ?? "Unknown",
            artworkUrl: art(a.editorialArtwork?.subscriptionCover?.url ?? a.editorialArtwork?.brandLogo?.url),
          })
        } else if (item.type === "curators" || item.type === "apple-curators") {
          if (seen.has(item.id)) continue
          seen.add(item.id)
          curators.push({
            id: item.id,
            kind: item.type === "apple-curators" ? "apple-curator" : "curator",
            isApple: item.type === "apple-curators",
            name: a.name ?? "Unknown",
            artworkUrl: art(a.artwork?.url),
          })
        }
      }
    }
    // Multirooms first — they're the strongest editorial match.
    return [...multirooms, ...curators]
  } catch { return [] }
}

searchRoutes.get("/suggestions", async (c) => {
  const term  = c.req.query("term") ?? ""
  const limit = Number(c.req.query("limit") ?? 8)
  if (!term) return c.json({ suggestions: [] })
  const res = await music.Suggestions.suggestions({ term, limit })
  return c.json({ suggestions: res })
})

// For library items, artwork may live in the catalog relationship
function artworkUrl(item: any): string | null {
  return item.attributes?.artwork?.url
    ?? item.relationships?.catalog?.data?.[0]?.attributes?.artwork?.url
    ?? null
}

export function normaliseSong(s: any) {
  const a = s.attributes ?? {}
  return {
    id:             s.id,
    type:           s.type ?? "songs",
    title:          a.name ?? "Unknown",
    artistName:     a.artistName ?? "",
    artistId:       s.relationships?.artists?.data?.[0]?.id ?? null,
    albumId:        s.relationships?.albums?.data?.[0]?.id ?? null,
    albumName:      a.albumName ?? "",
    durationMs:     a.durationInMillis ?? a.durationInMilliseconds ?? 0,
    artworkUrl:     artworkUrl(s),
    artworkBgColor: a.artwork?.bgColor ?? s.relationships?.catalog?.data?.[0]?.attributes?.artwork?.bgColor ?? null,
    previewUrl:     a.previews?.[0]?.url ?? null,
    previewHlsUrl:  a.previews?.[0]?.hlsUrl ?? null,
    hasLyrics:      a.hasLyrics ?? false,
    trackNumber:    a.trackNumber ?? null,
    genreNames:     a.genreNames ?? [],
  }
}

export function normaliseAlbum(a: any) {
  const attr = a.attributes ?? {}
  return {
    id:             a.id,
    title:          attr.name ?? "Unknown",
    artistName:     attr.artistName ?? "",
    artworkUrl:     artworkUrl(a),
    artworkBgColor: attr.artwork?.bgColor ?? a.relationships?.catalog?.data?.[0]?.attributes?.artwork?.bgColor ?? null,
    releaseDate:    attr.releaseDate ?? null,
    trackCount:     attr.trackCount ?? 0,
    genreNames:     attr.genreNames ?? [],
  }
}

export function normaliseArtist(a: any) {
  const attr = a.attributes ?? {}
  return {
    id:         a.id,
    name:       attr.name ?? "Unknown",
    artworkUrl: attr.artwork?.url ?? null,
    genreNames: attr.genreNames ?? [],
  }
}

export function normalisePlaylist(p: any) {
  const attr = p.attributes ?? {}
  return {
    id:             p.id,
    name:           attr.name ?? "Unknown",
    curatorName:    attr.curatorName ?? attr.description?.standard ?? "",
    artworkUrl:     artworkUrl(p),
    artworkBgColor: attr.artwork?.bgColor ?? null,
    description:    attr.description?.short ?? null,
  }
}
