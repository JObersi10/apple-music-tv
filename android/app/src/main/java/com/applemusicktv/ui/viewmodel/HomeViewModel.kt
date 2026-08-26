package com.applemusicktv.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.repository.MusicRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HomeSection(val title: String, val albums: List<Album>)

data class HomeUiState(
    val isLoading: Boolean       = true,
    val error:     String?       = null,
    val sections:  List<HomeSection> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: MusicRepository,
    @ApplicationContext context: Context,
    moshi: Moshi,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private val prefs = context.getSharedPreferences("home_cache", Context.MODE_PRIVATE)
    private val adapter = moshi.adapter<List<HomeSection>>(
        Types.newParameterizedType(List::class.java, HomeSection::class.java))

    // A feed with only the charts fallback is this few sections. Anything above it means the
    // personalized recommendations actually loaded — only THOSE are worth caching / worth replacing
    // a cached good feed with. Apple's recommendations API 500s in bad streaks (all retries can fail),
    // and without this a cold start in one of those windows dropped Home to 3 chart rows.
    private val FALLBACK_MAX = 4

    init {
        // Read + parse the cached feed OFF the main thread — the JSON is large (all shelves × albums)
        // and doing it in init on the UI thread froze startup (hundreds of ms / multi-second stalls).
        viewModelScope.launch {
            val cached = withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { prefs.getString("sections", null)?.let { adapter.fromJson(it) } }.getOrNull()
            }
            if (!cached.isNullOrEmpty() && _state.value.sections.isEmpty()) {
                _state.value = HomeUiState(isLoading = true, sections = cached)
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            val cached = _state.value.sections
            if (cached.isEmpty()) _state.value = HomeUiState(isLoading = true)
            var lastErr: String? = null
            repeat(4) { attempt ->
                val result = repo.getHome()
                result.onSuccess { home ->
                    val sections = home.sections.map { s ->
                        HomeSection(title = s.title, albums = s.albums.map(repo::albumFromDto))
                    }
                    if (sections.isNotEmpty()) {
                        // A rich (personalized) feed replaces the cache. A thin fallback does NOT clobber
                        // a richer cached feed — keep showing the good one until recs recover.
                        val useCache = sections.size <= FALLBACK_MAX && cached.size > sections.size
                        val show = if (useCache) cached else sections
                        _state.value = HomeUiState(isLoading = false, sections = show)
                        if (sections.size > FALLBACK_MAX) {
                            launch(kotlinx.coroutines.Dispatchers.Default) {
                                runCatching { prefs.edit().putString("sections", adapter.toJson(sections)).apply() }
                            }
                        }
                        return@launch
                    }
                }.onFailure { lastErr = it.message }
                if (attempt < 3) kotlinx.coroutines.delay(1500)
            }
            // Total failure: keep any cached feed rather than blanking.
            if (cached.isNotEmpty()) _state.value = HomeUiState(isLoading = false, sections = cached)
            else _state.value = HomeUiState(isLoading = false, error = lastErr)
        }
    }
}
