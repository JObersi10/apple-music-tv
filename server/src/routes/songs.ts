import { Hono } from "hono"
import axios from "axios"
import { music } from "../index"
import { normaliseSong } from "./search"
import { getBearerToken, getMUT, getStorefront } from "../auth"

export const songRoutes = new Hono()

songRoutes.get("/", async (c) => {
  const ids = (c.req.query("ids") ?? "").split(",").filter(Boolean).slice(0, 25)
  if (!ids.length) return c.json({ error: "ids is required" }, 400)
  const results = await Promise.allSettled(ids.map((id) => music.Songs.get({ id })))
  const songs = results
    .filter((r): r is PromiseFulfilledResult<any> => r.status === "fulfilled")
    .flatMap((r) => r.value.data)
    .map(normaliseSong)
  return c.json({ songs })
})

songRoutes.get("/:id", async (c) => {
  const id  = c.req.param("id")
  const res = await music.Songs.get({ id })
  const song = res.data[0]
  if (!song) return c.json({ error: "Song not found" }, 404)
  return c.json(normaliseSong(song))
})

songRoutes.get("/:id/related", async (c) => {
  const id = c.req.param("id")
  const sf = getStorefront() || "us"
  const h = {
    Authorization: `Bearer ${getBearerToken()}`,
    Origin: "https://music.apple.com",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
  }
  try {
    // Get the song to find artistId + genre
    const songRes = await music.Songs.get({ id })
    const song = songRes.data[0]
    const artistId = song?.relationships?.artists?.data?.[0]?.id
    const genreName: string = song?.attributes?.genreNames?.[0] ?? ""
    const artistName: string = song?.attributes?.artistName ?? ""

    if (artistId) {
      // Get artist's top songs via catalog endpoint
      const res = await axios.get(
        `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/artists/${artistId}/view/top-songs`,
        { headers: h, params: { limit: 25 } }
      )
      const songs = (res.data?.data ?? []).map(normaliseSong).filter((s: any) => s.id !== id)
      if (songs.length >= 5) return c.json({ songs })
    }

    // Fallback: search by genre + artist
    const term = genreName || artistName
    const searchRes = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/search`,
      { headers: h, params: { term, types: "songs", limit: 25 } }
    )
    const songs = (searchRes.data?.results?.songs?.data ?? [])
      .map(normaliseSong)
      .filter((s: any) => s.id !== id)
    return c.json({ songs })
  } catch { return c.json({ songs: [] }) }
})
