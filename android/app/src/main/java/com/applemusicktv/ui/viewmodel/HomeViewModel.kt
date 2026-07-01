package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.data.repository.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading:     Boolean     = true,
    val error:         String?     = null,
    val topPicks:      List<Album> = emptyList(),
    val madeForYou:    List<Album> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val newReleases:   List<Album> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(private val repo: MusicRepository) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = HomeUiState(isLoading = true)
            try {
                val topD    = async { repo.search("top hits 2024", 10) }
                val madeD   = async { repo.search("chill playlist", 8) }
                val recentD = async { repo.search("new album 2025", 10) }
                val newD    = async { repo.search("new release 2025", 8) }
                _state.value = HomeUiState(
                    isLoading     = false,
                    topPicks      = topD.await().getOrDefault(empty()).albums,
                    madeForYou    = madeD.await().getOrDefault(empty()).albums,
                    recentlyAdded = recentD.await().getOrDefault(empty()).albums,
                    newReleases   = newD.await().getOrDefault(empty()).albums,
                )
            } catch (e: Exception) {
                _state.value = HomeUiState(isLoading = false, error = e.message)
            }
        }
    }

    private fun empty() = SearchResults()
}
