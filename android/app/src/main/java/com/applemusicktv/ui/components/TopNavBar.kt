package com.applemusicktv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.applemusicktv.ui.navigation.TopNavTab

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopNavBar(selected: TopNavTab, onSelect: (TopNavTab) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().background(Color(0xFF0A0A0A)).padding(vertical = 10.dp),
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
                        Text(
                            text       = tab.label,
                            fontSize   = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color      = textColor,
                            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}
