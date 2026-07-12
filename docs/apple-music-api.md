# Apple Music API — Complete Reverse-Engineering Guide

No Apple Developer account. No MusicKit JS. Everything scraped or inferred from `music.apple.com` traffic.

---

## Auth

### Bearer JWT (required for everything)

Apple embeds a long-lived JWT in their web player's JavaScript bundle. Scrape it once at startup; it rotates infrequently (days/weeks).

```
GET https://music.apple.com/
  → parse HTML for: crossorigin src="(/assets/index.*.js)"
GET https://music.apple.com{scriptPath}
  → regex: /(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)/
```

Use the first match. This is a standard JWT (`eyJ...`). Valid for all catalog and public endpoints.

### Music-User-Token (MUT)

Required for: library, personalized content, full stream decryption.  
Obtained by the user from `music.apple.com` → developer tools → any API request → `Music-User-Token` header. No automated way to get it.

Store server-side. Send as both `Music-User-Token` and `Media-User-Token` headers on requests that need it.

### Standard headers for every amp-api request

```
Authorization: Bearer {jwt}
Music-User-Token: {mut}       ← only when required
Media-User-Token: {mut}       ← only when required (stream/DRM endpoints)
Origin: https://music.apple.com
User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15
```

### Storefront detection

```
GET https://amp-api-edge.music.apple.com/v1/me/storefront
  headers: Authorization + Music-User-Token
  response: { data: [{ id: "us", ... }] }
```

`data[0].id` = two-letter storefront code (`us`, `gb`, `jp`, etc.). Required for catalog URL path. Default `us`.

---

## Base URL

```
https://amp-api-edge.music.apple.com
```

All API paths below are relative to this base.

---

## ID Prefixes

| Prefix | Type |
|--------|------|
| *(none, numeric)* | Catalog song/album/artist |
| `pl.` | Catalog/editorial/shared playlist |
| `i.` | Library song |
| `l.` | Library album |
| `r.` | Library artist |
| `p.` | User-created library playlist |
| `ra.` | Radio station |

Library IDs cannot be used on catalog endpoints and vice versa. Always branch on prefix.

---

## Search

```
GET /v1/catalog/{sf}/search
  params:
    term: string
    types: "songs,albums,artists,playlists"   ← comma-separated
    limit: 20
  headers: Authorization only (no MUT needed)
```

Response shape:
```json
{
  "results": {
    "songs":     { "data": [ ...songObjects ] },
    "albums":    { "data": [ ...albumObjects ] },
    "artists":   { "data": [ ...artistObjects ] },
    "playlists": { "data": [ ...playlistObjects ] }
  }
}
```

---

## Catalog Objects — Parsing

### Song object

```
song.id                                          → song ID
song.attributes.name                             → title
song.attributes.artistName                       → artist name
song.relationships.artists.data[0].id            → artistId (for artist page nav)
song.attributes.albumName                        → album name
song.attributes.durationInMillis                 → duration ms
song.attributes.artwork.url                      → artwork template (replace {w},{h},{f})
song.attributes.artwork.bgColor                  → hex bg color string (no #)
song.attributes.previews[0].url                  → 30s AAC preview URL
song.attributes.previews[0].hlsUrl               → HLS preview URL
song.attributes.hasLyrics                        → bool
song.attributes.trackNumber                      → int
song.attributes.genreNames                       → string[]
```

### Album object

```
album.id
album.attributes.name                            → title
album.attributes.artistName
album.attributes.artwork.url                     → artwork template
album.attributes.artwork.bgColor
album.attributes.releaseDate                     → "YYYY-MM-DD"
album.attributes.trackCount
album.attributes.genreNames
album.attributes.recordLabel
album.attributes.copyright
album.attributes.editorialNotes.standard         → bio/description
album.attributes.isMasteredForItunes             → bool
```

### Artist object

```
artist.id
artist.attributes.name
artist.attributes.artwork.url                    → artwork template
artist.attributes.genreNames
artist.attributes.editorialNotes.standard
```

### Playlist object

```
playlist.id
playlist.attributes.name
playlist.attributes.curatorName
playlist.attributes.artwork.url                  → artwork template
playlist.attributes.artwork.bgColor
playlist.attributes.description.short
```

### Artwork URL template

Every artwork URL contains literal `{w}`, `{h}`, `{f}` placeholders:
```
https://is1-ssl.mzstatic.com/image/thumb/.../source/{w}x{h}bb.{f}

→ replace {w} and {h} with pixel size (e.g. 500)
→ replace {f} with "jpg"
→ result: .../source/500x500bb.jpg
```

---

## Catalog — Albums

### Get album metadata

```
GET /v1/catalog/{sf}/albums/{id}
  params: include=tracks,artists
  headers: Authorization
  response: { data: [ albumObject ] }
```

Album is at `data[0]`.

### Get album tracks

```
GET /v1/catalog/{sf}/albums/{id}/relationships/tracks
  params: limit=50
  headers: Authorization
  response: { data: [ ...songObjects ], next: "/v1/..." | null }
```

Filter for `type === "songs"` — Apple occasionally includes music-videos in track lists.  
Follow `response.next` to paginate.

### Library album (id starts with `l.`)

```
GET /v1/me/library/albums/{id}
  params: include=catalog
  headers: Authorization + MUT

GET /v1/me/library/albums/{id}/tracks
  params: include=catalog&limit=100
  headers: Authorization + MUT
```

With `include=catalog`, the real catalog item is embedded at:
```
album.relationships.catalog.data[0]   → full catalog album object
song.relationships.catalog.data[0]    → full catalog song object
```

Prefer the catalog item for artwork, IDs, and metadata.

---

## Catalog — Artists

### Get artist (basic)

```
GET /v1/catalog/{sf}/artists/{id}
  headers: Authorization
  response: { data: [ artistObject ] }
```

### Get artist — full page (bio + sections)

```
GET /v1/catalog/{sf}/artists/{id}
  params:
    views: top-songs,latest-release,full-albums,featured-albums,similar-artists
    extend: editorialNotes
    limit[artists:top-songs]: 20
    limit[artists:full-albums]: 30
  headers: Authorization (+ MUT optional)
```

Response structure:
```
artist.views["top-songs"].data        → songObjects[]
artist.views["latest-release"].data   → albumObjects[]  (usually 1)
artist.views["full-albums"].data      → albumObjects[]
artist.views["featured-albums"].data  → albumObjects[]
artist.views["similar-artists"].data  → artistObjects[]
artist.attributes.editorialNotes.standard → bio text
```

### Library artist (id starts with `r.`)

Resolve to catalog first:
```
GET /v1/me/library/artists/{id}
  params: include=catalog
  → artist.relationships.catalog.data[0].id → real catalog ID
```

Then use the catalog ID for the full artist page request above.

### Get artist albums

```
GET /v1/catalog/{sf}/artists/{id}/relationships/albums
  params: limit=25
  headers: Authorization
  response: { data: [ ...albumObjects ] }
```

---

## Catalog — Songs

### Get single song

```
GET /v1/catalog/{sf}/songs/{id}
  headers: Authorization
  response: { data: [ songObject ] }
```

### Get multiple songs

```
GET /v1/catalog/{sf}/songs
  params: ids=1234,5678,9012    ← comma-separated, max ~25
  headers: Authorization
  response: { data: [ ...songObjects ] }
```

### Related songs (for autoplay)

```
GET /v1/catalog/{sf}/artists/{artistId}/view/top-songs
  params: limit=25
  headers: Authorization (+ MUT)
  response: { data: [ ...songObjects ] }
```

Fall back to a search if artist ID isn't available:
```
GET /v1/catalog/{sf}/search
  params: term={genreName or artistName}, types=songs, limit=25
```

---

## Library

All library endpoints require MUT. 401 without it.

### Songs

```
GET /v1/me/library/songs
  params: limit=100, include=catalog
  headers: Authorization + MUT
  response: { data: [ ...librarySongObjects ], next: "/v1/..." | null }
```

Paginate by following `response.next`. Up to 2000 items. With `include=catalog`, each item embeds its catalog equivalent at `song.relationships.catalog.data[0]` — use that for artwork and real IDs.

### Albums

```
GET /v1/me/library/albums
  params: limit=100, include=catalog
  headers: Authorization + MUT
  response: { data: [ ...libraryAlbumObjects ], next: string | null }
```

### Playlists

```
GET /v1/me/library/playlists
  params: limit=100, include=catalog
  headers: Authorization + MUT
  response: { data: [ ...libraryPlaylistObjects ], next: string | null }
```

Playlist artwork quirk: library playlists often have no `attributes.artwork`. Check:
```
1. playlist.attributes.artwork.url
2. playlist.relationships.catalog.data[0].attributes.artwork.url
```

### Artists

```
GET /v1/me/library/artists
  params: limit=100
  headers: Authorization + MUT
  response: { data: [ ...libraryArtistObjects ], next: string | null }
```

---

## Playlist Tracks

### User library playlist (`p.xxx` or any non-`pl.` ID)

```
GET /v1/me/library/playlists/{id}/tracks
  params: limit=100, offset=0, include=catalog
  headers: Authorization + MUT
  response: { data: [ ...librarySongObjects ], next: string | null }
```

Paginate with `offset += 100`. Each track: prefer `relationships.catalog.data[0]` for the real catalog song object and catalog ID (for streaming). Fall back to the library object — it will have an `i.xxx` ID which the stream route handles.

### Catalog / editorial / shared playlist (`pl.xxx`)

```
GET /v1/catalog/{sf}/playlists/{id}/tracks
  params: limit=100, offset=0
  headers: Authorization + MUT
  response: { data: [ ...songObjects ], next: string | null }
```

MUT is sent but not strictly required for public playlists.

---

## Charts / Browse

### Charts

```
GET /v1/catalog/{sf}/charts
  params:
    types: "songs" | "albums" | "playlists"   ← one at a time, or comma-separated
    limit: 20
    genre: {genreId}    ← optional, filters to a genre
  headers: Authorization
  response:
    results.songs[0].data      → songObjects[]
    results.albums[0].data     → albumObjects[]
    results.playlists[0].data  → playlistObjects[]
```

`results.songs[0].name`, `results.albums[0].name`, etc. = chart name (e.g. "Daily Top 100").

### Genres

```
GET /v1/catalog/{sf}/genres
  params: limit=50
  headers: Authorization
  response: { data: [ { id: "20", attributes: { name: "Rock" } }, ... ] }
```

Genre ID `34` = Podcasts — filter it out. Pass a genre ID to the charts endpoint to get genre-specific charts.

---

## Streaming (Full Decrypt)

High-level flow:
1. Fetch the song's HLS manifest URL + DRM key URI from Apple's play endpoint
2. Download the encrypted HLS segments
3. Decrypt with `mp4decrypt` (Bento4)
4. Remux with ffmpeg to a seekable progressive MP4

### Step 1 — Get stream parameters

**Library song (`i.xxx`)**:
```
POST https://play.music.apple.com/WebObjects/MZPlay.woa/wa/subVideoPlaybackInfo
  headers:
    Authorization: Bearer {jwt}
    Media-User-Token: {mut}
    Content-Type: application/json
    Origin: https://music.apple.com
  body: {
    "salableAdamId": "{numericId}",   ← strip "i." prefix, or use actual catalog ID
    "type": "libraryTracks"
  }
  response: {
    "songList": [ { "hls-catalog-url": "...", "asset-info": { ... } } ]
  }
```

**Catalog song (numeric ID)**:
```
POST https://play.music.apple.com/WebObjects/MZPlay.woa/wa/subVideoPlaybackInfo
  body: { "salableAdamId": "{id}", "type": "songs" }
```

The `songList[0]["hls-catalog-url"]` is the master HLS playlist URL.

### Step 2 — Resolve the media playlist

Fetch the master playlist. Look for `#EXT-X-STREAM-INF` lines. If present, it's a master playlist — pick the best variant:
- Prefer `CODECS` containing `"ec-3"` or `"ac-3"` (AAC/AAC+)
- Cap bandwidth at 500 kbps to avoid ALAC (lossless, 100-350MB)
- Sort by bandwidth descending, pick highest under the cap
- If all variants exceed 500 kbps, pick the lowest

**Asset selection in the HLS manifest** (more reliable than bandwidth cap):
```
#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,...URI="skd://...",KEYFORMAT="..."
#EXT-X-STREAM-INF:BANDWIDTH=256000,...CODECS="ec-3"
```
Prefer `ctrp64` (AES-128 CTR, 64-bit key) over `ctrp256` (AES-256 = ALAC lossless). The number is key size, NOT audio bitrate.

### Step 3 — Extract encryption key

In the media playlist:
```
#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="skd://...",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
```

Rewrite `URI` to point to your proxy: `skd://` → `https://your-proxy/key/...`

The `URI` value after `skd://` is the key URI that `mp4decrypt` will use to fetch the AES key.

**Fetch the key**:
```
POST {keyUri}
  headers:
    Authorization: Bearer {jwt}
    Media-User-Token: {mut}
    Content-Type: application/json
  body: { "challenge": "..." }   ← base64 Widevine/FairPlay challenge if SAMPLE-AES
```

For AES-128 tracks (simpler), the key is returned directly as binary.

### Step 4 — Download segments

Parse the media playlist for `#EXTINF` + URL lines, and the `#EXT-X-MAP` init segment.

For **multi-segment** playlists:
- Download init segment (`#EXT-X-MAP URI="..."`) separately
- Download all `#EXTINF` segments in parallel (`asyncio.gather`)
- Concatenate: init + segment1 + segment2 + ...

Fetching only the init segment causes choppy/silent audio on multi-segment tracks.

### Step 5 — Decrypt

```bash
mp4decrypt --key {trackId}:{hexKey} input.mp4 output_decrypted.mp4
```

`mp4decrypt` is from Bento4. Key is fetched via the key URI from step 3.

### Step 6 — Remux to seekable MP4

```bash
# Single-segment (no timestamp gaps):
ffmpeg -i decrypted.mp4 -c copy -movflags +faststart output.mp4

# Multi-segment (fix timestamp gaps at boundaries):
ffmpeg -i decrypted.mp4 -af aresample=async=1 -movflags +faststart output.mp4
```

`+faststart` moves the `moov` atom to the front → HTTP Range requests work → ExoPlayer can seek instantly without buffering the whole file.

Multi-segment tracks have timestamp gaps at segment boundaries that cause `UnexpectedDiscontinuityException` in ExoPlayer. `aresample=async=1` fixes them by resampling the audio to fill gaps. Re-encoding is fast for AAC.

---

## Motion Artwork (Animated Cover)

```
GET /v1/catalog/{sf}/songs/{id}
  params: (no special params needed)
  → song.relationships.albums.data[0].id   → album ID

GET /v1/catalog/{sf}/albums/{albumId}
  params: extend=editorialVideo
  → album.attributes.editorialVideo.motionDetailSquare.video   → HLS URL
  → album.attributes.editorialVideo.motionSquareVideo1x1.video → HLS URL (fallback)
```

Not all albums have motion art. Return `null` if the fields are missing. The HLS URL is a muted looping video meant to play over the album artwork.

---

## Lyrics

### Apple (preferred)

```
GET /v1/catalog/{sf}/songs/{id}/syllable-lyrics    ← word-level TTML
GET /v1/catalog/{sf}/songs/{id}/lyrics             ← line-level TTML (fallback)
  headers: Authorization + MUT
```

Response is TTML XML. Parse structure:
```xml
<tt>
  <body>
    <div>
      <p begin="12.5s" end="15.2s">
        <span begin="12.5s" end="13.0s">Word</span>
        <span ttm:role="x-bg" begin="12.5s" end="15.2s">Background vocals</span>
      </p>
    </div>
  </body>
</tt>
```

- Timestamps have trailing `s` suffix — strip it, parse as float seconds → multiply by 1000 for ms
- `itunes:timing="Word"` in `<tt>` = word-by-word sync; `"Line"` = line sync only
- `ttm:role="x-bg"` spans = background vocals (render differently, offset by -300ms)
- Namespace prefixes vary (`tt:span` vs `span`) — match namespace-tolerant

### Fallback: lrclib.net (no auth, line-sync)

```
GET https://lrclib.net/api/get
  params: track_name={title}&artist_name={artist}&duration={durationSeconds}
  response: { syncedLyrics: "[mm:ss.xx] Line text\n..." }
```

Parse `[mm:ss.xx]` timestamps:
```
[01:23.45] → (1*60 + 23.45) * 1000 = 83450 ms
```

---

## Pagination

All list endpoints support cursor-style pagination:
```json
{ "data": [...], "next": "/v1/me/library/songs?offset=100&limit=100" }
```

`next` is a path (not full URL). Prepend `https://amp-api-edge.music.apple.com`. Continue until `next` is null or absent. Most endpoints cap at 100 items per page.

---

## Rate limits / gotchas

- No documented rate limits, but ~100 req/s sustained will get 429s
- Bearer JWT scraped from `music.apple.com` JS bundle; changes when Apple redeploys but usually stable for days
- `amp-api-edge` is faster/more reliable than `amp-api.music.apple.com` (non-edge)
- Library items (`i.`, `l.`, `r.`, `p.`) always need MUT and must use `/v1/me/library/...` paths — using catalog endpoints with library IDs returns 404
- `include=catalog` on library requests embeds the real catalog item inline — always request this for artwork and canonical IDs
- Artwork URL `{f}` must be replaced with `"jpg"` — requesting `webp` works too but `jpg` is universal
- Stream decrypt takes 5–10s on first request; cache the output file keyed by song ID to avoid re-decrypting on scrub/reconnect
