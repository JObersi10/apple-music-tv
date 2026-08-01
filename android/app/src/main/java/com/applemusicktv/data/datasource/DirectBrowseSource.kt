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

        if (hasMut) {
            runCatching {
                val raw = api.recommendationsRaw()
                val topPicks = mutableListOf<AlbumDto>()
                val genreSections = linkedMapOf<String, List<AlbumDto>>()
                for (rec in (raw["data"] as? List<*>).orEmpty()) {
                    val r = rec as? Map<*, *> ?: continue
                    val attrs = r["attributes"] as? Map<*, *> ?: emptyMap<String, Any>()
                    val title = ((attrs["title"] as? Map<*, *>)?.get("stringForDisplay") as? String)
                        ?: "For You"
                    val recType = (attrs["resourceTypes"] as? List<*>)?.firstOrNull() as? String ?: ""
                    // Stations aren't playable through this app's queue.
                    if (recType == "stations" || title.lowercase().contains("station")) continue
                    val contents = ((r["relationships"] as? Map<*, *>)?.get("contents") as? Map<*, *>)
                        ?.get("data")
                    val items = (contents as? List<*>).orEmpty()
                        .mapNotNull { it as? Map<*, *> }
                        .filter { it["type"] != "stations" }
                        .mapNotNull(::itemFromRaw)
                    if (items.isEmpty()) continue
                    if (title.lowercase().contains("genre") || attrs["kind"] == "genre-mix") {
                        genreSections[title] = items
                    } else {
                        topPicks += items
                    }
                }
                if (topPicks.isNotEmpty()) sections += "Top Picks for You" to topPicks
                genreSections.forEach { (t, i) -> sections += t to i }
            }

            runCatching {
                val items = listOfItems((api.recentPlayed()["data"]))
                if (items.isNotEmpty()) sections += "Recently Played" to items
            }
        }

        runCatching {
            chart(api.charts(sf, types = "albums", limit = 20), "albums")
                ?.let { sections += it }
        }
        runCatching {
            chart(api.charts(sf, types = "playlists", limit = 20), "playlists")
                ?.let { sections += it }
        }

        if (hasMut) {
            runCatching {
                val items = api.recentlyAdded(20).data
                    .filter { it.attributes != null }
                    .mapNotNull { item ->
                        val a = item.attributes ?: return@mapNotNull null
                        val url = a.artwork?.resolved() ?: return@mapNotNull null
                        AlbumDto(
                            id = item.id, title = a.name, artistName = a.artistName,
                            artworkUrl = url, artworkBgColor = a.artwork?.bgColor,
                            releaseDate = a.releaseDate, trackCount = a.trackCount,
                            genreNames = a.genreNames,
                        )
                    }
                if (items.isNotEmpty()) sections += "Recently Added" to items
            }
        }
        return sections
    }

    /** The editorial shelves browse.ts falls back to when groupings is thin. */
    private val editorialQueries = listOf(
        "Apple Music Live" to "apple music live concert",
        "Artists Take Over" to "artists take over apple music",
        "In Studio Performances" to "in studio performance apple music",
        "Best Club DJ Mixes" to "club dj mix apple music",
        "Updated Playlists" to "apple music editors playlist updated",
    )

    /** Mirrors browse.ts, including the Daily Top 100 split and editorial searches. */
    @Suppress("UNCHECKED_CAST")
    suspend fun browse(): List<Pair<String, List<AlbumDto>>> {
        val sections = mutableListOf<Pair<String, List<AlbumDto>>>()

        runCatching {
            chart(api.charts(sf, types = "songs", limit = 20), "songs")?.let { sections += it }
        }

        runCatching {
            val raw = api.charts(sf, types = "playlists", limit = 30)
            val results = raw["results"] as? Map<*, *>
            val first = (results?.get("playlists") as? List<*>)?.firstOrNull() as? Map<*, *>
            val daily = mutableListOf<AlbumDto>()
            val other = mutableListOf<AlbumDto>()
            for (e in (first?.get("data") as? List<*>).orEmpty()) {
                val m = e as? Map<*, *> ?: continue
                val dto = itemFromRaw(m) ?: continue
                val name = ((m["attributes"] as? Map<*, *>)?.get("name") as? String ?: "").lowercase()
                if (name.contains("daily top 100") || name.contains("top 100")) daily += dto else other += dto
            }
            if (daily.isNotEmpty()) sections += "Daily Top 100" to daily
            if (other.isNotEmpty()) sections += (first?.get("name") as? String ?: "Top Playlists") to other
        }

        runCatching {
            chart(api.charts(sf, types = "albums", limit = 20), "albums")
                ?.let { sections += "New Releases" to it.second }
        }

        runCatching {
            val raw = api.groupings(sf)
            val grouping = (raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val contents = ((grouping?.get("relationships") as? Map<*, *>)?.get("contents") as? Map<*, *>)
                ?.get("data")
            val items = listOfItems(contents)
            if (items.isNotEmpty()) sections += "Featured on Apple Music" to items
        }

        for ((title, term) in editorialQueries) {
            runCatching {
                val raw = api.searchRaw(sf, term, types = "playlists", limit = 10)
                val data = ((raw["results"] as? Map<*, *>)?.get("playlists") as? Map<*, *>)?.get("data")
                val items = (data as? List<*>).orEmpty()
                    .mapNotNull { it as? Map<*, *> }
                    // Only Apple's own editorial playlists — a plain term search
                    // otherwise returns user playlists with the same words in them.
                    .filter { m ->
                        val a = m["attributes"] as? Map<*, *> ?: return@filter false
                        val name = (a["name"] as? String ?: "").lowercase()
                        val curator = (a["curatorName"] as? String ?: "").lowercase()
                        curator.contains("apple music") || name.contains("apple music")
                    }
                    .mapNotNull(::itemFromRaw)
                    .take(8)
                if (items.isNotEmpty()) sections += title to items
            }
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
