package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.LyricsOffsetPreferences
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.InAppWebServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class LogEntry(val level: String, val message: String)

data class DevMenuState(
    val serverOk:       Boolean        = false,
    val hasBearer:      Boolean        = false,
    val hasMUT:         Boolean        = false,
    val mutSetAt:       String?        = null,
    val webServerUrl:   String         = "",
    val pcServerIp:     String         = "",
    val standaloneMode: Boolean        = false,
    val lyricsOffsetMs: Long           = 0L,
    val logs:           List<LogEntry> = emptyList(),
)

@HiltViewModel
class DevMenuViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val webServer: InAppWebServer,
    private val mutPrefs: MutPreferences,
    private val serverPrefs: ServerPreferences,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(DevMenuState(
        webServerUrl   = webServer.serverUrl(),
        pcServerIp     = serverPrefs.getPcServerIp(),
        lyricsOffsetMs = lyricsOffsetPrefs.getOffset(),
    ))
    val state: StateFlow<DevMenuState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(webServerUrl = webServer.serverUrl()) }
        log("INFO", "Refreshing status...")
        runCatching { repo.getAuthStatus() }.onSuccess { s ->
            val localHasMUT = mutPrefs.hasMUT()
            _state.update { it.copy(serverOk = true, hasBearer = s.hasBearer, hasMUT = localHasMUT, mutSetAt = s.mutSetAt, standaloneMode = false) }
            log(if (localHasMUT) "OK" else "WARN", "MUT: ${if (localHasMUT) "active" else "not set"}")
            log(if (s.hasBearer) "OK" else "WARN", "Bearer: ${if (s.hasBearer) "active" else "not ready"}")
        }.onFailure {
            _state.update { s -> s.copy(serverOk = false, standaloneMode = true) }
            log("WARN", "Server unreachable — standalone mode active")
        }
    }

    fun setPcServerIp(ip: String) {
        serverPrefs.setPcServerIp(ip)
        _state.update { it.copy(pcServerIp = ip) }
        log("INFO", if (ip.isEmpty()) "PC server cleared — using default" else "PC server set to $ip → ${serverPrefs.effectiveBaseUrl()}")
    }

    fun setMUT(token: String) = viewModelScope.launch {
        log("INFO", "Saving Music-User-Token (${token.length} chars)...")
        runCatching { repo.setMUT(token) }.onSuccess {
            log("OK", "Token saved — library + streaming now active")
            refresh()
        }.onFailure { log("ERROR", "Failed: ${it.message}") }
    }

    fun clearMUT() = viewModelScope.launch {
        runCatching { repo.clearMUT() }.onSuccess {
            log("WARN", "Token cleared")
            refresh()
        }.onFailure { log("ERROR", it.message ?: "Failed") }
    }

    fun clearLogs() = _state.update { it.copy(logs = emptyList()) }

    private fun log(level: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(logs = it.logs + LogEntry(level, "[$time] $message")) }
    }
}
