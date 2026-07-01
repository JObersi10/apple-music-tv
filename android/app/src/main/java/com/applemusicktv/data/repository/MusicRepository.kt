package com.applemusicktv.data.repository

import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Artist
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.data.network.*
import javax.inject.Inject
import javax.inject.Singleton

data class SearchResults(
    val songs:   List<Song>   = emptyList(),
    val albums:  List<Album>  = emptyList(),
    val artists: List<Artist> = emptyList(),
)

@Singleton
class MusicRepository @Inject constructor(
    private val api: ProxyApi,
    private val mutPrefs: MutPreferences,
    private val serverPrefs: ServerPreferences,
) {

    // ── Catalog ───────────────────────────────────────────────────────────
    suspend fun search(term: String, limit: Int = 20) = runCatching {
        val res = api.search(term, limit)
        SearchResults(songs = res.songs.map(::songFromDto), albums = res.albums.map(::albumFromDto), artists = res.artists.map(::artistFromDto))
    }

    suspend fun getAlbum(id: String)         = runCatching { albumFromDto(api.getAlbum(id)) }
    suspend fun getAlbumTracks(id: String)   = runCatching { api.getAlbumTracks(id).tracks.map(::songFromDto) }
    suspend fun getRelatedAlbums(id: String) = runCatching { api.getRelatedAlbums(id).albums.map(::albumFromDto) }
    suspend fun getSong(id: String)          = runCatching { songFromDto(api.getSong(id)) }
    suspend fun getArtist(id: String)        = runCatching { artistFromDto(api.getArtist(id)) }
    suspend fun getArtistFull(id: String)    = runCatching { api.getArtistFull(id) }
    suspend fun getArtistAlbums(id: String)  = runCatching { api.getArtistAlbums(id).albums.map(::albumFromDto) }

    // ── Lyrics ────────────────────────────────────────────────────────────
    suspend fun getLyrics(songId: String) = runCatching { api.getLyrics(songId).lines }
    suspend fun getMotion(songId: String) = runCatching { api.getMotion(songId).video }

    /** Probe whether the configured proxy server is reachable. */
    suspend fun pingServer(): Boolean =
        runCatching { api.health(); true }.getOrDefault(false)

    // ── Full song stream URL ───────────────────────────────────────────────
    fun streamUrl(songId: String): String =
        "${serverPrefs.effectiveBaseUrl()}api/stream/$songId"

    // ── Library ───────────────────────────────────────────────────────────
    suspend fun getLibrarySongs(limit: Int = 25, offset: Int = 0)  = runCatching { api.getLibrarySongs(limit, offset).songs.map(::songFromDto) }
    suspend fun getLibraryAlbums(limit: Int = 25, offset: Int = 0) = runCatching { api.getLibraryAlbums(limit, offset).albums.map(::albumFromDto) }
    suspend fun getLibraryPlaylists(limit: Int = 25)               = runCatching { api.getLibraryPlaylists(limit).playlists }
    suspend fun getPlaylistTracks(id: String)                      = runCatching { api.getPlaylistTracks(id).songs.map(::songFromDto) }
    suspend fun getLibraryArtists(limit: Int = 25)                 = runCatching { api.getLibraryArtists(limit).artists.map(::artistFromDto) }

    // ── Auth ──────────────────────────────────────────────────────────────
    suspend fun getAuthStatus() = api.getAuthStatus()
    suspend fun setMUT(token: String) {
        mutPrefs.setMUT(token)
        api.setMUT(mapOf("mut" to token))
    }
    suspend fun syncMUTToServer(token: String) = api.setMUT(mapOf("mut" to token))
    suspend fun clearMUT() {
        mutPrefs.setMUT("")
        api.clearMUT()
    }

    // ── Mappers ───────────────────────────────────────────────────────────
    fun songFromDto(dto: SongDto) = Song(
        id             = dto.id,
        title          = dto.title,
        artistName     = dto.artistName,
        albumName      = dto.albumName,
        durationMs     = dto.durationMs,
        artworkUrl     = dto.artworkUrl,
        artworkBgColor = dto.artworkBgColor,
        previewUrl     = dto.previewHlsUrl ?: dto.previewUrl,
        hasLyrics      = dto.hasLyrics,
        trackNumber    = dto.trackNumber,
        genreNames     = dto.genreNames,
    )

    fun albumFromDto(dto: AlbumDto) = Album(
        id             = dto.id,
        title          = dto.title,
        artistName     = dto.artistName,
        artworkUrl     = dto.artworkUrl,
        artworkBgColor = dto.artworkBgColor,
        releaseDate    = dto.releaseDate,
        trackCount     = dto.trackCount,
        genreNames     = dto.genreNames,
        recordLabel    = dto.recordLabel,
        copyright      = dto.copyright,
        editorialNotes = dto.editorialNotes,
    )

    fun artistFromDto(dto: ArtistDto) = Artist(
        id             = dto.id,
        name           = dto.name,
        artworkUrl     = dto.artworkUrl,
        genreNames     = dto.genreNames,
        editorialNotes = dto.editorialNotes,
    )
}
