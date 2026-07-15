package com.applemusicktv.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.applemusicktv.data.LyricsOffsetPreferences
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.media.InAppWebServer
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.AppleDirectClient
import com.applemusicktv.media.AppleMusicDrmCallback
import com.applemusicktv.media.BeatAnalyzer
import com.applemusicktv.media.BeatAwareRenderersFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RepeatMode { Off, One, All }

data class PlayerState(
    val currentSong:      Song?           = null,
    val song:             Song?           = null,
    val isPlaying:        Boolean         = false,
    val progressMs:       Long            = 0L,
    val queue:            List<Song>      = emptyList(),
    val queueIndex:       Int             = 0,
    val lyrics:           List<LyricLine> = emptyList(),
    val isFullStream:     Boolean         = false,
    val motionUrl:        String?         = null,
    val lyricsOffsetMs:   Long            = 0L,
    val isShuffled:       Boolean         = false,
    val repeatMode:       RepeatMode      = RepeatMode.Off,
    val sleepTimerEndsAt: Long?           = null,
    val mutExpired:       Boolean         = false,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MusicRepository,
    private val moshi: Moshi,
    private val mutPrefs: com.applemusicktv.data.MutPreferences,
    private val serverPrefs: ServerPreferences,
    private val appleClient: AppleDirectClient,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
    private val webServer: InAppWebServer,
    val beatAnalyzer: BeatAnalyzer,
) : ViewModel() {

    private fun hasMUT() = mutPrefs.hasMUT()
    private fun isStandalone() = !serverPrefs.hasPcServer()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("player_state", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var lastErrorKey: String? = null
    private var hasPlayedSomething = false
    var nowPlayingVisible = false
    private var mediaSession: androidx.media3.session.MediaSession? = null
    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var motionJob: kotlinx.coroutines.Job? = null
    private var fadeJob: kotlinx.coroutines.Job? = null
    private var crossfadeInProgress = false
    private val crossfadeDurationMs = 4_000L

    // True while the on-device (Widevine) path is driving playback, so the
    // error handler doesn't bounce back to the proxy in a loop.
    private var usingStandalone: Boolean = false



    @OptIn(UnstableApi::class)
    val player: ExoPlayer = run {
        // The proxy holds the HTTP connection open for a few seconds while it
        // decrypts (no bytes until mp4decrypt finishes), so give ExoPlayer
        // generous connect/read timeouts — otherwise the first play of a fresh
        // (uncached) song times out and silently fails, which looks like "some
        // songs don't play."
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory =
            androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,  // min buffer before playback starts
                60_000,  // max buffer (pre-cache 60s ahead)
                1_500,   // buffer to start after initial load
                3_000,   // buffer to restart after rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setRenderersFactory(
                BeatAwareRenderersFactory(context, beatAnalyzer)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), false, // don't yield to Fire TV Alexa audio focus steals
            )
            .setHandleAudioBecomingNoisy(false)
            .build().also { it.repeatMode = Player.REPEAT_MODE_OFF }
    }

    fun setLyricsOffset(ms: Long) {
        lyricsOffsetPrefs.setOffset(ms)
        _state.update { it.copy(lyricsOffsetMs = ms) }
    }

    init {
        _state.update { it.copy(lyricsOffsetMs = lyricsOffsetPrefs.getOffset()) }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                if (repeatMode != Player.REPEAT_MODE_OFF) player.repeatMode = Player.REPEAT_MODE_OFF
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                webServer.addLog("ERR", "${error.errorCodeName} — skip to next")
                advanceQueue()
            }
        })
        // A MediaSession makes the system route external controller / Bluetooth
        // media buttons (play/pause/next/prev) to our player — those don't come
        // through Activity.dispatchKeyEvent, which is why the controller buttons
        // seemed dead.
        mediaSession = androidx.media3.session.MediaSession.Builder(context, player)
            .setCallback(object : androidx.media3.session.MediaSession.Callback {
                override fun onConnect(
                    session: androidx.media3.session.MediaSession,
                    controller: androidx.media3.session.MediaSession.ControllerInfo,
                ): androidx.media3.session.MediaSession.ConnectionResult {
                    // Block Fire TV system controllers (Alexa, AudioMediaPlayerWrapper)
                    // from sending pause/stop commands that interrupt playback.
                    // Remote buttons still work via MainActivity.dispatchKeyEvent.
                    val pkg = controller.packageName
                    val allowed = pkg == context.packageName
                    webServer.addLog("PLR", "MediaSession connect pkg=$pkg allowed=$allowed")
                    return if (allowed) super.onConnect(session, controller)
                    else androidx.media3.session.MediaSession.ConnectionResult.reject()
                }
            })
            .build()
        player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun updateBtLatency() {
            val btTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
            val onBt = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in btTypes }
            beatAnalyzer.latencyMs = if (onBt) 200L else 0L
        }
        updateBtLatency()
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) { updateBtLatency() }
            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { updateBtLatency() }
        }, null)

        viewModelScope.launch { repo.authErrorFlow.collect { _state.update { it.copy(mutExpired = true) } } }
        pollProgress()
        restoreState()
        checkServerReachable()
    }

    /** Health-check the configured server; flips to/from standalone accordingly. */
    fun recheckServer() = viewModelScope.launch {
        val up = repo.pingServer()
        val wasDown = !serverPrefs.serverReachable
        serverPrefs.serverReachable = up
        Log.i("PlayerVM", if (up) "Server reachable — proxy mode" else "Server DOWN — standalone mode")
        if (!up) {
            repo.prepareStandalone()
        } else if (wasDown) {
            // Recovered — reset standalone flag so next play uses proxy.
            usingStandalone = false
            Log.i("PlayerVM", "Server recovered — returning to proxy mode")
        }
    }

    private fun checkServerReachable() = viewModelScope.launch {
        recheckServer()
        // Recheck every 30s so the app recovers when the PC comes back online.
        while (true) {
            kotlinx.coroutines.delay(30_000)
            recheckServer()
        }
    }

    // True only when the configured server (default proxy OR a Dev-menu IP)
    // failed its health check. The default PROXY_BASE_URL is still a real
    // server, so "no Dev-menu IP" must NOT by itself force standalone —
    // otherwise normal proxy playback (albums/playlists) breaks.
    private fun useStandalone() = !serverPrefs.serverReachable

    private fun saveState() {
        val s = _state.value
        val song = s.currentSong ?: return
        val adapter = moshi.adapter(Song::class.java)
        val listType = Types.newParameterizedType(List::class.java, Song::class.java)
        val listAdapter = moshi.adapter<List<Song>>(listType)
        prefs.edit {
            putString("song",  adapter.toJson(song))
            putString("queue", listAdapter.toJson(s.queue))
            putInt("queue_index", s.queueIndex)
            putLong("position_ms", player.currentPosition)
            putBoolean("full_stream", s.isFullStream)
        }
    }

    private fun restoreState() = viewModelScope.launch {
        val songJson = prefs.getString("song", null) ?: return@launch
        if (hasPlayedSomething) return@launch
        try {
            val adapter = moshi.adapter(Song::class.java)
            val listType = Types.newParameterizedType(List::class.java, Song::class.java)
            val listAdapter = moshi.adapter<List<Song>>(listType)
            val song  = adapter.fromJson(songJson) ?: return@launch
            val queue = listAdapter.fromJson(prefs.getString("queue", "[]") ?: "[]") ?: listOf(song)
            val idx   = prefs.getInt("queue_index", 0).coerceIn(0, queue.lastIndex)
            val posMs = prefs.getLong("position_ms", 0L)
            val full  = prefs.getBoolean("full_stream", false)
            _state.update { it.copy(currentSong = song, song = song, queue = queue, queueIndex = idx, isFullStream = full) }
            val uri = if (full) repo.streamUrl(song.id) else (song.previewUrl ?: repo.streamUrl(song.id))
            webServer.addLog("PLR", "restoreState idx=$idx posMs=$posMs song=${song.title}")
            player.setMediaItem(buildMediaItem(song, uri), posMs)
            player.prepare()
        } catch (_: Exception) {}
    }

    fun playFromQueue(idx: Int) = playQueueItem(idx)

    private fun advanceQueue() {
        val s = _state.value
        // Repeat one: replay current
        if (s.repeatMode == RepeatMode.One) { playQueueItem(s.queueIndex, skipFadeIn = true); return }

        val nextIdx = if (s.isShuffled && s.queue.size > 1) {
            val candidates = s.queue.indices.filter { it != s.queueIndex }
            candidates.random()
        } else s.queueIndex + 1

        webServer.addLog("PLR", "advance → idx=$nextIdx / ${s.queue.size}")
        if (nextIdx >= s.queue.size) {
            if (s.repeatMode == RepeatMode.All) { playQueueItem(0); return }
            // Queue exhausted — fetch related songs and continue
            val lastSong = s.queue.lastOrNull()
            if (lastSong != null) {
                viewModelScope.launch {
                    val related = repo.getRelatedSongs(lastSong.id).getOrDefault(emptyList())
                    if (related.isNotEmpty()) {
                        _state.update { it.copy(queue = it.queue + related) }
                        playQueueItem(nextIdx)
                    }
                }
            }
            return
        }
        playQueueItem(nextIdx)
    }

    fun toggleShuffle() { _state.update { it.copy(isShuffled = !it.isShuffled) } }
    fun toggleRepeat() {
        _state.update { it.copy(repeatMode = when (it.repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        })}
    }
    fun setSleepTimer(minutes: Int) { _state.update { it.copy(sleepTimerEndsAt = System.currentTimeMillis() + minutes * 60_000L) } }
    fun cancelSleepTimer() { _state.update { it.copy(sleepTimerEndsAt = null) } }
    fun dismissMutExpired() { _state.update { it.copy(mutExpired = false) } }

    private fun playQueueItem(idx: Int, skipFadeIn: Boolean = false) {
        val q = _state.value.queue
        if (q.isEmpty() || idx !in q.indices) {
            webServer.addLog("PLR", "playQueueItem idx=$idx out of bounds (size=${q.size}) — stopping")
            return
        }
        beatAnalyzer.resetBeat()
        // Cancel any in-progress crossfade
        fadeJob?.cancel()
        crossfadeInProgress = false

        val song = q[idx]
        val full = _state.value.isFullStream
        val uri = if (full) repo.streamUrl(song.id) else (song.previewUrl ?: repo.streamUrl(song.id))
        webServer.addLog("PLR", "playQueueItem idx=$idx song=${song.title}")
        _state.update { it.copy(currentSong = song, song = song, queueIndex = idx, lyrics = emptyList(), motionUrl = null) }
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItem(buildMediaItem(song, uri))
        player.prepare()
        if (skipFadeIn) {
            player.volume = 1f
        } else {
            player.volume = 0f
        }
        player.play()
        if (!skipFadeIn) {
            fadeJob = viewModelScope.launch {
                val steps = 40
                val stepMs = crossfadeDurationMs / steps
                for (i in 1..steps) {
                    player.volume = (i.toFloat() / steps).coerceAtMost(1f)
                    delay(stepMs)
                }
            }
        }
        if (full) loadLyrics(song.id)
        loadMotion(song.id)
        // Pre-warm server cache for next song — hits /prefetch which returns instantly
        val nextSong = q.getOrNull(idx + 1)
        if (full && nextSong != null) viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL(repo.prefetchUrl(nextSong.id)).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                conn.getResponseCode()
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun playSong(song: Song, useFullStream: Boolean = hasMUT()) {
        usingStandalone = false
        lastErrorKey = null
        hasPlayedSomething = true
        _state.update { it.copy(currentSong = song, song = song, queue = listOf(song), queueIndex = 0, lyrics = emptyList(), isFullStream = useFullStream, motionUrl = null) }
        val uri = if (useFullStream) repo.streamUrl(song.id) else (song.previewUrl ?: repo.streamUrl(song.id))
        player.setMediaItem(buildMediaItem(song, uri))
        player.prepare()
        player.play()
        if (useFullStream) loadLyrics(song.id)
        loadMotion(song.id)
    }

    fun playAlbum(songs: List<Song>, startIndex: Int = 0, useFullStream: Boolean = hasMUT()) {
        if (songs.isEmpty()) return
        val stack = Thread.currentThread().stackTrace
        val callers = (3..7).mapNotNull { stack.getOrNull(it) }.joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        webServer.addLog("PLR", "playAlbum size=${songs.size} idx=$startIndex << $callers")
        usingStandalone = false
        lastErrorKey = null
        hasPlayedSomething = true
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        val song = songs[idx]
        _state.update { it.copy(queue = songs, isFullStream = useFullStream) }
        playQueueItem(idx)
    }

    fun playStation(stationId: String) = viewModelScope.launch {
        val songs = repo.getStationTracks(stationId).getOrDefault(emptyList())
        if (songs.isNotEmpty()) playAlbum(songs)
    }

    @OptIn(UnstableApi::class)
    fun playLiveStation(stationId: String) = viewModelScope.launch {
        val info = repo.getStationStream(stationId).getOrNull() ?: return@launch
        val url = info.liveStreamUrl ?: return@launch
        Log.d("PlayerVM", "playLiveStation id=$stationId url=${url.take(80)} keyUri=${info.drmKeyUri?.take(40)}")
        usingStandalone = false
        lastErrorKey = null
        val fakeSong = com.applemusicktv.data.model.Song(
            id = stationId, title = "Apple Music Radio", artistName = "Apple Music",
            albumName = "", durationMs = 0L, artworkUrl = null, artworkBgColor = null,
            previewUrl = null, hasLyrics = false,
        )
        _state.update { it.copy(queue = listOf(fakeSong), currentSong = fakeSong, song = fakeSong, queueIndex = 0, lyrics = emptyList(), isFullStream = true, motionUrl = null) }

        val keyUri = info.drmKeyUri
        val adamId = info.adamId ?: stationId.replace(Regex("^ra\\."), "")
        if (keyUri != null) {
            try {
                val bearer = appleClient.getBearer()
                val mut = mutPrefs.getMUT()
                val drmCallback = AppleMusicDrmCallback(adamId, keyUri, bearer, mut)
                val drmManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(false)
                    .build(drmCallback)
                val mediaSource = DefaultMediaSourceFactory(context)
                    .setDrmSessionManagerProvider { drmManager }
                    .createMediaSource(buildMediaItem(fakeSong, url))
                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
                return@launch
            } catch (e: Exception) {
                Log.e("PlayerVM", "Live station DRM failed: ${e.message}")
            }
        }
        // No DRM key or DRM failed — try plain HLS
        player.setMediaItem(buildMediaItem(fakeSong, url))
        player.prepare()
        player.play()
    }

    @OptIn(UnstableApi::class)
    private fun playStandalone(song: Song) = viewModelScope.launch {
        usingStandalone = true
        var usedWidevine = false
        try {
            val bearer = appleClient.getBearer()
            val mut    = mutPrefs.getMUT()
            if (bearer.isNotEmpty() && mut.isNotEmpty()) {
                val wb = appleClient.getWebPlayback(song.id, bearer, mut)
                val drmCallback = AppleMusicDrmCallback(wb.adamId, wb.keyUri, bearer, mut)
                val drmManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(false)
                    .build(drmCallback)
                val mediaSource = DefaultMediaSourceFactory(context)
                    .setDrmSessionManagerProvider { drmManager }
                    .createMediaSource(buildMediaItem(song, wb.hlsUrl))
                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
                usedWidevine = true
            }
        } catch (e: Exception) {
            Log.e("PlayerVM", "Standalone Widevine failed: ${e.message}")
        }

        if (!usedWidevine) {
            Log.d("PlayerVM", "Falling back to proxy stream for ${song.id}")
            val uri = repo.streamUrl(song.id)
            player.setMediaItem(buildMediaItem(song, uri))
            player.prepare()
            player.play()
        }
        loadLyrics(song.id)
        loadMotion(song.id)
    }

    fun pause() { player.pause() }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }

    fun next() { fadeJob?.cancel(); crossfadeInProgress = false; player.volume = 1f; playQueueItem(_state.value.queueIndex + 1, skipFadeIn = true) }
    fun prev() {
        fadeJob?.cancel(); crossfadeInProgress = false; player.volume = 1f
        val prevIdx = _state.value.queueIndex - 1
        if (prevIdx >= 0) playQueueItem(prevIdx, skipFadeIn = true) else player.seekTo(0L)
    }

    fun moveQueueItem(from: Int, to: Int) {
        val q = _state.value.queue.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        val item = q.removeAt(from)
        q.add(to, item)
        val newIdx = when {
            from == _state.value.queueIndex -> to
            from < _state.value.queueIndex && to >= _state.value.queueIndex -> _state.value.queueIndex - 1
            from > _state.value.queueIndex && to <= _state.value.queueIndex -> _state.value.queueIndex + 1
            else -> _state.value.queueIndex
        }
        _state.update { it.copy(queue = q, queueIndex = newIdx) }
    }
    fun seekForward() { webServer.addLog("PLR", "seekForward pos=${player.currentPosition}"); player.seekTo((player.currentPosition + 15_000L).coerceAtMost(player.duration.coerceAtLeast(0L))) }
    fun seekBack()    { webServer.addLog("PLR", "seekBack pos=${player.currentPosition}"); player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L)) }

    fun addToQueue(song: Song) { _state.update { it.copy(queue = it.queue + song) } }

    fun playNext(song: Song) {
        val q = _state.value.queue.toMutableList()
        q.add((_state.value.queueIndex + 1).coerceIn(0, q.size), song)
        _state.update { it.copy(queue = q) }
    }

    private fun loadLyrics(songId: String) {
        lyricsJob?.cancel()
        val song = _state.value.currentSong
        lyricsJob = viewModelScope.launch {
            repo.getLyrics(
                songId,
                title      = song?.title ?: "",
                artist     = song?.artistName ?: "",
                durationSec = (song?.durationMs ?: 0L) / 1000,
            ).onSuccess { lines ->
                if (_state.value.currentSong?.id == songId)
                    _state.update { it.copy(lyrics = lines) }
            }
        }
    }

    private fun loadMotion(songId: String) {
        motionJob?.cancel()
        motionJob = viewModelScope.launch {
            _state.update { it.copy(motionUrl = null) }
            repo.getMotion(songId).onSuccess { url ->
                if (_state.value.currentSong?.id == songId)
                    _state.update { it.copy(motionUrl = url) }
            }
        }
    }

    private fun pollProgress() = viewModelScope.launch {
        while (true) {
            val playing = player.isPlaying
            val playState = player.playbackState
            if (playing) _state.update { it.copy(progressMs = player.currentPosition) }
            if (playState == Player.STATE_ENDED) advanceQueue()
            val timerEnd = _state.value.sleepTimerEndsAt
            if (timerEnd != null && System.currentTimeMillis() >= timerEnd) {
                player.pause()
                _state.update { it.copy(sleepTimerEndsAt = null) }
            }

            // Start crossfade when within crossfadeDurationMs of natural end
            if (playing && playState == Player.STATE_READY && !crossfadeInProgress) {
                val dur = player.duration
                val pos = player.currentPosition
                val remaining = dur - pos
                val q = _state.value
                val hasNext = q.queueIndex + 1 < q.queue.size
                if (dur > 0 && remaining in 1..crossfadeDurationMs && hasNext) {
                    crossfadeInProgress = true
                    val startVol = player.volume
                    fadeJob = viewModelScope.launch {
                        val steps = 40
                        val stepMs = remaining / steps
                        for (i in 1..steps) {
                            player.volume = (startVol * (1f - i.toFloat() / steps)).coerceAtLeast(0f)
                            delay(stepMs)
                        }
                    }
                }
            }

            delay(if (playing && nowPlayingVisible) 200L else if (playing) 1_000L else 500L)
        }
    }

    private fun buildMediaItem(song: Song, uri: String) = MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artistName)
                .setAlbumTitle(song.albumName)
                .setArtworkUri(song.artworkUrl(600)?.let { android.net.Uri.parse(it) })
                .build()
        ).build()

    override fun onCleared() {
        saveState()
        lyricsJob?.cancel()
        motionJob?.cancel()
        fadeJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        player.stop()
        player.clearMediaItems()
        player.release()
        super.onCleared()
    }
}
