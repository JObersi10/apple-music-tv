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

data class HomeSection(val title: String, val albums: List<Album>, val style: String? = null)

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

    // Personalization loaded ⇔ /me/recommendations succeeded. Its signature shelves carry the
    // "picks" hero (Top Picks for You) or the "gradient" row (Playlists Made for You); the charts
    // fallback and the mood/category rooms have neither. This — not section count — is the test.
    private fun List<HomeSection>.isPersonalized() =
        any { it.style == "picks" || it.style == "gradient" }

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
                        HomeSection(title = s.title, albums = s.albums.map(repo::albumFromDto), style = s.style)
                    }
                    if (sections.isNotEmpty()) {
                        // Personalization is present ONLY when /me/recommendations succeeded — its hero
                        // ("Top Picks for You", style "picks") is the marker. A feed WITHOUT it must never
                        // overwrite a cached feed WITH it, no matter how many mood/category/chart rows it
                        // has (those can exceed the old size threshold and were wiping the good cache).
                        val fresh = sections.isPersonalized()
                        val haveCached = cached.isPersonalized()
                        val show = if (!fresh && haveCached) cached else sections
                        _state.value = HomeUiState(isLoading = false, sections = show)
                        if (fresh) {   // only cache a genuinely personalized feed
                            launch(kotlinx.coroutines.Dispatchers.Default) {
                                runCatching { prefs.edit().putString("sections", adapter.toJson(sections)).apply() }
                            }
                        }
                        // A non-personalized fetch isn't the final word — recs may recover on a later
                        // attempt in this same load. Only stop early once we actually have personalization.
                        if (fresh || !haveCached) return@launch
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
