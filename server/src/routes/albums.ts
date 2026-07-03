import { Hono } from "hono"
import axios from "axios"
import { music } from "../index"
import { normaliseSong, normaliseAlbum } from "./search"
import { getBearerToken, getMUT, hasMUT, getStorefront, ensureBearer } from "../auth"

export const albumRoutes = new Hono()

// Library album ids start with "l." — they must be fetched from the personal
// library endpoint (raw amp-api), not the catalog wrapper.
const isLibraryAlbum = (id: string) => id.startsWith("l.")

function ampHeaders() {
  const h: Record<string, string> = {
    Authorization: `Bearer ${getBearerToken()}`,
    Origin: "https://music.apple.com",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
  }
  if (hasMUT()) h["Music-User-Token"] = getMUT()
  return h
}

albumRoutes.get("/:id", async (c) => {
  const id = c.req.param("id")

  if (isLibraryAlbum(id)) {
    try {
      const res = await axios.get(
        `https://amp-api-edge.music.apple.com/v1/me/library/albums/${id}?include=catalog`,
        { headers: ampHeaders() }
      )
      const album = res.data?.data?.[0]
      if (!album) return c.json({ error: "Album not found" }, 404)
      const attr = album.attributes ?? {}
      const cat  = album.relationships?.catalog?.data?.[0]?.attributes ?? {}
      const artwork = attr.artwork ?? cat.artwork
      return c.json({
        id:             album.id,
        title:          attr.name ?? cat.name ?? "Unknown",
        artistName:     attr.artistName ?? cat.artistName ?? "",
        artworkUrl:     artwork?.url ?? null,
        artworkBgColor: artwork?.bgColor ?? null,
        releaseDate:    attr.releaseDate ?? cat.releaseDate ?? null,
        trackCount:     attr.trackCount ?? cat.trackCount ?? 0,
        genreNames:     attr.genreNames ?? cat.genreNames ?? [],
        recordLabel:    cat.recordLabel ?? null,
        copyright:      cat.copyright ?? null,
        editorialNotes: cat.editorialNotes?.standard ?? null,
        isMasteredForItunes: cat.isMasteredForItunes ?? false,
      })
    } catch (e: any) {
      return c.json({ error: e.message, status: e?.response?.status }, 500)
    }
  }

  const res = await music.Albums.get({ id, include: ["tracks", "artists"] })
  const album = res.data[0]
  if (!album) return c.json({ error: "Album not found" }, 404)
  const attr = album.attributes ?? {}
  return c.json({
    id:             album.id,
    title:          attr.name ?? "Unknown",
    artistName:     attr.artistName ?? "",
    artworkUrl:     attr.artwork?.url ?? null,
    artworkBgColor: attr.artwork?.bgColor ?? null,
    releaseDate:    attr.releaseDate ?? null,
    trackCount:     attr.trackCount ?? 0,
    genreNames:     attr.genreNames ?? [],
    recordLabel:    attr.recordLabel ?? null,
    copyright:      attr.copyright ?? null,
    editorialNotes: attr.editorialNotes?.standard ?? null,
    isMasteredForItunes: attr.isMasteredForItunes ?? false,
  })
})

albumRoutes.get("/:id/tracks", async (c) => {
  const id    = c.req.param("id")
  const limit = Number(c.req.query("limit") ?? 50)

  if (isLibraryAlbum(id)) {
    try {
      const res = await axios.get(
        `https://amp-api-edge.music.apple.com/v1/me/library/albums/${id}/tracks?include=catalog&limit=${Math.min(limit, 100)}`,
        { headers: ampHeaders() }
      )
      const data = res.data?.data ?? []
      return c.json({ tracks: data.map(normaliseSong), next: null })
    } catch (e: any) {
      return c.json({ error: e.message, status: e?.response?.status, tracks: [] }, 500)
    }
  }

  const res = await music.Albums.getRelationship({ id, relationship: "tracks", limit })
  const raw = res.data ?? []
  const nonSongs = raw.filter((t: any) => t.type !== "songs")
  if (nonSongs.length) console.log(`[albums] skipping non-song items: ${nonSongs.map((t: any) => `${t.type}:${t.id}`).join(", ")}`)
  const tracks = raw.filter((t: any) => t.type === "songs").map(normaliseSong)
  console.log(`[albums] tracks id=${id} raw=${raw.length} songs=${tracks.length}`)
  return c.json({ tracks, next: res.next ?? null })
})

function decodeStationId(id: string): string {
  if (!id.startsWith("ra.q-")) return id
  try {
    const buf = Buffer.from(id.slice(5), "base64")
    const hex = buf.toString("latin1").match(/[0-9a-f]{32}/)?.[0]
    if (hex) return `ra.u-${hex}`
  } catch {}
  return id
}

albumRoutes.get("/station/:id/tracks", async (c) => {
  const rawId = c.req.param("id")
  const id = decodeStationId(rawId)
  const sf = getStorefront() || "us"
  const headers = ampHeaders()
  console.log(`[station] id=${rawId} → resolved=${id}`)

  // 1. Fetch station metadata to get stationHash, then use it to get queue
  try {
    const metaRes = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/stations`,
      { headers, params: { ids: id } }
    )
    const attrs = metaRes.data?.data?.[0]?.attributes ?? {}
    console.log(`[station] full attrs:`, JSON.stringify(attrs))
    const stationHash = attrs.playParams?.stationHash
    console.log(`[station] stationHash=${stationHash}`)
    if (stationHash) {
      const queueRes = await axios.post(
        `https://amp-api-edge.music.apple.com/v1/me/stations/queue`,
        { stationHash },
        { headers }
      )
      console.log(`[station] queue response:`, JSON.stringify(queueRes.data).substring(0, 300))
      const songs = (queueRes.data?.data ?? []).filter((t: any) => t.type === "songs")
      if (songs.length > 0) return c.json({ songs: songs.map(normaliseSong) })
    }
  } catch (e: any) {
    console.warn(`[station] stationHash attempt failed:`, e.message)
  }

  // 2. Personal recently-played tracks as fallback
  try {
    const res = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/me/recent/played/tracks`,
      { headers, params: { limit: 25, types: "songs" } }
    )
    const songs = (res.data?.data ?? []).filter((t: any) => t.type === "songs")
    console.log(`[station] recent tracks count=${songs.length}`)
    if (songs.length > 0) return c.json({ songs: songs.map(normaliseSong) })
  } catch (e: any) {
    console.warn(`[station] recent-played failed:`, e.message)
  }

  return c.json({ songs: [] })
})

// Apple Music Radio live streams (isLive:true, hasDrm:true) are not accessible
// via any public API — webPlayback rejects them (failureType 3077), radioPlayback
// 404s, and radio.apple.com doesn't resolve. Kept as a stub; returns null.

albumRoutes.get("/:id/related", async (c) => {
  const id  = c.req.param("id")
  // Library albums have no related-albums view (it 400s). Return empty.
  if (isLibraryAlbum(id)) return c.json({ albums: [] })
  try {
    const res = await music.Albums.getView({ id, view: "related-albums" })
    return c.json({ albums: res.data.map(normaliseAlbum) })
  } catch (e: any) {
    return c.json({ albums: [] })
  }
})
