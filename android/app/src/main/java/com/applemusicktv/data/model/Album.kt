package com.applemusicktv.data.model

data class Album(
    val id:             String,
    val title:          String,
    val artistName:     String,
    val artworkUrl:     String?,
    val artworkBgColor: String?      = null,
    val releaseDate:    String?      = null,
    val trackCount:     Int          = 0,
    val genreNames:     List<String> = emptyList(),
    val recordLabel:    String?      = null,
    val copyright:      String?      = null,
    val editorialNotes: String?      = null,
    val color:          Long         = 0xFF1A1A2E,
) {
    fun artworkUrl(size: Int) = artworkUrl
        ?.replace("{w}", "$size")
        ?.replace("{h}", "$size")
        ?.replace("{f}", "jpg")
}
