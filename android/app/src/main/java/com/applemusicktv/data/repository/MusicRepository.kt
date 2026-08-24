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

/** An editorial curator (e.g. "Formula 1", "Tomorrowland") — opens a page of its playlists. */
data class Curator(
    val id:         String,
    val name:       String,
    val kind:       String,   // "multiroom" | "curator" | "apple-curator"
    val isApple:    Boolean,
    val artworkUrl: String?,
)

/** A titled row of category tiles (Genres, Moods & Activities, Decades). */
data class CategoryGroup(val title: String, val items: List<Curator>)

data class SearchResults(
    val songs:     List<Song>    = emptyList(),
    val albums:    List<Album>   = emptyList(),
    val artists:   List<Artist>  = emptyList(),
    val playlists: List<Album>   = emptyList(),
    val curators:  List<Curator> = emptyList(),
)

@Singleton
class MusicRepository @Inject constructor(
    private val api: ProxyApi,
    private val mutPrefs: MutPreferences,
    private val serverPrefs: ServerPreferences,
    private val direct: DirectMusicDataSource,
    private val directLyrics: DirectLyricsSource,
    private val directBrowse: com.applemusicktv.data.datasource.DirectBrowseSource,
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
                SearchResults(songs = r.songs.map(::songFromDto), albums = r.albums.map(::albumFromDto), artists = r.artists.map(::artistFromDto),
                    playlists = r.playlists.map(::playlistToAlbum).filter { it.id.startsWith("pl.") },
                    curators = r.curators.map { Curator(it.id, it.name, it.kind, it.isApple, it.artworkUrl) })
            }
        }
        return runCatching {
            val res = api.search(term, limit)
            // The proxy backend often doesn't surface catalog playlists — fall back to a
            // direct Apple catalog search for them so the Playlists row still populates.
            // Keep only Apple editorial/curated playlists (catalog id `pl.`); drop personal
            // user playlists (`p.`), which aren't what a catalog search is meant to show.
            val playlists = (res.playlists.map(::playlistToAlbum).ifEmpty {
                runCatching { direct.search(term, limit).getOrNull()?.playlists?.map(::playlistToAlbum) }
                    .getOrNull().orEmpty()
            }).filter { it.id.startsWith("pl.") }
            val curators = res.curators.map { Curator(it.id, it.name, it.kind, it.isApple, it.artworkUrl) }
            SearchResults(songs = res.songs.map(::songFromDto), albums = res.albums.map(::albumFromDto), artists = res.artists.map(::artistFromDto), playlists = playlists, curators = curators)
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
    suspend fun getHome() =
        if (!useProxy) runCatching { sectionsOf(directBrowse.home(mutPrefs.hasMUT())) }
        else runCatching { api.getHome() }

    private fun sectionsOf(pairs: List<Pair<String, List<com.applemusicktv.data.network.AlbumDto>>>) =
        com.applemusicktv.data.network.HomeResponse(
            sections = pairs.map { (title, albums) ->
                com.applemusicktv.data.network.HomeSection(title, albums)
            }
        )

    suspend fun getBrowse() =
        if (!useProxy) runCatching { com.applemusicktv.data.network.HomeResponse(directBrowse.browse()) }
        else runCatching { api.getBrowse() }
    /** Editorial "multiroom" category page (e.g. The Sounds of Formula 1). Proxy-only for now —
     *  standalone (direct) port is a follow-up. */
    // Curator page (playlists, or grouping tabs for rich apple-curators).
    suspend fun getCurator(id: String, isApple: Boolean) =
        if (!useProxy) runCatching { direct.getCurator(id, isApple) }
        else runCatching { api.getCurator(id, if (isApple) 1 else 0) }

    // Editorial multiroom page (hand-built shelves; hero blurb on proxy only).
    /** A plain editorial room — the "see all" page opened by a shelf's "More" card. */
    suspend fun getRoom(id: String) =
        if (!useProxy) runCatching { direct.getRoom(id) }
        else runCatching { api.getRoom(id) }

    suspend fun getMultiRoom(id: String) =
        if (!useProxy) runCatching { direct.getMultiRoom(id) }
        else runCatching { api.getMultiRoom(id) }

    // Genre/mood/decade tile grid (each tile is a curator → category page).
    suspend fun getCategories(): Result<List<CategoryGroup>> =
        if (!useProxy) runCatching { direct.getCategories().map { it.toGroup() } }
        else runCatching {
            api.getCategories().sections.map { s ->
                CategoryGroup(s.title, s.items.map { Curator(it.id, it.name, it.kind, it.isApple, it.artworkUrl) })
            }
        }

    private fun CategorySectionDto.toGroup() =
        CategoryGroup(title, items.map { Curator(it.id, it.name, it.kind, it.isApple, it.artworkUrl) })

    suspend fun getGenres() =
        if (!useProxy) direct.genres().map { g -> g.filter { it.name.isNotEmpty() && it.id != "34" } }
        else runCatching { api.getGenres().genres }
    suspend fun getGenreContent(id: String) =
        if (!useProxy) runCatching { sectionsOf(directBrowse.genreContent(id)) }
        else runCatching { api.getGenreContent(id) }
    suspend fun getRelatedSongs(songId: String) =
        if (!useProxy) direct.relatedSongs(songId).map { it.map(::songFromDto) }
        else runCatching { api.getRelatedSongs(songId).songs.map(::songFromDto) }

    suspend fun getGenreStation(genreId: String) =
        if (!useProxy) direct.genreStationSongs(genreId).map { it.map(::songFromDto) }
        else runCatching { emptyList<Song>() }

    // ── Lyrics ────────────────────────────────────────────────────────────
    suspend fun getLyrics(songId: String, title: String = "", artist: String = "", durationSec: Long = 0) =
        if (!useProxy) runCatching {
            directLyrics.getLyrics(songId, direct.storefront, title, artist, durationSec)
        } else runCatching { api.getLyrics(songId).lines }

    /** Warm the lyrics cache for an upcoming song so it shows instantly on switch. */
    suspend fun prefetchLyrics(songId: String, title: String, artist: String, durationSec: Long) {
        if (useProxy) return
        runCatching { directLyrics.getLyrics(songId, direct.storefront, title, artist, durationSec) }
    }

    suspend fun getMotion(songId: String) =
        if (!useProxy) direct.motion(songId) else runCatching { api.getMotion(songId).video }

    /** Probe a personalized ra.* station's raw payload (standalone only). */
    suspend fun probeStation(id: String): Result<String> =
        if (!useProxy) direct.probeStation(id) else Result.success("")

    /** Animated cover for an editorial playlist; null in proxy mode / user playlists. */
    suspend fun getPlaylistMotion(playlistId: String): Result<String?> =
        if (!useProxy) direct.playlistMotion(playlistId) else Result.success(null)

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
        type           = dto.type,
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
        motionUrl      = dto.motionUrl,
    )

    /** Search playlists render as album-style cards; the "pl.*" id routes to the playlist screen. */
    private fun playlistToAlbum(dto: PlaylistDto) = Album(
        id             = dto.id,
        title          = dto.name,
        artistName     = dto.curatorName,
        artworkUrl     = dto.artworkUrl,
        artworkBgColor = dto.artworkBgColor,
    )

    fun artistFromDto(dto: ArtistDto) = Artist(
        id             = dto.id,
        name           = dto.name,
        artworkUrl     = dto.artworkUrl,
        genreNames     = dto.genreNames,
        editorialNotes = dto.editorialNotes,
    )
}
