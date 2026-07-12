# MUT Flow — Search, Library, Playlist Tracks

## Where MUT lives

- **Android**: `MutPreferences` (SharedPreferences). OkHttp interceptor in `NetworkModule` adds `X-Music-User-Token` header to every Retrofit request automatically.
- **Server**: stored in memory + persisted to `auth-state.json`. Synced from Android on startup and when the user sets it via the phone web server (port 8080).
- **Server reads MUT**: `resolveMUT(c)` → prefers the `X-Music-User-Token` header from the request, falls back to the server's stored MUT.

---

## 1. Search

**Route**: `GET /api/search?term=...`  
**File**: `server/src/routes/search.ts`

Search uses the `@syncfm/applemusic-api` SDK (`music.Search.search`), which uses the scraped bearer JWT only. **MUT is not passed**. This means:

- Results are catalog-only (no personal library items mixed in)
- No personalization (no "suggested for you" weighting)
- Works without MUT — bearer JWT alone is sufficient

`normaliseSong` extracts `artistId` from `relationships.artists.data[0].id` so the artist page is navigable from search results.

---

## 2. Library

**Routes**: `GET /api/library/songs|albums|playlists|artists`  
**File**: `server/src/routes/library.ts`

All library routes call `guard(c)` which calls `resolveMUT(c)`. If MUT is missing → **401**. MUT is mandatory.

### Songs (`/library/songs`)
```
GET /v1/me/library/songs
  params: { limit: 100, include: "catalog" }
  headers: Authorization + Music-User-Token + Media-User-Token
```
Paginates up to 2000 songs. The `include: "catalog"` param asks Apple to embed the catalog item in `relationships.catalog.data[0]` — this gives the real catalog ID (not `i.xxx`), better artwork, and full metadata. `normaliseSong` is called on each item directly (not `normaliseLibrarySong` — that's only for playlist tracks).

### Albums (`/library/albums`)
Same pattern as songs: paginates with `include: "catalog"`, maps via `normaliseAlbum`.

### Playlists (`/library/playlists`)
```
GET /v1/me/library/playlists
  params: { limit: 100, include: "catalog" }
```
Uses `normaliseLibraryPlaylist` which prefers `attr.artwork.url` → `catalog.attributes.artwork.url` (library playlists often have no artwork themselves, it comes from the catalog relationship).

Returns playlist objects — not their tracks. Tracks are a separate call (see below).

### Artists (`/library/artists`)
```
GET /v1/me/library/artists
  params: { limit: 100 }
  // No include: "catalog" — artist artwork comes from catalog separately
```

---

## 3. Playlist Tracks

**Route**: `GET /api/library/playlists/:id/tracks`  
**File**: `server/src/routes/library.ts` → `library.get("/playlists/:id/tracks", ...)`

The ID prefix determines which Apple endpoint is called:

### `pl.xxx` — Catalog / editorial / shared playlist
```
GET /v1/catalog/{storefront}/playlists/{id}/tracks
  params: { limit: 100, offset }
  headers: Authorization + MUT
```
Uses the **catalog** endpoint. MUT is still sent (for potential personalization / entitlement), but the content is public. `normaliseSong` is called on each track.

### Any other ID (user library playlist, e.g. `p.xxx`)
```
GET /v1/me/library/playlists/{id}/tracks
  params: { limit: 100, offset, include: "catalog" }
  headers: Authorization + MUT
```
Uses the **library** endpoint. `include: "catalog"` is critical here: library track items have `i.xxx` IDs and minimal metadata. The embedded catalog item in `relationships.catalog.data[0]` provides the real catalog ID and full attributes.

`normaliseLibrarySong` is used:
```ts
function normaliseLibrarySong(s: any) {
  const cat = s.relationships?.catalog?.data?.[0];
  if (cat) return normaliseSong(cat);   // use catalog item (real ID, full metadata)
  return normaliseSong(s);              // fallback: library item with i.xxx ID
}
```

When a catalog item is resolved, the song gets a proper catalog ID → stream route uses catalog endpoint. When it falls back to the library item, the ID stays as `i.xxx` → stream route handles the `i.` prefix separately via the library stream endpoint.

---

## Summary

| Flow | MUT required | Apple endpoint |
|---|---|---|
| Search | No | Catalog (via SDK) |
| Library songs/albums/artists | Yes (401 without) | `/v1/me/library/...` |
| Library playlists list | Yes | `/v1/me/library/playlists` |
| `pl.xxx` tracks | Yes (sent, not strictly required) | `/v1/catalog/{sf}/playlists/{id}/tracks` |
| User library playlist tracks | Yes | `/v1/me/library/playlists/{id}/tracks` |

## Key gotchas

- `include: "catalog"` is essential for library items — without it, artwork and real catalog IDs are missing
- Library song IDs (`i.xxx`) must never be swapped for catalog IDs — the stream route handles both, but they go to different Apple endpoints
- MUT is forwarded header-first (`X-Music-User-Token` from Android OkHttp → server `resolveMUT`) so the server always uses the most current token
- Bearer JWT is scraped at server startup; MUT must be set separately by the user via the phone web server (port 8080)
