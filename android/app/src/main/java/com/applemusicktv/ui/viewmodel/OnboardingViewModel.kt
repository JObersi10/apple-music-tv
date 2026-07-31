package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.CrossfadePreferences
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.OnboardingPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.InAppWebServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ServerCheck { Idle, Checking, Ok, Failed }

data class OnboardingState(
    val step:         Int          = 1,
    val ipDraft:      String       = "",
    val serverCheck:  ServerCheck  = ServerCheck.Idle,
    val serverError:  String?      = null,
    val phoneUrl:     String       = "",
    val hasMut:       Boolean      = false,
    val remoteChoice: String       = OnboardingPreferences.REMOTE_AUTO,
    val crossfadeMs:  Long         = CrossfadePreferences.DEFAULT_MS,
    /** A token was already stored when step 2 opened — don't skip past the screen. */
    val tokenPresetOnEntry: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboarding: OnboardingPreferences,
    private val serverPrefs: ServerPreferences,
    private val mutPrefs: MutPreferences,
    private val crossfadePrefs: CrossfadePreferences,
    private val repo: MusicRepository,
    private val webServer: InAppWebServer,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OnboardingState(
            ipDraft = serverPrefs.getPcServerIp(),
            phoneUrl = webServer.serverUrl(),
            hasMut = mutPrefs.hasMUT(),
            crossfadeMs = crossfadePrefs.getDuration(),
            remoteChoice = onboarding.remoteOverride,
        )
    )
    val state: StateFlow<OnboardingState> = _state

    private var mutPollJob: Job? = null

    val totalSteps = 5

    fun setIpDraft(v: String) = _state.update { it.copy(ipDraft = v, serverCheck = ServerCheck.Idle, serverError = null) }

    /**
     * Save the IP and ping it. We save first because the Retrofit base URL is derived
     * from ServerPreferences — pinging before saving would test the old address.
     */
    fun checkServer() {
        val ip = _state.value.ipDraft.trim()
        serverPrefs.setPcServerIp(ip)
        _state.update { it.copy(serverCheck = ServerCheck.Checking, serverError = null) }
        viewModelScope.launch {
            val ok = runCatching { repo.pingServer() }.getOrDefault(false)
            serverPrefs.serverReachable = ok
            _state.update {
                it.copy(
                    serverCheck = if (ok) ServerCheck.Ok else ServerCheck.Failed,
                    serverError = if (ok) null else describeFailure(ip),
                )
            }
        }
    }

    private fun describeFailure(ip: String): String = when {
        ip.isEmpty() -> "No address entered."
        ip.contains(' ') -> "Address contains a space."
        !ip.startsWith("http") && !ip.matches(Regex("""^[\d.]+(:\d+)?$""")) ->
            "Doesn't look like an IP. Expected something like 192.168.1.190"
        else -> "Couldn't reach $ip. Check the server is running (bun run start) and that the TV and PC are on the same network."
    }

    /**
     * Poll /auth/status while step 2 is open so the flow advances by itself once the
     * token is pasted on the phone.
     *
     * Only auto-advances on a false→true transition. The proxy persists the MUT in
     * auth-state.json across restarts, so a token is very often already present when
     * the step opens — advancing on that flashed straight past the screen.
     */
    fun startMutPolling() {
        if (mutPollJob?.isActive == true) return
        mutPollJob = viewModelScope.launch {
            var sawTokenOnEntry: Boolean? = null
            while (isActive) {
                // Only the token stored ON THE DEVICE counts. The proxy persists its own
                // copy in auth-state.json, so asking the server "do you have a MUT?"
                // returns true long after the app's own copy is gone — which reported a
                // token that isn't there, and the app needs its local copy for the
                // X-Music-User-Token header on every library request.
                val has = mutPrefs.hasMUT()
                if (has != _state.value.hasMut) _state.update { it.copy(hasMut = has) }
                if (sawTokenOnEntry == null) {
                    sawTokenOnEntry = has
                    _state.update { it.copy(tokenPresetOnEntry = has) }
                }
                if (has && sawTokenOnEntry == false) {
                    // The phone POSTs to the app, which syncs to the proxy — but if the
                    // token only landed server-side, push our copy up too.
                    runCatching { repo.syncMUTToServer(mutPrefs.getMUT()) }
                    next()
                    return@launch
                }
                delay(2_000)
            }
        }
    }

    fun stopMutPolling() { mutPollJob?.cancel(); mutPollJob = null }

    fun setRemote(choice: String) {
        onboarding.remoteOverride = choice
        _state.update { it.copy(remoteChoice = choice) }
    }

    fun setCrossfade(ms: Long) {
        crossfadePrefs.setDuration(ms)
        _state.update { it.copy(crossfadeMs = crossfadePrefs.getDuration()) }
    }

    fun next() = _state.update { it.copy(step = (it.step + 1).coerceAtMost(totalSteps)) }
    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(1)) }

    fun finish() {
        stopMutPolling()
        onboarding.markCompleted()
    }

}
