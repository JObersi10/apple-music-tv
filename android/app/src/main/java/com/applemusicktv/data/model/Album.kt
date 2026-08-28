package com.applemusicktv.data.model

data class Album(
    val id:             String,
    val title:          String,
    val artistName:     String,
    val artistId:       String?      = null,
    val artworkUrl:     String?,
    val type:           String       = "albums",
    val artworkBgColor: String?      = null,
    val releaseDate:    String?      = null,
    val trackCount:     Int          = 0,
    val genreNames:     List<String> = emptyList(),
    val recordLabel:    String?      = null,
    val copyright:      String?      = null,
    val editorialNotes: String?      = null,
    /** Square motion-artwork HLS loop; only set for shelves we animate (Playlists Made for You). */
    val motionUrl:      String?      = null,
    /** Editorial tagline shown as the small uppercase label on a spotlight card ("New Release"). */
    val tagline:        String?      = null,
    /** Ready-to-load landscape editorial image (superHeroWide/subscriptionHero). Not a template. */
    val wideArtworkUrl: String?      = null,
    val color:          Long         = 0xFF1A1A2E,
) {
    fun artworkUrl(size: Int) = artworkUrl
        ?.replace("{w}", "$size")
        ?.replace("{h}", "$size")
        ?.replace("{f}", "jpg")
}
