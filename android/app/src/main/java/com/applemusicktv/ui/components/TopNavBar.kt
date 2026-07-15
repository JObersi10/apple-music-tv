package com.applemusicktv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.applemusicktv.ui.navigation.TopNavTab

@Composable
private fun EqualizerDot() {
    val inf = rememberInfiniteTransition(label = "eq")
    val h1 by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "h1")
    val h2 by inf.animateFloat(1f, 0.3f, infiniteRepeatable(tween(300, easing = LinearEasing), RepeatMode.Reverse), label = "h2")
    val h3 by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "h3")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(12.dp)) {
        listOf(h1, h2, h3).forEach { frac ->
            Box(Modifier.width(2.dp).fillMaxHeight(frac).clip(RoundedCornerShape(1.dp)).background(Color(0xFFFA233B)))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopNavBar(selected: TopNavTab, onSelect: (TopNavTab) -> Unit, isPlaying: Boolean = false, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (selected == TopNavTab.NowPlaying && !isFocused) 0f else 1f,
        animationSpec = tween(500),
        label = "navBarAlpha"
    )

    Box(
        modifier = modifier
            .onFocusChanged { isFocused = it.hasFocus }
            .graphicsLayer { this.alpha = alpha }
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape  = RoundedCornerShape(50),
            colors = SurfaceDefaults.colors(containerColor = Color(0xFF1C1C1E)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TopNavTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    val bgColor by animateColorAsState(
                        if (isSelected) Color.White else Color.Transparent, tween(180))
                    val textColor by animateColorAsState(
                        if (isSelected) Color.Black else Color(0xFF9A9A9A), tween(180))

                    Surface(
                        onClick = { onSelect(tab) },
                        shape   = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors  = ClickableSurfaceDefaults.colors(
                            containerColor        = bgColor,
                            focusedContainerColor = if (isSelected) Color.White else Color(0xFF2C2C2E),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text       = tab.label,
                                fontSize   = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color      = textColor,
                            )
                            if (tab == TopNavTab.NowPlaying && isPlaying && !isSelected) {
                                EqualizerDot()
                            }
                        }
                    }
                }
            }
        }
    }
}
