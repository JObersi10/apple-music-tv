package com.applemusicktv

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.applemusicktv.ui.AppShell
import com.applemusicktv.ui.viewmodel.MusicVideoViewModel
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val navVm: NavigationViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()
    private val mvVm: MusicVideoViewModel by viewModels()

    private val pipActionName = "com.applemusicktv.PIP_TOGGLE"
    private var pipReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppShell() }
        // Keep the PiP play/pause button's icon in sync while we're in PiP (the track can
        // pause/resume on its own, e.g. at end of queue).
        lifecycleScope.launch {
            playerVm.state.map { it.isPlaying }.distinctUntilChanged().collect {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                    runCatching { setPictureInPictureParams(pipParams()) }
                }
            }
        }
    }

    /** PiP params with a single play/pause remote action wired to a broadcast we handle. */
    private fun pipParams(): PictureInPictureParams {
        val playing = playerVm.state.value.isPlaying
        val icon = Icon.createWithResource(
            this,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        val label = if (playing) "Pause" else "Play"
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(pipActionName).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(RemoteAction(icon, label, label, pi)))
            .build()
    }

    /** Enter Picture-in-Picture. May be unsupported on some Fire TV hardware — harmless no-op there. */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /** Home / recents while on Now Playing → drop into PiP. Anywhere else, just keep playing. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Only drop into PiP when something is actually PLAYING — a paused song/video should just
        // background quietly, not pop a frozen PiP tile.
        val playing = playerVm.state.value.isPlaying || mvVm.state.value.playing
        if ((navVm.isOnNowPlaying || navVm.isOnMusicVideo) && playing && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        playerVm.setPipMode(isInPip)
        if (isInPip) {
            // Register a receiver for the PiP button so its taps actually toggle playback.
            if (pipReceiver == null) {
                pipReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        if (i?.action == pipActionName) {
                            playerVm.togglePlayPause()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                                runCatching { setPictureInPictureParams(pipParams()) }
                            }
                        }
                    }
                }
                val filter = IntentFilter(pipActionName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(pipReceiver, filter)
                }
            }
        } else {
            pipReceiver?.let { runCatching { unregisterReceiver(it) } }
            pipReceiver = null
        }
    }

    override fun onStop() {
        super.onStop()
        // Background audio is for Picture-in-Picture only: leaving the app any other way pauses.
        if (!isInPictureInPictureMode) playerVm.pause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While a video is active, media keys drive the VIDEO — never the (paused) audio
        // player. D-pad keys still fall through so the fullscreen video screen (or the UI
        // behind an in-app PiP) can handle them.
        if (navVm.isOnMusicVideo) {
            if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { mvVm.next(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> { mvVm.prev(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                    { mvVm.togglePlayPause(); return true }
                // A video can be the current track while you browse other tabs (it keeps playing).
                // Only toggle the in-video queue when actually ON the video screen; elsewhere Menu
                // brings the video fullscreen (Now Playing), same as for audio.
                KeyEvent.KEYCODE_MENU -> {
                    if (navVm.isOnNowPlaying) mvVm.toggleQueue() else navVm.navigateToNowPlaying()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (navVm.isOnNowPlaying) navVm.toggleQueuePanel()
                    else navVm.navigateToNowPlaying()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { playerVm.togglePlayPause(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT     -> { playerVm.next(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playerVm.prev(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { playerVm.next(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND       -> { playerVm.prev(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
