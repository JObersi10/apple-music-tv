package com.applemusicktv.ui.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.repository.MusicRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailState(
    val loading: Boolean = false,
    val tracks: List<Song> = emptyList(),
    val artworkUrl: String? = null,
    val curatorName: String = "",
    val motionUrl: String? = null,
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailState())
    val state: StateFlow<PlaylistDetailState> = _state

    private var loadedId: String? = null
    private val prefs = context.getSharedPreferences("playlist_detail_cache", Context.MODE_PRIVATE)
    private val listType = Types.newParameterizedType(List::class.java, Song::class.java)

    fun load(playlistId: String, initialArtworkUrl: String? = null) {
        if (loadedId == playlistId && _state.value.tracks.isNotEmpty()) return
        loadedId = playlistId

        // Show cache immediately if available
        val cached = readCache(playlistId)
        if (cached != null) {
            _state.value = cached.copy(loading = true, artworkUrl = initialArtworkUrl ?: cached.artworkUrl)
        } else {
            _state.value = PlaylistDetailState(loading = true, artworkUrl = initialArtworkUrl)
        }

        viewModelScope.launch {
            repo.getPlaylistMotion(playlistId).onSuccess { url ->
                if (loadedId == playlistId && url != null)
                    _state.update { it.copy(motionUrl = url) }
            }
        }

        viewModelScope.launch {
            repo.getPlaylistTracks(playlistId).onSuccess { songs ->
                val newState = PlaylistDetailState(
                    tracks     = songs,
                    artworkUrl = initialArtworkUrl ?: songs.firstOrNull()?.artworkUrl,
                    curatorName = _state.value.curatorName,
                    motionUrl  = _state.value.motionUrl,
                )
                _state.value = newState
                writeCache(playlistId, newState)
            }.onFailure {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun readCache(playlistId: String): PlaylistDetailState? {
        val json = prefs.getString("tracks_$playlistId", null) ?: return null
        return try {
            val tracks = moshi.adapter<List<Song>>(listType).fromJson(json) ?: return null
            PlaylistDetailState(
                tracks     = tracks,
                artworkUrl = prefs.getString("art_$playlistId", null),
                curatorName = prefs.getString("curator_$playlistId", "") ?: "",
            )
        } catch (_: Exception) { null }
    }

    private fun writeCache(playlistId: String, state: PlaylistDetailState) {
        try {
            prefs.edit {
                putString("tracks_$playlistId", moshi.adapter<List<Song>>(listType).toJson(state.tracks))
                putString("art_$playlistId", state.artworkUrl)
                putString("curator_$playlistId", state.curatorName)
            }
        } catch (_: Exception) {}
    }
}
