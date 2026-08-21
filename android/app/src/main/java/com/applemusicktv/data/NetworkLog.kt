package com.applemusicktv.data

import java.text.SimpleDateFormat
import java.util.*

object NetworkLog {
    private val entries = ArrayDeque<String>(500)

    /** Live sink for each new line — the web server wires this to stream network logs on :8081/SSE. */
    @Volatile var listener: ((String) -> Unit)? = null

    private fun record(line: String) {
        synchronized(entries) {
            if (entries.size >= 500) entries.removeFirst()
            entries.addLast(line)
        }
        listener?.invoke(line)
    }

    fun add(method: String, path: String, code: Int, ms: Long) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val icon = when {
            code in 200..299 -> "✓"
            code >= 400 -> "✗"
            else -> "→"
        }
        // Strip scheme+host so the log shows just the request path.
        val short = path.replace(Regex("^https?://[^/]+"), "").take(60)
        record("[$t] $icon $method $short → $code (${ms}ms)")
    }

    /** Free-form tagged line (not a network request). */
    fun line(tag: String, msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        record("[$t] $tag $msg")
    }

    fun getAll(): List<String> = synchronized(entries) { entries.toList() }
    fun clear() = synchronized(entries) { entries.clear() }
}
