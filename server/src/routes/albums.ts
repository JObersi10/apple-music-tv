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

  let album: any = null
  try {
    const res = await music.Albums.get({ id, include: ["tracks", "artists"] })
    album = res.data[0]
  } catch (_) {}
  // MusicKit wrapper sometimes fails for certain storefronts — fallback to direct amp-api call
  if (!album) {
    try {
      const sf = getStorefront() || "us"
      const r = await axios.get(`https://amp-api-edge.music.apple.com/v1/catalog/${sf}/albums/${id}`, { headers: ampHeaders(), params: { include: "artists" } })
      album = r.data?.data?.[0]
    } catch (_) {}
  }
  if (!album) return c.json({ error: "Album not found" }, 404)
  const attr = album.attributes ?? {}
  const artistId = album.relationships?.artists?.data?.[0]?.id ?? null
  return c.json({
    id:             album.id,
    title:          attr.name ?? "Unknown",
    artistName:     attr.artistName ?? "",
    artistId,
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
        `https://amp-api-edge.music.apple.com/v1/me/library/albums/${id}/tracks?include=catalog,artists,albums&limit=${Math.min(limit, 100)}`,
        { headers: ampHeaders() }
      )
      const data = res.data?.data ?? []
      return c.json({ tracks: data.map(normaliseSong), next: null })
    } catch (e: any) {
      return c.json({ error: e.message, status: e?.response?.status, tracks: [] }, 500)
    }
  }

  // Direct amp-api call — supports include=artists on tracks unlike the MusicKit wrapper
  const sf = getStorefront() || "us"
  try {
    const r = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/albums/${id}/tracks`,
      { headers: ampHeaders(), params: { limit: Math.min(limit, 100), include: "artists" } }
    )
    const raw = r.data?.data ?? []
    const tracks = raw.filter((t: any) => t.type === "songs").map((s: any) => ({
      ...normaliseSong(s),
      albumId: id,
      artistId: s.relationships?.artists?.data?.[0]?.id ?? null,
    }))
    console.log(`[albums] tracks id=${id} raw=${raw.length} songs=${tracks.length}`)
    return c.json({ tracks, next: r.data?.next ?? null })
  } catch (e: any) {
    return c.json({ error: e.message, tracks: [] }, 500)
  }
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
  const headers = ampHeaders()
  console.log(`[station] id=${rawId} → resolved=${id}`)

  // Apple radio streams a ROLLING queue: POST /v1/me/stations/next-tracks/{id} returns a small
  // batch (usually 1–3 catalog songs) each call. We POST several times to build a playable queue
  // (deduping), exactly what the web player does as you listen. (Verified: this is the ONLY working
  // endpoint — /stations/next, /stations/queue, GET next-tracks all 405. Must be POST with {} body.)
  const songs: any[] = []
  const seen = new Set<string>()
  try {
    for (let i = 0; i < 12 && songs.length < 20; i++) {
      const res = await axios.post(
        `https://amp-api.music.apple.com/v1/me/stations/next-tracks/${id}`,
        {},
        { headers: { ...headers, "Content-Type": "application/json" } },
      )
      const batch = (res.data?.data ?? []).filter((t: any) => t.type === "songs")
      let added = 0
      for (const t of batch) {
        if (seen.has(t.id)) continue
        seen.add(t.id); songs.push(normaliseSong(t)); added++
      }
      if (batch.length === 0 && added === 0) break
    }
    console.log(`[station] next-tracks gathered ${songs.length}`)
    if (songs.length > 0) return c.json({ songs })
  } catch (e: any) {
    console.warn(`[station] next-tracks failed:`, e?.response?.status, e.message)
  }

  // Fallback: personal recently-played tracks, so a station tile is never dead.
  try {
    const res = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/me/recent/played/tracks`,
      { headers, params: { limit: 25, types: "songs" } }
    )
    const recent = (res.data?.data ?? []).filter((t: any) => t.type === "songs")
    if (recent.length > 0) return c.json({ songs: recent.map(normaliseSong) })
  } catch (e: any) {
    console.warn(`[station] recent-played failed:`, e.message)
  }

  return c.json({ songs: [] })
})

// Apple Music Radio LIVE streams (isLive:true). The web player resolves them via
// GET /v1/play/assets?kind=radioStation — returns a live CMAF HLS m3u8 plus a Widevine
// key server + service-cert URL. (webPlayback/radioPlayback 404 for these; play/assets
// is the one that works.) The client plays the HLS with a Widevine session pointed at
// keyServerUrl. stationHash is NOT required.
albumRoutes.get("/station/:id/stream", async (c) => {
  const id = decodeStationId(c.req.param("id"))
  try {
    const res = await axios.get("https://amp-api.music.apple.com/v1/play/assets", {
      headers: ampHeaders(),
      params: { format: "stream", hasDrm: true, id, kind: "radioStation", mediaType: 0, keyFormat: "web" },
    })
    const asset = res.data?.results?.assets?.[0]
    if (!asset?.url) return c.json({ liveStreamUrl: null })

    // The web player's license body sends `uri` = the Widevine EXT-X-KEY URI from the media
    // playlist (a data:…base64,<pssh> string). Resolve it here so the client doesn't have to
    // parse HLS: master → first media playlist → the EXT-X-KEY whose KEYFORMAT is the Widevine
    // UUID (edef8ba9-79d6-4ace-a3c8-27dcd51d21ed).
    let wvKeyUri: string | null = null
    try {
      const master = await axios.get(asset.url, { responseType: "text" })
      const mediaUrl = String(master.data).split("\n").find((l) => l.startsWith("http"))
      if (mediaUrl) {
        const media = await axios.get(mediaUrl.trim(), { responseType: "text" })
        const line = String(media.data).split("\n").find(
          (l) => l.startsWith("#EXT-X-KEY") && l.includes("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
        )
        const m = line?.match(/URI="([^"]*)"/)
        if (m) wvKeyUri = m[1]
      }
    } catch (e: any) {
      console.warn(`[station] wv key uri resolve failed:`, e?.message)
    }

    return c.json({
      liveStreamUrl: asset.url,
      drmKeyUri:     asset.keyServerUrl ?? null,           // Widevine license server (linear.tv.apple.com)
      certUrl:       asset.widevineKeyCertificateUrl ?? null,
      wvKeyUri,                                            // EXT-X-KEY URI → license body `uri`
      adamId:        id.replace(/^ra\./, ""),
    })
  } catch (e: any) {
    console.warn(`[station] live stream failed:`, e?.response?.status, e.message)
    return c.json({ liveStreamUrl: null })
  }
})

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
