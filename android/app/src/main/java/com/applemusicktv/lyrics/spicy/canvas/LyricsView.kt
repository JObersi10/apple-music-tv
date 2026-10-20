package com.applemusicktv.lyrics.spicy.canvas

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.applemusicktv.lyrics.spicy.RenderConfig
import com.applemusicktv.lyrics.spicy.ScrollConfig
import com.applemusicktv.lyrics.spicy.animation.LineAnimState
import com.applemusicktv.lyrics.spicy.animation.LyricsAnimator
import com.applemusicktv.lyrics.spicy.models.Line
import com.applemusicktv.lyrics.spicy.models.LyricsType
import com.applemusicktv.lyrics.spicy.models.FooterLine
import com.applemusicktv.lyrics.spicy.models.LyricsFooter
import com.applemusicktv.lyrics.spicy.parser.LetterSynthesizer
import com.applemusicktv.lyrics.spicy.support.keepsControlsHidden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The main lyrics display component representing the split architecture.
 * Delegates text measurement to LyricsLayoutCalculator,
 * scroll physics to ScrollManager,
 * and drawing to LyricsRenderer extensions.
 *
 * @param lines The list of lyric lines to display.
 * @param currentTimeMs The current playback time in milliseconds.
 * @param onSeekWord Callback triggered when a user taps a line to seek to its start time.
 */
@Composable
fun LyricsView(
    lines: List<Line>,
    documentId: String,
    footer: LyricsFooter = LyricsFooter(),
    currentTimeMs: () -> Long,
    onSeekWord: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeScale: Float = 1.0f,
    config: RenderConfig = RenderConfig.FULL,
    lyricsType: LyricsType = LyricsType.Syllable,
    romanize: Boolean = false,
    /** Extra opacity multiplier for translation gloss lines (fades them in when they arrive). */
    translationAlpha: Float = 1f,
    /** For UNSYNCED (Static) lyrics only: 0..1 of the song elapsed, used to glide the whole lyric from
     *  top (0:00) to bottom (end) in a "synced" way. Return <0 to disable (plain user-scrolled). */
    staticAutoScroll: () -> Float = { -1f },
    focusAnchorFraction: Float = 0.25f,
    // When set, the active line's top (not its centre) is kept this far below the view's top,
    // ("Top" scrolling); focusAnchorFraction is then unused.
    activeLineTopPx: Float? = null,
    // Without activeLineTopPx, the active line's centre sits this far above the focus anchor.
    focusLiftPx: Float = 0f,
    // Invoked with the raw frame-nanos at the top of this view's own animation frame, before
    // currentTimeMs() is read. Lets a caller (e.g. the playback clock smoothing in
    // SpicyLyricsPlayer) piggyback on this view's single withFrameNanos loop instead of running
    // a second, independent one — halving the Choreographer callbacks registered per lyrics
    // screen. Optional so other/future callers aren't forced to supply one.
    onFrameTick: ((Long) -> Unit)? = null,
    // Paused, the frame loop rests once nothing moves, looking in a few times a second for a seek.
    isPlaying: Boolean = true,
    /** Shared with the screen: the scroll-to-active button and the pinned credits. */
    viewState: LyricsViewState? = null,
    /** Credits drawn pinned by the screen instead of after the lyrics. */
    pinnedFooter: PinnedFooterMode = PinnedFooterMode.Off,
    /** How far up from the bottom the lyrics are covered (pinned credits); they fade out above it. */
    maskBottomPx: () -> Float = { 0f },
    /** When set, the view is focusable and D-pad up/down selects a line, OK seeks to it. */
    dpadFocus: FocusRequester? = null,
    /** Where focus goes when the user leaves the lyrics (up past the top line, left/right, or idle). */
    escapeFocus: FocusRequester? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    // What is on screen: lines and their layouts, swapped together once new layouts are measured.
    // Until then the previous lyrics stay up, so a source switch mid-song (a better answer
    // arriving) replaces them in one frame instead of blanking the view while it measures.
    var shown by remember { mutableStateOf<ShownLyrics?>(null) }
    val shownId = shown?.documentId
    val lineLayouts = shown?.layouts.orEmpty()
    // The type and credits of what is shown, not of what is still being measured.
    val incomingType = lyricsType
    val incomingFooter = footer
    val lyricsType = shown?.lyricsType ?: incomingType
    val footer = shown?.footer ?: incomingFooter
    val coroutineScope = rememberCoroutineScope()

    // Letter synthesis only reads the mode-dependent thresholds, not the motion boost or scrolling.
    val letterConfig = config.copy(wordMotionBoost = 1f, scroll = ScrollConfig())
    // Measured lyrics for these lines, per variant (romanized or not, width, size...). The other
    // romanization variant is measured ahead, so the romanize button swaps in a finished layout.
    val measuredCache = remember(lines) { HashMap<MeasureKey, MeasuredLyrics>() }

    val animator = remember(shownId) { LyricsAnimator(coroutineScope, config) }
    LaunchedEffect(config) { animator.config = config }
    val isStatic = lyricsType == LyricsType.Static

    // Keep the latest time provider without recomposing on every position tick: the frame loop
    // invokes it off-composition (inside withFrameNanos), so the changing clock never re-runs this
    // composable's body — only the Canvas redraws when the derived anim state actually changes.
    val currentTimeProvider by rememberUpdatedState(currentTimeMs)
    val linesUpdated by rememberUpdatedState(shown?.lines.orEmpty())
    val lineLayoutsUpdated by rememberUpdatedState(lineLayouts)
    val onFrameTickUpdated by rememberUpdatedState(onFrameTick)
    val isPlayingUpdated by rememberUpdatedState(isPlaying)
    val scrollConfigUpdated by rememberUpdatedState(config.scroll)

    val scrollPolicy = remember(shownId) { ScrollPolicyController() }
    // Read by the drag handler, written by the frame loop.
    val contentHeightForDrag = remember(shownId) { FloatArray(1) }
    val density = LocalDensity.current
    val scrollManager = remember(shownId) { ScrollManager().also { it.reset() } }
    scrollManager.smoothScrolling = config.scroll.smooth
    // Wakes a resting frame loop at once (a drag or tap), rather than at its next look.
    val wake = remember(shownId) { Channel<Unit>(Channel.CONFLATED) }
    val context = LocalContext.current
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // ── D-pad line selection (TV). null = follow playback; a value = the user is browsing lines. ──
    var selectedIndex by remember(shownId) { mutableStateOf<Int?>(null) }
    val selectedIndexUpdated by rememberUpdatedState(selectedIndex)
    var dpadFocused by remember(shownId) { mutableStateOf(false) }
    // Selectable = real lead lyric lines only (skip background vocals, interludes, songwriter rows).
    val selectable = remember(shown) {
        shown?.lines?.let { ls ->
            ls.indices.filter { i ->
                val l = ls[i]
                !l.isBackground && !l.isInterlude && !l.isSongwriter && l.words.isNotEmpty()
            }
        } ?: emptyList()
    }
    val selectableUpdated by rememberUpdatedState(selectable)
    val onSeekWordUpdated by rememberUpdatedState(onSeekWord)
    val seekFadeComp = config.scroll.seekFadeCompensation

    fun moveSelection(delta: Int) {
        val list = selectableUpdated
        if (list.isEmpty()) return
        val cur = selectedIndex
        val baseIdx = if (cur == null) {
            val now = currentTimeProvider()
            list.lastOrNull { (linesUpdated.getOrNull(it)?.startMs ?: Long.MAX_VALUE) <= now } ?: list.first()
        } else cur
        val pos = list.indexOf(baseIdx).coerceAtLeast(0)
        // First press just highlights the current line; later presses step from there.
        val newPos = if (cur == null) pos else (pos + delta).coerceIn(0, list.lastIndex)
        selectedIndex = list[newPos]
    }

    fun seekToSelected() {
        val idx = selectedIndex ?: return
        val line = linesUpdated.getOrNull(idx) ?: return
        val start = line.words.firstOrNull()?.startMs ?: line.startMs
        val target = if (seekFadeComp) (start - ScrollConfig.SEEK_FADE_COMPENSATION_MS).coerceAtLeast(0L) else start
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        onSeekWordUpdated(target)
        scrollManager.onSeek()
        selectedIndex = null
        wake.trySend(Unit)
    }

    val lyricsKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ ev ->
        if (isStatic || selectableUpdated.isEmpty()) return@handler false
        if (ev.type != KeyEventType.KeyDown) return@handler false
        when (ev.key) {
            Key.DirectionDown -> { moveSelection(+1); wake.trySend(Unit); true }
            Key.DirectionUp -> {
                val cur = selectedIndex
                if (cur != null && selectableUpdated.firstOrNull() == cur) {
                    // Up past the first line hands focus back out (to the transport), like v1.
                    selectedIndex = null
                    escapeFocus?.let { runCatching { it.requestFocus() } }
                    true
                } else { moveSelection(-1); wake.trySend(Unit); true }
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (selectedIndex != null) { seekToSelected(); true } else false
            }
            // LEFT returns to the nearest control on the left (the skip button / ⋯ — whatever's
            // adjacent), via the focus system's own spatial search, so you land on the last item you
            // came from rather than always the play button. Falls back to escapeFocus if the search
            // finds nothing. RIGHT stays put (nothing to its right).
            Key.DirectionLeft -> {
                selectedIndex = null
                if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)) {
                    escapeFocus?.let { runCatching { it.requestFocus() } }
                }
                true
            }
            Key.DirectionRight -> true
            Key.Back, Key.Escape -> {
                if (selectedIndex != null) { selectedIndex = null; true } else false
            }
            else -> false
        }
    }

    // Don't strand the D-pad in the lyrics: after a spell focused with no key, hand focus back.
    if (dpadFocus != null && escapeFocus != null) {
        LaunchedEffect(dpadFocused, selectedIndex) {
            if (!dpadFocused) return@LaunchedEffect
            kotlinx.coroutines.delay(7000L)
            if (dpadFocused) { selectedIndex = null; runCatching { escapeFocus.requestFocus() } }
        }
    }

    // Pre-select the current line so entering the lyrics (RIGHT) immediately highlights a line and OK
    // scrubs — no need to press DOWN first just to start a selection.
    fun selectCurrentLine() {
        val list = selectableUpdated
        if (list.isEmpty()) return
        val now = currentTimeProvider()
        selectedIndex = list.lastOrNull { (linesUpdated.getOrNull(it)?.startMs ?: Long.MAX_VALUE) <= now } ?: list.first()
    }

    val focusModifier = if (dpadFocus != null) Modifier
        .focusRequester(dpadFocus)
        .onFocusChanged {
            dpadFocused = it.hasFocus
            if (!it.hasFocus) selectedIndex = null
            else if (selectedIndex == null) selectCurrentLine()
        }
        .onKeyEvent(lyricsKeyHandler)
        .focusable()
    else Modifier

    BoxWithConstraints(modifier = modifier.then(focusModifier).fillMaxSize().clipToBounds()) {
        val canvasWidth = constraints.maxWidth.toFloat()
        val canvasHeight = constraints.maxHeight.toFloat()
        // Compact fullscreen keeps the active lyric in the upper portion of the viewport.
        val centerY = activeLineTopPx ?: (ScrollPolicyController.anchorY(canvasHeight, focusAnchorFraction) - focusLiftPx)
        val alignTop = activeLineTopPx != null
        // The frame loop and tap handler outlive a change of anchor (the header shown or hidden
        // mid-song), so they read the latest one; a change also wakes a resting loop to re-anchor.
        val centerYUpdated by rememberUpdatedState(centerY)
        val alignTopUpdated by rememberUpdatedState(alignTop)
        val canvasHeightUpdated by rememberUpdatedState(canvasHeight)
        LaunchedEffect(centerY, alignTop) { wake.trySend(Unit) }
        val footerMetrics = remember(canvasWidth, density.density, fontSizeScale, lyricsType) {
            LyricsLayoutMetrics(canvasWidth, density.density, lyricsType, fontSizeScale)
        }
        val footerSlot = footerMetrics.contentSlot(false, false, false)
        val lineGapUpdated by rememberUpdatedState(footerMetrics.lineGapPx)
        val rowHeightUpdated by rememberUpdatedState(footerMetrics.lineHeightPx(footerMetrics.baseFontSizeSp))
        // Credits are sized from the synced lyric size whatever the lyrics type, so static lyrics
        // (drawn smaller) get the same credits.
        val creditBaseSp = remember(canvasWidth, density.density, fontSizeScale) {
            creditBaseSp(canvasWidth, density.density, fontSizeScale)
        }
        scrollManager.pxPerReferencePx = creditBaseSp * density.density / REFERENCE_LYRIC_SIZE_PX
        // The rows the screen pins are drawn there instead.
        val footerLines = remember(footer, pinnedFooter) { footer.lines().filterNot { pinnedFooter.pins(it.kind) } }
        val fontKey = LyricsLayoutCalculator.fontKey
        val footerLayouts = remember(footerLines, creditBaseSp, footerSlot.widthPx, canvasWidth, fontKey) {
            measureFooterRows(footerLines, textMeasurer, creditBaseSp, density.density, footerSlot.widthPx)
        }
        val footerHeight = footerLayouts.sumOf { (it.marginTop + it.height).toDouble() }.toFloat()
        val footerHeightUpdated by rememberUpdatedState(footerHeight)
        val avatars = rememberFooterAvatars(footerLines)
        if (viewState != null) {
            SideEffect { viewState.shownFooter = footer }
            DisposableEffect(viewState) {
                onDispose {
                    viewState.shownFooter = null
                    viewState.activeLineDirection = null
                }
            }
            LaunchedEffect(viewState.scrollToActiveRequests) {
                if (viewState.scrollToActiveRequests == 0) return@LaunchedEffect
                scrollManager.returnToActive()
                wake.trySend(Unit)
            }
        }
        val viewStateUpdated by rememberUpdatedState(viewState)
        val maskBottomUpdated by rememberUpdatedState(maskBottomPx)
        // Recalculate layouts whenever the lyrics, dimensions, or font size change.
        // A newer key cancels a measurement still running, so only the latest one lands.
        LaunchedEffect(lines, letterConfig, canvasWidth, fontSizeScale, romanize, documentId, incomingType, incomingFooter, fontKey) {
            suspend fun measure(romanized: Boolean): MeasuredLyrics {
                val key = MeasureKey(letterConfig, canvasWidth, fontSizeScale, romanized, incomingType, fontKey)
                measuredCache[key]?.let { return it }
                return withContext(Dispatchers.Default) {
                    // Per-letter emphasis for held words (mode-dependent thresholds, romanized
                    // display). Syllable mode only; Line/Static never letter-split.
                    val display = if (incomingType == LyricsType.Syllable) LetterSynthesizer.apply(lines, letterConfig, romanized) else lines
                    MeasuredLyrics(display, LyricsLayoutCalculator.calculateLineLayouts(
                        display, canvasWidth, textMeasurer, density.density, incomingType, fontSizeScale, romanized, letterConfig.isSimple,
                        letterConfig.wideDuetPadding,
                    ))
                }.also { measuredCache[key] = it }
            }
            val measured = measure(romanize)
            shown = ShownLyrics(documentId, measured.lines, measured.layouts, incomingType, incomingFooter)
            if (lines.any { line -> line.words.any { it.romanizedText != null } }) measure(!romanize)
        }

        if (lineLayouts.isEmpty()) return@BoxWithConstraints

        var animStates by remember { mutableStateOf<List<LineAnimState>>(emptyList()) }
        var dynamicYOffsets by remember { mutableStateOf(FloatArray(0)) }
        var lastFrameTimeNanos by remember { mutableLongStateOf(0L) }
        
        // The high-frequency animation loop, restarted with each new document's animator and scroll.
        LaunchedEffect(shownId) {
            // Reused per-frame scratch for the dynamic Y offsets: filled every frame but only
            // published to state when its contents actually change, so a paused/idle screen stops
            // invalidating the Canvas (a fresh FloatArray each frame was forcing a redraw via array
            // identity-equality even when nothing moved).
            var dynamicYScratch = FloatArray(0)
            var settledYScratch = FloatArray(0)
            var stillFrames = 0
            var lastLayouts: List<LineLayout>? = null
            while (true) {
                if (stillFrames >= REST_AFTER_STILL_FRAMES) {
                    withTimeoutOrNull(REST_POLL_MS) { wake.receive() }
                }
                withFrameNanos { frameTimeNanos ->
                    val previousStates = animStates
                    val previousOffsets = dynamicYOffsets
                    val previousScroll = scrollManager.animScrollY
                    onFrameTickUpdated?.invoke(frameTimeNanos)

                    val currentLayouts = lineLayoutsUpdated
                    val currentLines = linesUpdated
                    // The same lyrics laid out again (romanized, resized): seen here, on the frame
                    // that first draws the new layouts, so the scroll jumps with them.
                    if (lastLayouts != null && currentLayouts !== lastLayouts) scrollManager.onRelayout()
                    lastLayouts = currentLayouts
                    val currentTime = currentTimeProvider()

                    // Unclamped: springs integrate analytically over any dt.
                    val deltaTime = if (lastFrameTimeNanos == 0L) {
                        0.016f
                    } else {
                        ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceAtLeast(0f)
                    }
                    lastFrameTimeNanos = frameTimeNanos

                    if (currentLayouts.size == currentLines.size && currentLines.isNotEmpty()) {
                        // D-pad selection in progress: the view follows the selected line, not the song.
                        val sel = selectedIndexUpdated?.takeIf { it in currentLayouts.indices }
                        // 1. Step the animator for visual properties (scale, opacity, glow).
                        // While browsing by D-pad, suppress distance blur so the selected line is crisp.
                        animStates = animator.animate(currentLines, currentTime, deltaTime, scrollManager.hideLineBlur || sel != null, lyricsType)

                        // 1.5 Calculate dynamic Y offsets based on interlude scales.
                        var accumulatedY = 0f
                        if (dynamicYScratch.size != currentLayouts.size) {
                            dynamicYScratch = FloatArray(currentLayouts.size)
                        }
                        val newDynamicYOffsets = dynamicYScratch

                        // Where each line settles once running interlude animations finish; the
                        // scroll aims here so it doesn't chase an opening or closing gap.
                        var settledY = 0f
                        if (settledYScratch.size != currentLayouts.size) {
                            settledYScratch = FloatArray(currentLayouts.size)
                        }
                        // Early Scroll picks its line ahead of the song, so it also has to judge
                        // which interludes are open at that time, or the target moves again when
                        // the dots really open or close.
                        val scrollTime = currentTime + scrollConfigUpdated.leadMs
                        // Where an open interlude's dots sit below its start: the middle of the gap
                        // before it plus its row, i.e. halfway between the lines around it.
                        val interludeCentre = (rowHeightUpdated - lineGapUpdated) / 2f
                        for (i in currentLayouts.indices) {
                            val layout = currentLayouts[i]
                            val state = animStates.getOrNull(i)

                            if (layout.isInterlude) {
                                val line = layout.line
                                val open = if (scrollTime >= line.startMs &&
                                    scrollTime <= line.endMs - LyricsAnimator.PRE_HIDDEN_DOT_LINE_MS) 1f else 0f
                                settledYScratch[i] = layout.yOffset + settledY + interludeCentre * open
                                settledY += rowHeightUpdated * open
                            } else {
                                settledYScratch[i] = layout.yOffset + settledY
                            }

                            if (layout.isInterlude) {
                                // An open interlude is one lyric row; the gap before it is already
                                // there, so it adds none after. The dots sit centred between the two
                                // lines, as far from each as two lines are from each other.
                                val scale = state?.scale?.coerceIn(0f, 1f) ?: 0f
                                newDynamicYOffsets[i] = layout.yOffset + accumulatedY + interludeCentre * scale
                                accumulatedY += rowHeightUpdated * scale
                            } else {
                                newDynamicYOffsets[i] = layout.yOffset + accumulatedY
                            }
                        }
                        // Publish only when the offsets actually changed (i.e. an interlude is
                        // expanding/collapsing); otherwise the Canvas keeps the last array and
                        // isn't invalidated. copyOf() so the published snapshot is immutable while
                        // the scratch keeps mutating next frame.
                        if (!newDynamicYOffsets.contentEquals(dynamicYOffsets)) {
                            dynamicYOffsets = newDynamicYOffsets.copyOf()
                        }

                        // 2. Resolve the lead/background overlap policy, then anchor
                        // that one line: its centre at the focus point, or its top (compact mode).
                        val decision = scrollPolicy.decide(currentLines, currentTime, leadMs = scrollConfigUpdated.leadMs)
                        // A lead with no words of its own (the line is only background vocals)
                        // has no height; its background vocals stand in for it.
                        val targetIndex = decision.targetIndex?.let { index ->
                            val next = currentLayouts.getOrNull(index + 1)
                            if (currentLayouts[index].line.words.isEmpty() && next != null && next.isBackground &&
                                next.line.groupId == currentLayouts[index].line.groupId) index + 1 else index
                        }
                        var targetY: Float? = targetIndex?.let { index ->
                            // An interlude's offset is already the centre of its dots, in a row
                            // one lyric line tall.
                            val half = when {
                                alignTopUpdated -> if (currentLayouts[index].isInterlude) -interludeCentre else 0f
                                currentLayouts[index].isInterlude -> 0f
                                else -> currentLayouts[index].height / 2f
                            }
                            -(settledYScratch[index] + half)
                        }
                        val targetVisiblePx = targetIndex?.let { index ->
                            val top = centerYUpdated + scrollManager.animScrollY + newDynamicYOffsets[index]
                            val bottom = top + currentLayouts[index].height
                            (minOf(bottom, canvasHeightUpdated) - maxOf(top, 0f)).coerceAtLeast(0f)
                        } ?: Float.POSITIVE_INFINITY

                        // Scrolled away by hand with the line being sung out of sight: which way it lies.
                        viewStateUpdated?.let { shared ->
                            val directionIndex = if (isStatic || !scrollManager.hideLineBlur) null
                                else targetIndex ?: currentLayouts.indices.firstOrNull { i ->
                                    currentLayouts[i].line.startMs > currentTime && !currentLayouts[i].isBackground
                                } ?: currentLayouts.lastIndex
                            val direction = directionIndex?.let { index ->
                                val top = centerYUpdated + scrollManager.animScrollY + newDynamicYOffsets[index]
                                val viewBottom = canvasHeightUpdated - maskBottomUpdated()
                                when {
                                    top + currentLayouts[index].height <= 0f -> ActiveLineDirection.Above
                                    top >= viewBottom -> ActiveLineDirection.Below
                                    else -> null
                                }
                            }
                            if (shared.activeLineDirection != direction) shared.activeLineDirection = direction
                        }

                        // 3. Step the scroll spring and handle user overrides.
                        val lastLayout = currentLayouts.lastOrNull()
                        // Credits scroll into reach too, most visibly when static lyrics are scrolled by hand.
                        val totalContentHeight = (lastLayout?.yOffset ?: 0f) + (lastLayout?.height ?: 0f) + accumulatedY +
                            footerHeightUpdated

                        // Static lyrics have no timing to follow: leave scrolling entirely to the user.
                        if (isStatic) targetY = null
                        contentHeightForDrag[0] = totalContentHeight
                        scrollManager.dpadSelecting = sel != null

                        // D-pad override: glide to the selected line (not the song's active line).
                        val navIndex: Int?
                        val navTargetY: Float?
                        val navSnap: Boolean
                        if (sel != null) {
                            val adj = run {
                                val next = currentLayouts.getOrNull(sel + 1)
                                if (currentLayouts[sel].line.words.isEmpty() && next != null && next.isBackground &&
                                    next.line.groupId == currentLayouts[sel].line.groupId) sel + 1 else sel
                            }
                            val half = when {
                                alignTopUpdated -> if (currentLayouts[adj].isInterlude) -interludeCentre else 0f
                                currentLayouts[adj].isInterlude -> 0f
                                else -> currentLayouts[adj].height / 2f
                            }
                            navIndex = adj
                            navTargetY = -(settledYScratch[adj] + half)
                            navSnap = false  // glide (fast fixed glide via scrollManager.dpadSelecting) — smooth + keeps up
                        } else if (isStatic && staticAutoScroll() >= 0f && !scrollManager.isUserScrolling) {
                            // Unsynced lyrics: glide the whole block from top (0:00) to bottom (end) in
                            // step with the song, since there's no per-line timing to anchor to.
                            val maxScroll = (totalContentHeight - canvasHeight).coerceAtLeast(0f)
                            navIndex = 0
                            navTargetY = -(staticAutoScroll().coerceIn(0f, 1f) * maxScroll)
                            navSnap = false
                        } else {
                            navIndex = targetIndex
                            navTargetY = targetY
                            navSnap = decision.motion == ScrollMotion.SNAP
                        }
                        scrollManager.updateScroll(
                            deltaTime, totalContentHeight, navIndex.takeIf { navTargetY != null }, navTargetY,
                            snap = navSnap,
                            targetVisiblePx = targetVisiblePx,
                        )
                    }
                    val still = !isPlayingUpdated && !scrollManager.isUserScrolling &&
                        animStates == previousStates && dynamicYOffsets === previousOffsets &&
                        scrollManager.animScrollY == previousScroll
                    stillFrames = if (still) stillFrames + 1 else 0
                }
            }
        }

        // The sole renderer mask: transparent through 16dp, ramping to opaque at 64dp, with a
        // symmetric bottom edge. Pinned credits lift the bottom edge above them: the lyrics fade
        // out over the 48dp just above.
        val maskStops = lyricsMaskStops(canvasHeight, density.density)
        val fadeBrush = remember(maskStops) { maskBrush(maskStops) }
        val pinnedFadePx = PINNED_FADE_DP * density.density

        // Where each credit row sits below the last line, in content space (before scrolling).
        // Shared by drawing and tapping so a tap always lands on what was drawn.
        fun footerRowTops(): List<Float> = footerRowTops(
            footerLayouts,
            lineLayouts.lastOrNull()?.let { layout ->
                dynamicYOffsets.getOrElse(lineLayouts.lastIndex) { layout.yOffset } + layout.height
            } ?: 0f,
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val covered = maskBottomUpdated()
                    val brush = if (covered <= 0f) fadeBrush else maskBrush(
                        maskStops.copy(
                            innerBottom = ((size.height - covered - pinnedFadePx) / size.height).coerceIn(maskStops.innerTop, 1f),
                            outerBottom = ((size.height - covered) / size.height).coerceIn(maskStops.innerTop, 1f),
                        ),
                    )
                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                }
                .pointerInput(scrollManager) {
                    // Interaction: Dragging.
                    detectDragGestures(
                        onDragStart = { scrollManager.onDragStart(); wake.trySend(Unit) },
                        onDragEnd = { scrollManager.onDragEnd() },
                        onDragCancel = { scrollManager.onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scrollManager.onDrag(dragAmount.y, contentHeightForDrag[0])
                        }
                    )
                }
                // Tapping a credit leaves the controls as they are.
                .keepsControlsHidden(tapsOnly = true) { position ->
                    footerProfileAt(footerLayouts, footerRowTops(), position.y - (centerYUpdated + scrollManager.animScrollY)) != null
                }
                .pointerInput(isStatic, footerLayouts, shown) {
                    detectTapGestures { tapOffset ->
                        val currentScrollY = scrollManager.animScrollY
                        val adjustedTapY = tapOffset.y - (centerYUpdated + currentScrollY)

                        // A credit with a profile asks the screen to open it.
                        footerProfileAt(footerLayouts, footerRowTops(), adjustedTapY)?.let { line ->
                            viewState?.requestProfile(line)
                            return@detectTapGestures
                        }

                        // Tapping a line seeks to it. Static lyrics are not seekable.
                        if (isStatic) return@detectTapGestures

                        for (i in lineLayouts.indices) {
                            val layout = lineLayouts[i]
                            val layoutDynamicY = dynamicYOffsets.getOrElse(i) { layout.yOffset }
                            if (adjustedTapY >= layoutDynamicY && adjustedTapY <= layoutDynamicY + layout.height) {
                                if (layout.isInterlude || layout.isSongwriter) continue
                                if (layout.line.words.isNotEmpty()) {
                                    // From the first sung word, which can come after the line's own start.
                                    val start = layout.line.words.first().startMs
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onSeekWord(
                                        if (scrollConfigUpdated.seekFadeCompensation) {
                                            (start - ScrollConfig.SEEK_FADE_COMPENSATION_MS).coerceAtLeast(0L)
                                        } else start
                                    )
                                    scrollManager.onSeek()
                                    wake.trySend(Unit)
                                }
                                return@detectTapGestures
                            }
                        }
                    }
                }
        ) {
            val scrollOffset = centerY + scrollManager.animScrollY

            // D-pad selection highlight: a soft pill behind the line the user is pointing at.
            selectedIndex?.let { selIdx ->
                lineLayouts.getOrNull(selIdx)?.let { layout ->
                    val dynamicY = dynamicYOffsets.getOrElse(selIdx) { layout.yOffset }
                    val top = scrollOffset + dynamicY
                    val x = getLineStartX(layout)
                    val pad = 14f * density.density
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.16f),
                        topLeft = Offset(x - pad, top - pad / 2f),
                        size = Size(layout.totalWidth + pad * 2f, layout.height + pad),
                        cornerRadius = CornerRadius(pad, pad),
                    )
                }
            }

            lineLayouts.forEachIndexed { lineIdx, layout ->
                val lineAnim = animStates.getOrNull(lineIdx) ?: return@forEachIndexed
                val dynamicY = dynamicYOffsets.getOrElse(lineIdx) { layout.yOffset }

                // Optimization: Don't draw invisible lines.
                if (lineAnim.opacity <= 0.01f) return@forEachIndexed

                // Optimization: Don't draw lines off-screen.
                val lineScreenY = scrollOffset + dynamicY
                if (lineScreenY < -layout.height * 3 || lineScreenY > canvasHeight + layout.height * 3) {
                    return@forEachIndexed
                }

                val lineStartX = getLineStartX(layout)

                when {
                    layout.isInterlude -> drawInterludeGroup(layout, lineAnim, lineStartX, scrollOffset, dynamicY)
                    // A translation gloss renders flat (no karaoke wipe, no per-word "play") regardless of
                    // the document type — otherwise its words all light up at once with the lead's timing.
                    layout.line.isTranslation -> drawStaticLine(layout, lineAnim.copy(opacity = lineAnim.opacity * translationAlpha), lineStartX, scrollOffset, dynamicY)
                    lyricsType == LyricsType.Static -> drawStaticLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY)
                    lyricsType == LyricsType.Line -> drawLineModeLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config)
                    // Minimal Lyrics Mode shrinks inactive lines (a CSS `scale`: paint only, no reflow)
                    // about their start edge, like line-synced lines.
                    lineAnim.scale != 1f -> withTransform({
                        scale(lineAnim.scale, lineAnim.scale, Offset(
                            if (layout.isRightAligned) lineStartX + layout.totalWidth else lineStartX,
                            dynamicY + scrollOffset + layout.height / 2f,
                        ))
                    }) { drawStandardLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config) }
                    else -> drawStandardLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config)
                }
            }

            drawFooterRows(footerLayouts, footerRowTops().map { it + scrollOffset }, footerSlot.startPx, avatars)
        }
    }
}

private fun maskBrush(stops: LyricsMaskStops) = Brush.verticalGradient(
    0f to Color.Transparent,
    stops.outerTop to Color.Transparent,
    stops.innerTop to Color.Black,
    stops.innerBottom to Color.Black,
    stops.outerBottom to Color.Transparent,
    1f to Color.Transparent,
)

/** Pinned credits: the lyrics fade out over this much just above them. */
private const val PINNED_FADE_DP = 48f

private data class MeasureKey(
    val config: RenderConfig,
    val widthPx: Float,
    val fontSizeScale: Float,
    val romanize: Boolean,
    val type: LyricsType,
    val fontKey: String,
)

private class MeasuredLyrics(val lines: List<Line>, val layouts: List<LineLayout>)

private class ShownLyrics(
    val documentId: String,
    val lines: List<Line>,
    val layouts: List<LineLayout>,
    val lyricsType: LyricsType,
    val footer: LyricsFooter,
)

/** Paused and this many frames without a change, the frame loop rests. */
private const val REST_AFTER_STILL_FRAMES = 30
/** How often a resting frame loop looks for a change (a seek while paused). */
private const val REST_POLL_MS = 150L

/** The desktop lyric size (its 3.5rem cap), in CSS px. */
private const val REFERENCE_LYRIC_SIZE_PX = 56f

/**
 * Calculates the horizontal starting position of a line based on its alignment and the presence of duets.
 */
private fun getLineStartX(
    layout: LineLayout,
): Float {
    return if (layout.isRightAligned) {
        layout.contentStartX + layout.contentWidth - layout.totalWidth
    } else {
        layout.contentStartX
    }
}
