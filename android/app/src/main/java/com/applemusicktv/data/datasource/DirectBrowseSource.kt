package com.applemusicktv.data.datasource

import com.applemusicktv.data.network.AlbumDto
import com.applemusicktv.data.network.DirectAppleApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device port of the proxy's `home.ts` and `browse.ts`. Section titles, ordering
 * and filtering are kept identical so the tabs look the same whichever path serves
 * them — that's the whole point of this file existing separately from the typed
 * data source.
 *
 * Everything is parsed out of raw maps: Apple returns a different attribute shape per
 * resource type here (albums vs playlists vs songs), and modelling all of them as
 * DTOs buys nothing when the screens only need the album-card fields.
 */
@Singleton
class DirectBrowseSource @Inject constructor(
    private val api: DirectAppleApi,
    private val direct: DirectMusicDataSource,
) {
    private val sf get() = direct.storefront

    private fun art(raw: String?, size: Int = 500): String? =
        raw?.replace("{w}", "$size")?.replace("{h}", "$size")?.replace("{f}", "jpg")

    /** The proxy drops anything without artwork — a card with no image looks broken. */
    private fun itemFromRaw(item: Map<*, *>): AlbumDto? {
        val attrs = item["attributes"] as? Map<*, *> ?: return null
        val url = art((attrs["artwork"] as? Map<*, *>)?.get("url") as? String) ?: return null
        return AlbumDto(
            id = item["id"] as? String ?: return null,
            title = (attrs["name"] ?: "Unknown") as? String ?: "Unknown",
            artistName = (attrs["artistName"] ?: attrs["curatorName"] ?: "") as? String ?: "",
            artworkUrl = url,
            type = item["type"] as? String ?: "albums",
            artworkBgColor = (attrs["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
            releaseDate = attrs["releaseDate"] as? String,
            trackCount = (attrs["trackCount"] as? Number)?.toInt() ?: 0,
            genreNames = (attrs["genreNames"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        )
    }

    private fun listOfItems(raw: Any?): List<AlbumDto> =
        (raw as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::itemFromRaw) } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun chart(raw: Map<String, Any>, kind: String): Pair<String, List<AlbumDto>>? {
        val results = raw["results"] as? Map<*, *> ?: return null
        val first = (results[kind] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
        val items = listOfItems(first["data"])
        if (items.isEmpty()) return null
        return (first["name"] as? String ?: kind.replaceFirstChar { it.uppercase() }) to items
    }

    /** Mirrors home.ts: recommendations → recently played → charts → recently added. */
    suspend fun home(hasMut: Boolean): List<Pair<String, List<AlbumDto>>> {
        val sections = mutableListOf<Pair<String, List<AlbumDto>>>()

        // The personalized recommendations feed IS the signed-in Home page. Emit each recommendation
        // as its own titled section in Apple's order ("Playlists Made for You", "Recently Played",
        // genre essentials, "More from <artist>", "New Releases for You"…). Stations skipped until
        // playback is wired. Mirrors home.ts exactly.
        if (hasMut) {
            runCatching {
                val raw = api.recommendationsRaw(limit = 25)
                val recs = (raw["data"] as? List<*>).orEmpty()
                android.util.Log.i("AMHome", "recommendations recs=${recs.size}")
                for (rec in recs) {
                    val r = rec as? Map<*, *> ?: continue
                    val attrs = r["attributes"] as? Map<*, *> ?: emptyMap<String, Any>()
                    val title = ((attrs["title"] as? Map<*, *>)?.get("stringForDisplay") as? String)
                        ?: (attrs["title"] as? String) ?: "For You"
                    val contents = ((r["relationships"] as? Map<*, *>)?.get("contents") as? Map<*, *>)?.get("data")
                    val items = (contents as? List<*>).orEmpty()
                        .mapNotNull { it as? Map<*, *> }
                        .mapNotNull(::itemFromRaw)
                    if (items.isNotEmpty()) {
                        // ONLY the "Playlists Made for You" shelf animates (Get Up!/Chill/Your
                        // Essentials…). Apple ships motion art for lots of things, but animating every
                        // row would be noise — and each card costs an extra request + a video decoder.
                        val withMotion = if (title.startsWith("Playlists Made for You", ignoreCase = true))
                            items.map { it.copy(motionUrl = motionForPlaylist(it.id)) } else items
                        sections += title to withMotion
                    }
                }
                android.util.Log.i("AMHome", "recommendation sections=${sections.size}")
            }.onFailure { android.util.Log.w("AMHome", "recommendations failed: ${it.message}") }
        }

        // "Find Your Mood" — Apple's Moods & Activities editorial room, same shelf the web Home
        // shows under the personalized feed. Cards carry the CategoryScreen id prefix ("ac-"/"c-")
        // so tapping one opens that category page, exactly like the Search "Categories" row.
        runCatching {
            direct.getCategories().firstOrNull { it.title.startsWith("Moods", ignoreCase = true) }
                ?.let { sec ->
                    val items = sec.items.mapNotNull { cur ->
                        val art = cur.artworkUrl ?: return@mapNotNull null
                        AlbumDto(
                            id = (if (cur.isApple) "ac-" else "c-") + cur.id,
                            title = cur.name, artistName = "", artworkUrl = art,
                            type = "curators", artworkBgColor = null,
                            releaseDate = null, trackCount = 0,
                        )
                    }
                    if (items.isNotEmpty()) sections += "Find Your Mood" to items
                }
        }.onFailure { android.util.Log.w("AMHome", "moods failed: ${it.message}") }

        // Fallback for logged-out / thin feed: charts so Home is never empty.
        if (sections.size < 2) {
            runCatching { chart(api.charts(sf, types = "albums", limit = 20), "albums")?.let { sections += it } }
            runCatching { chart(api.charts(sf, types = "playlists", limit = 20), "playlists")?.let { sections += it } }
        }
        return demoteMadeForYou(sections)
    }

    /** Apple puts "Playlists Made for You" near the top; the user wants it lower. Pull any such
     *  shelf down to ~4th position so the fresher personalized rows lead. */
    private fun demoteMadeForYou(
        sections: List<Pair<String, List<AlbumDto>>>,
    ): List<Pair<String, List<AlbumDto>>> {
        val made = sections.filter { it.first.startsWith("Playlists Made for You", ignoreCase = true) }
        if (made.isEmpty()) return sections
        val rest = sections.filterNot { it.first.startsWith("Playlists Made for You", ignoreCase = true) }.toMutableList()
        val at = minOf(3, rest.size)
        rest.addAll(at, made)
        return rest
    }

    /** Square motion-artwork loop for a playlist, or null. Apple exposes it as
     *  `attributes.editorialVideo.motionSquareVideo1x1.video` — a plain (unencrypted) mvod HLS URL,
     *  the same kind the Now Playing motion cover already plays. */
    private suspend fun motionForPlaylist(id: String): String? = runCatching {
        val raw = api.catalogPlaylistWithMotion(sf, id)
        val attrs = ((raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("attributes") as? Map<*, *>
        val ev = attrs?.get("editorialVideo") as? Map<*, *> ?: return null
        val pick = (ev["motionSquareVideo1x1"] ?: ev["motionDetailSquare"]) as? Map<*, *>
        pick?.get("video") as? String
    }.getOrNull()

    /** A song rendered as a card — type "songs" so BrowseRow routes it to playback, not detail. */
    private fun songCard(m: Map<*, *>): AlbumDto? {
        val attrs = m["attributes"] as? Map<*, *> ?: return null
        val url = art((attrs["artwork"] as? Map<*, *>)?.get("url") as? String) ?: return null
        return AlbumDto(
            id = m["id"] as? String ?: return null,
            title = (attrs["name"] ?: "Unknown") as? String ?: "Unknown",
            artistName = (attrs["artistName"] ?: "") as? String ?: "",
            artworkUrl = url,
            type = "songs",
            artworkBgColor = (attrs["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
            releaseDate = null, trackCount = 0,
        )
    }

    /** A music-video rendered as a SongDto so the Browse video row plays it in the video player. */
    private fun videoCard(m: Map<*, *>): com.applemusicktv.data.network.SongDto? {
        val attrs = m["attributes"] as? Map<*, *> ?: return null
        val url = art((attrs["artwork"] as? Map<*, *>)?.get("url") as? String) ?: return null
        val rel = m["relationships"] as? Map<*, *>
        fun relId(k: String) = (((rel?.get(k) as? Map<*, *>)?.get("data") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("id") as? String
        return com.applemusicktv.data.network.SongDto(
            id = m["id"] as? String ?: return null,
            type = "music-videos",
            title = (attrs["name"] ?: "Unknown") as? String ?: "Unknown",
            artistName = (attrs["artistName"] ?: "") as? String ?: "",
            artistId = relId("artists"),
            albumId = relId("albums"),
            albumName = (attrs["albumName"] ?: "") as? String ?: "",
            durationMs = (attrs["durationInMillis"] as? Number)?.toLong() ?: 0L,
            artworkUrl = url,
            artworkBgColor = (attrs["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
            previewUrl = null, previewHlsUrl = null,
        )
    }

    /** The real music.apple.com Browse/New page — one editorial grouping (name="music") whose default
     *  tab holds every shelf in Apple's order, personalized by the MUT. Mirrors browse.ts exactly. */
    @Suppress("UNCHECKED_CAST")
    suspend fun browse(): List<com.applemusicktv.data.network.HomeSection> {
        val sections = mutableListOf<com.applemusicktv.data.network.HomeSection>()
        val dropTitle = Regex("watch interviews|live radio|radio episode|radio now", RegexOption.IGNORE_CASE)
        runCatching {
            val raw = api.editorialGrouping(sf)
            val tab = (((raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *>)
                ?.get("relationships") as? Map<*, *>)?.let { it["tabs"] as? Map<*, *> }
                ?.get("data")?.let { (it as? List<*>)?.firstOrNull() as? Map<*, *> }
            val kids = ((tab?.get("relationships") as? Map<*, *>)?.get("children") as? Map<*, *>)
                ?.get("data") as? List<*> ?: emptyList<Any>()
            for (k in kids) {
                val kk = k as? Map<*, *> ?: continue
                val attrs = kk["attributes"] as? Map<*, *> ?: continue
                val kind = attrs["editorialElementKind"] as? String
                if (kind != "326" && kind != "327") continue
                val title = (attrs["name"] ?: attrs["title"] ?: "") as? String ?: ""
                if (title.isEmpty() || dropTitle.containsMatchIn(title)) continue
                val contents = ((kk["relationships"] as? Map<*, *>)?.get("contents") as? Map<*, *>)
                    ?.get("data") as? List<*> ?: continue
                val maps = contents.mapNotNull { it as? Map<*, *> }
                if (maps.isEmpty()) continue
                val types = maps.mapNotNull { it["type"] as? String }.toSet()
                if (types.contains("stations") || types.contains("uploaded-videos")) continue
                // The editorial-element's own id IS its room id (verified: "Daily Top 100" -> 6503108310,
                // the same id as music.apple.com/us/room/6503108310). Carried so the row can end in a
                // "More" card that opens the full room.
                val roomId = kk["id"] as? String
                if (types.isNotEmpty() && types.all { it == "music-videos" }) {
                    val videos = maps.mapNotNull(::videoCard)
                    if (videos.isNotEmpty()) sections += com.applemusicktv.data.network.HomeSection(title, videos = videos, roomId = roomId)
                    continue
                }
                val albums = maps.mapNotNull { m ->
                    when (m["type"] as? String) {
                        "songs", "music-videos" -> songCard(m)
                        else -> itemFromRaw(m)   // albums + playlists (playlist id prefix routes correctly)
                    }
                }
                if (albums.isNotEmpty()) sections += com.applemusicktv.data.network.HomeSection(title, albums, roomId = roomId)
            }
        }
        // Fallback: charts, so the tab is never blank if the editorial page fails.
        if (sections.isEmpty()) {
            runCatching { chart(api.charts(sf, types = "songs", limit = 20), "songs")?.let { sections += com.applemusicktv.data.network.HomeSection(it.first, it.second) } }
            runCatching { chart(api.charts(sf, types = "albums", limit = 20), "albums")?.let { sections += com.applemusicktv.data.network.HomeSection("New Releases", it.second) } }
            runCatching { chart(api.charts(sf, types = "playlists", limit = 20), "playlists")?.let { sections += com.applemusicktv.data.network.HomeSection(it.first, it.second) } }
        }
        return sections
    }

    /** Mirrors browse.ts `/genres/:id`. */
    @Suppress("UNCHECKED_CAST")
    suspend fun genreContent(id: String): List<Pair<String, List<AlbumDto>>> {
        val sections = mutableListOf<Pair<String, List<AlbumDto>>>()
        runCatching {
            val raw = api.charts(sf, types = "playlists,albums", limit = 20, genre = id)
            val results = raw["results"] as? Map<*, *>
            val pl = listOfItems(((results?.get("playlists") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("data"))
            val al = listOfItems(((results?.get("albums") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("data"))
            if (pl.isNotEmpty()) sections += "Top Playlists" to pl
            if (al.isNotEmpty()) sections += "Top Albums" to al
        }
        return sections
    }
}
