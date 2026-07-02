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
