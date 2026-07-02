import { startLogServer } from "./logserver"
startLogServer()
import { Hono } from "hono"
import { cors } from "hono/cors"
import { logger } from "hono/logger"
import { AppleMusic, AuthType, Region } from "@syncfm/applemusic-api"
import { searchRoutes } from "./routes/search"
import { albumRoutes } from "./routes/albums"
import { songRoutes } from "./routes/songs"
import { artistRoutes } from "./routes/artists"
import authRoutes from "./routes/auth"
import lyricsRoutes from "./routes/lyrics"
import libraryRoutes from "./routes/library"
import streamRoutes from "./routes/stream"
import motionRoutes from "./routes/motion"
import homeRoutes from "./routes/home"

export const music = new AppleMusic({ region: Region.US, authType: AuthType.Scraped })
await music.init()
console.log("✓ Apple Music client initialised")

// Clear stream cache on startup so stale files don't persist across restarts.
import fs from "fs"
import os from "os"
import path from "path"
const STREAM_CACHE = path.join(os.tmpdir(), "am_stream_cache")
try {
  if (fs.existsSync(STREAM_CACHE)) {
    fs.rmSync(STREAM_CACHE, { recursive: true, force: true })
  }
  fs.mkdirSync(STREAM_CACHE, { recursive: true })
  console.log("✓ Stream cache cleared")
} catch (e: any) {
  console.warn("⚠ Could not clear stream cache:", e.message)
}

// Scrape bearer JWT at startup so stream routes work immediately
import { setBearerToken } from "./auth"
import axios from "axios"
;(async () => {
  try {
    const ax = axios.create({ baseURL: "https://music.apple.com", headers: { "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15" } })
    const html: string = (await ax.get("/")).data
    const scriptMatch = html.match(/crossorigin src="(\/assets\/index.+?\.js)"/)
    if (!scriptMatch) throw new Error("no script tag found")
    const js: string = (await ax.get(scriptMatch[1])).data
    const tokenMatch = js.match(/(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)/)
    if (!tokenMatch) throw new Error("no JWT in script")
    setBearerToken(tokenMatch[1])
    console.log("✓ Bearer JWT scraped")
  } catch (e: any) {
    console.warn("⚠ Bearer scrape failed on startup:", e.message)
  }
})()

const app = new Hono()
app.use("*", logger())
app.use("*", cors({ origin: "*" }))

app.get("/health", (c) => c.json({ ok: true }))
app.route("/auth",          authRoutes)
app.route("/api/search",    searchRoutes)
app.route("/api/albums",    albumRoutes)
app.route("/api/songs",     songRoutes)
app.route("/api/artists",   artistRoutes)
app.route("/api/lyrics",    lyricsRoutes)
app.route("/api/library",   libraryRoutes)
app.route("/api/stream",    streamRoutes)
app.route("/api/motion",    motionRoutes)
app.route("/api/home",      homeRoutes)

app.onError((err, c) => {
  console.error(err)
  return c.json({ error: err.message }, 500)
})

const PORT = Number(process.env.PORT ?? 3000)
console.log(`🎵 Proxy running on http://0.0.0.0:${PORT}`)
export default { port: PORT, fetch: app.fetch }
