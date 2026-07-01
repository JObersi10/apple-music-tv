package com.applemusicktv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val AppleRed    = Color(0xFFFA233B)
val DeepBlack   = Color(0xFF000000)
val SurfaceDark = Color(0xFF111111)
val OnSurface   = Color(0xFFE5E5E7)
val SubtleGray  = Color(0xFF8E8E93)

@Composable
fun AppleMusicTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary          = AppleRed,
            onPrimary        = Color.White,
            primaryContainer = AppleRed.copy(alpha = 0.15f),
            secondary        = Color(0xFF1D1D1F),
            onSecondary      = OnSurface,
            background       = DeepBlack,
            surface          = SurfaceDark,
            onSurface        = OnSurface,
            onSurfaceVariant = SubtleGray,
            error            = Color(0xFFFF453A),
        ),
        content = content,
    )
}
