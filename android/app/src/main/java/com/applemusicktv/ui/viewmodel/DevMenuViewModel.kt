package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.CrossfadePreferences
import com.applemusicktv.data.LyricsOffsetPreferences
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.data.StandalonePreferences
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.BeatAnalyzer
import com.applemusicktv.data.NetworkLog
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
    val standaloneOn:   Boolean        = false,
    val lyricsOffsetMs: Long           = 0L,
    val crossfadeMs:    Long           = 7_000L,
    val beatLatencyMs:  Long           = 0L,
    val logs:           List<LogEntry> = emptyList(),
)

@HiltViewModel
class DevMenuViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val webServer: InAppWebServer,
    private val mutPrefs: MutPreferences,
    private val serverPrefs: ServerPreferences,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
    private val crossfadePrefs: CrossfadePreferences,
    private val standalonePrefs: StandalonePreferences,
    private val beatAnalyzer: BeatAnalyzer,
) : ViewModel() {

    private val _state = MutableStateFlow(DevMenuState(
        webServerUrl   = webServer.serverUrl(),
        pcServerIp     = serverPrefs.getPcServerIp(),
        lyricsOffsetMs = lyricsOffsetPrefs.getOffset(),
        crossfadeMs    = crossfadePrefs.getDuration(),
        beatLatencyMs  = beatAnalyzer.latencyMs,
        standaloneOn   = standalonePrefs.isEnabled(),
    ))
    val state: StateFlow<DevMenuState> = _state

    init { refresh() }

    fun refresh(onDone: () -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(webServerUrl = webServer.serverUrl()) }
        log("INFO", "Refreshing status...")
        runCatching { repo.getAuthStatus() }.onSuccess { s ->
            val localHasMUT = mutPrefs.hasMUT()
            _state.update { it.copy(serverOk = true, hasBearer = s.hasBearer, hasMUT = localHasMUT, mutSetAt = s.mutSetAt, standaloneMode = false) }
            log(if (localHasMUT) "OK" else "WARN", "MUT: ${if (localHasMUT) "active" else "not set"}")
            log(if (s.hasBearer) "OK" else "WARN", "Bearer: ${if (s.hasBearer) "active" else "not ready"}")
        }.onFailure {
            // Standalone mode (no PC server) is the normal case — the token still lives locally, so read
            // it from prefs here too. Otherwise the Account section shows "Music-User-Token: not set" even
            // when it's saved, because hasMUT was only populated on the server-reachable path.
            _state.update { s -> s.copy(serverOk = false, standaloneMode = true, hasMUT = mutPrefs.hasMUT()) }
            log(if (mutPrefs.hasMUT()) "OK" else "WARN", "Server unreachable — standalone; MUT ${if (mutPrefs.hasMUT()) "active" else "not set"}")
        }
        onDone()   // reload Home/Library AFTER status (incl. reachability) is settled
    }

    fun recheckServer(playerVm: PlayerViewModel, onDone: () -> Unit = {}) = viewModelScope.launch {
        log("INFO", "Re-checking server...")
        playerVm.recheckServer().join()   // flips serverReachable FIRST
        refresh()
        onDone()                          // …then reload Home/Library on the fresh path (was a race)
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

    // ── User settings (mirror of the phone web page) ──────────────────────
    fun setCrossfade(ms: Long) {
        crossfadePrefs.setDuration(ms)
        val v = crossfadePrefs.getDuration()
        _state.update { it.copy(crossfadeMs = v) }
        log("OK", "Crossfade ${"%.1f".format(v / 1000f)}s — from next song")
    }

    fun setLyricsOffset(ms: Long) {
        lyricsOffsetPrefs.setOffset(ms)
        _state.update { it.copy(lyricsOffsetMs = lyricsOffsetPrefs.getOffset()) }
    }


    fun setBeatLatency(ms: Long) {
        val v = ms.coerceIn(0L, 500L)
        beatAnalyzer.latencyMs = v
        beatAnalyzer.resetBeat()
        _state.update { it.copy(beatLatencyMs = v) }
    }

    fun toggleStandalone() {
        val on = !standalonePrefs.isEnabled()
        standalonePrefs.setEnabled(on)
        _state.update { it.copy(standaloneOn = on) }
        log("OK", "Standalone ${if (on) "ON" else "OFF"} — from next song")
    }

    /** Pre-formatted HTTP lines from the direct client's interceptor. */
    fun networkLogs(): List<String> = NetworkLog.getAll()

    fun clearLogs() = _state.update { it.copy(logs = emptyList()) }

    private fun log(level: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(logs = it.logs + LogEntry(level, "[$time] $message")) }
    }
}
