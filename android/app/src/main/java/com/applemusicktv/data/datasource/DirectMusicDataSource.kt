package com.applemusicktv.data.datasource

import com.applemusicktv.data.network.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectMusicDataSource @Inject constructor(private val api: DirectAppleApi) {

    var storefront: String = "us"
        private set

    // ── Library writes (standalone path) ────────────────────────────────
    /** Add a catalog item to the user's library. type is "songs"/"albums"/"music-videos"/"playlists". */
    suspend fun addToLibrary(id: String, type: String): Result<Unit> = runCatching {
        val res = api.addToLibrary(mapOf("ids[$type]" to id))
        if (!res.isSuccessful) error("HTTP ${res.code()}")
    }

    /** Append a song to an editable library playlist. */
    suspend fun addToPlaylist(playlistId: String, songId: String, type: String = "songs"): Result<Unit> = runCatching {
        val res = api.addTracksToPlaylist(playlistId,
            com.applemusicktv.data.network.AddTracksBody(listOf(com.applemusicktv.data.network.AddTrackRef(songId, type))))
        if (!res.isSuccessful) error("HTTP ${res.code()}")
    }

    suspend fun detectStorefront() {
        runCatching {
            val sf = api.storefront().data.firstOrNull()?.id
            if (!sf.isNullOrEmpty()) storefront = sf
        }
    }

    suspend fun search(term: String, limit: Int = 20): Result<SearchResponse> = runCatching {
        val res = api.search(storefront, term, limit)
        SearchResponse(
            songs   = res.results.songs.data.map { it.toSongDto() },
            albums  = res.results.albums.data.map { it.toAlbumDto() },
            artists = res.results.artists.data.map { it.toArtistDto() },
            // Apple Music's own editorial playlists (Sports: F1, etc.) first.
            playlists = res.results.playlists.data
                .sortedByDescending { it.attributes?.playlistType == "editorial" }
                .map { it.toPlaylistDto() },
            curators = searchCurators(term),
        )
    }

    // ── Editorial categories (curators + multirooms) — standalone parity ──────
    private fun resolveArt(url: String?, size: Int = 400): String? = url
        ?.replace("{w}", "$size")?.replace("{h}", "$size")?.replace("{f}", "jpg")

    private fun edItemToAlbumDto(it: EdItem): AlbumDto? {
        val a = it.attributes ?: return null
        val url = a.artwork?.url ?: return null   // require artwork, same as the proxy
        return AlbumDto(
            id             = it.id,
            title          = a.name ?: "Unknown",
            artistName     = a.artistName ?: a.curatorName ?: "",
            artworkUrl     = url,
            artworkBgColor = a.artwork.bgColor,
            releaseDate    = null,
        )
    }

    /** Curators + multirooms, tagged by kind (multirooms first). Empty on any failure. */
    suspend fun searchCurators(term: String): List<CuratorDto> = runCatching {
        val res = api.edSearch(
            storefront, term,
            // editorial-items (multirooms) is only accepted alongside a broad type set.
            types = "artists,albums,songs,playlists,curators,apple-curators,music-videos,stations,editorial-items",
        )
        val multirooms = mutableListOf<CuratorDto>()
        val curators   = mutableListOf<CuratorDto>()
        val seen = HashSet<String>()
        val idRe = Regex("""multi-?room[s]?/(\d+)""")
        for (group in res.results.values) for (item in group.data) {
            val a = item.attributes ?: continue
            when (item.type) {
                "editorial-items" -> {
                    if (a.link?.feature != "multirooms") continue
                    val id = idRe.find(a.link.url ?: a.url ?: "")?.groupValues?.get(1) ?: continue
                    if (!seen.add("mr$id")) continue
                    multirooms += CuratorDto(
                        id = id, name = a.editorialNotes?.name ?: a.name ?: "Unknown",
                        kind = "multiroom", isApple = false,
                        artworkUrl = resolveArt(a.editorialArtwork?.subscriptionCover?.url ?: a.editorialArtwork?.brandLogo?.url),
                    )
                }
                "curators", "apple-curators" -> {
                    if (!seen.add(item.id)) continue
                    curators += CuratorDto(
                        id = item.id, name = a.name ?: "Unknown",
                        kind = if (item.type == "apple-curators") "apple-curator" else "curator",
                        isApple = item.type == "apple-curators",
                        artworkUrl = resolveArt(a.artwork?.url),
                    )
                }
            }
        }
        multirooms + curators
    }.getOrDefault(emptyList())

    // Genre/mood/decade grid — three editorial rooms of apple-curators. Mirrors the proxy.
    suspend fun getCategories(): List<CategorySectionDto> {
        val rooms = listOf("Genres" to "6456176470", "Moods & Activities" to "6456176472", "Decades" to "6456176471")
        val drop = Regex("rewind|replay|year in|wrapped", RegexOption.IGNORE_CASE)
        return rooms.mapNotNull { (title, room) ->
            val items = runCatching {
                api.edRoom(storefront, room).data.firstOrNull()?.relationships?.contents?.data.orEmpty()
                    .filter { it.type == "apple-curators" || it.type == "curators" }
                    .filter { !drop.containsMatchIn(it.attributes?.name ?: "") }
                    .map {
                        val a = it.attributes
                        val ea = a?.editorialArtwork
                        CuratorDto(
                            id = it.id,
                            name = (a?.name ?: "Unknown").replace(Regex("^Apple Music (?=\\S)"), "").replace(Regex("^Apple (?=\\S)"), ""),
                            kind = if (it.type == "apple-curators") "apple-curator" else "curator",
                            isApple = it.type == "apple-curators",
                            artworkUrl = resolveArt(ea?.subscriptionCover?.url ?: ea?.brandLogo?.url ?: a?.artwork?.url, 600),
                        )
                    }
            }.getOrDefault(emptyList())
            if (items.isEmpty()) null else CategorySectionDto(title, items)
        }
    }

    suspend fun getCurator(id: String, isApple: Boolean): MultiRoomDto {
        val kind = if (isApple) "apple-curators" else "curators"
        val cur = api.edCurator(storefront, kind, id).data.firstOrNull()
            ?: return MultiRoomDto(id = id)
        var sections = cur.relationships?.grouping?.data?.firstOrNull()?.id
            ?.let { groupingSections(it) } ?: emptyList()
        if (sections.isEmpty()) {
            val items = cur.relationships?.playlists?.data?.mapNotNull(::edItemToAlbumDto).orEmpty()
            if (items.isNotEmpty()) sections = listOf(HomeSection("Playlists", items))
        }
        val ea = cur.attributes?.editorialArtwork
        val hero = resolveArt(ea?.superHeroWide?.url ?: ea?.brandLogo?.url ?: cur.attributes?.artwork?.url, 1600)
        return MultiRoomDto(id = id, title = cur.attributes?.name ?: "", artworkUrl = hero, sections = sections)
    }

    private suspend fun groupingSections(groupingId: String): List<HomeSection> = runCatching {
        val tab = api.edGrouping(storefront, groupingId).data.firstOrNull()
            ?.relationships?.tabs?.data?.firstOrNull()
        (tab?.relationships?.children?.data ?: emptyList()).mapNotNull { k ->
            val kind = k.attributes?.editorialElementKind
            if (kind != "326" && kind != "345") return@mapNotNull null
            val title = k.attributes?.name ?: k.attributes?.title ?: return@mapNotNull null
            val albums = k.relationships?.contents?.data?.mapNotNull(::edItemToAlbumDto).orEmpty()
            if (albums.isEmpty()) null else HomeSection(title, albums)
        }
    }.getOrDefault(emptyList())

    // Multiroom hero blurb is skipped on the direct path (its description is a string|object
    // union that the typed parser can't take); shelves are what matter.
    /** A plain editorial ROOM — the "see all" page behind a Browse shelf. Its contents are a flat
     *  list (e.g. room 6503108310 "Daily Top 100" = 100 country playlists), so it renders as one
     *  section. This is what the "More" card at the end of a shelf opens. */
    suspend fun getRoom(id: String): MultiRoomDto {
        val room = api.edRoom(storefront, id).data.firstOrNull() ?: return MultiRoomDto(id = id)
        val items = room.relationships?.contents?.data?.mapNotNull(::edItemToAlbumDto).orEmpty()
        val title = room.attributes?.title ?: room.attributes?.name ?: ""
        return MultiRoomDto(
            id = id, title = title,
            sections = if (items.isEmpty()) emptyList() else listOf(HomeSection(title, items)),
        )
    }

    suspend fun getMultiRoom(id: String): MultiRoomDto {
        val room = api.edMultiRoom(storefront, id).data.firstOrNull()
            ?: return MultiRoomDto(id = id)
        val sections = (room.relationships?.children?.data ?: emptyList()).mapNotNull { k ->
            if (k.attributes?.editorialElementKind != "345") return@mapNotNull null
            val title = k.attributes.title ?: return@mapNotNull null
            val albums = k.relationships?.contents?.data?.mapNotNull(::edItemToAlbumDto).orEmpty()
            if (albums.isEmpty()) null else HomeSection(title, albums)
        }
        return MultiRoomDto(id = id, title = room.attributes?.title ?: "", sections = sections)
    }

    suspend fun librarySongs(): Result<LibrarySongsResponse> = runCatching {
        val all = mutableListOf<AppleItem<AppleSongAttrs>>()
        var offset = 0
        while (true) {
            val page = api.librarySongs(limit = 100, offset = offset)
            all += page.data
            if (page.next == null || page.data.isEmpty()) break
            offset += 100
            if (all.size >= 2000) break
        }
        LibrarySongsResponse(songs = all.map { it.toSongDto() })
    }

    suspend fun libraryAlbums(): Result<LibraryAlbumsResponse> = runCatching {
        val all = mutableListOf<AppleItem<AppleAlbumAttrs>>()
        var offset = 0
        while (true) {
            val page = api.libraryAlbums(limit = 100, offset = offset)
            all += page.data
            if (page.next == null || page.data.isEmpty()) break
            offset += 100
            if (all.size >= 2000) break
        }
        LibraryAlbumsResponse(albums = all.map { it.toAlbumDto() })
    }

    suspend fun libraryPlaylists(): Result<LibraryPlaylistsResponse> = runCatching {
        val page = api.libraryPlaylists(limit = 100)
        LibraryPlaylistsResponse(playlists = page.data.map { it.toPlaylistDto() })
    }

    suspend fun recommendations(): Result<List<Pair<String, List<AlbumDto>>>> = runCatching {
        val recs = api.recommendations()
        val sections = mutableListOf<Pair<String, List<AlbumDto>>>()
        for (rec in recs.data) {
            val title = rec.attributes?.title?.stringForDisplay?.takeIf { it.isNotEmpty() } ?: "For You"
            val items = rec.relationships?.contents?.data
                ?.mapNotNull { item -> item.takeIf { it.attributes != null }?.toAlbumDto() }
                ?: emptyList()
            if (items.isNotEmpty()) sections.add(title to items)
        }
        // Recently added
        val recent = api.recentlyAdded(20).data
            .mapNotNull { it.takeIf { i -> i.attributes != null }?.toAlbumDto() }
        if (recent.isNotEmpty()) sections.add("Recently Added" to recent)
        sections
    }

    /**
     * Library ids (l./r./i.) have to go through the library endpoints; everything else
     * is a catalog id. Where a library row carries a catalog relationship we prefer the
     * catalog copy — it has the richer metadata the detail screens expect.
     */
    private fun isLibraryId(id: String) =
        id.startsWith("l.") || id.startsWith("r.") || id.startsWith("i.") || id.startsWith("p.")

    private suspend fun catalogIdForAlbum(id: String): String? = runCatching {
        api.libraryAlbum(id).data.firstOrNull()?.relationships?.catalog?.data?.firstOrNull()?.id
    }.getOrNull()

    private suspend fun catalogIdForArtist(id: String): String? = runCatching {
        api.libraryArtist(id).data.firstOrNull()?.relationships?.catalog?.data?.firstOrNull()?.id
    }.getOrNull()

    private suspend fun catalogIdForSong(id: String): String? = runCatching {
        api.librarySong(id).data.firstOrNull()?.relationships?.catalog?.data?.firstOrNull()?.id
    }.getOrNull()

    suspend fun album(id: String): Result<AlbumDto> = runCatching {
        if (isLibraryId(id)) {
            val catId = catalogIdForAlbum(id)
            if (catId != null) return@runCatching api.catalogAlbum(storefront, catId).data.first().toAlbumDto()
            api.libraryAlbum(id).data.first().toAlbumDto()
        } else {
            api.catalogAlbum(storefront, id).data.first().toAlbumDto()
        }
    }

    suspend fun albumTracks(id: String): Result<List<SongDto>> = runCatching {
        if (isLibraryId(id)) {
            val catId = catalogIdForAlbum(id)
            if (catId != null) return@runCatching api.catalogAlbumTracks(storefront, catId).data.map { it.toSongDto() }
            api.libraryAlbumTracks(id).data.map { it.toSongDto() }
        } else {
            api.catalogAlbumTracks(storefront, id).data.map { it.toSongDto() }
        }
    }

    suspend fun song(id: String): Result<SongDto> = runCatching {
        if (isLibraryId(id)) {
            val catId = catalogIdForSong(id)
            if (catId != null) return@runCatching api.catalogSong(storefront, catId).data.first().toSongDto()
            api.librarySong(id).data.first().toSongDto()
        } else {
            api.catalogSong(storefront, id).data.first().toSongDto()
        }
    }

    suspend fun artist(id: String): Result<ArtistDto> = runCatching {
        val catId = if (isLibraryId(id)) catalogIdForArtist(id) else id
        api.catalogArtist(storefront, catId ?: id).data.first().toArtistDto()
    }

    /** Standalone autoplay: Apple's song radio needs a bearer we don't hold on-disk, so
     *  derive a comparable queue from the seed's artist + similar artists' top songs. */
    suspend fun relatedSongs(seedId: String): Result<List<SongDto>> = runCatching {
        val artistId = song(seedId).getOrNull()?.artistId ?: return@runCatching emptyList()
        val seed = artistFull(artistId).getOrNull() ?: return@runCatching emptyList()
        val pool = seed.topSongs.toMutableList()
        seed.similarArtists.take(3).forEach { sim ->
            artistFull(sim.id).getOrNull()?.let { pool += it.topSongs }
        }
        pool.distinctBy { it.id }.filter { it.id != seedId }.shuffled()
    }

    /** Top songs for a genre (Apple charts) → a shuffled genre station queue. */
    @Suppress("UNCHECKED_CAST")
    suspend fun genreStationSongs(genreId: String): Result<List<SongDto>> = runCatching {
        val raw = api.charts(storefront, types = "songs", limit = 50, genre = genreId)
        val results = raw["results"] as? Map<*, *> ?: return@runCatching emptyList()
        val songCharts = results["songs"] as? List<*> ?: return@runCatching emptyList()
        val out = mutableListOf<SongDto>()
        for (chart in songCharts) {
            val data = (chart as? Map<*, *>)?.get("data") as? List<*> ?: continue
            for (e in data) {
                val n = e as? Map<*, *> ?: continue
                val a = n["attributes"] as? Map<*, *> ?: continue
                val art = a["artwork"] as? Map<*, *>
                out += SongDto(
                    id = n["id"] as? String ?: continue,
                    title = a["name"] as? String ?: "",
                    artistName = a["artistName"] as? String ?: "",
                    albumName = a["albumName"] as? String ?: "",
                    durationMs = (a["durationInMillis"] as? Number)?.toLong() ?: 0L,
                    artworkUrl = art?.get("url") as? String,
                    artworkBgColor = art?.get("bgColor") as? String,
                    previewUrl = null,
                    previewHlsUrl = null,
                    hasLyrics = (a["hasLyrics"] as? Boolean) ?: false,
                )
            }
        }
        out.distinctBy { it.id }.shuffled()
    }

    /**
     * The artist page's payload, mapped from Apple's `views` into the same shape the
     * proxy returns so the screen doesn't care which path it came from.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun artistFull(id: String): Result<ArtistFullDto> = runCatching {
        val catId = (if (isLibraryId(id)) catalogIdForArtist(id) else id) ?: id
        val raw = api.catalogArtistFull(storefront, catId)
        val item = (raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: error("artist $catId not found")
        val attrs = item["attributes"] as? Map<*, *> ?: emptyMap<String, Any>()
        val views = item["views"] as? Map<*, *> ?: emptyMap<String, Any>()

        fun artwork(m: Map<*, *>?): String? =
            (m?.get("artwork") as? Map<*, *>)?.get("url") as? String

        fun songs(key: String): List<SongDto> =
            ((views[key] as? Map<*, *>)?.get("data") as? List<*>)?.mapNotNull { e ->
                val n = e as? Map<*, *> ?: return@mapNotNull null
                val a = n["attributes"] as? Map<*, *> ?: return@mapNotNull null
                SongDto(
                    id = n["id"] as? String ?: return@mapNotNull null,
                    title = a["name"] as? String ?: "",
                    artistName = a["artistName"] as? String ?: "",
                    albumName = a["albumName"] as? String ?: "",
                    durationMs = (a["durationInMillis"] as? Number)?.toLong() ?: 0L,
                    artworkUrl = artwork(a),
                    artworkBgColor = (a["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
                    previewUrl = ((a["previews"] as? List<*>)?.firstOrNull() as? Map<*, *>)
                        ?.get("url") as? String,
                    previewHlsUrl = null,
                    trackNumber = (a["trackNumber"] as? Number)?.toInt(),
                )
            } ?: emptyList()

        fun albums(key: String): List<AlbumDto> =
            ((views[key] as? Map<*, *>)?.get("data") as? List<*>)?.mapNotNull { e ->
                val n = e as? Map<*, *> ?: return@mapNotNull null
                val a = n["attributes"] as? Map<*, *> ?: return@mapNotNull null
                AlbumDto(
                    id = n["id"] as? String ?: return@mapNotNull null,
                    title = a["name"] as? String ?: "",
                    artistName = a["artistName"] as? String ?: "",
                    artworkUrl = artwork(a),
                    artworkBgColor = (a["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
                    releaseDate = a["releaseDate"] as? String,
                    trackCount = (a["trackCount"] as? Number)?.toInt() ?: 0,
                )
            } ?: emptyList()

        val similar = ((views["similar-artists"] as? Map<*, *>)?.get("data") as? List<*>)
            ?.mapNotNull { e ->
                val n = e as? Map<*, *> ?: return@mapNotNull null
                val a = n["attributes"] as? Map<*, *> ?: return@mapNotNull null
                SimilarArtistDto(
                    id = n["id"] as? String ?: return@mapNotNull null,
                    name = a["name"] as? String ?: "",
                    artworkUrl = artwork(a),
                )
            } ?: emptyList()

        ArtistFullDto(
            id = item["id"] as? String ?: catId,
            name = attrs["name"] as? String ?: "",
            artworkUrl = artwork(attrs),
            genreNames = (attrs["genreNames"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            editorialNotes = ((attrs["editorialNotes"] as? Map<*, *>)?.get("standard")
                ?: (attrs["editorialNotes"] as? Map<*, *>)?.get("short")) as? String,
            topSongs = songs("top-songs"),
            latestRelease = albums("latest-release").firstOrNull(),
            albums = albums("full-albums"),
            featuredAlbums = albums("featured-albums"),
            similarArtists = similar,
        )
    }

    suspend fun artistAlbums(id: String): Result<List<AlbumDto>> = runCatching {
        val catId = if (isLibraryId(id)) catalogIdForArtist(id) else id
        api.catalogArtistAlbums(storefront, catId ?: id).data.map { it.toAlbumDto() }
    }

    suspend fun libraryArtists(): Result<List<ArtistDto>> = runCatching {
        val libArtists = mutableListOf<AppleItem<AppleArtistAttrs>>()
        var offset = 0
        while (true) {
            val page = api.libraryArtists(limit = 100, offset = offset)
            libArtists += page.data
            if (page.next == null || page.data.isEmpty()) break
            offset += 100
            if (libArtists.size >= 1000) break
        }
        // Library artist objects carry no artwork — resolve it from the catalog artist
        // the library entry points at (batched, ~20 ids/request).
        val catIdByLib = libArtists.associate { it.id to it.relationships?.catalog?.data?.firstOrNull()?.id }
        val artworkByCatId = HashMap<String, String?>()
        catIdByLib.values.filterNotNull().distinct().chunked(20).forEach { chunk ->
            runCatching {
                api.catalogArtistsByIds(storefront, chunk.joinToString(",")).data.forEach { ca ->
                    artworkByCatId[ca.id] = ca.attributes?.artwork?.url
                }
            }
        }
        libArtists.map { item ->
            val dto = item.toArtistDto()
            if (dto.artworkUrl != null) return@map dto
            val art = catIdByLib[item.id]?.let { artworkByCatId[it] }
            if (art != null) dto.copy(artworkUrl = art) else dto
        }
    }

    /**
     * Charts, shaped like the proxy's Browse/genre payload. Apple nests these as
     * results.albums[] -> { name, data[] }, one entry per chart.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun charts(genre: String? = null): Result<List<Pair<String, List<AlbumDto>>>> = runCatching {
        val raw = api.charts(storefront, genre = genre)
        val results = raw["results"] as? Map<*, *> ?: return@runCatching emptyList()
        val out = mutableListOf<Pair<String, List<AlbumDto>>>()
        for (kind in listOf("albums", "playlists")) {
            val charts = results[kind] as? List<*> ?: continue
            for (chart in charts) {
                val c = chart as? Map<*, *> ?: continue
                val title = c["name"] as? String ?: continue
                val items = (c["data"] as? List<*>)?.mapNotNull { e ->
                    val n = e as? Map<*, *> ?: return@mapNotNull null
                    val a = n["attributes"] as? Map<*, *> ?: return@mapNotNull null
                    AlbumDto(
                        id = n["id"] as? String ?: return@mapNotNull null,
                        title = a["name"] as? String ?: "",
                        artistName = (a["artistName"] ?: a["curatorName"]) as? String ?: "",
                        artworkUrl = (a["artwork"] as? Map<*, *>)?.get("url") as? String,
                        type = if (kind == "playlists") "playlists" else "albums",
                        artworkBgColor = (a["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
                        releaseDate = a["releaseDate"] as? String,
                        trackCount = (a["trackCount"] as? Number)?.toInt() ?: 0,
                    )
                } ?: emptyList()
                if (items.isNotEmpty()) out.add(title to items)
            }
        }
        out
    }

    suspend fun genres(): Result<List<GenreDto>> = runCatching {
        api.catalogGenres(storefront).data.map { GenreDto(id = it.id, name = it.attributes?.name ?: "") }
    }

    /**
     * Motion artwork hangs off the album, not the song, so resolve song -> album first.
     * Returns null rather than failing — a missing loop is not an error.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun motion(songId: String): Result<String?> = runCatching {
        val catSongId = if (isLibraryId(songId)) catalogIdForSong(songId) ?: return@runCatching null else songId
        val song = api.catalogSong(storefront, catSongId).data.firstOrNull() ?: return@runCatching null
        val albumId = song.relationships?.albums?.data?.firstOrNull()?.id
            ?: song.relationships?.catalog?.data?.firstOrNull()?.id
            ?: return@runCatching null
        val raw = api.catalogAlbumWithMotion(storefront, albumId)
        val data = (raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return@runCatching null
        val attrs = data["attributes"] as? Map<*, *> ?: return@runCatching null
        val video = attrs["editorialVideo"] as? Map<*, *> ?: return@runCatching null
        val square = (video["motionSquareVideo1x1"] ?: video["motionDetailSquare"]) as? Map<*, *>
            ?: return@runCatching null
        ((square["video"] as? String))
    }

    /** Probe: log what a personalized station (ra.*) actually returns. */
    suspend fun probeStation(id: String): Result<String> = runCatching {
        val raw = api.catalogStation(storefront, id)
        val json = org.json.JSONObject(raw as Map<String, Any?>).toString()
        android.util.Log.i("StationProbe", "id=$id keys=${(raw["data"] as? List<*>)?.firstOrNull()?.let { (it as? Map<*,*>)?.keys }}")
        android.util.Log.i("StationProbe", "body=${json.take(1200)}")
        json
    }

    /** Motion artwork for an editorial playlist (pl.*). Null for user playlists. */
    @Suppress("UNCHECKED_CAST")
    suspend fun playlistMotion(playlistId: String): Result<String?> = runCatching {
        if (!playlistId.startsWith("pl.")) return@runCatching null
        val raw = api.catalogPlaylistWithMotion(storefront, playlistId)
        val data = (raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return@runCatching null
        val attrs = data["attributes"] as? Map<*, *> ?: return@runCatching null
        val video = attrs["editorialVideo"] as? Map<*, *> ?: return@runCatching null
        val square = (video["motionSquareVideo1x1"] ?: video["motionDetailSquare"]) as? Map<*, *>
            ?: return@runCatching null
        (square["video"] as? String)
    }

    suspend fun playlistTracks(id: String): Result<LibrarySongsResponse> = runCatching {
        // "pl.*" = catalog playlist; "p.*" (and everything else) = the user's library
        // playlist — routing library ids to the catalog endpoint 404'd them. Paginate
        // both so playlists longer than 100 tracks come back whole.
        val isCatalog = id.startsWith("pl.")
        val all = mutableListOf<AppleItem<AppleSongAttrs>>()
        var offset = 0
        while (true) {
            val page = if (isCatalog) api.catalogPlaylistTracks(storefront, id, limit = 100, offset = offset)
                       else api.playlistTracks(id, limit = 100, offset = offset)
            all += page.data
            if (page.next == null || page.data.isEmpty()) break
            offset += 100
            if (all.size >= 1000) break
        }
        LibrarySongsResponse(songs = all.map { it.toSongDto() })
    }
}
