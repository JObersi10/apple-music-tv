package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor() : ViewModel() {
    private val _goToNowPlaying = MutableStateFlow(false)
    val goToNowPlaying: StateFlow<Boolean> = _goToNowPlaying

    // Toggles queue/lyrics panel in NowPlaying screen
    private val _toggleQueue = MutableStateFlow(0)
    val toggleQueue: StateFlow<Int> = _toggleQueue

    // MainActivity sets this so Menu key knows current screen
    var isOnNowPlaying: Boolean = false

    // When a fullscreen music video is on screen, MainActivity must NOT eat media/menu
    // keys for the audio player — the video screen owns transport, and Menu shouldn't
    // jump to Now Playing.
    var isOnMusicVideo: Boolean = false

    fun navigateToNowPlaying() { _goToNowPlaying.value = true }
    fun consumeNowPlayingNavigation() { _goToNowPlaying.value = false }
    fun toggleQueuePanel() { _toggleQueue.value++ }
}
