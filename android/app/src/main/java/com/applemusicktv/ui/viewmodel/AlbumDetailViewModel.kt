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
                // Albums rarely list MVs in the tracklist, but the artist usually has related ones.
                // Pull the artist feed and prefer videos whose title/album matches this album; fall back
                // to the artist's videos so the shelf isn't empty. Best-effort — never fails the page.
                val videos = album?.artistId?.takeIf { it.isNotBlank() }?.let { aid ->
                    runCatching { repo.getArtistFull(aid).getOrNull()?.musicVideos?.map(repo::songFromDto).orEmpty() }.getOrDefault(emptyList())
                }.orEmpty().let { all ->
                    val name = album?.title?.lowercase().orEmpty()
                    val matched = all.filter { it.albumName.lowercase() == name || it.title.lowercase().contains(name) }
                    (if (matched.isNotEmpty()) matched else all).take(12)
                }
                val newState = AlbumDetailUiState(
                    isLoading     = false,
                    album         = album,
                    tracks        = tracks,
                    relatedAlbums = relatedD.await().getOrDefault(emptyList()),
                    musicVideos   = videos,
                    motionUrl     = motionUrl,
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
