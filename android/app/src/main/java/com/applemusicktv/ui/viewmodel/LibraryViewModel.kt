package com.applemusicktv.ui.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Artist
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.PlaylistDto
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

enum class SortField { DEFAULT, NAME, ARTIST, DATE }
enum class SortDir   { ASC, DESC }

data class SortState(val field: SortField = SortField.DEFAULT, val dir: SortDir = SortDir.ASC)

data class LibraryState(
    val isLoading: Boolean = false,
    val hasMut: Boolean = false,
    val playlists: List<PlaylistDto> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val songs: List<Song> = emptyList(),
    val error: String? = null,
    val sort: SortState = SortState(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MusicRepository,
    private val mutPrefs: MutPreferences,
    private val moshi: Moshi,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state

    private val cachePrefs = context.getSharedPreferences("library_cache", Context.MODE_PRIVATE)
    private val playlistsAdapter = moshi.adapter<List<PlaylistDto>>(Types.newParameterizedType(List::class.java, PlaylistDto::class.java))
    private val albumsAdapter    = moshi.adapter<List<Album>>(Types.newParameterizedType(List::class.java, Album::class.java))
    private val artistsAdapter   = moshi.adapter<List<Artist>>(Types.newParameterizedType(List::class.java, Artist::class.java))
    private val songsAdapter     = moshi.adapter<List<Song>>(Types.newParameterizedType(List::class.java, Song::class.java))

    init {
        loadCache()
        loadSavedSort()
        refresh()
    }

    private fun loadSavedSort() {
        val fieldIdx = cachePrefs.getInt("sort_field", -1)
        if (fieldIdx < 0) return
        val field = SortField.entries.getOrNull(fieldIdx) ?: return
        val dir = SortDir.entries.getOrNull(cachePrefs.getInt("sort_dir", 0)) ?: SortDir.ASC
        _state.value = _state.value.copy(sort = SortState(field, dir))
    }

    /** Load the persisted library so content shows immediately on cold start. */
    private fun loadCache() {
        if (mutPrefs.getMUT().isEmpty()) return
        try {
            val playlists = cachePrefs.getString("playlists", null)?.let { playlistsAdapter.fromJson(it) } ?: emptyList()
            val albums    = cachePrefs.getString("albums", null)?.let { albumsAdapter.fromJson(it) } ?: emptyList()
            val artists   = cachePrefs.getString("artists", null)?.let { artistsAdapter.fromJson(it) } ?: emptyList()
            val songs     = cachePrefs.getString("songs", null)?.let { songsAdapter.fromJson(it) } ?: emptyList()
            if (playlists.isNotEmpty() || albums.isNotEmpty() || songs.isNotEmpty()) {
                _state.value = _state.value.copy(hasMut = true, playlists = playlists, albums = albums, artists = artists, songs = songs)
            }
        } catch (_: Exception) {}
    }

    private fun saveCache(playlists: List<PlaylistDto>, albums: List<Album>, artists: List<Artist>, songs: List<Song>) {
        try {
            cachePrefs.edit {
                putString("playlists", playlistsAdapter.toJson(playlists))
                putString("albums", albumsAdapter.toJson(albums))
                putString("artists", artistsAdapter.toJson(artists))
                putString("songs", songsAdapter.toJson(songs))
            }
        } catch (_: Exception) {}
    }

    fun setSort(field: SortField) {
        val cur = _state.value.sort
        val newDir = if (cur.field == field && cur.dir == SortDir.ASC) SortDir.DESC else SortDir.ASC
        val newSort = SortState(field, newDir)
        _state.value = _state.value.copy(sort = newSort)
        cachePrefs.edit {
            putInt("sort_field", newSort.field.ordinal)
            putInt("sort_dir", newSort.dir.ordinal)
        }
    }

    private val _pinnedIds = MutableStateFlow(loadPinnedIds())
    val pinnedIds: StateFlow<Set<String>> = _pinnedIds

    private fun loadPinnedIds(): Set<String> {
        val raw = cachePrefs.getString("pinned_playlists", "") ?: ""
        return if (raw.isEmpty()) emptySet() else raw.split(",").toSet()
    }

    fun togglePin(id: String) {
        val cur = _pinnedIds.value.toMutableSet()
        if (id in cur) cur.remove(id) else cur.add(id)
        _pinnedIds.value = cur
        cachePrefs.edit { putString("pinned_playlists", cur.joinToString(",")) }
    }

    fun isPinned(id: String) = id in _pinnedIds.value

    fun sortedPlaylists(): List<PlaylistDto> {
        val s = _state.value
        val pinned = _pinnedIds.value
        val list = s.playlists
        val sorted = when (s.sort.field) {
            SortField.NAME -> list.sortedBy { it.name.lowercase() }
            else           -> list
        }
        val ordered = if (s.sort.dir == SortDir.DESC) sorted.reversed() else sorted
        val (pins, rest) = ordered.partition { it.id in pinned }
        return pins.sortedBy { it.name.lowercase() } + rest
    }

    fun sortedAlbums(): List<Album> {
        val s = _state.value
        val list = s.albums
        val sorted = when (s.sort.field) {
            SortField.NAME   -> list.sortedBy { it.title.lowercase() }
            SortField.ARTIST -> list.sortedBy { it.artistName.lowercase() }
            else             -> list
        }
        return if (s.sort.dir == SortDir.DESC) sorted.reversed() else sorted
    }

    fun sortedSongs(): List<Song> {
        val s = _state.value
        val list = s.songs
        val sorted = when (s.sort.field) {
            SortField.NAME   -> list.sortedBy { it.title.lowercase() }
            SortField.ARTIST -> list.sortedBy { it.artistName.lowercase() }
            else             -> list
        }
        return if (s.sort.dir == SortDir.DESC) sorted.reversed() else sorted
    }

    fun sortedArtists(): List<Artist> {
        val s = _state.value
        val list = s.artists
        val sorted = when (s.sort.field) {
            SortField.NAME -> list.sortedBy { it.name.lowercase() }
            else           -> list
        }
        return if (s.sort.dir == SortDir.DESC) sorted.reversed() else sorted
    }

    fun playPlaylist(id: String, playerVm: PlayerViewModel) = viewModelScope.launch {
        repo.getPlaylistTracks(id).onSuccess { songs ->
            if (songs.isNotEmpty()) playerVm.playAlbum(songs, 0)
        }
    }

    fun refresh() {
        val mut = mutPrefs.getMUT()
        if (mut.isEmpty()) {
            _state.value = LibraryState(hasMut = false)
            return
        }
        _state.update { it.copy(isLoading = true, hasMut = true) }
        // Fetch each section independently — each shows up the moment it arrives.
        val job = viewModelScope.launch {
            launch {
                repo.getLibraryPlaylists().onSuccess { p ->
                    _state.update { it.copy(playlists = p) }
                    cachePrefs.edit { putString("playlists", playlistsAdapter.toJson(p)) }
                }
            }
            launch {
                repo.getLibraryAlbums().onSuccess { a ->
                    _state.update { it.copy(albums = a) }
                    cachePrefs.edit { putString("albums", albumsAdapter.toJson(a)) }
                }
            }
            launch {
                repo.getLibraryArtists().onSuccess { art ->
                    _state.update { it.copy(artists = art) }
                    cachePrefs.edit { putString("artists", artistsAdapter.toJson(art)) }
                }
            }
            launch {
                repo.getLibrarySongs().onSuccess { s ->
                    _state.update { it.copy(songs = s) }
                    cachePrefs.edit { putString("songs", songsAdapter.toJson(s)) }
                }
            }
        }
        job.invokeOnCompletion { _state.update { it.copy(isLoading = false) } }
    }
}
