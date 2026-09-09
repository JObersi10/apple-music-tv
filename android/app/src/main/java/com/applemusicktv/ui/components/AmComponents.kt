package com.applemusicktv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.ui.theme.AmTokens

/**
 * v2 UI-overhaul component kit — Phase 0. **Additive: no existing screen is wired to these yet**
 * (see ROADMAP.md → UI Overhaul). These encode Apple Music's shared grammar — one card system, the
 * shelf, the section header — off [AmTokens] so Phase 2 can repoint Home/Browse cheaply and
 * consistently. Deliberately model-agnostic (plain url/title/subtitle) so they don't couple to the
 * data layer, which stays untouched.
 */

/**
 * The one card used everywhere: an optional UPPERCASE micro-label ABOVE the art, the art at its OWN
 * aspect ratio (never letterboxed), then title + grey subtitle below. Focus = scale up + white title
 * + soft halo, exactly like the existing [AlbumCard] so old and new shelves match during transition.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AmCard(
    title: String,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    label: String? = null,
    width: Int = 168,
    aspectRatio: Float = 1f,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Column(modifier = modifier.width(width.dp)) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                color = AmTokens.Color.LabelUppercase,
                fontSize = AmTokens.Type.LabelSize,
                fontWeight = AmTokens.Type.LabelWeight,
                letterSpacing = AmTokens.Type.LabelTracking,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = AmTokens.Space.xs, start = 2.dp),
            )
        }
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            scale = CardDefaults.scale(focusedScale = AmTokens.Focus.Scale, pressedScale = 0.96f),
            glow = CardDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.22f), 18.dp)),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
            ),
            shape = CardDefaults.shape(RoundedCornerShape(AmTokens.Radius.Card)),
            // Match the focus border's corners to the art's radius — the default border shape didn't,
            // so the highlight looked more/less rounded than the card (the roundness mismatch).
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(AmTokens.Radius.Card),
                ),
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(AmTokens.Radius.Card)),
                contentAlignment = Alignment.Center,
            ) {
                if (artworkUrl != null) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(AmTokens.Color.Surface))
                }
            }
        }
        Text(
            text = title,
            color = AmTokens.Color.TextPrimary,
            fontSize = AmTokens.Type.TitleSize,
            fontWeight = AmTokens.Type.TitleWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = AmTokens.Space.sm, start = 2.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = AmTokens.Color.TextSecondary,
                fontSize = AmTokens.Type.SubtitleSize,
                fontWeight = AmTokens.Type.SubtitleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp, start = 2.dp),
            )
        }
    }
}

/** A shelf header: bold title with an optional "See All". */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = AmTokens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = AmTokens.Color.TextPrimary,
            fontSize = AmTokens.Type.HeaderSize,
            fontWeight = AmTokens.Type.HeaderWeight,
            modifier = Modifier.weight(1f),
        )
        if (onSeeAll != null) {
            Text(
                text = "See All",
                color = AmTokens.Color.Accent,
                fontSize = AmTokens.Type.SubtitleSize,
                fontWeight = AmTokens.Type.TitleWeight,
            )
        }
    }
}

/**
 * A horizontal shelf: [SectionHeader] over a [LazyRow] of items. Generic over the item type so a
 * caller passes its own list and a card composable — keeps this decoupled from the data layer.
 */
@Composable
fun <T> Shelf(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    key: ((T) -> Any)? = null,
    card: @Composable (T) -> Unit,
) {
    Column(modifier = modifier.padding(bottom = AmTokens.Space.shelfGap)) {
        SectionHeader(title, onSeeAll = onSeeAll)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AmTokens.Space.md)) {
            items(items = items, key = key) { item -> card(item) }
        }
    }
}

/**
 * Apple's song shelf: a horizontally-scrolling GRID of song rows, 3 rows per column (art + title +
 * subtitle). Generic so callers pass their own item type + accessors. Matches Apple Music's
 * "Best New Songs" / artist "Top Songs" layout.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun <T> SongGridRow(
    title: String,
    items: List<T>,
    title2: (T) -> String,
    subtitle: (T) -> String,
    artUrl: (T) -> String?,
    onClick: (Int) -> Unit,
    rows: Int = 3,
    columnWidth: Int = 360,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, modifier = Modifier.padding(start = 24.dp, end = 24.dp))
        val columns = items.chunked(rows)
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(AmTokens.Space.md),
        ) {
            items(items = columns) { col ->
                Column(Modifier.width(columnWidth.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    col.forEach { item ->
                        val idx = items.indexOf(item)
                        SongCell(title2(item), subtitle(item), artUrl(item)) { onClick(idx) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongCell(title: String, subtitle: String, artUrl: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(AmTokens.Radius.Tile)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = AmTokens.Color.SurfaceGlass),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(AmTokens.Color.Surface)) {
                if (artUrl != null) AsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = AmTokens.Color.TextPrimary, fontSize = AmTokens.Type.TitleSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = AmTokens.Color.TextSecondary, fontSize = AmTokens.Type.SubtitleSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Translucent glass surface — the material Apple layers over the ambient wash. Real blur is a no-op
 * below API 31 on Fire TV (a hard-won perf lesson), so this is a tinted scrim, not `Modifier.blur`.
 */
@Composable
fun GlassSurface(modifier: Modifier = Modifier, high: Boolean = false, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AmTokens.Radius.Card))
            .background(if (high) AmTokens.Color.SurfaceGlassHi else AmTokens.Color.SurfaceGlass),
    ) { content() }
}
