package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.datasource.RadioSource
import com.applemusicktv.data.datasource.RadioStation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RadioUiState(
    val loading: Boolean = false,
    val stations: List<RadioStation> = emptyList(),
    val query: String = "",
    /** Country chips (display names). First is the geo-detected one. */
    val countries: List<String> = emptyList(),
    /** "" = Popular, " search" = search results, else the active country name. */
    val activeCountry: String = "",
    /** Toast-ish note when a typed country name was auto-corrected. */
    val correctionNote: String? = null,
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val radio: RadioSource,
) : ViewModel() {

    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state

    /** name → ISO code, so selecting a chip loads by exact code (name lookups are flaky). */
    private val codeByName = mutableMapOf<String, String>()
    private var allCountries: List<Pair<String, String>> = emptyList()

    init {
        viewModelScope.launch {
            allCountries = radio.countryList()
            val geo = radio.detectCountry()
            if (geo != null) {
                val (code, name) = geo
                codeByName[name] = code
                _state.update { it.copy(countries = listOf(name), activeCountry = name) }
                load(name) { radio.byCountry(code) }
            } else selectPopular()
        }
    }

    fun selectPopular() { _state.update { it.copy(activeCountry = "", correctionNote = null) }; load("") { radio.top() } }

    fun selectCountry(name: String) {
        _state.update { it.copy(activeCountry = name, correctionNote = null) }
        val code = codeByName[name]
        load(name) { if (code != null) radio.byCountry(code) else radio.byCountryName(name) }
    }

    /** "+" — add a country by (possibly misspelled) name; correct it, then switch to it. */
    fun addCountry(input: String) {
        val typed = input.trim()
        if (typed.isEmpty()) return
        viewModelScope.launch {
            if (allCountries.isEmpty()) allCountries = radio.countryList()
            val match = bestCountryMatch(typed)
            val name = match?.first ?: typed
            val note = if (match != null && !name.equals(typed, ignoreCase = true)) "“$typed” → $name" else null
            if (match != null) codeByName[name] = match.second
            _state.update { s ->
                s.copy(countries = if (name in s.countries) s.countries else s.countries + name,
                    activeCountry = name, correctionNote = note)
            }
            load(name) { match?.second?.let { radio.byCountry(it) } ?: radio.byCountryName(name) }
        }
    }

    fun onQueryChange(q: String) { _state.update { it.copy(query = q) } }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        _state.update { it.copy(activeCountry = " search", correctionNote = null) }
        load(" search") { radio.search(q) }
    }

    /** Exact/prefix/contains first, then closest by edit distance (handles typos). */
    private fun bestCountryMatch(input: String): Pair<String, String>? {
        if (allCountries.isEmpty()) return null
        val q = input.lowercase()
        allCountries.firstOrNull { it.first.equals(input, ignoreCase = true) }?.let { return it }
        allCountries.firstOrNull { it.first.lowercase().startsWith(q) }?.let { return it }
        allCountries.firstOrNull { it.first.lowercase().contains(q) }?.let { return it }
        return allCountries.minByOrNull { levenshtein(q, it.first.lowercase()) }
            ?.takeIf { levenshtein(q, it.first.lowercase()) <= 3 }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun load(tag: String, fetch: suspend () -> List<RadioStation>) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val list = fetch()
            if (_state.value.activeCountry == tag)
                _state.update { it.copy(loading = false, stations = list) }
            else _state.update { it.copy(loading = false) }
        }
    }
}
