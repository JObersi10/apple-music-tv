import { Hono } from "hono"
import axios from "axios"
import { music } from "../index"
import { normaliseAlbum, normaliseSong } from "./search"
import { getBearerToken, getMUT, hasMUT, getStorefront } from "../auth"

export const artistRoutes = new Hono()

// Rich artist page — everything Apple Music shows: bio, top songs, latest
// release, full/featured albums, similar artists. Uses the raw amp-api with
// ?views= (the syncfm wrapper doesn't expose views).
artistRoutes.get("/:id/full", async (c) => {
  let id = c.req.param("id")
  const sf = getStorefront()
  const headers: Record<string, string> = {
    Authorization: `Bearer ${getBearerToken()}`,
    Origin: "https://music.apple.com",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
  }
  if (hasMUT()) headers["Music-User-Token"] = getMUT()

  // Library artist ids ("r.xxx") aren't catalog ids — resolve to the catalog
  // artist first so the rich views work.
  if (id.startsWith("r.")) {
    try {
      const rel = await axios.get(
        `https://amp-api-edge.music.apple.com/v1/me/library/artists/${id}?include=catalog`,
        { headers }
      )
      const catId = rel.data?.data?.[0]?.relationships?.catalog?.data?.[0]?.id
      if (catId) id = catId
      else return c.json({ error: "No catalog artist for library id" }, 404)
    } catch (e: any) {
      return c.json({ error: e.message, status: e?.response?.status }, 500)
    }
  }

  try {
    const views = "top-songs,latest-release,full-albums,featured-albums,similar-artists,top-music-videos,playlists"
    const url = `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/artists/${id}` +
      `?views=${views}&extend=editorialNotes,artistBio,bornOrFormed,origin,editorialArtwork` +
      `&limit[artists:top-songs]=20&limit[artists:full-albums]=30&limit[artists:top-music-videos]=20&limit[artists:playlists]=15`
    const res = await axios.get(url, { headers })
    const artist = res.data?.data?.[0]
    if (!artist) return c.json({ error: "Artist not found" }, 404)
    const attr = artist.attributes ?? {}
    const v = artist.views ?? {}

    const topSongs = (v["top-songs"]?.data ?? []).map(normaliseSong)
    const musicVideos = (v["top-music-videos"]?.data ?? []).map(normaliseSong)
    const latest   = (v["latest-release"]?.data ?? []).map(normaliseAlbum)
    const full     = (v["full-albums"]?.data ?? []).map(normaliseAlbum)
    const featured = (v["featured-albums"]?.data ?? []).map(normaliseAlbum)
    const similar  = (v["similar-artists"]?.data ?? []).map((s: any) => ({
      id: s.id,
      name: s.attributes?.name ?? "Unknown",
      artworkUrl: s.attributes?.artwork?.url ?? null,
    }))
    // Artist playlists (Essentials, Deep Cuts, etc.) — mapped like albums so the client renders a shelf.
    const playlists = (v["playlists"]?.data ?? []).map((p: any) => ({
      id: p.id,
      title: p.attributes?.name ?? "Unknown",
      artistName: p.attributes?.curatorName ?? attr.name ?? "",
      artworkUrl: p.attributes?.artwork?.url ?? null,
      artworkBgColor: p.attributes?.artwork?.bgColor ?? null,
      type: "playlists",
    }))

    // editorialArtwork = Apple's wide hero image + its gradient/bg/text colours (used on their own
    // artist pages). Pick the widest available flavour so the app can theme the ArtistDetail header.
    const ea = attr.editorialArtwork ?? {}
    const heroArt = ea.subscriptionHero ?? ea.bannerUber ?? ea.centeredFullscreenBackground
      ?? ea.storeFlowcase ?? ea.superHeroWide ?? ea.brickPlus ?? null
    const heroUrl = heroArt?.url
      ? String(heroArt.url).replace("{w}", "1600").replace("{h}", "900").replace("{f}", "jpg")
      : null

    return c.json({
      id: artist.id,
      name: attr.name ?? "Unknown",
      artworkUrl: attr.artwork?.url ?? null,
      // Apple editorial hero (wide banner) + theming colours; null when the artist has none.
      heroUrl,
      heroBgColor: heroArt?.bgColor ? `#${heroArt.bgColor}` : (attr.artwork?.bgColor ? `#${attr.artwork.bgColor}` : null),
      heroTextColor: heroArt?.textColor1 ? `#${heroArt.textColor1}` : null,
      genreNames: attr.genreNames ?? [],
      editorialNotes: attr.editorialNotes?.standard ?? attr.editorialNotes?.short ?? null,
      // Artist "About" facts (The Weeknd → origin "Toronto, ON, Canada", born "February 16, 1990").
      // Apple only returns these when explicitly extended; artistBio is the richer long-form note.
      origin: attr.origin ?? null,
      bornOrFormed: attr.bornOrFormed ?? null,
      artistBio: attr.artistBio ?? null,
      topSongs,
      musicVideos,
      latestRelease: latest[0] ?? null,
      albums: full,
      featuredAlbums: featured,
      playlists,
      similarArtists: similar,
    })
  } catch (e: any) {
    return c.json({ error: e.message, status: e?.response?.status }, 500)
  }
})

artistRoutes.get("/:id", async (c) => {
  const id  = c.req.param("id")
  const res = await music.Artists.get({ id })
  const artist = res.data[0]
  if (!artist) return c.json({ error: "Artist not found" }, 404)
  const attr = artist.attributes ?? {}
  return c.json({
    id:             artist.id,
    name:           attr.name ?? "Unknown",
    artworkUrl:     attr.artwork?.url ?? null,
    genreNames:     attr.genreNames ?? [],
    editorialNotes: attr.editorialNotes?.standard ?? null,
  })
})

artistRoutes.get("/:id/albums", async (c) => {
  const id    = c.req.param("id")
  const limit = Number(c.req.query("limit") ?? 25)
  const res   = await music.Artists.getRelationship({ id, relationship: "albums", limit })
  return c.json({ albums: res.data.map(normaliseAlbum) })
})
