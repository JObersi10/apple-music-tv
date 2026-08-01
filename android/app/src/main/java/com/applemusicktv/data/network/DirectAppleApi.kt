package com.applemusicktv.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.*

// ── Raw Apple Music API DTOs ──────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AppleArtwork(
    val url: String? = null,
    val bgColor: String? = null,
) {
    fun resolved(size: Int = 500) = url
        ?.replace("{w}", "$size")?.replace("{h}", "$size")?.replace("{f}", "jpg")
}

@JsonClass(generateAdapter = true)
data class ApplePreview(val url: String? = null)

@JsonClass(generateAdapter = true)
data class AppleSongAttrs(
    val name: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val durationInMillis: Long = 0,
    val artwork: AppleArtwork? = null,
    val previews: List<ApplePreview> = emptyList(),
    val hasLyrics: Boolean = false,
    val trackNumber: Int? = null,
    val genreNames: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AppleAlbumAttrs(
    val name: String = "",
    val artistName: String = "",
    val artwork: AppleArtwork? = null,
    val releaseDate: String? = null,
    val trackCount: Int = 0,
    val genreNames: List<String> = emptyList(),
    val recordLabel: String? = null,
    val copyright: String? = null,
)

@JsonClass(generateAdapter = true)
data class AppleArtistAttrs(
    val name: String = "",
    val artwork: AppleArtwork? = null,
    val genreNames: List<String> = emptyList(),
    val editorialNotes: AppleEditorialNotes? = null,
)

@JsonClass(generateAdapter = true)
data class ApplePlaylistAttrs(
    val name: String = "",
    val curatorName: String = "",
    val artwork: AppleArtwork? = null,
    val description: AppleEditorialNotes? = null,
)

@JsonClass(generateAdapter = true)
data class AppleRecTitle(val stringForDisplay: String = "")

@JsonClass(generateAdapter = true)
data class AppleRecAttrs(val title: AppleRecTitle? = null)

@JsonClass(generateAdapter = true)
data class AppleRecContents(val data: List<AppleItem<AppleAlbumAttrs>> = emptyList())

@JsonClass(generateAdapter = true)
data class AppleRecRelationships(val contents: AppleRecContents? = null)

@JsonClass(generateAdapter = true)
data class AppleRecItem(
    val id: String = "",
    val attributes: AppleRecAttrs? = null,
    val relationships: AppleRecRelationships? = null,
)

@JsonClass(generateAdapter = true)
data class AppleEditorialNotes(
    val standard: String? = null,
    val short: String? = null,
)

@JsonClass(generateAdapter = true)
data class AppleRelId(val id: String = "")

@JsonClass(generateAdapter = true)
data class AppleRelList(val data: List<AppleRelId> = emptyList())

@JsonClass(generateAdapter = true)
data class AppleRelationships(val catalog: AppleRelList? = null)

@JsonClass(generateAdapter = true)
data class AppleItem<T>(
    val id: String = "",
    val type: String = "",
    val attributes: T? = null,
    val relationships: AppleRelationships? = null,
)

@JsonClass(generateAdapter = true)
data class AppleList<T>(
    val data: List<T> = emptyList(),
    val next: String? = null,
)

@JsonClass(generateAdapter = true)
data class AppleSearchResults(
    val songs: AppleList<AppleItem<AppleSongAttrs>> = AppleList(),
    val albums: AppleList<AppleItem<AppleAlbumAttrs>> = AppleList(),
    val artists: AppleList<AppleItem<AppleArtistAttrs>> = AppleList(),
)

@JsonClass(generateAdapter = true)
data class AppleSearchResponse(val results: AppleSearchResults = AppleSearchResults())

// ── Normalizers → existing ProxyApi DTOs ─────────────────────────────────

fun AppleItem<AppleSongAttrs>.toSongDto() = SongDto(
    id             = id,
    title          = attributes?.name ?: "",
    artistName     = attributes?.artistName ?: "",
    albumName      = attributes?.albumName ?: "",
    durationMs     = attributes?.durationInMillis ?: 0,
    artworkUrl     = attributes?.artwork?.url,
    artworkBgColor = attributes?.artwork?.bgColor,
    previewUrl     = attributes?.previews?.firstOrNull()?.url,
    previewHlsUrl  = null,
    hasLyrics      = attributes?.hasLyrics ?: false,
    trackNumber    = attributes?.trackNumber,
    genreNames     = attributes?.genreNames ?: emptyList(),
)

fun AppleItem<AppleAlbumAttrs>.toAlbumDto() = AlbumDto(
    id             = id,
    title          = attributes?.name ?: "",
    artistName     = attributes?.artistName ?: "",
    artworkUrl     = attributes?.artwork?.url,
    artworkBgColor = attributes?.artwork?.bgColor,
    releaseDate    = attributes?.releaseDate,
    trackCount     = attributes?.trackCount ?: 0,
    genreNames     = attributes?.genreNames ?: emptyList(),
    recordLabel    = attributes?.recordLabel,
    copyright      = attributes?.copyright,
)

fun AppleItem<AppleArtistAttrs>.toArtistDto() = ArtistDto(
    id             = id,
    name           = attributes?.name ?: "",
    artworkUrl     = attributes?.artwork?.url,
    genreNames     = attributes?.genreNames ?: emptyList(),
    editorialNotes = attributes?.editorialNotes?.standard,
)

fun AppleItem<ApplePlaylistAttrs>.toPlaylistDto() = PlaylistDto(
    id             = id,
    name           = attributes?.name ?: "",
    curatorName    = attributes?.curatorName ?: "",
    artworkUrl     = attributes?.artwork?.resolved(),
    artworkBgColor = attributes?.artwork?.bgColor,
    description    = attributes?.description?.short,
)

// ── Retrofit interface (base URL: https://amp-api-edge.music.apple.com/) ─

interface DirectAppleApi {

    @GET("v1/catalog/{sf}/search")
    suspend fun search(
        @Path("sf") storefront: String,
        @Query("term") term: String,
        @Query("limit") limit: Int = 20,
        @Query("types") types: String = "songs,albums,artists",
    ): AppleSearchResponse

    @GET("v1/me/library/songs")
    suspend fun librarySongs(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("include") include: String = "catalog",
    ): AppleList<AppleItem<AppleSongAttrs>>

    @GET("v1/me/library/albums")
    suspend fun libraryAlbums(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): AppleList<AppleItem<AppleAlbumAttrs>>

    @GET("v1/me/library/playlists")
    suspend fun libraryPlaylists(
        @Query("limit") limit: Int = 100,
        @Query("include") include: String = "catalog",
    ): AppleList<AppleItem<ApplePlaylistAttrs>>

    @GET("v1/me/library/playlists/{id}/tracks")
    suspend fun playlistTracks(
        @Path("id") id: String,
        @Query("limit") limit: Int = 100,
    ): AppleList<AppleItem<AppleSongAttrs>>

    @GET("v1/catalog/{sf}/playlists/{id}/tracks")
    suspend fun catalogPlaylistTracks(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("limit") limit: Int = 100,
    ): AppleList<AppleItem<AppleSongAttrs>>

    @GET("v1/me/recommendations")
    suspend fun recommendations(
        @Query("limit") limit: Int = 20,
        @Query("include[personal-recommendation]") include: String = "contents",
    ): AppleList<AppleRecItem>

    @GET("v1/me/library/recently-added")
    suspend fun recentlyAdded(@Query("limit") limit: Int = 20): AppleList<AppleItem<AppleAlbumAttrs>>

    @GET("v1/me/storefront")
    suspend fun storefront(): AppleList<AppleItem<Map<String, Any>>>

    // ── Detail endpoints (standalone port of the proxy's album/artist routes) ──

    @GET("v1/catalog/{sf}/albums/{id}")
    suspend fun catalogAlbum(
        @Path("sf") storefront: String,
        @Path("id") id: String,
    ): AppleList<AppleItem<AppleAlbumAttrs>>

    @GET("v1/catalog/{sf}/albums/{id}/tracks")
    suspend fun catalogAlbumTracks(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("limit") limit: Int = 100,
    ): AppleList<AppleItem<AppleSongAttrs>>

    /** Library albums need the library endpoint; `include=catalog` gives us the id. */
    @GET("v1/me/library/albums/{id}")
    suspend fun libraryAlbum(
        @Path("id") id: String,
        @Query("include") include: String = "catalog",
    ): AppleList<AppleItem<AppleAlbumAttrs>>

    @GET("v1/me/library/albums/{id}/tracks")
    suspend fun libraryAlbumTracks(
        @Path("id") id: String,
        @Query("include") include: String = "catalog",
        @Query("limit") limit: Int = 100,
    ): AppleList<AppleItem<AppleSongAttrs>>

    @GET("v1/catalog/{sf}/songs/{id}")
    suspend fun catalogSong(
        @Path("sf") storefront: String,
        @Path("id") id: String,
    ): AppleList<AppleItem<AppleSongAttrs>>

    @GET("v1/me/library/songs/{id}")
    suspend fun librarySong(
        @Path("id") id: String,
        @Query("include") include: String = "catalog",
    ): AppleList<AppleItem<AppleSongAttrs>>

    @GET("v1/catalog/{sf}/artists/{id}")
    suspend fun catalogArtist(
        @Path("sf") storefront: String,
        @Path("id") id: String,
    ): AppleList<AppleItem<AppleArtistAttrs>>

    @GET("v1/me/library/artists/{id}")
    suspend fun libraryArtist(
        @Path("id") id: String,
        @Query("include") include: String = "catalog",
    ): AppleList<AppleItem<AppleArtistAttrs>>

    @GET("v1/me/library/artists")
    suspend fun libraryArtists(
        @Query("limit") limit: Int = 100,
    ): AppleList<AppleItem<AppleArtistAttrs>>

    /** Raw JSON — the artist page renders straight off Apple's `views` payload. */
    @GET("v1/catalog/{sf}/artists/{id}")
    suspend fun catalogArtistFull(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("views") views: String =
            "top-songs,latest-release,full-albums,featured-albums,similar-artists",
        @Query("extend") extend: String = "editorialArtwork,artistBio",
    ): Map<String, Any>

    @GET("v1/catalog/{sf}/artists/{id}/albums")
    suspend fun catalogArtistAlbums(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
    ): AppleList<AppleItem<AppleAlbumAttrs>>

    /** Motion artwork lives on the album, so a song has to be resolved to one first. */
    @GET("v1/catalog/{sf}/albums/{id}")
    suspend fun catalogAlbumWithMotion(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("extend") extend: String = "editorialVideo",
    ): Map<String, Any>

    /** Apple's charts — what the proxy's Browse tab is built from. */
    @GET("v1/catalog/{sf}/charts")
    suspend fun charts(
        @Path("sf") storefront: String,
        @Query("types") types: String = "albums,playlists",
        @Query("limit") limit: Int = 20,
        @Query("genre") genre: String? = null,
    ): Map<String, Any>

    @GET("v1/catalog/{sf}/genres")
    suspend fun catalogGenres(
        @Path("sf") storefront: String,
        @Query("limit") limit: Int = 40,
    ): AppleList<AppleItem<AppleGenreAttrs>>
}

@JsonClass(generateAdapter = true)
data class AppleGenreAttrs(val name: String = "")
