package com.applemusicktv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onLongClick: (Int) -> Unit = {},
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
                        SongCell(title2(item), subtitle(item), artUrl(item), onClick = { onClick(idx) }, onLongClick = { onLongClick(idx) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongCell(title: String, subtitle: String, artUrl: String?, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
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

/** One row in a card context menu. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AmMenuItem(label: String, modifier: Modifier = Modifier, destructive: Boolean = false, glyph: Glyph? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2C2C2E)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (glyph != null) Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(glyph, size = 17.dp, color = if (destructive) Color(0xFFFA233B) else Color(0xFFB0B0B4))
            }
            Text(label, fontSize = 15.sp, color = if (destructive) Color(0xFFFA233B) else Color.White)
        }
    }
}

/** One menu row for [AmContextMenu]. `glyph` shows a leading icon; `destructive` tints it red. */
data class AmMenuAction(val label: String, val glyph: Glyph? = null, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * THE canonical long-press context menu — the ONE menu for the whole app. Artwork header + subtitle,
 * a hairline divider, then icon+label rows. Real Dialog so D-pad focus is trapped. The first row
 * focuses immediately (snappy — no visible delay); a brief `blocked` window only swallows the
 * OK key-up that opened the menu so it doesn't instantly fire row 1.
 * Every long-press menu (Library, Album, Playlist, Artist, Category, Browse, MV) routes through
 * this — do NOT hand-roll another menu Column. See DESIGN_LANGUAGE.md.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AmContextMenu(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    actions: List<AmMenuAction>,
    onDismiss: () -> Unit,
    isVideoArt: Boolean = false,
) {
    val firstFocus = remember { FocusRequester() }
    // Long-press opens the menu while OK is still held. We must eat exactly that opening OK-release
    // no matter HOW LONG it's held (a timer can't — a slow release lands after the timer and fires
    // row 1). So: focus row 1 instantly, then gate on key events. `armed` stays false until we see the
    // opening OK key-UP (which has no matching key-DOWN inside this dialog, since the DOWN happened on
    // the card before the dialog existed) — we consume that UP. A deliberate later press has both a
    // DOWN and UP here, so it passes through. Any directional/Back press also arms (user has moved on).
    var armed by remember(title) { mutableStateOf(false) }
    LaunchedEffect(title) { runCatching { firstFocus.requestFocus() } }
    fun isOk(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
    // Resolve Apple's {w}x{h}bb.{f} template so the header art shows on every path (library rows
    // carry the raw template; catalog cards arrive pre-resolved — the replace is a no-op there).
    val art = artworkUrl?.replace("{w}", "200")?.replace("{h}", "200")?.replace("{f}", "jpg")
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(320.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E)).padding(8.dp)
                .onPreviewKeyEvent { e ->
                    // A held OK auto-repeats KeyDowns into the dialog once it's focused, so we can't
                    // trust "first down". Instead: consume EVERYTHING until the OK is physically
                    // released once (first KeyUp) — that release is the long-press ending. Consume
                    // that UP too and arm. Nothing can fire row 1 before a real, fresh press.
                    when {
                        armed -> false
                        e.type == KeyEventType.KeyUp -> { armed = true; true }
                        else -> true   // swallow all downs/repeats while the opening OK is still held
                    }
                },
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val artSize = if (isVideoArt) DpSize(78.dp, 44.dp) else DpSize(44.dp, 44.dp)
                    Box(Modifier.size(artSize).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A))) {
                        if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column {
                        Text(title, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))
                Spacer(Modifier.height(2.dp))
                actions.forEachIndexed { i, a ->
                    val m = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    AmMenuItem(a.label, m, destructive = a.destructive, glyph = a.glyph) { if (armed) a.onClick() }
                }
            }
        }
    }
}

/**
 * Shared long-press context menu for any card (album / playlist / song / music-video / artist).
 * Options adapt to [item].type. Delegates to [AmContextMenu] so it matches every other menu.
 */
@Composable
fun CardContextMenu(
    item: com.applemusicktv.data.model.Album,
    playerVm: com.applemusicktv.ui.viewmodel.PlayerViewModel,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit,
    onDismiss: () -> Unit,
    showGoToArtist: Boolean = true,
) {
    val isSong = item.type == "songs"
    val isVideo = item.type.contains("music-video")
    val song = com.applemusicktv.data.model.Song(
        id = item.id, title = item.title, artistName = item.artistName, albumName = "",
        durationMs = 0, artworkUrl = item.artworkUrl, artworkBgColor = item.artworkBgColor,
        previewUrl = null, artistId = item.artistId, type = item.type,
    )
    var addTo by remember { mutableStateOf(false) }
    // New/Browse cards arrive without an artist (or album) id → resolve it so Go to Artist/Album show.
    var artistId by remember(item.id) { mutableStateOf(item.artistId) }
    var albumId by remember(item.id) { mutableStateOf<String?>(null) }
    if (isSong || isVideo) LaunchedEffect(item.id) {
        if (artistId.isNullOrBlank() || albumId.isNullOrBlank()) {
            val (aId, alId) = playerVm.lookupSongIds(item.id)
            if (!aId.isNullOrBlank())  artistId = aId
            if (!alId.isNullOrBlank()) albumId = alId
        }
    }
    if (addTo) { AddToDialog(playerVm, song, onDismiss = { addTo = false; onDismiss() }); return }
    val actions = buildList {
        if (isSong || isVideo) {
            add(AmMenuAction("Play Next", Glyph.PLAY_NEXT) { playerVm.playNext(song); onDismiss() })
            add(AmMenuAction("Add to Queue", Glyph.QUEUE_ADD) { playerVm.addToQueue(song); onDismiss() })
            if (isSong) add(AmMenuAction("Add to…", Glyph.ADD_TO) { addTo = true })
        }
        if (showGoToArtist) artistId?.takeIf { it.isNotBlank() }?.let { aid ->
            add(AmMenuAction("Go to Artist", Glyph.ARTIST) { onArtist(aid); onDismiss() })
        }
        if (isSong) add(AmMenuAction("Go to Album", Glyph.ALBUM) { onAlbum(albumId ?: item.id); onDismiss() })
        if (!isSong && !isVideo) add(AmMenuAction("Open", Glyph.ALBUM) { onAlbum(item.id); onDismiss() })
    }
    AmContextMenu(
        title = item.title,
        subtitle = item.artistName.takeIf { it.isNotBlank() },
        artworkUrl = item.artworkUrl,
        actions = actions,
        onDismiss = onDismiss,
        isVideoArt = isVideo,
    )
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
