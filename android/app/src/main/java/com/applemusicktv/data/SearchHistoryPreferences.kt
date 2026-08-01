package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recent search terms. Typing on a TV remote is slow enough that re-running a past
 * search is worth a tap, so this is stored and shown whenever the box is empty.
 */
@Singleton
class SearchHistoryPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<String>> = _recent

    private fun load(): List<String> =
        (prefs.getString("terms", "") ?: "")
            .split(SEP)
            .filter { it.isNotBlank() }

    fun add(term: String) {
        val t = term.trim()
        if (t.length < 2) return
        // Case-insensitive de-dupe, most recent first.
        val next = (listOf(t) + _recent.value.filterNot { it.equals(t, ignoreCase = true) })
            .take(MAX)
        prefs.edit { putString("terms", next.joinToString(SEP)) }
        _recent.value = next
    }

    fun remove(term: String) {
        val next = _recent.value.filterNot { it.equals(term, ignoreCase = true) }
        prefs.edit { putString("terms", next.joinToString(SEP)) }
        _recent.value = next
    }

    fun clear() {
        prefs.edit { remove("terms") }
        _recent.value = emptyList()
    }

    private companion object {
        // Newline can't appear in a search box, so it's a safe separator.
        const val SEP = "\n"
        const val MAX = 12
    }
}
