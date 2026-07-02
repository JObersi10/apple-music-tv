package com.applemusicktv.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
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
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.AppleDirectClient
import com.applemusicktv.media.AppleMusicDrmCallback
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerState(
    val currentSong:    Song?           = null,
    val song:           Song?           = null,
    val isPlaying:      Boolean         = false,
    val progressMs:     Long            = 0L,
    val queue:          List<Song>      = emptyList(),
    val queueIndex:     Int             = 0,
    val lyrics:         List<LyricLine> = emptyList(),
    val isFullStream:   Boolean         = false,
    val motionUrl:      String?         = null,
    val lyricsOffsetMs: Long            = 0L,
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
) : ViewModel() {

    private fun hasMUT() = mutPrefs.hasMUT()
    private fun isStandalone() = !serverPrefs.hasPcServer()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("player_state", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var lastErrorKey: String? = null
    private var mediaSession: androidx.media3.session.MediaSession? = null
    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var motionJob: kotlinx.coroutines.Job? = null

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
            .setReadTimeoutMs(120_000)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory =
            androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

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
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
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
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Log the real cause so failing songs are diagnosable, then try
                // to recover ONCE by re-preparing (a stalled first-decrypt often
                // succeeds on the retry, now that it's cached). Bounded so a
                // genuinely broken track doesn't loop forever.
                val msg = "${error.errorCodeName}: ${error.message}"
                Log.e("PlayerVM", "Playback error for ${_state.value.currentSong?.title}: $msg")
                com.applemusicktv.data.NetworkLog.add(
                    "ERR", repo.streamUrl(_state.value.currentSong?.id ?: "?"), error.errorCode, 0
                )
                val song = _state.value.currentSong ?: return
                if (lastErrorKey != song.id) {
                    lastErrorKey = song.id
                    // Retry once — first decrypt is slow; a timeout often succeeds on retry.
                    val dur = player.duration
                    val pos = player.currentPosition
                    if (dur <= 0L || pos < dur - 5_000L) player.prepare()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player.currentMediaItemIndex
                val q = _state.value.queue
                if (idx in q.indices) {
                    // Clear old lyrics/motion immediately so the previous song's
                    // lyrics don't linger while the new ones load.
                    _state.update { it.copy(currentSong = q[idx], song = q[idx], queueIndex = idx, lyrics = emptyList(), motionUrl = null) }
                    loadLyrics(q[idx].id)
                    loadMotion(q[idx].id)
                }
            }
        })
        // A MediaSession makes the system route external controller / Bluetooth
        // media buttons (play/pause/next/prev) to our player — those don't come
        // through Activity.dispatchKeyEvent, which is why the controller buttons
        // seemed dead.
        mediaSession = androidx.media3.session.MediaSession.Builder(context, player).build()
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
            val items = queue.map { s -> buildMediaItem(s, if (full) repo.streamUrl(s.id) else (s.previewUrl ?: repo.streamUrl(s.id))) }
            player.setMediaItems(items, idx, posMs)
            player.prepare()
        } catch (_: Exception) {}
    }

    fun playSong(song: Song, useFullStream: Boolean = hasMUT()) {
        usingStandalone = false
        lastErrorKey = null
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
        usingStandalone = false
        lastErrorKey = null
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        _state.update { it.copy(queue = songs, currentSong = songs[idx], song = songs[idx], queueIndex = idx, lyrics = emptyList(), isFullStream = useFullStream, motionUrl = null) }
        player.setMediaItems(songs.map { s ->
            buildMediaItem(s, if (useFullStream) repo.streamUrl(s.id) else (s.previewUrl ?: repo.streamUrl(s.id)))
        }, idx, 0L)
        player.prepare()
        player.play()
        if (useFullStream) loadLyrics(songs[idx].id)
        loadMotion(songs[idx].id)
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

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }

    // seekToNext/Previous are the "smart" combined ops: they respect the
    // player's command set, wrap correctly at the ends of the queue, and (for
    // prev) restart the current track if we're past the threshold — matching
    // how the hardware media keys behave. The bare *MediaItem variants no-op
    // at boundaries, which is why the on-screen buttons appeared dead.
    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekToNext()
        player.play()
    }

    fun prev() {
        if (player.currentPosition > 3_000L || !player.hasPreviousMediaItem()) {
            player.seekTo(0L)
        } else {
            player.seekToPreviousMediaItem()
        }
        player.play()
    }
    fun seekForward() { player.seekTo((player.currentPosition + 15_000L).coerceAtMost(player.duration.coerceAtLeast(0L))) }
    fun seekBack()    { player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L)) }

    fun addToQueue(song: Song) {
        val uri = if (_state.value.isFullStream) repo.streamUrl(song.id) else (song.previewUrl ?: return)
        player.addMediaItem(buildMediaItem(song, uri))
        _state.update { it.copy(queue = it.queue + song) }
    }

    /** Insert a song right after the currently-playing one. */
    fun playNext(song: Song) {
        val uri = if (_state.value.isFullStream) repo.streamUrl(song.id) else (song.previewUrl ?: repo.streamUrl(song.id))
        val insertAt = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(insertAt, buildMediaItem(song, uri))
        val q = _state.value.queue.toMutableList()
        val qInsert = (_state.value.queueIndex + 1).coerceIn(0, q.size)
        q.add(qInsert, song)
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
            if (player.isPlaying)
                _state.update { it.copy(progressMs = player.currentPosition) }
            delay(if (player.isPlaying) 200L else 1_000L)
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
        mediaSession?.release()
        mediaSession = null
        player.stop()
        player.clearMediaItems()
        player.release()
        super.onCleared()
    }
}
