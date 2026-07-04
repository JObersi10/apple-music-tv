package com.applemusicktv.data.model

data class Song(
    val id:             String,
    val title:          String,
    val artistName:     String,
    val albumName:      String,
    val durationMs:     Long,
    val artworkUrl:     String?,
    val artworkBgColor: String?,
    val previewUrl:     String?,
    val hasLyrics:      Boolean = false,
    val trackNumber:    Int?    = null,
    val genreNames:     List<String> = emptyList(),
    val artistId:       String? = null,
) {
    fun artworkUrl(size: Int) = artworkUrl
        ?.replace("{w}", "$size")
        ?.replace("{h}", "$size")
        ?.replace("{f}", "jpg")

    val durationFormatted: String get() {
        val s = durationMs / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
