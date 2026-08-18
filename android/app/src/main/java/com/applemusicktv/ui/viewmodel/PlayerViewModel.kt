package com.applemusicktv.ui.viewmodel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
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
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.AppleDirectClient
import com.applemusicktv.media.AppleMusicDrmCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.applemusicktv.media.BeatAnalyzer
import com.applemusicktv.media.BeatAwareRenderersFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RepeatMode { Off, One, All }

/** Rebuffers on one song before standalone is abandoned for it. */
private const val STUTTER_LIMIT = 3

/** Dump schm/tenc/pssh of the standalone init segment. Costs a full segment download. */
private const val PROBE_INIT_SEGMENT = false

/** Path 1 milestone: run our own software Widevine CDM and log the raw content key
 *  (AMKEY). Compare against server/get_key.py for the same track to prove the on-device
 *  CDM derives the correct key before building the in-app decrypt. */
private const val PROBE_CDM_KEY = false

/** Path 1: decrypt segments in-app with our own CDM and play CLEAR AAC (no MediaDrm),
 *  which is what kills the 0x4004 chop. Falls back to the MediaDrm path on failure. */
private const val DECRYPT_IN_APP = true

/** DIAGNOSTIC: capture decoded PCM of the first track to inspect the chop offline. */
private const val CAPTURE_PCM = false

/**
 * What the Now Playing screen draws behind the card.
 *
 * - [DYNAMIC]: the drifting, album-coloured beat blobs (default).
 * - [PROJECTOR]: the same blobs pulled to the centre on true black, dissolved to black on every
 *   edge, so a projected image has no visible boundary. Halo brightness stays constant with the
 *   beat — only size moves — so the black never pumps.
 * - [BLACK]: plain black, no blobs, no beat.
 */
enum class NowPlayingBackground(val label: String) {
    DYNAMIC("Dynamic"),
    PROJECTOR("Projector"),
    BLACK("Black"),
    ;

    companion object {
        fun fromName(name: String?): NowPlayingBackground =
            entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}

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
    val originalQueue:    List<Song>      = emptyList(),
    val repeatMode:       RepeatMode      = RepeatMode.Off,
    val sleepTimerEndsAt: Long?           = null,
    val sleepAfterSong:   Boolean         = false,
    val mutExpired:       Boolean         = false,
    val beatIntensity:    Float           = 1.0f,
    val userQueue:        List<Song>      = emptyList(),
    val crossfadeEnabled: Boolean         = true,
    /** Idle minutes before the ambient screensaver; 0 = off. */
    val screensaverTimeoutMin: Int        = 10,
    /** What's drawn behind the Now Playing card. */
    val nowPlayingBackground: NowPlayingBackground = NowPlayingBackground.DYNAMIC,
    /**
     * When true, the beat blobs keep running once the screensaver kicks in. Off by default: the
     * screensaver drops to plain black so the extra motion doesn't linger overnight.
     */
    val screensaverKeepBackground: Boolean = false,
    /** Decrypt/buffer in flight — a cold track takes 15-20s, so the UI must say so. */
    val isLoading:        Boolean         = false,
    /** True while the current track is playing via on-device Widevine. */
    val standaloneActive: Boolean         = false,
    /** Keep audio playing when the app goes to the background / home is pressed. */
    val backgroundPlayEnabled: Boolean    = true,
    /** True while the activity is in Picture-in-Picture (renders the minimal PiP view). */
    val isInPip:          Boolean         = false,
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
    private val crossfadePrefs: com.applemusicktv.data.CrossfadePreferences,
    private val onboardingPrefs: com.applemusicktv.data.OnboardingPreferences,
    private val standalonePrefs: com.applemusicktv.data.StandalonePreferences,
    private val webServer: InAppWebServer,
    val beatAnalyzer: BeatAnalyzer,
) : ViewModel() {

    private fun hasMUT() = mutPrefs.hasMUT()
    private fun isStandalone() = !serverPrefs.hasPcServer()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("player_state", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /**
     * One-off user-facing messages. extraBufferCapacity so an emit from the polling
     * thread never suspends, and replay=0 so a message doesn't re-fire on rotation.
     */
    private val _toasts = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts
    fun toast(msg: String) { _toasts.tryEmit(msg) }

    private var lastErrorKey: String? = null
    private var hasPlayedSomething = false
    var nowPlayingVisible = false
    private var mediaSession: androidx.media3.session.MediaSession? = null
    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var motionJob: kotlinx.coroutines.Job? = null
    private var fadeJob: kotlinx.coroutines.Job? = null
    private var crossfadeInProgress = false
    /** Mirrors [crossfadePrefs] — read on the polling thread, so keep it a plain field. */
    @Volatile private var crossfadeDurationMs = com.applemusicktv.data.CrossfadePreferences.DEFAULT_MS
    private var crossfadeExo: ExoPlayer? = null
    private var cfExoErrListener: Player.Listener? = null  // stored so STATE_ENDED snap can remove it
    private var preloadedForSongId: String? = null
    private var crossfadeSkipSongId: String? = null
    /** Song whose crossfade-window decision has already been logged (see pollProgress). */
    private var cfWindowLoggedForSongId: String? = null
    /** Title of the song whose standalone attempt already failed — retry proxy once, then give up. */
    private var standaloneFailedSongId: String? = null
    /** Songs Apple 404s on (pulled from the catalogue). Skipped without a retry. */
    private val unavailableSongIds = mutableSetOf<String>()
    /** Avoids re-logging the audio format on every READY (seeks re-enter it). */
    private var lastFormatLoggedFor: String? = null
    /** Rebuffers seen on the current song, and which song they belong to. */
    private var stutterSongId: String? = null
    private var stutterCount = 0
    /** Songs whose standalone encode stutters — permanently routed via the proxy. */
    private val proxyOnlySongIds = mutableSetOf<String>()
    private var standaloneFailures = 0
    /** Pending N+1 prefetch, cancelled whenever a new song starts loading. */
    private var prefetchJob: kotlinx.coroutines.Job? = null
    /** Bumped on every playQueueItem — a slow async standalone build checks it before
     *  starting playback so rapidly-skipped songs don't each play for a moment. */
    private var playGen = 0

    // True while the on-device (Widevine) path is driving playback, so the
    // error handler doesn't bounce back to the proxy in a loop.
    private var usingStandalone: Boolean = false

    /** Held so onCleared can unregister it — AudioManager outlives this ViewModel. */
    private var audioDeviceCallback: AudioDeviceCallback? = null



    /**
     * The stock c2.android.aac.decoder glitches on some HE-AAC (SBR) encodes at
     * segment boundaries (Love Me Again, Bonetrousle). The device also ships the
     * older OMX.google.aac.decoder — a different implementation — so prefer it for
     * AAC and see if it handles the boundaries cleanly. Both are software, so both
     * work on the Widevine (L3) secure path.
     */
    @OptIn(UnstableApi::class)
    private val aacPreferOmxSelector =
        androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, secure, tunneling ->
            val infos = androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                .getDecoderInfos(mimeType, secure, tunneling)
            if (mimeType == androidx.media3.common.MimeTypes.AUDIO_AAC) {
                webServer.addLog("CODEC", "aac decoders (secure=$secure): ${infos.joinToString { it.name }}")
                infos.sortedByDescending { it.name.startsWith("OMX.google.aac") }
            } else infos
        }

    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(): ExoPlayer = run {
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
                50_000,  // min buffer — standalone streams live from Apple's CDN, and
                         // a WAN dip underruns long before a 15s floor notices
                180_000, // max buffer: hoard as much of the track as we can
                2_500,   // buffer to start after initial load
                5_000,   // buffer to restart after rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            // Byte cap, not just seconds. The default is sized for video and starves a
            // 320kbps audio stream well before the duration targets are reached, which
            // is what left otherside sitting at 35% buffered and skipping.
            .setTargetBufferBytes(32 * 1024 * 1024)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setRenderersFactory(
                BeatAwareRenderersFactory(context, beatAnalyzer.newProcessor().also { p ->
                    mainProc = p; beatAnalyzer.activate(p)
                }, com.applemusicktv.media.GapConcealProcessor())
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(aacPreferOmxSelector)
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

    /** Processor feeding the beat bus for [player]; [cfProc] for the crossfade player. */
    private var mainProc: com.applemusicktv.media.BeatProcessor? = null
    private var cfProc: com.applemusicktv.media.BeatProcessor? = null

    /** Hand the beat bus over to the crossfade player once it becomes the one we hear. */
    private fun promoteCrossfadeBeat() {
        cfProc?.let { beatAnalyzer.activate(it); mainProc = it }
        cfProc = null
    }

    /** Crossfade-only player. Own BeatProcessor so it can drive the visuals after the swap. */
    @OptIn(UnstableApi::class)
    private fun buildCrossfadeExo(): ExoPlayer {
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000).setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .setRenderersFactory(
                BeatAwareRenderersFactory(context, beatAnalyzer.newProcessor().also { cfProc = it }, com.applemusicktv.media.GapConcealProcessor())
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(aacPreferOmxSelector)
            )
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .setHandleAudioBecomingNoisy(false)
            .build().also { it.repeatMode = Player.REPEAT_MODE_OFF }
    }

    @OptIn(UnstableApi::class)
    var player: ExoPlayer = buildExoPlayer()
        private set

    init {
        // Offset can also be changed from the phone web server — follow it live.
        viewModelScope.launch {
            lyricsOffsetPrefs.offsetMs.collect { ms ->
                _state.update { it.copy(lyricsOffsetMs = ms) }
            }
        }
        // Same for crossfade length. Takes effect at the next song boundary — an
        // in-flight fade keeps the length it started with.
        viewModelScope.launch {
            crossfadePrefs.durationMs.collect { crossfadeDurationMs = it }
        }
    }

    fun setLyricsOffset(ms: Long) {
        lyricsOffsetPrefs.setOffset(ms)
        _state.update { it.copy(lyricsOffsetMs = ms) }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val pos = player.currentPosition
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("EXO", "isPlaying=$isPlaying pos=${pos}ms song=$song cfade=$crossfadeInProgress")
            _state.update { it.copy(isPlaying = isPlaying) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($state)"
            }
            val pos = player.currentPosition
            val dur = player.duration
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("EXO", "state=$name pos=${pos}ms dur=${dur}ms song=$song cfade=$crossfadeInProgress")
            // One-line audio diagnostic per track: what actually got decoded, and by
            // which path. Guessing at codec/rate from the server side isn't possible.
            // Some encodes stream badly on the standalone path — the proxy's remux
            // repairs segment gaps that ExoPlayer just plays through. Rather than
            // guessing which tracks those are, watch for repeated rebuffering and
            // move that song to the proxy for good.
            val curSong = _state.value.queue.getOrNull(_state.value.queueIndex)
            if (state == Player.STATE_BUFFERING && usingStandalone && player.playWhenReady) {
                if (stutterSongId != curSong?.id) { stutterSongId = curSong?.id; stutterCount = 0 }
                stutterCount++
                // Only worth falling back to the proxy if the proxy is actually
                // reachable. With in-app decrypt the file plays fine and a brief
                // seek-induced rebuffer must NOT bounce us to a dead server.
                if (stutterCount >= STUTTER_LIMIT && curSong != null && serverPrefs.serverReachable &&
                    curSong.id !in proxyOnlySongIds && player.currentPosition > 3_000
                ) {
                    proxyOnlySongIds.add(curSong.id)
                    usingStandalone = false
                    val at = player.currentPosition
                    webServer.addLog("PLR", "standalone stuttering on ${curSong.title} " +
                        "($stutterCount rebuffers) — switching to proxy at ${at}ms")
                    player.setMediaItem(buildMediaItem(curSong, repo.streamUrl(curSong.id)), at)
                    player.prepare(); player.play()
                }
            }
            if (state == Player.STATE_READY && lastFormatLoggedFor != song) {
                lastFormatLoggedFor = song
                val f = player.audioFormat
                webServer.addLog(
                    "FMT",
                    "codec=${f?.sampleMimeType ?: "?"} " +
                        "bitrate=${f?.bitrate ?: -1} rate=${f?.sampleRate ?: -1}Hz " +
                        "ch=${f?.channelCount ?: -1} enc=${f?.pcmEncoding ?: -1} " +
                        "path=${if (usingStandalone) "standalone" else "proxy"} " +
                        "buffered=${player.bufferedPercentage}%",
                )
            }
            // Old player ended naturally during crossfade — snap cfExo in immediately
            if (state == Player.STATE_ENDED && crossfadeInProgress) {
                webServer.addLog("CFXO", "STATE_ENDED mid-fade cfExo=${crossfadeExo != null} cfExoState=${crossfadeExo?.playbackState} fadeJob=${fadeJob?.isActive}")
                try {
                    fadeJob?.cancel()
                    val cfExo = crossfadeExo
                    crossfadeExo = null
                    crossfadeInProgress = false
                    val oldP = player
                    oldP.removeListener(this)
                    oldP.volume = 0f; oldP.stop(); oldP.release()
                    if (cfExo != null && (cfExo.playbackState == Player.STATE_READY || cfExo.playbackState == Player.STATE_BUFFERING)) {
                        cfExo.volume = 1f
                        player = cfExo
                        promoteCrossfadeBeat()
                        cfExoErrListener?.let { cfExo.removeListener(it) }; cfExoErrListener = null
                        cfExo.addListener(this)
                        webServer.addLog("CFXO", "snap done cfExo.isPlaying=${cfExo.isPlaying} state=${cfExo.playbackState}")
                        val sNow = _state.value
                        val nextUp = sNow.userQueue.firstOrNull() ?: sNow.queue.getOrNull(sNow.queueIndex + 1)
                        if (nextUp != null && preloadedForSongId != nextUp.id) {
                            preloadedForSongId = nextUp.id; prefetchSong(nextUp)
                        }
                        _state.update { it.copy(isPlaying = cfExo.isPlaying) }
                        // Rebuilding the MediaSession reinits the audio output pipeline —
                        // defer it so it doesn't gap the audio right at the swap.
                        viewModelScope.launch {
                            delay(500)
                            mediaSession?.release()
                            mediaSession = buildMediaSession(player)
                        }
                    } else {
                        // cfExo bad or null — need a fresh player, then advance
                        webServer.addLog("CFXO", "snap: cfExo unusable (state=${cfExo?.playbackState}) — rebuilding player and advancing")
                        try { cfExo?.stop(); cfExo?.release() } catch (_: Exception) {}
                        player = buildExoPlayer().also { it.addListener(this) }
                        mediaSession?.release()
                        mediaSession = buildMediaSession(player)
                        advanceQueue()
                    }
                } catch (e: Exception) {
                    webServer.addLog("CFXO", "snap exception: ${e.message}")
                    crossfadeInProgress = false
                    advanceQueue()
                }
            }
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            // During crossfade, REPEAT_MODE_ONE is intentionally set on old player — don't reset it
            if (repeatMode != Player.REPEAT_MODE_OFF && !crossfadeInProgress) player.repeatMode = Player.REPEAT_MODE_OFF
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val pos = player.currentPosition
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("ERR", "${error.errorCodeName} pos=${pos}ms song=$song cfade=$crossfadeInProgress cause=${error.cause?.message}")
            // Silence looks like a crash otherwise — say why we skipped.
            toast(if (serverPrefs.serverReachable) "Couldn't play \"$song\" — skipping"
                  else "Can't reach the server")
            val gone = error.errorCode ==
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                (error.cause?.message?.contains("404") == true)
            if (gone) {
                val cur = _state.value.queue.getOrNull(_state.value.queueIndex)
                if (cur != null) unavailableSongIds.add(cur.id)
                webServer.addLog("ERR", "unavailable (404) song=$song — skipping, won't retry")
                toast("\"$song\" isn't available any more")
                advanceQueue()
                return
            }

            // A standalone failure must not advance — it'd fail identically on the next
            // song and rip through the whole queue. Retry this one through the proxy.
            if (usingStandalone && !crossfadeInProgress && standaloneFailedSongId != song) {
                standaloneFailedSongId = song
                usingStandalone = false
                standaloneFailures++
                webServer.addLog("PLR", "standalone failed for $song — retrying via proxy (#$standaloneFailures)")
                // If it fails repeatedly it's not the track, it's the device or the
                // scheme — stop paying the failed-attempt cost on every single song.
                if (standaloneFailures >= 3 && standalonePrefs.isEnabled()) {
                    standalonePrefs.setEnabled(false)
                    webServer.addLog("PLR", "standalone disabled after $standaloneFailures failures")
                    toast("On-device playback isn't working here — switched back to the server")
                } else {
                    toast("On-device playback failed — using the server")
                }
                val s = _state.value
                val cur = s.queue.getOrNull(s.queueIndex)
                if (cur != null) {
                    player.setMediaItem(buildMediaItem(cur, repo.streamUrl(cur.id)))
                    player.prepare(); player.volume = 1f; player.play()
                    return
                }
            }
            if (crossfadeInProgress && crossfadeExo != null) {
                // Old player died mid-fade — snap cfExo to full volume immediately
                crossfadeExo!!.volume = 1f
                // cfExo is audible; keep UI showing playing so it doesn't flash paused
                _state.update { it.copy(isPlaying = true) }
            } else {
                advanceQueue()
            }
        }
    }

    private fun buildMediaSession(exo: ExoPlayer): androidx.media3.session.MediaSession =
        androidx.media3.session.MediaSession.Builder(context, exo)
            .setCallback(object : androidx.media3.session.MediaSession.Callback {
                override fun onConnect(
                    session: androidx.media3.session.MediaSession,
                    controller: androidx.media3.session.MediaSession.ControllerInfo,
                ): androidx.media3.session.MediaSession.ConnectionResult {
                    val pkg = controller.packageName
                    val allowed = pkg == context.packageName
                    return if (allowed) super.onConnect(session, controller)
                    else androidx.media3.session.MediaSession.ConnectionResult.reject()
                }
            }).build()

    init {
        // Load the lightweight display settings up front so they apply even on a cold launch with
        // no track to restore (restoreState, which reads the rest, only runs when a song was saved).
        _state.update { it.copy(
            lyricsOffsetMs = lyricsOffsetPrefs.getOffset(),
            nowPlayingBackground = NowPlayingBackground.fromName(prefs.getString("np_background", null)),
            screensaverKeepBackground = prefs.getBoolean("screensaver_keep_bg", false),
        ) }
        player.addListener(playerListener)
        mediaSession = buildMediaSession(player)
        // Confirm the bundled FFmpeg audio decoder loaded — when true, ExoPlayer
        // prefers it over MediaCodec (EXTENSION_RENDERER_MODE_PREFER) and HE-AAC
        // decodes like the proxy, killing the standalone chop.
        webServer.addLog("FFMPEG", "FfmpegLibrary.isAvailable=${androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()}")
        // DIAGNOSTIC: capture ~25s of decoded PCM (44100Hz stereo s16le) from the
        // active player to inspect the standalone chop offline. Remove after repair.
        if (CAPTURE_PCM) try {
            val f = java.io.File(context.cacheDir, "pcmcap.raw")
            beatAnalyzer.startCapture(f.outputStream().buffered(), 44_100 * 2 * 2 * 25)
            webServer.addLog("CAP", "capturing PCM to ${f.absolutePath}")
        } catch (e: Exception) { webServer.addLog("CAP", "capture start failed: ${e.message}") }
        // EventLogger formats a string on every playback event for the life of the
        // player — pure debug overhead on an underpowered Fire TV. Gate it behind the
        // same probe flag the init-segment dump uses.
        if (PROBE_INIT_SEGMENT) player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun updateBtLatency() {
            val btTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
            val onBt = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in btTypes }
            beatAnalyzer.latencyMs = if (onBt) 200L else 0L
        }
        updateBtLatency()
        // AudioManager is a system service and outlives this ViewModel, so hold the
        // callback and unregister it in onCleared — otherwise every future BT connect
        // fires into a dead instance.
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) { updateBtLatency() }
            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { updateBtLatency() }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        viewModelScope.launch {
            repo.authErrorFlow.collect {
                _state.update { it.copy(mutExpired = true) }
                showAuthNotification()
            }
        }
        pollProgress()
        // Don't restore-and-play behind the setup screen — a first-run user would
        // get audio over onboarding. onOnboardingFinished() picks it up instead.
        if (onboardingPrefs.completed) restoreState()
        checkServerReachable()
        viewModelScope.launch { checkAppleServiceStatus() }
    }

    fun onboardingCompleted(): Boolean = onboardingPrefs.completed

    /** Remote type chosen in setup — overrides hardware detection for the toggle button. */
    fun remoteOverride(): String = onboardingPrefs.remoteOverride

    /** Dev menu: replay setup on next launch. */
    fun resetOnboarding() = onboardingPrefs.reset()

    /** Setup just finished — safe to bring back whatever was playing before. */
    fun onOnboardingFinished() {
        // Setup may have just added the token; the proxy needs its own copy before
        // any library call, and _state still says isFullStream=false.
        viewModelScope.launch {
            if (hasMUT()) runCatching { repo.syncMUTToServer(mutPrefs.getMUT()) }
            recheckServer().join()
            _state.update { it.copy(isFullStream = hasMUT()) }
            restoreState()
        }
    }

    /** Health-check the configured server; flips to/from standalone accordingly. */
    fun recheckServer() = viewModelScope.launch {
        val up = repo.pingServer()
        val wasDown = !serverPrefs.serverReachable
        serverPrefs.serverReachable = up
        Log.i("PlayerVM", if (up) "Server reachable — proxy mode" else "Server DOWN — standalone mode")
        // When the user has chosen standalone, the server is irrelevant — don't nag
        // about losing/finding a connection we aren't using.
        val announce = !standalonePrefs.isEnabled()
        if (!up) {
            if (!wasDown && announce) toast("Lost connection to the server")
            repo.prepareStandalone()
        } else if (wasDown) {
            // Recovered — reset standalone flag so next play uses proxy.
            usingStandalone = false
            if (announce) toast("Server back online")
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
    /**
     * On-device Widevine instead of the proxy's decrypt+remux. Either the user turned
     * it on (it's faster: no 15-20s wait), or the server is gone and it's the only way
     * to play anything.
     */
    private fun useStandalone() = standalonePrefs.isEnabled() || !serverPrefs.serverReachable

    private fun saveState() {
        val s = _state.value
        val song = s.currentSong ?: return
        // Mid-crossfade _state already points at the NEXT song while `player` is still
        // the outgoing one, so a save here pairs one song's title with another song's
        // position — which is how a restore lands on the wrong track.
        if (crossfadeInProgress) return
        val adapter = moshi.adapter(Song::class.java)
        val listType = Types.newParameterizedType(List::class.java, Song::class.java)
        val listAdapter = moshi.adapter<List<Song>>(listType)
        prefs.edit {
            putString("song",  adapter.toJson(song))
            putString("queue", listAdapter.toJson(s.queue))
            putInt("queue_index", s.queueIndex)
            putLong("position_ms", player.currentPosition)
            putBoolean("full_stream", s.isFullStream)
            putFloat("beat_intensity", s.beatIntensity)
            putBoolean("crossfade_enabled", s.crossfadeEnabled)
            putBoolean("background_play", s.backgroundPlayEnabled)
        }
    }

    /** True when the app should keep playing after it leaves the foreground. */
    val backgroundPlayEnabled: Boolean get() = _state.value.backgroundPlayEnabled

    fun toggleBackgroundPlay() {
        val next = !_state.value.backgroundPlayEnabled
        _state.update { it.copy(backgroundPlayEnabled = next) }
        prefs.edit { putBoolean("background_play", next) }
    }

    /** Called by the activity when it enters/leaves Picture-in-Picture. */
    fun setPipMode(on: Boolean) { _state.update { it.copy(isInPip = on) } }

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
            val full  = hasMUT()
            val beat      = prefs.getFloat("beat_intensity", 1.0f)
            val crossfade = prefs.getBoolean("crossfade_enabled", true)
            val screensaverMin = prefs.getInt("screensaver_min", 10)
            val bgPlay = prefs.getBoolean("background_play", true)
            val npBg = NowPlayingBackground.fromName(prefs.getString("np_background", null))
            val keepBg = prefs.getBoolean("screensaver_keep_bg", false)
            _state.update { it.copy(currentSong = song, song = song, queue = queue, queueIndex = idx, isFullStream = full, beatIntensity = beat, crossfadeEnabled = crossfade, screensaverTimeoutMin = screensaverMin, backgroundPlayEnabled = bgPlay, nowPlayingBackground = npBg, screensaverKeepBackground = keepBg, progressMs = posMs) }
            val uri = if (full) repo.streamUrl(song.id) else (song.previewUrl ?: repo.streamUrl(song.id))
            val standalone = full && useStandalone()
            webServer.addLog("PLR", "restoreState idx=$idx posMs=$posMs song=${song.title}${if (standalone) " [standalone]" else ""}")
            usingStandalone = standalone
            val src = if (standalone) buildStandaloneSource(song) else null
            _state.update { it.copy(standaloneActive = src != null) }
            if (src != null) player.setMediaSource(src, posMs)
            else player.setMediaItem(buildMediaItem(song, uri), posMs)
            player.prepare()

            // Restore bypasses playQueueItem, which is where lyrics/motion normally
            // load — so without this a restored track comes back with no lyrics.
            if (full) loadLyrics(song.id)
            loadMotion(song.id)

            // N+1 prefetch normally happens in playQueueItem, which restore
            // bypasses — so without this the song after a restored one is always
            // cold and the crossfade into it always falls back to a hard cut.
            // Wait for the restored song to be READY first: its own decrypt is
            // cold too, and racing two decrypts delays the audio we need *now*.
            val nextSong = queue.getOrNull(idx + 1)
            if (full && !standalone && nextSong != null) {
                preloadedForSongId = null
                val deadline = System.currentTimeMillis() + 60_000
                while (player.playbackState != Player.STATE_READY &&
                       System.currentTimeMillis() < deadline) {
                    delay(500)
                }
                if (preloadedForSongId != nextSong.id) {
                    preloadedForSongId = nextSong.id
                    webServer.addLog("PRE", "prefetch N+1 after restore song=${nextSong.title}")
                    prefetchSong(nextSong)
                }
            }
        } catch (_: Exception) {}
    }

    fun playFromQueue(idx: Int) = playQueueItem(idx)

    private fun advanceQueue() {
        val s = _state.value
        val pos = player.currentPosition
        webServer.addLog("ADV", "advanceQueue pos=${pos}ms from=${s.currentSong?.title} idx=${s.queueIndex}/${s.queue.size}")
        if (s.sleepAfterSong) { _state.update { it.copy(sleepAfterSong = false) }; player.stop(); webServer.addLog("SLEEP", "sleepAfterSong — stopped"); return }
        // Repeat one: replay current
        if (s.repeatMode == RepeatMode.One) { playQueueItem(s.queueIndex, skipFadeIn = true); return }

        // Drain user priority queue first (Play Next / Add to Queue songs)
        if (s.userQueue.isNotEmpty()) {
            val next = s.userQueue.first()
            val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
            val newQueue = s.queue.toMutableList().also { it.add(insertIdx, next) }
            _state.update { it.copy(queue = newQueue, userQueue = it.userQueue.drop(1)) }
            playQueueItem(insertIdx)
            return
        }

        val nextIdx = if (s.isShuffled && s.queue.size > 1) {
            val candidates = s.queue.indices.filter { it > s.queueIndex }
            candidates.randomOrNull() ?: (s.queueIndex + 1)
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

    fun toggleShuffle() {
        _state.update { s ->
            if (s.isShuffled) {
                // Turning off: restore original order, keep current song position
                val restored = if (s.originalQueue.isNotEmpty()) s.originalQueue else s.queue
                val currentSongId = s.currentSong?.id
                val newIdx = restored.indexOfFirst { it.id == currentSongId }.coerceAtLeast(s.queueIndex)
                s.copy(isShuffled = false, queue = restored, queueIndex = newIdx, originalQueue = emptyList())
            } else {
                // Turning on: save original, shuffle remaining
                val before = s.queue.subList(0, s.queueIndex + 1)
                val after  = s.queue.subList(s.queueIndex + 1, s.queue.size).shuffled()
                s.copy(isShuffled = true, originalQueue = s.queue, queue = before + after)
            }
        }
    }
    fun toggleRepeat() {
        _state.update { it.copy(repeatMode = when (it.repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        })}
    }
    fun setSleepTimer(minutes: Int) { _state.update { it.copy(sleepTimerEndsAt = System.currentTimeMillis() + minutes * 60_000L, sleepAfterSong = false) } }
    fun setSleepAfterSong() { _state.update { it.copy(sleepAfterSong = true, sleepTimerEndsAt = null) } }
    fun cancelSleepTimer() { _state.update { it.copy(sleepTimerEndsAt = null, sleepAfterSong = false) } }
    fun dismissMutExpired() { _state.update { it.copy(mutExpired = false) } }
    fun cycleBeatIntensity() {
        val next = when (_state.value.beatIntensity) { 1.0f -> 2.0f; 2.0f -> 3.5f; else -> 1.0f }
        _state.update { it.copy(beatIntensity = next) }
        prefs.edit { putFloat("beat_intensity", next) }
    }

    fun toggleCrossfade() {
        val next = !_state.value.crossfadeEnabled
        _state.update { it.copy(crossfadeEnabled = next) }
        prefs.edit { putBoolean("crossfade_enabled", next) }
    }

    /** Cycle the Now Playing backdrop: Dynamic → Projector → Black → Dynamic (dir = +1 / -1). */
    fun stepNowPlayingBackground(dir: Int) {
        val modes = NowPlayingBackground.entries
        val cur = _state.value.nowPlayingBackground.ordinal
        val next = modes[((cur + dir) % modes.size + modes.size) % modes.size]
        _state.update { it.copy(nowPlayingBackground = next) }
        prefs.edit { putString("np_background", next.name) }
    }

    fun toggleScreensaverKeepBackground() {
        val next = !_state.value.screensaverKeepBackground
        _state.update { it.copy(screensaverKeepBackground = next) }
        prefs.edit { putBoolean("screensaver_keep_bg", next) }
    }

    private val screensaverSteps = listOf(0, 1, 2, 5, 10, 20, 30, 60, 120)

    /** Cycle the screensaver idle timeout: Off → 1 → 2 → 5 → 10 → 20 → 30 → Off. */
    fun cycleScreensaverTimeout() = stepScreensaverTimeout(1)

    /** Step the screensaver idle timeout through [screensaverSteps] (dir = +1 / -1). */
    fun stepScreensaverTimeout(dir: Int) {
        val cur = screensaverSteps.indexOf(_state.value.screensaverTimeoutMin).coerceAtLeast(0)
        val next = screensaverSteps[(cur + dir).coerceIn(0, screensaverSteps.lastIndex)]
        _state.update { it.copy(screensaverTimeoutMin = next) }
        prefs.edit { putInt("screensaver_min", next) }
    }

    // Set on every track change; holds the UI clock at 0:00 until the NEW item is actually
    // playing, so the poll loop can't push the outgoing song's elapsed/duration through the
    // window while the standalone source is still building.
    @Volatile private var awaitingSongStart: String? = null

    private fun playQueueItem(idx: Int, skipFadeIn: Boolean = false) {
        val q = _state.value.queue
        if (q.isEmpty() || idx !in q.indices) {
            webServer.addLog("PLR", "playQueueItem idx=$idx out of bounds (size=${q.size}) — stopping")
            return
        }
        beatAnalyzer.resetBeat(); mainProc?.resetBeat()
        crossfadeSkipSongId = null
        // Cancel any in-progress crossfade
        fadeJob?.cancel()
        crossfadeInProgress = false

        val song = q[idx]
        if (song.id in unavailableSongIds) {
            webServer.addLog("PLR", "skipping known-unavailable ${song.title}")
            if (idx + 1 < q.size) playQueueItem(idx + 1, skipFadeIn) 
            return
        }
        val full = _state.value.isFullStream
        val standalone = full && useStandalone() && song.id !in proxyOnlySongIds
        val uri = if (full) repo.streamUrl(song.id) else (song.previewUrl ?: repo.streamUrl(song.id))
        webServer.addLog("PLR", "playQueueItem idx=$idx song=${song.title}${if (standalone) " [standalone]" else ""}")
        awaitingSongStart = song.id
        _state.update { it.copy(currentSong = song, song = song, queueIndex = idx, lyrics = emptyList(), motionUrl = null, progressMs = 0L) }
        saveState()
        player.repeatMode = Player.REPEAT_MODE_OFF
        // Silence the outgoing track immediately on selection — building a standalone
        // source (webPlayback + decrypt) takes a beat, and the old song shouldn't keep
        // playing under it. The loading ring covers the gap.
        player.pause()
        // Volume/play/fade must run AFTER the media source is set, or we start and
        // fade in whatever was loaded before.
        fun startPlayback() {
            // No fade-in when starting a track — play it at full volume straight away.
            // (The end-of-song crossfade is separate; this only killed the per-song ramp.)
            player.volume = 1f
            player.play()
        }
        val myGen = ++playGen
        if (standalone) {
            usingStandalone = true
            viewModelScope.launch {
                val src = buildStandaloneSource(song)
                // Superseded by a newer selection while we were decrypting — drop it so
                // the skipped-over song never briefly plays.
                if (myGen != playGen) return@launch
                if (src != null) player.setMediaSource(src)
                else player.setMediaItem(buildMediaItem(song, uri))
                player.prepare()
                if (src != null) standaloneFailures = 0
                _state.update { it.copy(standaloneActive = src != null) }
                startPlayback()
            }
        } else {
            usingStandalone = false
            _state.update { it.copy(standaloneActive = false) }
            player.setMediaItem(buildMediaItem(song, uri))
            player.prepare()
            startPlayback()
        }
        preloadedForSongId = null
        if (full) loadLyrics(song.id)
        loadMotion(song.id)
        if (song.artistId == null || song.albumId == null) enrichSongIds(song.id)
        // Prefetch N+1 immediately so it's cached well before crossfade. Under Repeat
        // All the last track's "next" is index 0, so warm that instead of nothing.
        val nextSong = (_state.value.userQueue.firstOrNull()
            ?: q.getOrNull(idx + 1)
            ?: q.firstOrNull().takeIf { _state.value.repeatMode == RepeatMode.All && q.size > 1 })
        // Wait for THIS song to be playable before warming the next one. Firing both at
        // once splits the WAN download and the song you're waiting on lands last — a
        // skip used to queue up four concurrent decrypts and take 30s+. Cancelled on
        // the next playQueueItem, so hammering skip only ever warms the song you land on.
        // Persist the song change right away rather than waiting up to 10s for the
        // ticker — that window is what let a restore come back on a previous track.
        lastAutoSaveMs = System.currentTimeMillis()
        saveState()
        prefetchJob?.cancel()
        // Prefetch N+1 in BOTH modes. Standalone was excluded before, which is why the
        // crossfade partner was always cold and crossfade/skip felt broken — now
        // prefetchSong warms the in-app decrypt cache for the next song.
        if (full && nextSong != null) {
            prefetchJob = viewModelScope.launch {
                val deadline = System.currentTimeMillis() + 60_000
                while (player.playbackState != Player.STATE_READY &&
                       System.currentTimeMillis() < deadline) {
                    delay(300)
                }
                if (player.playbackState != Player.STATE_READY) {
                    webServer.addLog("PRE", "prefetch N+1 abandoned — ${song.title} never became ready")
                    return@launch
                }
                preloadedForSongId = nextSong.id
                webServer.addLog("PRE", "prefetch N+1 song=${nextSong.title}")
                prefetchSong(nextSong)
            }
        } else {
            webServer.addLog("PRE", "prefetch N+1 skip — full=$full nextSong=${nextSong?.title}")
        }
    }

    fun playSong(album: Album) {
        val song = Song(id = album.id, title = album.title, artistName = album.artistName,
            artworkUrl = album.artworkUrl, artworkBgColor = album.artworkBgColor,
            durationMs = 0, albumName = "", previewUrl = null)
        playSong(song)
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
        if (song.artistId == null || song.albumId == null) enrichSongIds(song.id)
    }

    fun playAlbum(songs: List<Song>, startIndex: Int = 0, useFullStream: Boolean = hasMUT(), shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val stack = Thread.currentThread().stackTrace
        val callers = (3..7).mapNotNull { stack.getOrNull(it) }.joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        webServer.addLog("PLR", "playAlbum size=${songs.size} idx=$startIndex shuffle=$shuffle << $callers")
        usingStandalone = false
        lastErrorKey = null
        hasPlayedSomething = true
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        val queue = if (shuffle) {
            val first = songs[idx]
            listOf(first) + songs.filterIndexed { i, _ -> i != idx }.shuffled()
        } else songs
        val queueIdx = if (shuffle) 0 else idx
        _state.update { it.copy(queue = queue, isFullStream = useFullStream, isShuffled = shuffle, originalQueue = if (shuffle) songs else emptyList()) }
        playQueueItem(queueIdx)
    }

    fun shufflePlayPlaylist(playlistId: String) = viewModelScope.launch {
        val tracks = repo.getPlaylistTracks(playlistId).getOrDefault(emptyList())
        if (tracks.isNotEmpty()) playAlbum(tracks, startIndex = tracks.indices.random(), shuffle = true)
    }

    /** Temp: dump a personalized ra.* station's payload to logcat (tag StationProbe). */
    fun probeStation(id: String) = viewModelScope.launch { repo.probeStation(id) }

    fun playStation(stationId: String) = viewModelScope.launch {
        val songs = repo.getStationTracks(stationId).getOrDefault(emptyList())
        if (songs.isNotEmpty()) playAlbum(songs)
    }

    /** Genre station — shuffled top songs for the genre, played standalone. */
    fun playGenreStation(genreId: String) = viewModelScope.launch {
        val songs = repo.getGenreStation(genreId).getOrDefault(emptyList())
        if (songs.isNotEmpty()) playAlbum(songs, shuffle = true)
    }

    /** Plain internet-radio stream player. Radio UI is currently hidden — kept only so
     *  RadioScreen still compiles. No ICY song-identification. */
    @OptIn(UnstableApi::class)
    fun playInternetRadio(name: String, streamUrl: String, subtitle: String = "Internet Radio") {
        usingStandalone = false
        lastErrorKey = null
        prefetchJob?.cancel()
        val fakeSong = com.applemusicktv.data.model.Song(
            id = "radio:$streamUrl", title = name, artistName = subtitle,
            albumName = "", durationMs = 0L, artworkUrl = null, artworkBgColor = null,
            previewUrl = null, hasLyrics = false,
        )
        _state.update { it.copy(queue = listOf(fakeSong), currentSong = fakeSong, song = fakeSong, queueIndex = 0, lyrics = emptyList(), isFullStream = true, motionUrl = null) }
        player.setMediaItem(buildMediaItem(fakeSong, streamUrl))
        player.prepare()
        player.volume = 1f
        player.play()
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

    /**
     * Widevine media source for on-device playback. Suspends on a network round-trip
     * (bearer + webPlayback), so callers must await it before touching play() —
     * fire-and-forget here meant the PREVIOUS track kept playing and faded back in
     * while the UI already showed the new one. Returns null if standalone can't run;
     * the caller falls back to the proxy URL.
     */
    @OptIn(UnstableApi::class)
    private suspend fun buildStandaloneSource(song: Song): androidx.media3.exoplayer.source.MediaSource? {
        @Suppress("ConstantConditionIf")
        if (DECRYPT_IN_APP) {
            val clear = buildDecryptedStandaloneSource(song)
            if (clear != null) return clear
            Log.w("AMCENC", "in-app decrypt failed, falling back to MediaDrm path")
        }
        return try {
            val bearer = appleClient.getBearer()
            val mut = mutPrefs.getMUT()
            if (bearer.isEmpty() || mut.isEmpty()) return null
            val wb = appleClient.getWebPlayback(song.id, bearer, mut)
            // Serve a rewritten copy of the playlist from disk — see
            // rewritePlaylistForExo for why the EXT-X-KEY line has to go.
            val playlistUri = try {
                val f = java.io.File(context.cacheDir, "standalone_${song.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.m3u8")
                f.writeText(appleClient.rewritePlaylistForExo(wb.hlsText, wb.hlsUrl))
                android.net.Uri.fromFile(f).toString()
            } catch (e: Exception) {
                Log.w("PlayerVM", "Playlist rewrite failed, using raw URL: ${e.message}")
                wb.hlsUrl
            }
            // Diagnostic only — it downloads the whole init segment (~2MB). Flip on
            // by hand when standalone breaks again; see AMProbe in logcat.
            @Suppress("ConstantConditionIf")
            if (PROBE_INIT_SEGMENT) appleClient.probeInitSegment(wb.hlsText, wb.hlsUrl, bearer, mut)
            @Suppress("ConstantConditionIf")
            if (PROBE_CDM_KEY && wb.keyUri != null) probeCdmKey(wb.adamId, wb.keyUri, bearer, mut)
            val drmCallback = AppleMusicDrmCallback(wb.adamId, wb.keyUri, bearer, mut)
            val drmManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(drmCallback)
            DefaultMediaSourceFactory(context)
                .setDrmSessionManagerProvider { drmManager }
                .createMediaSource(buildMediaItem(song, playlistUri))
        } catch (e: Exception) {
            Log.e("PlayerVM", "Standalone source failed for ${song.id}: ${e.message}")
            null
        }
    }

    /** Path 1 milestone probe: derive the raw content key on-device with our own
     *  software Widevine CDM and log it (AMKEY). Non-fatal; wrapped in runCatching. */
    private val cdmHttp by lazy { okhttp3.OkHttpClient() }

    /** Run our software Widevine CDM to get the raw content key (hex) for a track. */
    private fun deriveContentKey(adamId: String, keyUri: String, bearer: String, mut: String): String? =
        com.applemusicktv.media.widevine.WidevineCdm().getContentKey(keyUri) { challengeB64 ->
            val body = org.json.JSONObject().apply {
                put("challenge", challengeB64)
                put("key-system", "com.widevine.alpha")
                put("uri", keyUri)
                put("adamId", adamId)
                put("isLibrary", false)
                put("user-initiated", true)
            }.toString()
            val resp = cdmHttp.newCall(
                okhttp3.Request.Builder()
                    .url("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $bearer")
                    .addHeader("Cookie", "media-user-token=$mut")
                    .addHeader("Origin", "https://music.apple.com")
                    .build()
            ).execute()
            val licenseB64 = org.json.JSONObject(resp.body!!.string()).getString("license")
            android.util.Base64.decode(licenseB64, android.util.Base64.DEFAULT)
        }

    private fun probeCdmKey(adamId: String, keyUri: String, bearer: String, mut: String) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val key = deriveContentKey(adamId, keyUri, bearer, mut)
            Log.i("AMKEY", "adamId=$adamId key=$key (${System.currentTimeMillis() - t0}ms)")
            webServer.addLog("AMKEY", "adamId=$adamId key=$key (${System.currentTimeMillis() - t0}ms)")
        }.onFailure { Log.e("AMKEY", "probe error: ${it.message}", it) }
    }

    /**
     * Path 1 real path: derive key → download init + segments → decrypt in-app to a
     * CLEAR fMP4 on disk → play it with NO DRM, so the AAC decoder gets intact frames.
     * Returns null on any failure so the caller can fall back to the MediaDrm path.
     */
    private suspend fun buildDecryptedStandaloneSource(song: Song): androidx.media3.exoplayer.source.MediaSource? {
        val out = decryptToFile(song) ?: return null
        return DefaultMediaSourceFactory(context)
            .createMediaSource(buildMediaItem(song, android.net.Uri.fromFile(out).toString()))
    }

    private val decryptInFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<java.io.File?>>()

    /** Derive key → fetch whole fMP4 → decrypt in-app → clear file on disk. Reuses an
     *  already-decrypted file (that's what lets prefetch warm crossfade/skip targets),
     *  and dedupes concurrent requests for the same song (fast-skip fires many). */
    private suspend fun decryptToFile(song: Song): java.io.File? {
        val out = java.io.File(context.cacheDir, "clear_${song.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.mp4")
        if (out.exists() && out.length() > 0) return out
        val existing = decryptInFlight[song.id]
        if (existing != null) return existing.await()
        val job = viewModelScope.async(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bearer = appleClient.getBearer()
                val mut = mutPrefs.getMUT()
                if (bearer.isEmpty() || mut.isEmpty()) return@async null
                val wb = appleClient.getWebPlayback(song.id, bearer, mut)
                val keyUri = wb.keyUri ?: return@async null
                val t0 = System.currentTimeMillis()
                val keyHex = deriveContentKey(wb.adamId, keyUri, bearer, mut) ?: run {
                    Log.e("AMCENC", "no key"); return@async null
                }
                // Apple delivers ONE fMP4 file, segmented only by #EXT-X-BYTERANGE.
                val base = wb.hlsUrl.substring(0, wb.hlsUrl.lastIndexOf('/') + 1)
                fun abs(u: String) = if (u.startsWith("http")) u else base + u
                val fileUri = (Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(wb.hlsText)?.groupValues?.get(1)
                    ?: wb.hlsText.lineSequence().map { it.trim() }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?: return@async null)
                    .substringBefore('?').let(::abs)
                val encrypted = cdmHttp.newCall(
                    okhttp3.Request.Builder().url(fileUri)
                        .addHeader("Authorization", "Bearer $bearer")
                        .addHeader("Cookie", "media-user-token=$mut").build()
                ).execute().body!!.bytes()
                val dec = com.applemusicktv.media.widevine.CencDecryptor(keyHex)
                val tmp = java.io.File(context.cacheDir, "${out.name}.tmp")
                tmp.writeBytes(dec.decryptWhole(encrypted))
                tmp.renameTo(out)   // atomic — a half-written file is never played
                webServer.addLog("AMCENC", "song=${song.id} in=${encrypted.size} out=${out.length()} ${System.currentTimeMillis() - t0}ms")
                out
            } catch (e: Exception) {
                Log.e("AMCENC", "decrypt failed for ${song.id}: ${e.message}", e); null
            } finally {
                decryptInFlight.remove(song.id)
            }
        }
        decryptInFlight[song.id] = job
        return job.await()
    }

    fun pause() { player.pause() }
    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
            // The 10s auto-save only ticks while playing, so without this a pause
            // followed by the process being killed restores a stale position.
            saveState()
        } else player.play()
    }

    fun next() {
        val s = _state.value
        webServer.addLog("NAV", "next() pos=${player.currentPosition}ms idx=${s.queueIndex} userQueue=${s.userQueue.size}")
        fadeJob?.cancel(); crossfadeInProgress = false; player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        preloadedForSongId = null
        if (s.userQueue.isNotEmpty()) {
            val nextSong = s.userQueue.first()
            val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
            val newQueue = s.queue.toMutableList().also { it.add(insertIdx, nextSong) }
            _state.update { it.copy(queue = newQueue, userQueue = it.userQueue.drop(1)) }
            playQueueItem(insertIdx, skipFadeIn = true)
        } else {
            // Repeat All wraps on a manual skip too — otherwise pressing Next on the
            // last track just stops, which contradicts the mode being on.
            val target = if (s.repeatMode == RepeatMode.All && s.queueIndex + 1 >= s.queue.size) 0
                         else s.queueIndex + 1
            playQueueItem(target, skipFadeIn = true)
        }
    }
    fun prev() {
        val s = _state.value
        val pos = player.currentPosition
        webServer.addLog("NAV", "prev() pos=${pos}ms idx=${s.queueIndex}")
        fadeJob?.cancel(); crossfadeInProgress = false; player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        preloadedForSongId = null
        // Past 10s, Prev restarts the current song (standard player behaviour);
        // only a quick double-press walks back to the previous track.
        if (pos > 10_000L) { player.seekTo(0L); _state.update { it.copy(progressMs = 0L) }; return }
        val prevIdx = s.queueIndex - 1
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

    private fun prefetchSong(song: Song) {
        if (!_state.value.isFullStream) return
        // Warm lyrics for the upcoming song so they render the instant it starts.
        viewModelScope.launch {
            runCatching { repo.prefetchLyrics(song.id, song.title, song.artistName, song.durationMs / 1000) }
        }
        // Standalone: warm the in-app decrypt cache instead of pinging the (often
        // absent) proxy. decryptToFile writes clear_<id>.mp4, which the next play or
        // crossfade then reuses instantly.
        if (useStandalone() && song.id !in proxyOnlySongIds) {
            viewModelScope.launch { runCatching { decryptToFile(song) } }
            return
        }
        val url = repo.prefetchUrl(song.id)
        webServer.addLog("PRE", "prefetch start song=${song.title} url=$url")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-Music-User-Token", mutPrefs.getMUT())
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                conn.disconnect()
                webServer.addLog("PRE", "prefetch response=$code song=${song.title}")
            } catch (e: Exception) {
                webServer.addLog("PRE", "prefetch error=${e.message} song=${song.title}")
            }
        }
    }

    // Add to end of user priority queue (plays before rest of playlist)
    fun addToQueue(song: Song) { _state.update { it.copy(userQueue = it.userQueue + song) }; prefetchSong(song) }

    // Insert at front of user priority queue (plays immediately next)
    fun playNext(song: Song) { _state.update { it.copy(userQueue = listOf(song) + it.userQueue) }; prefetchSong(song) }

    // Play a specific userQueue item immediately, removing it from the queue
    fun playFromUserQueue(idx: Int) {
        val s = _state.value
        val song = s.userQueue.getOrNull(idx) ?: return
        val newUserQueue = s.userQueue.toMutableList().also { it.removeAt(idx) }
        _state.update { it.copy(userQueue = newUserQueue) }
        hasPlayedSomething = true
        val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
        val newQueue = s.queue.toMutableList().also { it.add(insertIdx, song) }
        _state.update { it.copy(queue = newQueue) }
        playQueueItem(insertIdx, skipFadeIn = true)
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

    private fun enrichSongIds(songId: String) = viewModelScope.launch {
        repo.getSong(songId).onSuccess { enriched ->
            if (enriched.artistId == null && enriched.albumId == null) return@onSuccess
            _state.update { s ->
                val updatedQueue = s.queue.map { q -> if (q.id == songId) q.copy(artistId = enriched.artistId ?: q.artistId, albumId = enriched.albumId ?: q.albumId) else q }
                val updatedSong  = s.currentSong?.takeIf { it.id == songId }?.copy(artistId = enriched.artistId ?: s.currentSong.artistId, albumId = enriched.albumId ?: s.currentSong.albumId)
                s.copy(queue = updatedQueue, currentSong = updatedSong ?: s.currentSong, song = updatedSong ?: s.song)
            }
        }
    }

    suspend fun lookupSongIds(songId: String): Pair<String?, String?> =
        repo.getSong(songId).getOrNull()?.let { it.artistId to it.albumId } ?: (null to null)

    private var lastAutoSaveMs = 0L
    private var lastProgressPushMs = 0L

    private fun pollProgress() = viewModelScope.launch {
        while (true) {
            val now = System.currentTimeMillis()
            val playing = player.isPlaying
            val playState = player.playbackState
            // During a crossfade the UI already shows the NEXT song, so track that
            // player's position — otherwise the bar sits frozen on the old song.
            val cf = crossfadeExo
            val progressSource = if (crossfadeInProgress && cf != null && cf.playbackState == Player.STATE_READY) cf else player
            // Re-anchor the UI clock about once a second, not every 200ms. The loop must
            // stay fast to catch STATE_ENDED and the crossfade window promptly, but the
            // UI interpolates between anchors per frame (rememberSmoothProgressMs), so
            // pushing progress 5x/sec just recomposed everything reading PlayerState for
            // no visible gain.
            val sourcePos = progressSource.currentPosition
            if (awaitingSongStart != null) {
                // Just switched tracks — pin the clock at 0 until the new item is genuinely
                // playing (READY and near the start), so the previous song's position/duration
                // doesn't linger on the bar during the source build.
                if (playState == Player.STATE_READY && sourcePos < 2_000L) awaitingSongStart = null
                if (_state.value.progressMs != 0L) _state.update { it.copy(progressMs = 0L) }
            } else {
                // A seek while PAUSED moves the player but not the clock, and the throttle
                // below only runs while playing — so the bar would sit frozen at the old
                // position until playback resumed. Detect the jump and push it through.
                val jumped = kotlin.math.abs(sourcePos - _state.value.progressMs) > 1_000L
                if (playing || progressSource !== player || jumped) {
                    if (jumped || now - lastProgressPushMs >= 900L) {
                        lastProgressPushMs = now
                        _state.update { it.copy(progressMs = sourcePos) }
                    }
                }
            }
            // Buffering only counts as "loading" while we're mid-crossfade or actually
            // trying to play — a paused player sitting at IDLE isn't waiting on anything.
            val loading = (playState == Player.STATE_BUFFERING) &&
                (player.playWhenReady || crossfadeInProgress)
            if (loading != _state.value.isLoading) _state.update { it.copy(isLoading = loading) }
            // Guard against a double-advance: after advanceQueue() the player keeps
            // reporting STATE_ENDED for a few ticks while the new (standalone) source builds.
            // awaitingSongStart stays set until the next item is genuinely playing, so we
            // don't skip a second time within a couple of seconds.
            if (playState == Player.STATE_ENDED && !crossfadeInProgress && awaitingSongStart == null) advanceQueue()
            // Auto-save position every 10s while playing so restore lands at the right spot
            if (playing && now - lastAutoSaveMs > 10_000L) { lastAutoSaveMs = now; saveState() }
            val timerEnd = _state.value.sleepTimerEndsAt
            if (timerEnd != null && System.currentTimeMillis() >= timerEnd) {
                val pos = player.currentPosition
                webServer.addLog("SLEEP", "timer fired at pos=${pos}ms song=${_state.value.currentSong?.title}")
                player.pause()
                _state.update { it.copy(sleepTimerEndsAt = null, isPlaying = false) }
            }

            // HTTP prefetch N+2 at 2/3 through current song
            if (playing && playState == Player.STATE_READY && !crossfadeInProgress) {
                val dur = player.duration
                val pos = player.currentPosition
                val s = _state.value
                val n2Song = if (s.userQueue.size >= 2) s.userQueue[1]
                             else if (s.userQueue.size == 1) s.queue.getOrNull(s.queueIndex + 2)
                             else s.queue.getOrNull(s.queueIndex + 2)
                if (dur > 0 && pos >= dur * 3 / 4 && n2Song != null && s.isFullStream
                    && preloadedForSongId != n2Song.id) {
                    preloadedForSongId = n2Song.id
                    prefetchSong(n2Song)
                    webServer.addLog("PRE", "prefetch N+2 at pos=${pos}ms/${dur}ms song=${n2Song.title}")
                }
            }

            // Crossfade: old player fades out over 5s; new player fades IN only for the last 3s
            if (playing && playState == Player.STATE_READY && !crossfadeInProgress) {
                val dur = player.duration
                val pos = player.currentPosition
                val remaining = dur - pos
                val s = _state.value
                val nextIdx = s.queueIndex + 1
                // Repeat One replays the current song, so there is no "next" to fade
                // into — fading would swap in the wrong track entirely. Repeat All on
                // the last song wraps to index 0, but only if that isn't the same song.
                val wrapsToStart = s.repeatMode == RepeatMode.All &&
                    nextIdx >= s.queue.size && s.queue.size > 1
                val hasNext = s.repeatMode != RepeatMode.One &&
                    (s.userQueue.isNotEmpty() || nextIdx < s.queue.size || wrapsToStart)
                if (dur > 0 && remaining in 1..crossfadeDurationMs && hasNext && s.crossfadeEnabled) {
                    val nextSong = s.userQueue.firstOrNull()
                        ?: s.queue.getOrNull(nextIdx)
                        ?: s.queue.firstOrNull().takeIf { wrapsToStart }
                    if (cfWindowLoggedForSongId != s.queue.getOrNull(s.queueIndex)?.id) {
                        webServer.addLog("CFXO", "crossfade window: remaining=${remaining}ms next=${nextSong?.title} isFullStream=${s.isFullStream} enabled=${s.crossfadeEnabled} skip=${nextSong?.id == crossfadeSkipSongId}")
                    }
                    // Gapless: consecutive tracks off the same album were often mastered
                    // to run continuous (live records, mixes, segued sides). Fading them
                    // talks over the transition the artist built, so hand off cleanly
                    // instead. albumId is null on some library rows — fall back to name.
                    val cur = s.queue.getOrNull(s.queueIndex)
                    val sameAlbum = cur != null && nextSong != null &&
                        (if (cur.albumId != null && nextSong.albumId != null) cur.albumId == nextSong.albumId
                         else cur.albumName.isNotBlank() && cur.albumName == nextSong.albumName)
                    val consecutive = cur?.trackNumber != null && nextSong?.trackNumber != null &&
                        nextSong.trackNumber == cur.trackNumber + 1
                    val gapless = sameAlbum && consecutive && s.userQueue.isEmpty()
                    // Log once per song, not once per 200ms poll — this block re-evaluates
                    // for the whole crossfade window and each addLog is an HTTP POST.
                    if (cfWindowLoggedForSongId != cur?.id) {
                        cfWindowLoggedForSongId = cur?.id
                        if (gapless) {
                            webServer.addLog("CFXO", "gapless: same album consecutive tracks — no fade into ${nextSong?.title}")
                        }
                    }
                    if (nextSong != null && s.isFullStream && !gapless && nextSong.id != crossfadeSkipSongId) {
                        crossfadeInProgress = true
                        // buildCrossfadeExo has no DRM of its own, so in standalone mode
                        // the fade partner must get a Widevine source too — otherwise it
                        // fetches a proxy URL that may not even be reachable, errors out,
                        // and every transition silently degrades to a hard cut.
                        val cfStandalone = useStandalone()
                        val cfExo = buildCrossfadeExo().also { e ->
                            e.volume = 0f
                            if (!cfStandalone) {
                                e.setMediaItem(buildMediaItem(nextSong, repo.streamUrl(nextSong.id)))
                                e.prepare()
                                e.play()
                            }
                        }
                        if (cfStandalone) {
                            viewModelScope.launch {
                                val src = buildStandaloneSource(nextSong)
                                if (src != null) cfExo.setMediaSource(src)
                                else cfExo.setMediaItem(buildMediaItem(nextSong, repo.streamUrl(nextSong.id)))
                                cfExo.prepare()
                                cfExo.play()
                            }
                        }
                        val preFadeQueue = s.queue; val preFadeIdx = s.queueIndex; val preFadeUserQueue = s.userQueue
                        val newUserQueue = if (s.userQueue.isNotEmpty()) s.userQueue.drop(1) else s.userQueue
                        val newQueue = if (s.userQueue.isNotEmpty()) {
                            s.queue.toMutableList().also { it.add((s.queueIndex + 1).coerceIn(0, it.size), nextSong) }
                        } else s.queue
                        // Repeat All off the end of the queue wraps to 0; coerceIn would
                        // otherwise clamp to lastIndex and leave the index on the song
                        // we're fading *out* of.
                        val actualNextIdx = if (wrapsToStart && s.userQueue.isEmpty()) 0
                            else (s.queueIndex + 1).coerceIn(0, newQueue.lastIndex)
                        val errListener = object : Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                webServer.addLog("CFXO", "cfExo error: ${error.errorCodeName} — restoring old player")
                                fadeJob?.cancel()
                                crossfadeInProgress = false; crossfadeExo = null; cfExoErrListener = null
                                crossfadeSkipSongId = nextSong.id
                                cfExo.removeListener(this); cfExo.release()
                                player.volume = 1f
                                val prevSong = preFadeQueue.getOrNull(preFadeIdx)
                                _state.update { it.copy(
                                    currentSong = prevSong, song = prevSong, lyrics = emptyList(), motionUrl = null,
                                    queue = preFadeQueue, queueIndex = preFadeIdx, userQueue = preFadeUserQueue,
                                )}
                                if (prevSong != null) { loadLyrics(prevSong.id); loadMotion(prevSong.id) }
                            }
                        }
                        cfExoErrListener = errListener
                        cfExo.addListener(errListener)
                        crossfadeExo = cfExo
                        val oldPlayer = player
                        _state.update { it.copy(
                            currentSong = nextSong, song = nextSong, lyrics = emptyList(), motionUrl = null,
                            queue = newQueue, queueIndex = actualNextIdx, userQueue = newUserQueue,
                            progressMs = 0L,
                        )}
                        loadLyrics(nextSong.id); loadMotion(nextSong.id)
                        if (nextSong.artistId == null || nextSong.albumId == null) enrichSongIds(nextSong.id)
                        val fadeDurationMs = remaining.coerceIn(300L, crossfadeDurationMs)
                        // Cancel the song-start fade-in first. On a track shorter than
                        // ~2x the crossfade length the two windows overlap, and without
                        // this both jobs drive the same player's volume — one up, one
                        // down — and the old job is orphaned when the field is reassigned.
                        fadeJob?.cancel()
                        fadeJob = viewModelScope.launch {
                            val steps = 50
                            val stepMs = fadeDurationMs / steps
                            val startVol = oldPlayer.volume
                            // Hand the beat bus to the incoming song once it's the louder
                            // one. Otherwise the visuals pulse to the outgoing song's tail
                            // (often a quiet outro) for the whole crossfade and read as dead.
                            var beatPromoted = false
                            for (i in 1..steps) {
                                val frac = i.toFloat() / steps
                                oldPlayer.volume = (startVol * (1f - frac)).coerceAtLeast(0f)
                                cfExo.volume = frac.coerceAtMost(1f)
                                if (!beatPromoted && frac >= 0.5f && cfExo.playbackState == Player.STATE_READY) {
                                    promoteCrossfadeBeat(); beatPromoted = true
                                }
                                delay(stepMs)
                            }
                            if (!crossfadeInProgress || crossfadeExo == null) {
                                webServer.addLog("CFXO", "cfExo released during fade — aborting swap")
                                return@launch
                            }
                            // If cfExo is still buffering (cache not ready), give it a short grace
                            // period. The old song loops while we wait, so waiting long is worse
                            // than bailing — and if the server is down it's never coming.
                            if (cfExo.playbackState == Player.STATE_BUFFERING) {
                                val waitMs = if (serverPrefs.serverReachable) 8_000L else 0L
                                webServer.addLog("CFXO", "cfExo buffering after fade — waiting ${waitMs}ms (reachable=${serverPrefs.serverReachable})")
                                oldPlayer.volume = 1f
                                val deadline = System.currentTimeMillis() + waitMs
                                while (cfExo.playbackState == Player.STATE_BUFFERING
                                    && System.currentTimeMillis() < deadline
                                    && crossfadeInProgress) {
                                    delay(100)
                                }
                            }
                            if (!crossfadeInProgress || crossfadeExo == null) {
                                webServer.addLog("CFXO", "cfExo released while waiting for ready — aborting")
                                return@launch
                            }
                            if (cfExo.playbackState == Player.STATE_READY) {
                                // Quick 1s crossfade if we had to wait
                                val qSteps = 10
                                repeat(qSteps) { i ->
                                    val f = (i + 1).toFloat() / qSteps
                                    oldPlayer.volume = (1f - f).coerceAtLeast(0f)
                                    cfExo.volume = f.coerceAtMost(1f)
                                    delay(100)
                                }
                                oldPlayer.removeListener(playerListener)
                                oldPlayer.volume = 0f; oldPlayer.stop(); oldPlayer.release()
                                cfExo.removeListener(errListener); cfExoErrListener = null
                                cfExo.addListener(playerListener)
                                player = cfExo; crossfadeExo = null; crossfadeInProgress = false
                                promoteCrossfadeBeat()
                                webServer.addLog("CFXO", "fade complete → cfExo isPlaying=${cfExo.isPlaying}")
                                mediaSession?.release(); mediaSession = buildMediaSession(cfExo)
                                _state.update { it.copy(isPlaying = cfExo.isPlaying) }
                                val sNow = _state.value
                                val prefetchNext = sNow.userQueue.firstOrNull() ?: sNow.queue.getOrNull(sNow.queueIndex + 1)
                                if (prefetchNext != null && preloadedForSongId != prefetchNext.id) {
                                    preloadedForSongId = prefetchNext.id; prefetchSong(prefetchNext)
                                }
                            } else {
                                webServer.addLog("CFXO", "cfExo state=${cfExo.playbackState} — giving up, playing next directly")
                                crossfadeInProgress = false; crossfadeExo = null; cfExoErrListener = null
                                try { cfExo.removeListener(errListener); cfExo.stop(); cfExo.release() } catch (_: Exception) {}
                                oldPlayer.volume = 1f
                                playQueueItem(_state.value.queueIndex, skipFadeIn = true)
                            }
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

    fun showNotification(title: String, body: String, id: Int = 42) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "am_alerts"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Apple Music Alerts", NotificationManager.IMPORTANCE_HIGH)
                    .also { it.description = "Auth and service alerts" }
            )
        }
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }

    private suspend fun checkAppleServiceStatus() {
        val result = repo.getAppleStatus().getOrNull() ?: return
        if (!result.ok) {
            val affected = result.services
                .filter { it.status != "operational" }
                .joinToString(", ") { it.name }
                .ifEmpty { "Apple Music" }
            webServer.addLog("SVCMON", "Apple service issue detected — $affected")
            showNotification(
                title = "Apple Music — Service Disruption",
                body  = "Upstream issue detected ($affected). Expect degraded performance.",
                id    = 43,
            )
        }
    }

    private fun showAuthNotification() {
        val url = webServer.serverUrl().replace(":8080", "").let { "$it:8080" }
        showNotification(
            title = "Action Required — Token Expired",
            body  = "Your Apple Music session has ended. Visit $url to re-authenticate.",
            id    = 42,
        )
    }

    override fun onCleared() {
        audioDeviceCallback?.let {
            runCatching { context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(it) }
        }
        audioDeviceCallback = null
        saveState()
        lyricsJob?.cancel()
        motionJob?.cancel()
        fadeJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        player.stop()
        player.clearMediaItems()
        player.release()
        super.onCleared()
    }
}
