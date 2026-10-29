package com.applemusicktv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design-system tokens for the v2 UI overhaul — the single source of truth for colour roles, radii,
 * spacing, elevation and the type ramp, so every new screen looks like Apple Music's own app and
 * every swap is consistent. **Phase 0: additive only — nothing is wired to these yet** (see
 * ROADMAP.md → UI Overhaul). Existing screens keep using [Theme.kt] until each is repointed here
 * behind the New-UI flag.
 *
 * The look is ~8 systems used consistently (materials/depth, one card system, a tight type ramp,
 * shelves, two-pane detail, etc.); these tokens encode the primitives those systems share.
 */
object AmTokens {

    /** Colour roles. `accent` is Apple's exact red. Surfaces are translucent glass over the wash. */
    object Color {
        val Accent          = androidx.compose.ui.graphics.Color(0xFFFA233B)
        val Background      = androidx.compose.ui.graphics.Color(0xFF000000)
        /** Card / nav-pill glass — a translucent scrim (real blur only where API >= 31). */
        val SurfaceGlass    = androidx.compose.ui.graphics.Color(0x14FFFFFF)
        val SurfaceGlassHi  = androidx.compose.ui.graphics.Color(0x24FFFFFF)
        val Surface         = androidx.compose.ui.graphics.Color(0xFF141416)
        val TextPrimary     = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        val TextSecondary   = androidx.compose.ui.graphics.Color(0xFF9A9AA0)
        val LabelUppercase  = androidx.compose.ui.graphics.Color(0xFFB9B9C0)
        val Divider         = androidx.compose.ui.graphics.Color(0x1AFFFFFF)
    }

    /** Corner radii. Cards are ~14 (Apple's card corner). */
    object Radius {
        val Card = 14.dp
        val Tile = 10.dp
        val Pill = 100.dp
    }

    /** 4-based spacing scale, reused everywhere so gaps read as one rhythm. */
    object Space {
        val xs = 4.dp
        val sm = 8.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 40.dp
        val shelfGap = 28.dp
    }

    /**
     * Type ramp — SF-style, three or four sizes reused (never ad-hoc). Tiny UPPERCASE tracking-wide
     * labels above art, bold titles, medium-grey subtitles.
     */
    object Type {
        val LabelSize = 10.sp
        val LabelTracking = 1.2.sp
        val LabelWeight = FontWeight.SemiBold

        val TitleSize = 13.sp
        val TitleWeight = FontWeight.SemiBold

        val SubtitleSize = 11.sp
        val SubtitleWeight = FontWeight.Normal

        val HeaderSize = 17.sp
        val HeaderWeight = FontWeight.Bold
    }

    /** Focus behaviour shared by every card: scale up + brighten. */
    object Focus {
        const val Scale = 1.06f
        const val RestScale = 1.0f
    }
}
