package com.applemusicktv.data.network

import com.squareup.moshi.JsonClass
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class SongDto(
    val id:             String,
    val title:          String,
    val artistName:     String,
    val artistId:       String? = null,
    val albumId:        String? = null,
    val albumName:      String,
    val durationMs:     Long,
    val artworkUrl:     String?,
    val artworkBgColor: String?,
    val previewUrl:     String?,
    val previewHlsUrl:  String?,
    val hasLyrics:      Boolean      = false,
    val trackNumber:    Int?         = null,
    val genreNames:     List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AlbumDto(
    val id:             String,
    val title:          String,
    val artistName:     String,
    val artworkUrl:     String?,
    val artworkBgColor: String?,
    val releaseDate:    String?,
    val trackCount:     Int          = 0,
    val genreNames:     List<String> = emptyList(),
    val recordLabel:    String?      = null,
    val copyright:      String?      = null,
    val editorialNotes: String?      = null,
    val isMasteredForItunes: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ArtistDto(
    val id:             String,
    val name:           String,
    val artworkUrl:     String?,
    val genreNames:     List<String> = emptyList(),
    val editorialNotes: String?      = null,
)

@JsonClass(generateAdapter = true)
data class SimilarArtistDto(
    val id:         String,
    val name:       String,
    val artworkUrl: String?,
)

@JsonClass(generateAdapter = true)
data class ArtistFullDto(
    val id:             String,
    val name:           String,
    val artworkUrl:     String?,
    val genreNames:     List<String>          = emptyList(),
    val editorialNotes: String?               = null,
    val topSongs:       List<SongDto>         = emptyList(),
    val latestRelease:  AlbumDto?             = null,
    val albums:         List<AlbumDto>        = emptyList(),
    val featuredAlbums: List<AlbumDto>        = emptyList(),
    val similarArtists: List<SimilarArtistDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class PlaylistDto(
    val id:             String,
    val name:           String,
    val curatorName:    String,
    val artworkUrl:     String?,
    val artworkBgColor: String?,
    val description:    String?,
    val playlistType:   String? = null,
) {
    fun artworkUrl(size: Int) = artworkUrl
        ?.replace("{w}", "$size")
        ?.replace("{h}", "$size")
        ?.replace("{f}", "jpg")
}

@JsonClass(generateAdapter = true)
data class SearchResponse(
    val songs:     List<SongDto>     = emptyList(),
    val albums:    List<AlbumDto>    = emptyList(),
    val artists:   List<ArtistDto>   = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TracksResponse(val tracks: List<SongDto>, val next: String? = null)

@JsonClass(generateAdapter = true)
data class AlbumsResponse(val albums: List<AlbumDto> = emptyList())

@JsonClass(generateAdapter = true)
data class SongsResponse(val songs: List<SongDto> = emptyList())

@JsonClass(generateAdapter = true)
data class StationStreamResponse(
    val liveStreamUrl: String? = null,
    val drmKeyUri:     String? = null,
    val adamId:        String? = null,
    val isLive:        Boolean = true,
)

@JsonClass(generateAdapter = true)
data class LibrarySongsResponse(val songs: List<SongDto> = emptyList())

@JsonClass(generateAdapter = true)
data class LibraryAlbumsResponse(val albums: List<AlbumDto> = emptyList())

@JsonClass(generateAdapter = true)
data class LibraryPlaylistsResponse(val playlists: List<PlaylistDto> = emptyList())

@JsonClass(generateAdapter = true)
data class LibraryArtistsResponse(val artists: List<ArtistDto> = emptyList())

@JsonClass(generateAdapter = true)
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

@JsonClass(generateAdapter = true)
data class LyricBackground(
    val startMs: Long,
    val endMs:   Long = 0L,
    val text:    String,
    val words:   List<LyricWord> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class LyricLine(
    val startMs:    Long,
    val endMs:      Long = 0L,
    val text:       String,
    val words:      List<LyricWord> = emptyList(),
    val background: LyricBackground? = null,
)

@JsonClass(generateAdapter = true)
data class LyricsResponse(val lines: List<LyricLine> = emptyList(), val source: String? = null)

@JsonClass(generateAdapter = true)
data class MotionResponse(val video: String? = null)

@JsonClass(generateAdapter = true)
data class GenreDto(val id: String, val name: String)

@JsonClass(generateAdapter = true)
data class GenresResponse(val genres: List<GenreDto> = emptyList())

@JsonClass(generateAdapter = true)
data class HomeSection(val title: String, val albums: List<AlbumDto> = emptyList())

@JsonClass(generateAdapter = true)
data class HomeResponse(val sections: List<HomeSection> = emptyList())

@JsonClass(generateAdapter = true)
data class AuthStatus(
    val hasMUT:     Boolean,
    val mutSetAt:   String?,
    val hasBearer:  Boolean,
)

interface ProxyApi {
    // ── Catalog ───────────────────────────────────────────────────────────
    @GET("api/search")
    suspend fun search(
        @Query("term")  term:  String,
        @Query("limit") limit: Int    = 20,
        @Query("types") types: String = "songs,albums,artists",
    ): SearchResponse

    @GET("api/albums/{id}")
    suspend fun getAlbum(@Path("id") id: String): AlbumDto

    @GET("api/albums/{id}/tracks")
    suspend fun getAlbumTracks(@Path("id") id: String, @Query("limit") limit: Int = 50): TracksResponse

    @GET("api/albums/{id}/related")
    suspend fun getRelatedAlbums(@Path("id") id: String): AlbumsResponse

    @GET("api/albums/station/{id}/tracks")
    suspend fun getStationTracks(@Path("id") id: String): SongsResponse

    @GET("api/albums/station/{id}/stream")
    suspend fun getStationStream(@Path("id") id: String): StationStreamResponse

    @GET("api/songs/{id}")
    suspend fun getSong(@Path("id") id: String): SongDto

    @GET("api/songs")
    suspend fun getSongs(@Query("ids") ids: String): SongsResponse

    @GET("api/artists/{id}")
    suspend fun getArtist(@Path("id") id: String): ArtistDto

    @GET("api/artists/{id}/albums")
    suspend fun getArtistAlbums(@Path("id") id: String, @Query("limit") limit: Int = 25): AlbumsResponse

    @GET("api/artists/{id}/full")
    suspend fun getArtistFull(@Path("id") id: String): ArtistFullDto

    // ── Home / Listen Now ─────────────────────────────────────────────────
    @GET("api/home")
    suspend fun getHome(): HomeResponse

    @GET("api/browse")
    suspend fun getBrowse(): HomeResponse

    @GET("api/browse/genres")
    suspend fun getGenres(): GenresResponse

    @GET("api/browse/genres/{id}")
    suspend fun getGenreContent(@Path("id") id: String): HomeResponse

    @GET("api/songs/{id}/related")
    suspend fun getRelatedSongs(@Path("id") id: String): SongsResponse

    // ── Lyrics ────────────────────────────────────────────────────────────
    @GET("api/lyrics/{id}")
    suspend fun getLyrics(@Path("id") id: String): LyricsResponse

    // ── Motion artwork ────────────────────────────────────────────────────
    @GET("api/motion/{id}")
    suspend fun getMotion(@Path("id") id: String): MotionResponse


    // (stream URL is built client-side from PROXY_BASE_URL)

    // ── Library ───────────────────────────────────────────────────────────
    @GET("api/library/songs")
    suspend fun getLibrarySongs(@Query("limit") limit: Int = 25, @Query("offset") offset: Int = 0): LibrarySongsResponse

    @GET("api/library/albums")
    suspend fun getLibraryAlbums(@Query("limit") limit: Int = 25, @Query("offset") offset: Int = 0): LibraryAlbumsResponse

    @GET("api/library/playlists")
    suspend fun getLibraryPlaylists(@Query("limit") limit: Int = 100): LibraryPlaylistsResponse

    @GET("api/library/artists")
    suspend fun getLibraryArtists(@Query("limit") limit: Int = 25): LibraryArtistsResponse

    @GET("api/library/playlists/{id}/tracks")
    suspend fun getPlaylistTracks(@Path("id") id: String): LibrarySongsResponse

    // ── Auth ──────────────────────────────────────────────────────────────
    @GET("auth/status")
    suspend fun getAuthStatus(): AuthStatus

    @POST("auth/token")
    suspend fun setMUT(@Body body: Map<String, String>): Map<String, Any>

    @DELETE("auth/token")
    suspend fun clearMUT(): Map<String, Any>

    @GET("health")
    suspend fun health(): Map<String, Boolean>
}
