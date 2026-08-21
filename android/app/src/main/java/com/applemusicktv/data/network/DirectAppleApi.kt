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
    // "editorial" = Apple Music's own curated playlists (Sports, etc.) — ranked first.
    val playlistType: String? = null,
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
data class AppleRelationships(
    val catalog: AppleRelList? = null,
    val artists: AppleRelList? = null,
    val albums: AppleRelList? = null,
)

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
    val playlists: AppleList<AppleItem<ApplePlaylistAttrs>> = AppleList(),
)

@JsonClass(generateAdapter = true)
data class AppleSearchResponse(val results: AppleSearchResults = AppleSearchResults())

// ── Normalizers → existing ProxyApi DTOs ─────────────────────────────────

fun AppleItem<AppleSongAttrs>.toSongDto() = SongDto(
    id             = id,
    title          = attributes?.name ?: "",
    artistName     = attributes?.artistName ?: "",
    albumName      = attributes?.albumName ?: "",
    artistId       = relationships?.artists?.data?.firstOrNull()?.id,
    albumId        = relationships?.albums?.data?.firstOrNull()?.id,
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

/** Playlists surface in search folded into the album grid (id "pl.*" routes to the
 *  playlist screen). Named distinctly from the album mapper — same erased signature. */
fun AppleItem<ApplePlaylistAttrs>.toPlaylistAlbumDto() = AlbumDto(
    id             = id,
    title          = attributes?.name ?: "",
    artistName     = attributes?.curatorName ?: "",
    artworkUrl     = attributes?.artwork?.url,
    artworkBgColor = attributes?.artwork?.bgColor,
    releaseDate    = null,
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
        @Query("types") types: String = "songs,albums,artists,playlists",
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
        @Query("offset") offset: Int = 0,
    ): AppleList<AppleItem<AppleSongAttrs>>

    @GET("v1/catalog/{sf}/playlists/{id}/tracks")
    suspend fun catalogPlaylistTracks(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
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
        @Query("include") include: String = "artists,albums",
    ): AppleList<AppleItem<AppleSongAttrs>>

    @GET("v1/me/library/songs/{id}")
    suspend fun librarySong(
        @Path("id") id: String,
        @Query("include") include: String = "catalog,artists,albums",
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
        @Query("offset") offset: Int = 0,
        // catalog relationship carries the artist id we can resolve artwork from
        @Query("include") include: String = "catalog",
    ): AppleList<AppleItem<AppleArtistAttrs>>

    /** Batch catalog artist lookup — used to backfill artwork for library artists. */
    @GET("v1/catalog/{sf}/artists")
    suspend fun catalogArtistsByIds(
        @Path("sf") storefront: String,
        @Query("ids") ids: String,
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

    /** Raw station resource — used to probe what a personalized ra.* mix exposes. */
    @GET("v1/catalog/{sf}/stations/{id}")
    suspend fun catalogStation(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("include") include: String = "tracks,contents,radio-show",
    ): Map<String, Any>

    /** Editorial playlists carry their own motion artwork, same shape as albums. */
    @GET("v1/catalog/{sf}/playlists/{id}")
    suspend fun catalogPlaylistWithMotion(
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

    @GET("v1/me/recent/played")
    suspend fun recentPlayed(
        @Query("limit") limit: Int = 20,
        @Query("types") types: String = "albums,playlists",
    ): Map<String, Any>

    @GET("v1/catalog/{sf}/groupings")
    suspend fun groupings(
        @Path("sf") storefront: String,
        @Query("ids") ids: String = "music-browse",
        @Query("include") include: String = "contents",
        @Query("limit") limit: Int = 8,
    ): Map<String, Any>

    /** Raw search — the typed one only maps songs/albums/artists. */
    @GET("v1/catalog/{sf}/search")
    suspend fun searchRaw(
        @Path("sf") storefront: String,
        @Query("term") term: String,
        @Query("types") types: String,
        @Query("limit") limit: Int = 10,
    ): Map<String, Any>

    @GET("v1/me/recommendations")
    suspend fun recommendationsRaw(
        @Query("limit") limit: Int = 20,
        @Query("include[personal-recommendation]") include: String = "contents",
    ): Map<String, Any>

    @GET("v1/catalog/{sf}/genres")
    suspend fun catalogGenres(
        @Path("sf") storefront: String,
        @Query("limit") limit: Int = 40,
    ): AppleList<AppleItem<AppleGenreAttrs>>

    // ── Editorial categories: curators + multirooms (standalone parity) ───────
    @GET("v1/catalog/{sf}/search")
    suspend fun edSearch(
        @Path("sf") storefront: String,
        @Query("term") term: String,
        @Query("types") types: String,
        @Query("with") with: String = "serverBubbles,topResults",
        @Query("limit") limit: Int = 6,
        @Query("platform") platform: String = "web",
        @Query("l") l: String = "en-US",
    ): EdSearchResponse

    @GET("v1/catalog/{sf}/{kind}/{id}")
    suspend fun edCurator(
        @Path("sf") storefront: String,
        @Path("kind") kind: String,
        @Path("id") id: String,
        @Query("include") include: String = "grouping,playlists",
        @Query("limit[curators:playlists]") plLimit: Int = 10,
        @Query("l") l: String = "en-US",
        @Query("platform") platform: String = "web",
    ): EdDataResponse

    @GET("v1/editorial/{sf}/groupings/{id}")
    suspend fun edGrouping(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("include") include: String = "tabs",
        @Query("extend") extend: String = "editorialArtwork",
        @Query("l") l: String = "en-US",
        @Query("platform") platform: String = "web",
    ): EdDataResponse

    @GET("v1/editorial/{sf}/multirooms/{id}")
    suspend fun edMultiRoom(
        @Path("sf") storefront: String,
        @Path("id") id: String,
        @Query("extend") extend: String = "editorialArtwork",
        @Query("include[albums]") incAlbums: String = "artists",
        @Query("l") l: String = "en-US",
        @Query("platform") platform: String = "web",
    ): EdDataResponse
}

@JsonClass(generateAdapter = true)
data class AppleGenreAttrs(val name: String = "")

// ── Editorial discovery DTOs (curators + multirooms) ─────────────────────────
@JsonClass(generateAdapter = true)
data class EdArtwork(val url: String? = null, val bgColor: String? = null)

@JsonClass(generateAdapter = true)
data class EdLink(val feature: String? = null, val url: String? = null)

@JsonClass(generateAdapter = true)
data class EdNotes(val name: String? = null, val tagline: String? = null)

@JsonClass(generateAdapter = true)
data class EdEditorialArtwork(
    val subscriptionCover: EdArtwork? = null,
    val brandLogo: EdArtwork? = null,
)

@JsonClass(generateAdapter = true)
data class EdAttrs(
    val name: String? = null,
    val title: String? = null,
    val artistName: String? = null,
    val curatorName: String? = null,
    val artwork: EdArtwork? = null,
    val url: String? = null,
    val link: EdLink? = null,
    val editorialNotes: EdNotes? = null,
    val editorialArtwork: EdEditorialArtwork? = null,
    val editorialElementKind: String? = null,
)

@JsonClass(generateAdapter = true)
data class EdRels(
    val children: EdListRel? = null,
    val contents: EdListRel? = null,
    val tabs: EdListRel? = null,
    val grouping: EdListRel? = null,
    val playlists: EdListRel? = null,
)

@JsonClass(generateAdapter = true)
data class EdItem(
    val id: String = "",
    val type: String = "",
    val attributes: EdAttrs? = null,
    val relationships: EdRels? = null,
)

@JsonClass(generateAdapter = true)
data class EdListRel(val data: List<EdItem> = emptyList())

@JsonClass(generateAdapter = true)
data class EdSearchResponse(val results: Map<String, EdListRel> = emptyMap())

@JsonClass(generateAdapter = true)
data class EdDataResponse(val data: List<EdItem> = emptyList())
