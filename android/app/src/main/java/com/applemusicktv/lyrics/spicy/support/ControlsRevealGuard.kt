package com.applemusicktv.lyrics.spicy.support

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where a touch leaves the playback controls as they are, when any other touch on the screen
 * brings them back: the cover's gestures and the credits' profile links.
 */
class ControlsRevealGuard {
    /** What a touch going down somewhere holds back. */
    enum class Hold {
        None,
        /** A tap; once the finger moves it's a drag like any other and shows the controls. */
        Taps,
        /** The whole gesture, drags included. */
        All,
    }

    internal class Area(val tapsOnly: Boolean, val hit: (Offset) -> Boolean) {
        var coordinates: LayoutCoordinates? = null
    }

    private val areas = mutableListOf<Area>()

    /** The screen the touches are reported in. */
    var screen: LayoutCoordinates? = null

    internal fun add(area: Area) { areas += area }
    internal fun remove(area: Area) { areas -= area }

    /** What a touch going down at [position], on [screen], holds back. */
    fun holdAt(position: Offset): Hold {
        val screen = screen?.takeIf { it.isAttached } ?: return Hold.None
        var hold = Hold.None
        for (area in areas) {
            val coordinates = area.coordinates?.takeIf { it.isAttached } ?: continue
            val local = coordinates.localPositionOf(screen, position)
            val size = coordinates.size
            if (local.x !in 0f..size.width.toFloat() || local.y !in 0f..size.height.toFloat()) continue
            if (!area.hit(local)) continue
            if (!area.tapsOnly) return Hold.All
            hold = Hold.Taps
        }
        return hold
    }
}

val LocalControlsRevealGuard = staticCompositionLocalOf<ControlsRevealGuard?> { null }

/**
 * Touches here, where [hit] says so (in this element's space), don't bring the controls back.
 * With [tapsOnly], a drag starting here still does.
 */
fun Modifier.keepsControlsHidden(tapsOnly: Boolean = false, hit: (Offset) -> Boolean = { true }): Modifier = composed {
    val guard = LocalControlsRevealGuard.current ?: return@composed Modifier
    val latestHit by rememberUpdatedState(hit)
    val area = remember(tapsOnly) { ControlsRevealGuard.Area(tapsOnly) { latestHit(it) } }
    DisposableEffect(guard, area) {
        guard.add(area)
        onDispose { guard.remove(area) }
    }
    Modifier.onGloballyPositioned { area.coordinates = it }
}
