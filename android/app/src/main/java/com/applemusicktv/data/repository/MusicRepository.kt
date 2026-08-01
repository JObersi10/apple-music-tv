package com.applemusicktv.data.repository

import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Artist
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.data.datasource.DirectLyricsSource
import com.applemusicktv.data.datasource.DirectMusicDataSource
import com.applemusicktv.data.network.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import retrofit2.HttpException
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
    private val direct: DirectMusicDataSource,
    private val directLyrics: DirectLyricsSource,
    private val standalonePrefs: com.applemusicktv.data.StandalonePreferences,
) {
    /**
     * Standalone means *everything* on device — browse, library, search, artwork and
     * lyrics all talk to Apple directly, not just playback. The proxy is used when it's
     * reachable and standalone is off; the server-down case falls through to the same
     * direct path, which is why it doubles as the offline fallback.
     */
    private val useProxy get() = serverPrefs.serverReachable && !standalonePrefs.isEnabled()

    private val _authErrorFlow = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val authErrorFlow: SharedFlow<Unit> = _authErrorFlow

    private suspend fun <T> apiCall(block: suspend () -> T): Result<T> =
        runCatching { block() }.onFailure { e ->
            if (e is HttpException && e.code() == 401) _authErrorFlow.tryEmit(Unit)
        }

    // ── Catalog ───────────────────────────────────────────────────────────
    suspend fun search(term: String, limit: Int = 20): Result<SearchResults> {
        if (!useProxy) {
            return direct.search(term, limit).map { r ->
                SearchResults(songs = r.songs.map(::songFromDto), albums = r.albums.map(::albumFromDto), artists = r.artists.map(::artistFromDto))
            }
        }
        return runCatching {
            val res = api.search(term, limit)
            SearchResults(songs = res.songs.map(::songFromDto), albums = res.albums.map(::albumFromDto), artists = res.artists.map(::artistFromDto))
        }
    }

    suspend fun getStationTracks(id: String) = apiCall { api.getStationTracks(id).songs.map(::songFromDto) }
    suspend fun getStationStream(id: String) = apiCall { api.getStationStream(id) }
    suspend fun getAlbum(id: String) =
        if (!useProxy) direct.album(id).map(::albumFromDto)
        else apiCall { albumFromDto(api.getAlbum(id)) }

    suspend fun getAlbumTracks(id: String) =
        if (!useProxy) direct.albumTracks(id).map { it.map(::songFromDto) }
        else apiCall { api.getAlbumTracks(id).tracks.map(::songFromDto) }

    // Apple has no "related albums" endpoint — the proxy derives the shelf from the
    // album's artist relationship, which AlbumDto doesn't carry. Standalone returns
    // nothing rather than guessing; the shelf just doesn't render.
    suspend fun getRelatedAlbums(id: String) =
        if (!useProxy) Result.success(emptyList<com.applemusicktv.data.model.Album>())
        else apiCall { api.getRelatedAlbums(id).albums.map(::albumFromDto) }

    suspend fun getSong(id: String) =
        if (!useProxy) direct.song(id).map(::songFromDto)
        else apiCall { songFromDto(api.getSong(id)) }

    suspend fun getArtist(id: String) =
        if (!useProxy) direct.artist(id).map(::artistFromDto)
        else apiCall { artistFromDto(api.getArtist(id)) }

    suspend fun getArtistFull(id: String) =
        if (!useProxy) direct.artistFull(id)
        else apiCall { api.getArtistFull(id) }

    suspend fun getArtistAlbums(id: String) =
        if (!useProxy) direct.artistAlbums(id).map { it.map(::albumFromDto) }
        else apiCall { api.getArtistAlbums(id).albums.map(::albumFromDto) }

    // ── Home ─────────────────────────────────────────────────────────────
    suspend fun getHome() = if (!useProxy) {
        // /me/recommendations is often empty, and an empty Listen Now looks broken.
        // Fall back to charts so the tab always has something in it.
        direct.recommendations().mapCatching { recs ->
            val sections = recs.ifEmpty { direct.charts().getOrDefault(emptyList()) }
            sectionsOf(sections)
        }
    } else runCatching { api.getHome() }

    private fun sectionsOf(pairs: List<Pair<String, List<com.applemusicktv.data.network.AlbumDto>>>) =
        com.applemusicktv.data.network.HomeResponse(
            sections = pairs.map { (title, albums) ->
                com.applemusicktv.data.network.HomeSection(title, albums)
            }
        )

    suspend fun getBrowse() =
        if (!useProxy) direct.charts().map(::sectionsOf) else runCatching { api.getBrowse() }
    suspend fun getGenres() =
        if (!useProxy) direct.genres() else runCatching { api.getGenres().genres }
    suspend fun getGenreContent(id: String) =
        if (!useProxy) direct.charts(genre = id).map(::sectionsOf)
        else runCatching { api.getGenreContent(id) }
    suspend fun getRelatedSongs(songId: String) = runCatching { api.getRelatedSongs(songId).songs.map(::songFromDto) }

    // ── Lyrics ────────────────────────────────────────────────────────────
    suspend fun getLyrics(songId: String, title: String = "", artist: String = "", durationSec: Long = 0) =
        if (!useProxy) runCatching {
            directLyrics.getLyrics(songId, direct.storefront, title, artist, durationSec)
        } else runCatching { api.getLyrics(songId).lines }

    suspend fun getMotion(songId: String) =
        if (!useProxy) direct.motion(songId) else runCatching { api.getMotion(songId).video }

    /** Probe whether the configured proxy server is reachable. */
    suspend fun pingServer(): Boolean =
        runCatching { api.health(); true }.getOrDefault(false)

    suspend fun getAppleStatus() = runCatching { api.appleStatus() }

    /** Pre-warm bearer token + storefront for standalone mode. */
    suspend fun prepareStandalone() {
        direct.detectStorefront()
    }

    // ── Full song stream URL ───────────────────────────────────────────────
    fun streamUrl(songId: String): String =
        "${serverPrefs.effectiveBaseUrl()}api/stream/$songId"

    fun prefetchUrl(songId: String): String =
        "${serverPrefs.effectiveBaseUrl()}api/stream/prefetch/$songId"

    fun serverBaseUrl(): String = serverPrefs.effectiveBaseUrl().trimEnd('/')

    // ── Library ───────────────────────────────────────────────────────────
    suspend fun getLibrarySongs(limit: Int = 25, offset: Int = 0) =
        if (!useProxy) direct.librarySongs().map { it.songs.map(::songFromDto) }
        else apiCall { api.getLibrarySongs(limit, offset).songs.map(::songFromDto) }

    suspend fun getLibraryAlbums(limit: Int = 25, offset: Int = 0) =
        if (!useProxy) direct.libraryAlbums().map { it.albums.map(::albumFromDto) }
        else apiCall { api.getLibraryAlbums(limit, offset).albums.map(::albumFromDto) }

    suspend fun getLibraryPlaylists(limit: Int = 25) =
        if (!useProxy) direct.libraryPlaylists().map { it.playlists }
        else apiCall { api.getLibraryPlaylists(limit).playlists }

    suspend fun getPlaylistTracks(id: String) =
        if (!useProxy) direct.playlistTracks(id).map { it.songs.map(::songFromDto) }
        else apiCall { api.getPlaylistTracks(id).songs.map(::songFromDto) }

    suspend fun getLibraryArtists(limit: Int = 25) =
        if (!useProxy) direct.libraryArtists().map { it.map(::artistFromDto) }
        else apiCall { api.getLibraryArtists(limit).artists.map(::artistFromDto) }

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
        artistId       = dto.artistId,
        albumId        = dto.albumId,
    )

    fun albumFromDto(dto: AlbumDto) = Album(
        id             = dto.id,
        title          = dto.title,
        artistName     = dto.artistName,
        artworkUrl     = dto.artworkUrl,
        type           = dto.type,
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
