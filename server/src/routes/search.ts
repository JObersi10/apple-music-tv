import { Hono } from "hono"
import { music } from "../index"
import { ResourceType } from "@syncfm/applemusic-api"

export const searchRoutes = new Hono()

searchRoutes.get("/", async (c) => {
  const term  = c.req.query("term") ?? ""
  const limit = Number(c.req.query("limit") ?? 20)
  const types = (c.req.query("types") ?? "songs,albums,artists")
    .split(",").map((t) => t.trim() as ResourceType)
  if (!term) return c.json({ error: "term is required" }, 400)
  const results = await music.Search.search({ term, limit, types })
  return c.json({
    songs:     results.results.songs?.data.map(normaliseSong) ?? [],
    albums:    results.results.albums?.data.map(normaliseAlbum) ?? [],
    artists:   results.results.artists?.data.map(normaliseArtist) ?? [],
    playlists: results.results.playlists?.data.map(normalisePlaylist) ?? [],
  })
})

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
