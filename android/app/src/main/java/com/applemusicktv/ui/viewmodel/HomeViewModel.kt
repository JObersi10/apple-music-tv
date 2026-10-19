package com.applemusicktv.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.MutPreferences
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
    /** True when the shown feed is the non-personalized fallback AND a MUT is set — recommendations
     *  failed to load, so Home shows a "retry" banner. Never set without a MUT (no-MUT Home is
     *  charts-only by design, not an error). */
    val needsRefresh: Boolean    = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val mutPrefs: MutPreferences,
    @ApplicationContext context: Context,
    moshi: Moshi,
) : ViewModel() {

    /** Whether a non-personalized [feed] should raise the retry banner (only with a MUT present). */
    private fun fallbackBanner(feed: List<HomeSection>) = !feed.isPersonalized() && mutPrefs.hasMUT()

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

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load() {
        // One load at a time: tapping the "needs refresh" banner repeatedly used to stack several
        // background self-heal loops, each re-pushing state every few seconds → the banner flickered
        // back ("loads, says refresh, click, normal, refresh again a sec later"). Cancel the prior run.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
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
                        _state.value = HomeUiState(isLoading = false, sections = show, needsRefresh = fallbackBanner(show))
                        if (fresh) {   // only cache a genuinely personalized feed
                            launch(kotlinx.coroutines.Dispatchers.Default) {
                                runCatching { prefs.edit().putString("sections", adapter.toJson(sections)).apply() }
                            }
                        }
                        // A non-personalized fetch isn't the final word — recs may recover on a later
                        // attempt. Only stop the foreground loop once we actually have personalization;
                        // otherwise fall through to the background self-heal below.
                        if (fresh) return@launch
                    }
                }.onFailure { lastErr = it.message }
                if (attempt < 3) kotlinx.coroutines.delay(1500)
            }
            // We got here WITHOUT personalization (every attempt 500'd, or only charts/moods came back —
            // /me/recommendations streaks 500s and is often not ready on a cold start). Show the best feed
            // we have (cached or the last non-personalized fetch), then keep retrying in the BACKGROUND
            // with backoff so Home self-heals once recs recover — instead of stranding the user on the
            // "needs refresh" banner until they relaunch. This is the fix for "home keeps needing refresh
            // on startup": the banner now clears itself when personalization lands.
            val best = if (cached.isNotEmpty()) cached else _state.value.sections
            if (best.isNotEmpty()) _state.value = HomeUiState(isLoading = false, sections = best, needsRefresh = fallbackBanner(best))
            else _state.value = HomeUiState(isLoading = false, error = lastErr)
            var backoff = 4_000L
            repeat(15) {
                kotlinx.coroutines.delay(backoff)
                backoff = (backoff * 3 / 2).coerceAtMost(30_000L)
                val r = repo.getHome().getOrNull() ?: return@repeat
                val secs = r.sections.map { s -> HomeSection(title = s.title, albums = s.albums.map(repo::albumFromDto), style = s.style) }
                if (secs.isEmpty()) return@repeat
                val personalized = secs.isPersonalized()
                // Only COMMIT when personalization finally lands. While it's still non-personalized we
                // leave the already-shown feed untouched — re-pushing an equivalent non-personalized feed
                // every few seconds was what re-raised the banner and reset the scroll ("refresh again a
                // sec later"). We keep retrying silently; nothing on screen changes until recs recover.
                if (!personalized) return@repeat
                _state.value = HomeUiState(isLoading = false, sections = secs, needsRefresh = false)
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { prefs.edit().putString("sections", adapter.toJson(secs)).apply() }
                }
                return@launch   // healed — stop retrying
            }
        }
    }
}
