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

/** One selectable subtitle option. index -1 = the synthetic "Off". */
data class SubtitleOption(val label: String, val index: Int)

data class MvUiState(
    val loading:  Boolean = true,
    val error:    String? = null,
    val playing:  Boolean = false,
    val title:    String  = "",
    val artist:   String  = "",
    val positionMs: Long   = 0,
    val durationMs: Long    = 0,
    val subtitles:  List<SubtitleOption> = emptyList(),
    val subtitleIndex: Int = -1,   // -1 = Off
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

    // Held so it can be released with the player — an externally-provided DrmSessionManager
    // is NOT auto-released by ExoPlayer, and a leaked Widevine session starved the secure
    // video decoder on the next video (it played audio-only).
    private var drmManager: DefaultDrmSessionManager? = null
    private var trackSelector: DefaultTrackSelector? = null
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
                    mv.subsText?.let { java.io.File(dir, com.applemusicktv.media.MV_SUBS_FILE).writeText(it) }
                    val master = java.io.File(dir, com.applemusicktv.media.MV_MASTER_FILE)
                    master.writeText(mv.masterText)
                    android.net.Uri.fromFile(master).toString()
                }

                val drmCallback = AppleMusicDrmCallback(mv.adamId, mv.keyUri, bearer, mut, mv.keyMap)
                val drmManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    // MV HLS carries SEPARATE key ids for the audio and video tracks.
                    // multiSession=true opens one Widevine session per KID so both the
                    // audio key and the video key load — with a single session only one
                    // track's key arrives and the other renderer dies "Crypto key not
                    // available" (it was the video renderer that failed).
                    .setMultiSession(true)
                    .build(drmCallback)
                    .also { this@MusicVideoViewModel.drmManager = it }

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
                val selector = DefaultTrackSelector(context).apply {
                    // Cap at 1080p and start with subtitles OFF (Apple defaults to Auto/Off too).
                    parameters = buildUponParameters()
                        .setMaxVideoSize(1920, 1080)
                        .setPreferredTextLanguage(null)
                        .setSelectUndeterminedTextLanguage(false)
                        .setDisabledTextTrackSelectionFlags(0)
                        .build()
                }
                trackSelector = selector
                val exo = ExoPlayer.Builder(context)
                    .setTrackSelector(selector)
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
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val opts = mutableListOf(SubtitleOption("Off", -1))
                        var sel = -1
                        var i = 0
                        for (group in tracks.groups) {
                            if (group.type != C.TRACK_TYPE_TEXT) continue
                            for (t in 0 until group.length) {
                                val fmt = group.getTrackFormat(t)
                                val name = fmt.label ?: fmt.language?.uppercase() ?: "Subtitles"
                                opts.add(SubtitleOption(name, i))
                                if (group.isTrackSelected(t)) sel = i
                                i++
                            }
                        }
                        _state.value = _state.value.copy(
                            subtitles = if (opts.size > 1) opts else emptyList(),
                            subtitleIndex = sel,
                        )
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

    /** Off (-1) disables the text renderer; any real track enables captions. Our MV master
     *  carries a single English rendition, so this is effectively an Off/English toggle. */
    @OptIn(UnstableApi::class)
    fun setSubtitle(index: Int) {
        val sel = trackSelector ?: return
        sel.parameters = sel.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, index < 0)
            .setPreferredTextLanguage(if (index < 0) null else "en")
            .setSelectUndeterminedTextLanguage(index >= 0)
            .build()
        _state.value = _state.value.copy(subtitleIndex = index)
    }

    fun togglePlayPause() { player?.let { it.playWhenReady = !it.playWhenReady } }
    fun seekBy(deltaMs: Long) { player?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) } }

    fun release() {
        progressJob?.cancel()
        player?.release()
        player = null
        drmManager?.release()
        drmManager = null
    }

    override fun onCleared() { release() }
}
