package com.applemusicktv.data

import java.text.SimpleDateFormat
import java.util.*

object NetworkLog {
    private val entries = ArrayDeque<String>(500)

    fun add(method: String, path: String, code: Int, ms: Long) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val icon = when {
            code in 200..299 -> "✓"
            code >= 400 -> "✗"
            else -> "→"
        }
        // Strip scheme+host so the log shows just the request path.
        val short = path.replace(Regex("^https?://[^/]+"), "").take(60)
        synchronized(entries) {
            if (entries.size >= 500) entries.removeFirst()
            entries.addLast("[$t] $icon $method $short → $code (${ms}ms)")
        }
    }

    fun getAll(): List<String> = synchronized(entries) { entries.toList() }
    fun clear() = synchronized(entries) { entries.clear() }
}
