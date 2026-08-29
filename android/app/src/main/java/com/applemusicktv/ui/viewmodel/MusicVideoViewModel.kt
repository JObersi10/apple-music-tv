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
    val audioTracks:  List<SubtitleOption> = emptyList(),
    val audioIndex:   Int = 0,
    val info:         String? = null,
    val artistId:     String? = null,
    /** Max video height cap the user picked (persisted, applied to every video). */
    val qualityHeight: Int = 1080,
    /** The height ACTUALLY decoding right now (from the decoder), so the UI shows real, not requested. */
    val actualHeight: Int = 0,
    /** The heights THIS video actually offers (from its master), ascending. */
    val availableQualities: List<Int> = emptyList(),
)

/** Selectable max-quality tiers for music videos. Persisted globally. */
val MV_QUALITY_TIERS = listOf(480, 720, 1080, 2160)
fun qualityLabel(h: Int) = if (h >= 2160) "4K" else "${h}p"

/** Apple only ever exposes a handful of clean choices (480p/720p/1080p/4K). An HLS master, though,
 *  carries a dozen odd ladder rungs (310p, 352p, 378p, …). Snap the raw variant heights to the
 *  standard tiers the video can actually reach, so the quality menu reads like Apple's, not like a
 *  debug dump. A tier is offered when the video has a variant at least that tall (small tolerance
 *  for off-by-a-few encodes). setQuality(tier) then picks the best real variant ≤ that tier. */
fun qualityTiers(rawHeights: List<Int>): List<Int> {
    val maxH = rawHeights.maxOrNull() ?: return listOf(720)
    return MV_QUALITY_TIERS.filter { it <= maxH + 60 }.ifEmpty { listOf(MV_QUALITY_TIERS.first()) }
}

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
    private val videoQueue: com.applemusicktv.media.VideoQueue,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("mv_prefs", Context.MODE_PRIVATE)
    private var qualityHeight = prefs.getInt("quality_height", 1080)
    // HDCP ceiling learned from this display: Apple requires an active HDCP link for protected HD.
    // When a tier fails with "Required output protections are not active" we cap here so the next
    // video in THIS session starts at a height this HDMI chain can show (no fail-then-reload glitch
    // every play). It is NOT persisted across launches: a persisted low cap trapped a good TV at
    // 480p forever after the app had once run on a weak display. Each launch re-probes optimistically;
    // a genuinely HDCP-limited display just re-learns with one glitch on the first HD play.
    private var hdcpCap = 4320
    /** The height we actually request: the user's pick, clamped to what this display can decrypt. */
    private fun effHeight() = minOf(qualityHeight, hdcpCap)

    private val _state = MutableStateFlow(MvUiState(qualityHeight = qualityHeight))
    val state: StateFlow<MvUiState> = _state

    // The video currently loaded — so a quality change can rebuild it at the new tier and resume.
    private var curMvId: String? = null
    private var curAdamId: String? = null   // resolved numeric CATALOG id (library ids don't work for metadata)
    private var curTitle = ""
    private var curArtist = ""
    private var pendingSeekMs = 0L
    // The last resolved playback + creds, kept so detach/attach can REBUILD the player from disk
    // (no network) when swapping between the full video player and an audio-only continuation.
    private var curMv: com.applemusicktv.media.MusicVideoResult? = null
    private var curBearer = ""
    private var curMut = ""

    /** Pick a quality tier, persist globally, and RELOAD the current video at that tier.
     *  The MV master we build carries a single video variant (chosen by height), so there is
     *  nothing to switch in-session — quality only changes by rebuilding the playlist. */
    fun setQuality(height: Int) {
        if (height == qualityHeight) return
        qualityHeight = height
        prefs.edit().putInt("quality_height", qualityHeight).apply()
        _state.value = _state.value.copy(qualityHeight = qualityHeight)
        prefetchCache.clear()   // cached masters are the old quality now
        val id = curMvId ?: return
        pendingSeekMs = player?.currentPosition ?: 0L
        playItem(id, curTitle, curArtist)
    }

    // Whether a video is currently loaded/active. Hoisted in AppShell so the video survives
    // navigation: it plays fullscreen on the Now Playing tab and shrinks to an in-app PiP
    // window on any other tab — it never gets torn down by leaving a route.
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    // Live subtitle cues, pushed to a SubtitleView by the UI (we render video on a plain
    // TextureView, which has no caption surface of its own).
    private val _cues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    val cues: StateFlow<List<androidx.media3.common.text.Cue>> = _cues

    // The Up-Next queue panel in the video player (toggled by Menu on Fire TV, or the on-screen
    // queue button). The list itself lives in PlayerViewModel — AppShell hands it in.
    private val _showQueue = MutableStateFlow(false)
    val showQueue: StateFlow<Boolean> = _showQueue
    fun toggleQueue() { _showQueue.value = !_showQueue.value }
    fun hideQueue() { _showQueue.value = false }

    var player: ExoPlayer? = null
        private set

    // Observable player handle so the UI rebinds its PlayerView when a queue skip swaps in a
    // NEW ExoPlayer instance (the plain `player` var doesn't trigger recomposition → black).
    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow: StateFlow<ExoPlayer?> = _playerFlow

    // Held so it can be released with the player — an externally-provided DrmSessionManager
    // is NOT auto-released by ExoPlayer, and a leaked Widevine session starved the secure
    // video decoder on the next video (it played audio-only).
    private var drmManager: DefaultDrmSessionManager? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var progressJob: kotlinx.coroutines.Job? = null

    // Prefetch: the next video's webPlayback + master/keys, resolved ahead of time so a queue
    // advance into a video skips the ~0.3–1s network round-trip. The ExoPlayer/DRM session is
    // still built fresh at play time (licenses are per-session); only the metadata is cached.
    private val prefetchCache = java.util.concurrent.ConcurrentHashMap<String, com.applemusicktv.media.MusicVideoResult>()
    private var prefetchJob: kotlinx.coroutines.Job? = null
    private var prefetchingId: String? = null

    /** Warm the next video (call with the id of the upcoming queue item, or null to no-op). */
    fun prefetch(mvId: String?) {
        if (mvId.isNullOrEmpty() || prefetchCache.containsKey(mvId) || prefetchingId == mvId) return
        prefetchingId = mvId
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            try {
                val bearer = appleClient.getBearer(); val mut = mutPrefs.getMUT()
                if (bearer.isEmpty() || mut.isEmpty()) return@launch
                val mv = withContext(Dispatchers.IO) { appleClient.getMusicVideoPlayback(mvId, bearer, mut, effHeight()) }
                prefetchCache[mvId] = mv
                if (prefetchCache.size > 3) prefetchCache.keys.firstOrNull { it != mvId }?.let { prefetchCache.remove(it) }
            } catch (_: Exception) {} finally { if (prefetchingId == mvId) prefetchingId = null }
        }
    }

    /** Start (or replace) the active video. startPaused=true loads it but does NOT play (used when
     *  restoring on app open — reopening shouldn't blast a video's audio). */
    fun show(mvId: String, title: String, artist: String, startPaused: Boolean = false) {
        _active.value = true
        userPaused = startPaused
        // A freshly-opened video always navigates to Now Playing (autoOpen), so the surface will be
        // there by the time the async load builds the player — start with video enabled.
        videoDetached = false
        load(mvId, title, artist)
    }

    /** Tear the video down entirely and mark inactive (leaves fullscreen/PiP). */
    fun close() {
        releasePlayer()
        _active.value = false
        _state.value = MvUiState(loading = false)
    }

    /** Entry point. Uses the queue set by the caller if it holds this id,
     *  otherwise falls back to a one-item queue so prev/next are simply no-ops. */
    // The integrated queue lives in PlayerViewModel; prev/next/auto-advance ask it to move and
    // it hands us the next video (or a song, which dismisses us). Wired by AppShell.
    var onRequestNext: () -> Unit = {}
    var onRequestPrev: () -> Unit = {}

    /** Play a single video (the shared queue's current item). */
    fun load(mvId: String, title: String, artist: String) = playItem(mvId, title, artist)

    fun next() = onRequestNext()

    /** Like the audio player: restart if we're past 3s, else ask the queue for the previous item. */
    fun prev() {
        if ((player?.currentPosition ?: 0) > 3000) { player?.seekTo(0); return }
        onRequestPrev()
    }

    @OptIn(UnstableApi::class)
    private fun playItem(mvId: String, title: String, artist: String) {
        releasePlayer()
        curMvId = mvId; curTitle = title; curArtist = artist
        _state.value = MvUiState(loading = true, title = title, artist = artist, qualityHeight = qualityHeight)
        viewModelScope.launch {
            try {
                val bearer = appleClient.getBearer()
                val mut = mutPrefs.getMUT()
                if (bearer.isEmpty() || mut.isEmpty()) {
                    _state.value = _state.value.copy(loading = false, error = "Sign-in required")
                    return@launch
                }
                // Use the prefetched result if the queue warmed this id ahead of time.
                val mv = prefetchCache.remove(mvId)
                    ?: withContext(Dispatchers.IO) { appleClient.getMusicVideoPlayback(mvId, bearer, mut, effHeight()) }
                _state.value = _state.value.copy(availableQualities = qualityTiers(mv.heights))
                curAdamId = mv.adamId   // numeric catalog id for metadata/artist lookups
                curMv = mv; curBearer = bearer; curMut = mut

                // Write the corrected master + media playlists to disk (once), then build the player.
                withContext(Dispatchers.IO) {
                    val dir = context.cacheDir
                    java.io.File(dir, com.applemusicktv.media.MV_VIDEO_FILE).writeText(mv.videoText)
                    java.io.File(dir, com.applemusicktv.media.MV_AUDIO_FILE).writeText(mv.audioText)
                    mv.subsText?.let { java.io.File(dir, com.applemusicktv.media.MV_SUBS_FILE).writeText(it) }
                    java.io.File(dir, com.applemusicktv.media.MV_MASTER_FILE).writeText(mv.masterText)
                }
                buildAndPlay(mv, bearer, mut, disableVideo = videoDetached, seekMs = pendingSeekMs, playWhenReady = !userPaused)
                pendingSeekMs = 0
                // Real catalogue metadata for the Info panel — genre, release, album, composer.
                launch {
                    withContext(Dispatchers.IO) { appleClient.getMusicVideoDetails(curAdamId ?: mvId, bearer, mut) }?.let { d ->
                        _state.value = _state.value.copy(info = d.info, artistId = d.artistId)
                    }
                }
            } catch (e: Exception) {
                Log.e("AMMV", "MV load failed: ${e.message}", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load video")
            }
        }
    }

    /** Build (or rebuild) the ExoPlayer from an already-resolved result. The playlists are on disk,
     *  so this needs NO network — used both by the initial load and by detach/attach, which swap
     *  between a full video player and an audio-only continuation without a fetch. When disableVideo
     *  is true the secure video decoder is never created (no surface, no library-bleed overlay). */
    @OptIn(UnstableApi::class)
    private fun buildAndPlay(
        mv: com.applemusicktv.media.MusicVideoResult,
        bearer: String, mut: String,
        disableVideo: Boolean, seekMs: Long, playWhenReady: Boolean,
    ) {
        releasePlayer()
        val masterUri = android.net.Uri.fromFile(
            java.io.File(context.cacheDir, com.applemusicktv.media.MV_MASTER_FILE)).toString()
        run {
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
                    // disableVideo starts the player audio-only — used for the off-Now-Playing
                    // continuation so no secure video decoder (and its lingering overlay) exists.
                    parameters = buildUponParameters()
                        .setMaxVideoSize(3840, effHeight())
                        .setPreferredTextLanguage(null)
                        .setSelectUndeterminedTextLanguage(false)
                        .setDisabledTextTrackSelectionFlags(0)
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
                        .build()
                }
                trackSelector = selector
                val exo = ExoPlayer.Builder(context)
                    .setTrackSelector(selector)
                    .build()
                exo.setMediaSource(source)
                if (seekMs > 0) exo.seekTo(seekMs)   // resume position (quality change / detach-attach swap)
                exo.prepare()
                exo.playWhenReady = playWhenReady
                exo.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.value = _state.value.copy(playing = isPlaying)
                    }
                    override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                        // Show the resolution actually decoding, not the tier we asked for (HDCP can
                        // silently cap it). The pill then never reads "1080p" over a 432p picture.
                        if (vs.height > 0) _state.value = _state.value.copy(actualHeight = vs.height)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            _state.value = _state.value.copy(loading = false, durationMs = exo.duration.coerceAtLeast(0))
                        } else if (state == Player.STATE_ENDED) {
                            // Auto-advance through the queue, exactly like the audio player.
                            onRequestNext()   // auto-advance through the shared queue
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("AMMV", "MV playback error: ${error.message}", error)
                        // "Required output protections are not active" = this HDMI chain has no active
                        // HDCP link, so Apple blocks protected HD. Learn the ceiling (persist it) so we
                        // stop re-attempting HD on every video — that failed attempt then reload IS the
                        // 480p glitch. Cap to the highest tier strictly below what just failed.
                        val msg = (error.message.orEmpty() + " " + (error.cause?.message ?: ""))
                        val isHdcp = msg.contains("output protection", ignoreCase = true)
                        val failedAt = effHeight()
                        val lower = MV_QUALITY_TIERS.filter { it < failedAt }.maxOrNull()
                        if (lower != null && curMvId != null) {
                            // Cap in-memory so the reload doesn't re-pick the failing height (that would
                            // loop). Persist ONLY for the HDCP case — a stable hardware fact — so future
                            // launches skip HD straight away. A transient decoder hiccup shouldn't
                            // permanently lower quality.
                            hdcpCap = lower
                            if (isHdcp) {
                                // Session-only cap — NOT persisted (see the field comment): a persisted
                                // low cap trapped a good TV at 480p forever. Re-probes next launch.
                                Log.w("AMMV", "HDCP unavailable for ${failedAt}p → capping this session at ${lower}p")
                                // Tell the user once why HD dropped — the display/HDMI link isn't
                                // authenticating HDCP, so Apple blocks protected HD (not app-fixable).
                                android.widget.Toast.makeText(
                                    context,
                                    "HDCP Error — display link isn't protected, so HD is blocked. Playing ${lower}p.",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                Log.w("AMMV", "decoder failed at ${failedAt}p → falling back to ${lower}p (session)")
                            }
                            pendingSeekMs = player?.currentPosition ?: 0L
                            playItem(curMvId!!, curTitle, curArtist)   // reload clamped to effHeight()
                            return
                        }
                        _state.value = _state.value.copy(loading = false, error = error.message ?: "Playback failed")
                    }
                    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                        _cues.value = cueGroup.cues
                        Log.i("AMMVsub", "cues=${cueGroup.cues.size} text=${cueGroup.cues.firstOrNull()?.text}")
                    }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val subs = mutableListOf(SubtitleOption("Off", -1))
                        var subSel = -1; var si = 0
                        val auds = mutableListOf<SubtitleOption>()
                        var audSel = 0; var ai = 0
                        for (group in tracks.groups) {
                            when (group.type) {
                                C.TRACK_TYPE_TEXT -> for (t in 0 until group.length) {
                                    val fmt = group.getTrackFormat(t)
                                    subs.add(SubtitleOption(fmt.label ?: fmt.language?.uppercase() ?: "Subtitles", si))
                                    if (group.isTrackSelected(t)) subSel = si
                                    si++
                                }
                                C.TRACK_TYPE_AUDIO -> for (t in 0 until group.length) {
                                    val fmt = group.getTrackFormat(t)
                                    auds.add(SubtitleOption(langName(fmt.language) ?: fmt.label ?: "Audio ${ai + 1}", ai))
                                    if (group.isTrackSelected(t)) audSel = ai
                                    ai++
                                }
                                else -> {}
                            }
                        }
                        Log.i("AMMVsub", "tracks text=${subs.size - 1} audio=${auds.size} sel=$subSel")
                        _state.value = _state.value.copy(
                            subtitles = if (subs.size > 1) subs else emptyList(),
                            subtitleIndex = subSel,
                            audioTracks = if (auds.size > 1) auds else emptyList(),
                            audioIndex = audSel,
                        )
                    }
                })
                player = exo
                _playerFlow.value = exo
                _state.value = _state.value.copy(loading = false)
                startProgress()
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

    /** Off (-1) disables the text renderer; otherwise override to the exact text track by
     *  index. A hard override (not a language preference) is what actually turns on CEA-608
     *  closed captions muxed into the video (e.g. Die With a Smile). */
    @OptIn(UnstableApi::class)
    fun setSubtitle(index: Int) {
        val exo = player ?: return
        var params = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, index < 0)
        if (index >= 0) {
            var ti = 0
            outer@ for (group in exo.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                for (t in 0 until group.length) {
                    if (ti == index) {
                        params = params.setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, t))
                        break@outer
                    }
                    ti++
                }
            }
        }
        exo.trackSelectionParameters = params.build()
        _state.value = _state.value.copy(subtitleIndex = index)
    }

    /** Switch audio rendition by index (position in [MvUiState.audioTracks]). */
    @OptIn(UnstableApi::class)
    fun setAudio(index: Int) {
        val exo = player ?: return
        var ai = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (t in 0 until group.length) {
                if (ai == index) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, t))
                        .build()
                    _state.value = _state.value.copy(audioIndex = index)
                    return
                }
                ai++
            }
        }
    }

    /** Open the artist page. Uses the resolved id if we have it, else fetches it on demand
     *  (the details fetch can lag or fail, but the button stays usable). */
    fun openArtist(navigate: (String) -> Unit) {
        _state.value.artistId?.let { navigate(it); return }
        val id = curAdamId ?: curMvId ?: return
        viewModelScope.launch {
            val bearer = appleClient.getBearer(); val mut = mutPrefs.getMUT()
            if (bearer.isEmpty() || mut.isEmpty()) return@launch
            val d = withContext(Dispatchers.IO) { appleClient.getMusicVideoDetails(id, bearer, mut) }
            d?.artistId?.let { _state.value = _state.value.copy(artistId = it); navigate(it) }
        }
    }

    // A music video keeps playing (audio) across tabs just like a song — the picture is simply
    // hidden (its secure surface is parked off-screen by AppShell) and reappears on Now Playing.
    // Only a RESTORE loads it paused (userPaused=true via show(startPaused=true)).
    private var userPaused = false
    fun setScreenVisible(visible: Boolean) { /* no longer gates playback — kept for callers */ }

    // Leaving Now Playing must NOT stop the audio — a music video keeps playing across tabs like a
    // song. But on Fire TV the ONLY thing that reliably tears down a secure (HDCP) SurfaceView's
    // SurfaceFlinger overlay is releasing the secure video DECODER; hiding the View (GONE / detach /
    // off-screen / removing the AndroidView) all bled, and disabling the video *track* on a live
    // player kept the decoder allocated and still bled. So on leave we fully REBUILD the player as
    // audio-only (video track disabled at creation → the secure decoder never exists → no overlay),
    // seeked to the current position. It rebuilds from the on-disk playlists (no network), so the
    // audio only blips ~0.3s. Returning rebuilds WITH video at the current position; the recomposed
    // PlayerView attaches the surface. videoDetached also guards a queue advance into a new video
    // while off-screen (playItem starts it audio-only).
    // The library bleed was Compose DISPOSING the secure SurfaceView when we left Now Playing —
    // on Fire TV that orphans the secure SurfaceFlinger layer (it lingers as a frozen top overlay).
    // Fix (in AppShell): the PlayerView stays composed the whole time a video is active and only
    // toggles VISIBILITY, so the view keeps managing its layer and SurfaceFlinger reaps it cleanly.
    // Here we just disable the video track off-screen so the secure decoder stops (a secure decoder
    // with a detached surface would error) while the audio renderer keeps the sound going.
    // videoDetached tracks whether we are currently OFF Now Playing (video track disabled, no
    // secure decoder). isVideoDetached lets AppShell drive the ORDER of surface teardown/attach.
    private var videoDetached = false
    val isVideoDetached: Boolean get() = videoDetached
    // detach/attach keep the SAME ExoPlayer + DRM session alive (no rebuild, no license re-fetch —
    // rebuilding on every tab switch re-hit play.itunes.apple.com and stranded the user on the error
    // screen on any DNS blip). We only free the secure video codec + surface here; the actual overlay
    // teardown is AppShell UNMOUNTING (destroying) the SurfaceView while no protected frame is latched.
    @OptIn(UnstableApi::class)
    fun detachVideo() {
        if (videoDetached) return
        videoDetached = true
        val exo = player ?: return
        Log.i("AMMV", "detachVideo: clearVideoSurface + disable video track (keep player+audio+DRM)")
        // Detach the output surface FIRST so no protected frame stays latched, THEN disable the video
        // renderer to free the secure codec. AppShell then destroys the (now content-free) SurfaceView.
        exo.clearVideoSurface()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
    }
    @OptIn(UnstableApi::class)
    fun attachVideo() {
        if (!videoDetached) return
        videoDetached = false
        val exo = player ?: return
        Log.i("AMMV", "attachVideo: re-enable video track (remounted PlayerView reattaches surface)")
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
    }

    fun togglePlayPause() { player?.let { userPaused = it.playWhenReady; it.playWhenReady = !it.playWhenReady } }
    fun seekBy(deltaMs: Long) { player?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) } }
    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)) }

    /** Tear down the current player/DRM without clearing the queue (used between queue items). */
    private fun releasePlayer() {
        progressJob?.cancel()
        player?.release()
        player = null
        _playerFlow.value = null
        drmManager?.release()
        drmManager = null
    }

    fun release() = releasePlayer()

    override fun onCleared() { release() }

    private fun langName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        return runCatching { java.util.Locale(code).displayLanguage.ifBlank { code } }.getOrDefault(code)
    }
}
