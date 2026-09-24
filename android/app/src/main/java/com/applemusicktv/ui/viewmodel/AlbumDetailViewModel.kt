package com.applemusicktv.ui.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.repository.MusicRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val isLoading:     Boolean     = true,
    val album:         Album?      = null,
    val tracks:        List<Song>  = emptyList(),
    val relatedAlbums: List<Album> = emptyList(),
    val musicVideos:   List<Song>  = emptyList(),
    val motionUrl:     String?     = null,
    // Lowercased titles of the album's tracks that are also in the artist's top-songs — Apple's
    // "popular"/best-songs dot. Matched by title so library vs catalog ids don't matter.
    val popularTitles: Set<String> = emptySet(),
    val error:         String?     = null,
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val albumId = savedState.get<String>("albumId") ?: ""
    private val _state  = MutableStateFlow(AlbumDetailUiState())
    val state: StateFlow<AlbumDetailUiState> = _state

    private val prefs = context.getSharedPreferences("album_detail_cache", Context.MODE_PRIVATE)
    private val songListType  = Types.newParameterizedType(List::class.java, Song::class.java)
    private val albumListType = Types.newParameterizedType(List::class.java, Album::class.java)

    init { if (albumId.isNotEmpty()) load() }

    private fun load() {
        // Show cache instantly if available
        val cached = readCache(albumId)
        if (cached != null) {
            _state.value = cached.copy(isLoading = true)
        }

        viewModelScope.launch {
            try {
                val albumD   = async { repo.getAlbum(albumId) }
                val tracksD  = async { repo.getAlbumTracks(albumId) }
                val relatedD = async { repo.getRelatedAlbums(albumId) }
                val tracks = tracksD.await().getOrDefault(emptyList())
                val motionUrl = tracks.firstOrNull()?.id?.let { repo.getMotion(it).getOrNull() }
                val album = albumD.await().getOrNull()
                // Album music videos via the dedicated repo path (proxy route, or artist-feed
                // derivation on standalone). Best-effort — never fails the page.
                val videos = repo.getAlbumMusicVideos(albumId).getOrDefault(emptyList())
                android.util.Log.i("AMAlbumMV", "albumId=$albumId MVs=${videos.size} album='${album?.title}'")
                // Popular ("best songs") dot — Apple's own signal. Prefer the per-track popularity
                // attribute (from ?extend=popularity): dot tracks at/above a threshold (scale-aware:
                // 0..1 → 0.5, 0..100 → 50). If popularity isn't present (older cache / no extend),
                // fall back to matching the artist's top-songs by title.
                val withPop = tracks.mapNotNull { t -> t.popularity?.let { t to it } }
                val popularTitles: Set<String> = if (withPop.isNotEmpty()) {
                    val max = withPop.maxOf { it.second }
                    val thr = if (max <= 1.0) 0.5 else 50.0
                    withPop.filter { it.second >= thr }.map { it.first.title.trim().lowercase() }.toSet()
                } else {
                    val artistIdForTop = album?.artistId?.takeIf { it.isNotBlank() }
                        ?: tracks.firstOrNull { !it.artistId.isNullOrBlank() }?.artistId
                    artistIdForTop?.let { aid ->
                        runCatching {
                            repo.getArtistFull(aid).getOrNull()?.topSongs
                                ?.map { it.title.trim().lowercase() }?.toSet()
                        }.getOrNull()
                    }?.let { top -> tracks.map { it.title.trim().lowercase() }.filter { it in top }.toSet() }
                        ?: emptySet()
                }
                android.util.Log.i("AMPopular", "album='${album?.title}' withPop=${withPop.size} dots=${popularTitles.size}")
                val newState = AlbumDetailUiState(
                    isLoading     = false,
                    album         = album,
                    tracks        = tracks,
                    relatedAlbums = relatedD.await().getOrDefault(emptyList()),
                    musicVideos   = videos,
                    motionUrl     = motionUrl,
                    popularTitles = popularTitles,
                )
                _state.value = newState
                writeCache(albumId, newState)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun readCache(id: String): AlbumDetailUiState? {
        return try {
            val tracksJson  = prefs.getString("tracks_$id", null) ?: return null
            val albumJson   = prefs.getString("album_$id", null) ?: return null
            val relatedJson = prefs.getString("related_$id", null)
            AlbumDetailUiState(
                isLoading     = false,
                album         = moshi.adapter(Album::class.java).fromJson(albumJson),
                tracks        = moshi.adapter<List<Song>>(songListType).fromJson(tracksJson) ?: emptyList(),
                relatedAlbums = relatedJson?.let { moshi.adapter<List<Album>>(albumListType).fromJson(it) } ?: emptyList(),
            )
        } catch (_: Exception) { null }
    }

    private fun writeCache(id: String, state: AlbumDetailUiState) {
        try {
            prefs.edit {
                state.album?.let { putString("album_$id", moshi.adapter(Album::class.java).toJson(it)) }
                putString("tracks_$id",  moshi.adapter<List<Song>>(songListType).toJson(state.tracks))
                putString("related_$id", moshi.adapter<List<Album>>(albumListType).toJson(state.relatedAlbums))
            }
        } catch (_: Exception) {}
    }
}
