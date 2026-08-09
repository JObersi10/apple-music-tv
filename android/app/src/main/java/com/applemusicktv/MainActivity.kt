package com.applemusicktv

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.applemusicktv.ui.AppShell
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val navVm: NavigationViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppShell() }
    }

    /** Enter Picture-in-Picture. May be unsupported on some Fire TV hardware — harmless no-op there. */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    /** Home / recents while on Now Playing → drop into PiP. Anywhere else, just keep playing. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (navVm.isOnNowPlaying && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        playerVm.setPipMode(isInPip)
    }

    override fun onStop() {
        super.onStop()
        // Keep playing in the background when the user has enabled it (or when we're in PiP);
        // otherwise pause as before. The MediaSessionService keeps audio alive either way.
        if (!playerVm.backgroundPlayEnabled && !isInPictureInPictureMode) playerVm.pause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
