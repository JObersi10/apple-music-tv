package com.applemusicktv.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.applemusicktv.media.AppleDirectClient
import com.applemusicktv.media.AppleMusicDrmCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MvUiState(
    val loading:  Boolean = true,
    val error:    String? = null,
    val playing:  Boolean = false,
    val title:    String  = "",
    val artist:   String  = "",
    val positionMs: Long   = 0,
    val durationMs: Long    = 0,
)

/**
 * Fullscreen music-video playback. A wholly separate ExoPlayer from the audio
 * [PlayerViewModel] — video needs its own surface, DRM session and 1080p-capped
 * track selector, and the audio player is paused while a video is on screen.
 */
@HiltViewModel
class MusicVideoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mutPrefs: com.applemusicktv.data.MutPreferences,
    private val appleClient: AppleDirectClient,
) : ViewModel() {

    private val _state = MutableStateFlow(MvUiState())
    val state: StateFlow<MvUiState> = _state

    var player: ExoPlayer? = null
        private set

    private var progressJob: kotlinx.coroutines.Job? = null

    @OptIn(UnstableApi::class)
    fun load(mvId: String, title: String, artist: String) {
        _state.value = MvUiState(loading = true, title = title, artist = artist)
        viewModelScope.launch {
            try {
                val bearer = appleClient.getBearer()
                val mut = mutPrefs.getMUT()
                if (bearer.isEmpty() || mut.isEmpty()) {
                    _state.value = _state.value.copy(loading = false, error = "Sign-in required")
                    return@launch
                }
                val mv = withContext(Dispatchers.IO) { appleClient.getMusicVideoPlayback(mvId, bearer, mut) }

                // Write the corrected master + media playlists to disk; ExoPlayer plays
                // the master via file:// and streams the mvod segments they reference.
                val masterUri = withContext(Dispatchers.IO) {
                    val dir = context.cacheDir
                    java.io.File(dir, com.applemusicktv.media.MV_VIDEO_FILE).writeText(mv.videoText)
                    java.io.File(dir, com.applemusicktv.media.MV_AUDIO_FILE).writeText(mv.audioText)
                    val master = java.io.File(dir, com.applemusicktv.media.MV_MASTER_FILE)
                    master.writeText(mv.masterText)
                    android.net.Uri.fromFile(master).toString()
                }

                val drmCallback = AppleMusicDrmCallback(mv.adamId, mv.keyUri, bearer, mut)
                val drmManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    // MV HLS carries SEPARATE key ids for the audio and video tracks.
                    // multiSession=true opens one Widevine session per KID so both the
                    // audio key and the video key load — with a single session only one
                    // track's key arrives and the other renderer dies "Crypto key not
                    // available" (it was the video renderer that failed).
                    .setMultiSession(true)
                    .build(drmCallback)

                val httpFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(30_000).setReadTimeoutMs(30_000)
                    .setAllowCrossProtocolRedirects(true)
                // DefaultDataSource handles the file:// master + https mvod segments.
                val dataFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
                val source = HlsMediaSource.Factory(dataFactory)
                    .setDrmSessionManagerProvider { drmManager }
                    .createMediaSource(MediaItem.fromUri(masterUri))

                // Cap the adaptive ladder at 1080p — the master offers nothing higher,
                // but this keeps a flaky WAN from ever grabbing a bigger variant.
                val trackSelector = DefaultTrackSelector(context).apply {
                    parameters = buildUponParameters().setMaxVideoSize(1920, 1080).build()
                }
                val exo = ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector)
                    .build()
                exo.setMediaSource(source)
                exo.prepare()
                exo.playWhenReady = true
                exo.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.value = _state.value.copy(playing = isPlaying)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            _state.value = _state.value.copy(loading = false, durationMs = exo.duration.coerceAtLeast(0))
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("AMMV", "MV playback error: ${error.message}", error)
                        _state.value = _state.value.copy(loading = false, error = error.message ?: "Playback failed")
                    }
                })
                player = exo
                _state.value = _state.value.copy(loading = false)
                startProgress()
            } catch (e: Exception) {
                Log.e("AMMV", "MV load failed: ${e.message}", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load video")
            }
        }
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                player?.let {
                    _state.value = _state.value.copy(
                        positionMs = it.currentPosition.coerceAtLeast(0),
                        durationMs = it.duration.coerceAtLeast(0),
                    )
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    fun togglePlayPause() { player?.let { it.playWhenReady = !it.playWhenReady } }
    fun seekBy(deltaMs: Long) { player?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) } }

    fun release() {
        progressJob?.cancel()
        player?.release()
        player = null
    }

    override fun onCleared() { release() }
}
