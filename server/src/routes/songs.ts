import { Hono } from "hono"
import { music } from "../index"
import { normaliseSong } from "./search"

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
