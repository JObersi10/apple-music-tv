package com.applemusicktv.ui.navigation

sealed class Screen(val route: String) {
    object Home           : Screen("home")
    object Library        : Screen("library")
    object Search         : Screen("search")
    object NowPlaying     : Screen("now_playing")
    object DevMenu        : Screen("dev_menu")
    object Radio          : Screen("radio")
    object Browse         : Screen("browse")
    object AlbumDetail    : Screen("album/{albumId}") {
        fun route(id: String) = "album/$id"
    }
    object ArtistDetail   : Screen("artist/{artistId}") {
        fun route(id: String) = "artist/$id"
    }
    object Category       : Screen("category/{categoryId}") {
        fun route(id: String) = "category/$id"
    }
    object MusicVideo     : Screen("mv/{mvId}/{mvTitle}/{mvArtist}") {
        fun route(id: String, title: String, artist: String) =
            "mv/${encode(id)}/${encode(title)}/${encode(artist)}"
        private fun encode(s: String) = java.net.URLEncoder.encode(s.ifEmpty { " " }, "UTF-8")
    }
    object Genre          : Screen("genre/{genreId}/{genreName}") {
        fun route(id: String, name: String) = "genre/${encode(id)}/${encode(name)}"
        private fun encode(s: String) = java.net.URLEncoder.encode(s.ifEmpty { " " }, "UTF-8")
    }
    object PlaylistDetail : Screen("playlist/{playlistId}/{playlistName}/{artworkUrl}") {
        fun route(id: String, name: String, artworkUrl: String = "") =
            "playlist/${encode(id)}/${encode(name)}/${encode(artworkUrl)}"
        private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
}
