package com.applemusicktv.data.datasource

import com.applemusicktv.data.network.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectMusicDataSource @Inject constructor(private val api: DirectAppleApi) {

    var storefront: String = "us"
        private set

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
        )
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
        api.libraryArtists(limit = 100).data.map { it.toArtistDto() }
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
        val albumId = song.relationships?.catalog?.data?.firstOrNull()?.id
            ?: return@runCatching null
        val raw = api.catalogAlbumWithMotion(storefront, albumId)
        val data = (raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return@runCatching null
        val attrs = data["attributes"] as? Map<*, *> ?: return@runCatching null
        val video = attrs["editorialVideo"] as? Map<*, *> ?: return@runCatching null
        val square = (video["motionSquareVideo1x1"] ?: video["motionDetailSquare"]) as? Map<*, *>
            ?: return@runCatching null
        ((square["video"] as? String))
    }

    suspend fun playlistTracks(id: String): Result<LibrarySongsResponse> = runCatching {
        val tracks = when {
            id.startsWith("p.") || id.startsWith("pl.") -> {
                api.catalogPlaylistTracks(storefront, id).data
            }
            else -> api.playlistTracks(id).data
        }
        LibrarySongsResponse(songs = tracks.map { it.toSongDto() })
    }
}
