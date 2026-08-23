package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeSection(val title: String, val albums: List<Album>)

data class HomeUiState(
    val isLoading: Boolean       = true,
    val error:     String?       = null,
    val sections:  List<HomeSection> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(private val repo: MusicRepository) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = HomeUiState(isLoading = true)
            // The first open races the proxy's startup (bearer scrape) and the server-reachability
            // probe, so a cold /api/home can fail or come back empty — the user had to hit refresh.
            // Retry a few times with a short backoff instead.
            var lastErr: String? = null
            repeat(4) { attempt ->
                val result = repo.getHome()
                result.onSuccess { home ->
                    val sections = home.sections.map { s ->
                        HomeSection(title = s.title, albums = s.albums.map(repo::albumFromDto))
                    }
                    if (sections.isNotEmpty()) {
                        _state.value = HomeUiState(isLoading = false, sections = sections)
                        return@launch
                    }
                }.onFailure { lastErr = it.message }
                if (attempt < 3) kotlinx.coroutines.delay(1500)
            }
            _state.value = HomeUiState(isLoading = false, error = lastErr)
        }
    }
}
