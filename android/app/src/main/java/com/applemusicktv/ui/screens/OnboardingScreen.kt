package com.applemusicktv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import com.applemusicktv.data.CrossfadePreferences
import com.applemusicktv.data.OnboardingPreferences
import com.applemusicktv.ui.viewmodel.OnboardingViewModel
import com.applemusicktv.util.QrCode
import com.applemusicktv.util.TvDevice

/**
 * First-run setup. Full screen, no nav bar — a new install has nothing to browse
 * until the server and token are configured, so there's nothing to navigate to.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current

    /** No text fields left in setup, but the IME can linger from elsewhere. */
    fun dismissKeyboard() {
        keyboard?.hide()
        runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        focusManager.clearFocus(force = true)
    }

    // Every step must hand focus to its primary button, or the D-pad has nothing to
    // move from and the whole flow is unusable. Buttons only — the IP field stays
    // unfocused by default, same rule as the rest of the app.
    val primaryFocus = remember { FocusRequester() }
    val tipsNextFocus = remember { FocusRequester() }
    // Tips step gates the finish button: the user must page through every tip first.
    var tipsSeenAll by remember(s.step) { mutableStateOf(false) }
    val onTipsStep = s.step >= vm.totalSteps
    LaunchedEffect(s.step) {
        // The soft keyboard from the IP field stays on screen across a step change and
        // covers the next step, so tear it down before moving focus.
        dismissKeyboard()
        kotlinx.coroutines.delay(120)
        // On the tips step focus belongs in the carousel, not the (locked) finish button.
        runCatching { if (onTipsStep) tipsNextFocus.requestFocus() else primaryFocus.requestFocus() }
    }

    // Step 2 advances by itself when the token lands, so the user doesn't have to
    // walk back to the TV after pasting on their phone.
    LaunchedEffect(s.step) {
        if (s.step == 1) vm.startMutPolling() else vm.stopMutPolling()
    }
    DisposableEffect(Unit) { onDispose { vm.stopMutPolling() } }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .padding(horizontal = 96.dp, vertical = 56.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            ProgressDots(current = s.step, total = vm.totalSteps)
            Spacer(Modifier.height(14.dp))

            // Slide + fade between steps — forward slides in from the right, Back from the left.
            var prevStep by remember { mutableStateOf(s.step) }
            val forward = s.step >= prevStep
            LaunchedEffect(s.step) { prevStep = s.step }
            androidx.compose.animation.AnimatedContent(
                targetState = s.step,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (androidx.compose.animation.slideInHorizontally(
                        androidx.compose.animation.core.tween(320)
                    ) { w -> dir * w / 3 } + androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(320)
                    )) togetherWith (androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(160)
                    ))
                },
                label = "onboardingStep",
            ) { step ->
                Column {
                    when (step) {
                        1 -> StepAccount(vm, s)
                        2 -> StepRemote(vm, s, TvDevice.isFireTv(context))
                        3 -> StepPreferences(vm, s)
                        else -> StepTips(TvDevice.needsOnScreenMenuToggle(context, s.remoteChoice), tipsNextFocus) { tipsSeenAll = true }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.step > 1) OnbButton("Back", onClick = vm::back)
                when (s.step) {
                    1 -> OnbButton(if (s.hasMut) "Continue" else "Skip — preview only", primary = s.hasMut, modifier = Modifier.focusRequester(primaryFocus), onClick = vm::next)
                    2, 3 -> OnbButton("Continue", primary = true, modifier = Modifier.focusRequester(primaryFocus), onClick = vm::next)
                    else -> OnbButton(if (tipsSeenAll) "Start listening" else "See all tips first", primary = tipsSeenAll, enabled = tipsSeenAll, modifier = Modifier.focusRequester(primaryFocus)) { dismissKeyboard(); vm.finish(); onDone() }
                }
            }
        }
    }
}

@Composable
private fun StepAccount(vm: OnboardingViewModel, s: com.applemusicktv.ui.viewmodel.OnboardingState) {
    StepHeader("Sign in", "Your Apple Music token is pasted from your phone — it's too long to type with a remote.")

    Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("On your phone, scan this or open:", fontSize = 14.sp, color = Color(0xFF999999))
            Spacer(Modifier.height(8.dp))
            Text(
                s.phoneUrl.ifEmpty { "starting web server…" },
                fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = Color.White, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Paste your Music-User-Token there and this screen will continue on its own.",
                fontSize = 14.sp, color = Color(0xFF888888),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    s.hasMut && s.tokenPresetOnEntry -> "You already have a token saved. Paste a new one to replace it, or just press Continue."
                    s.hasMut -> "Token received."
                    else -> "Waiting for a token…"
                },
                fontSize = 14.sp,
                color = if (s.hasMut) Color(0xFF6BCB77) else Color(0xFF666666),
            )
            Spacer(Modifier.height(18.dp))
            Text("Without a token you get 30-second previews only.", fontSize = 12.sp, color = Color(0xFF555555))
        }

        // QR of the same URL. Drawn on a white card with a quiet zone — a code that
        // bleeds into a dark background won't scan.
        val matrix = remember(s.phoneUrl) { s.phoneUrl.takeIf { it.isNotEmpty() }?.let { QrCode.encode(it) } }
        if (matrix != null) {
            Box(
                // A full 4-module quiet zone, as the spec requires — the first version
                // left only ~2 and that is the usual reason a phone won't lock on.
                Modifier.padding(start = 40.dp).size(250.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(30.dp),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val n = matrix.size
                    val cell = size.minDimension / n
                    for (r in 0 until n) for (c in 0 until n) {
                        if (!matrix[r][c]) continue
                        drawRect(
                            color = Color.Black,
                            topLeft = androidx.compose.ui.geometry.Offset(c * cell, r * cell),
                            // Half-pixel overdraw: exact cell widths leave hairline gaps
                            // after rounding, which some scanners read as light modules.
                            size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StepRemote(vm: OnboardingViewModel, s: com.applemusicktv.ui.viewmodel.OnboardingState, detectedFire: Boolean) {
    val detectedLabel = if (detectedFire) "Fire TV" else "Google TV / other"
    StepHeader("Your remote", "Detected: $detectedLabel. Change it only if that's wrong.")

    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteOption(
            "Fire TV remote", "Menu button opens the queue and lyrics.",
            selected = s.remoteChoice == OnboardingPreferences.REMOTE_FIRE ||
                (s.remoteChoice == OnboardingPreferences.REMOTE_AUTO && detectedFire),
        ) { vm.setRemote(OnboardingPreferences.REMOTE_FIRE) }

        RemoteOption(
            "Google TV / other remote", "No Menu button — an on-screen list button is shown instead.",
            selected = s.remoteChoice == OnboardingPreferences.REMOTE_GOOGLE ||
                (s.remoteChoice == OnboardingPreferences.REMOTE_AUTO && !detectedFire),
        ) { vm.setRemote(OnboardingPreferences.REMOTE_GOOGLE) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StepPreferences(vm: OnboardingViewModel, s: com.applemusicktv.ui.viewmodel.OnboardingState) {
    StepHeader("Crossfade", "How long tracks overlap. Songs from the same album hand off with no fade regardless.")

    Row(
        Modifier.padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnbButton("−") { vm.setCrossfade(s.crossfadeMs - 1_000) }
        Text(
            "%.0f s".format(s.crossfadeMs / 1000f),
            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.width(90.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OnbButton("+") { vm.setCrossfade(s.crossfadeMs + 1_000) }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "Changeable any time from the ··· menu, or from the phone page you just used.",
        fontSize = 13.sp, color = Color(0xFF666666),
    )
}

/** Which mock frame a tip draws next to its caption. */
private enum class TipMock { LONG_PRESS, LYRICS, SCRUB, DOTS_MENU, LOADING, PROJECTOR, MUSIC_VIDEO }

private data class TipCard(val mock: TipMock, val title: String, val body: String)

/**
 * "Show, don't tell" tips. Each card pairs a caption with a small stylised mock of the
 * real screen — the feature being described lights up. Auto-advances so it needs no focus
 * of its own; the bottom "Start listening" button keeps D-pad focus the whole time.
 * All primitives + one shared pulse animation — no blur/multi-pass, safe on Fire TV.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StepTips(onScreenToggle: Boolean, nextFocus: FocusRequester, onSeenAll: () -> Unit) {
    val cards = remember(onScreenToggle) {
        listOf(
            TipCard(
                TipMock.LONG_PRESS, "Hold OK for more options",
                "On any song, album or playlist, keep OK pressed for a second. A menu opens — play next, add to queue, go to the artist. This is the big one; nothing on screen tells you it's there.",
            ),
            TipCard(
                TipMock.LYRICS,
                if (onScreenToggle) "The three-lines button shows the words" else "The Menu button shows the words",
                if (onScreenToggle) "It sits next to play and skip. Press it to swap between synced lyrics and what's coming up next."
                else "The button with three lines on your remote. Press it to swap between synced lyrics and what's coming up next.",
            ),
            TipCard(
                TipMock.SCRUB, "Jump around inside a song",
                "Press down until the thin bar lights up. Left and right move 10 seconds at a time, OK jumps to that spot.",
            ),
            TipCard(
                TipMock.DOTS_MENU, "The ··· button is your settings",
                "Sleep timer, shuffle, repeat, Full-Screen Lyrics, the ambient screensaver, Picture-in-Picture — all live here.",
            ),
            TipCard(
                TipMock.PROJECTOR, "Projector mode — let the room breathe",
                "An ambient mode: the lights go down to black and the album's own colours drift across the whole TV. No controls, no clutter — just the music, made a little bigger. Turn it on from the ··· menu and let it play.",
            ),
            TipCard(
                TipMock.MUSIC_VIDEO, "Music videos, full-screen",
                "Playlists and artist pages that carry videos play them full-screen, capped at 1080p and streamed as you watch — never fully downloaded. Look for the red MV tag, and use the ⧉ button to pop it into a corner while you browse.",
            ),
        )
    }

    // Remote-driven: user pages through with the on-screen ‹ › buttons. The finish
    // button stays locked until every tip has been reached at least once.
    var idx by remember { mutableStateOf(0) }
    var prevIdx by remember { mutableStateOf(0) }
    var maxSeen by remember { mutableStateOf(0) }
    LaunchedEffect(idx) {
        if (idx > maxSeen) maxSeen = idx
        if (maxSeen >= cards.lastIndex) onSeenAll()
    }
    val forward = idx >= prevIdx
    LaunchedEffect(idx) { prevIdx = idx }

    // One shared pulse drives every highlight ring — cheaper than per-mock animators.
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "tipPulse")
        .animateFloat(
            0.35f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(900),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "tipPulseA",
        )

    StepHeader("How to use it", "A few things you won't find on your own.")

    Row(Modifier.padding(top = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The mock frame — cross-fades between features.
        Box(Modifier.width(360.dp).height(210.dp), Alignment.Center) {
            androidx.compose.animation.AnimatedContent(
                targetState = idx,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(300)) { w -> dir * w / 4 } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300))) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
                },
                label = "tipMock",
            ) { i -> ScreenFrame { TipMockContent(cards[i].mock, pulse) } }
        }

        Spacer(Modifier.width(32.dp))

        Column(Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedContent(
                targetState = idx,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { it / 4 * dir } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280))) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
                },
                label = "tipText",
            ) { i ->
                Column {
                    Text(cards[i].title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(cards[i].body, fontSize = 14.sp, color = Color(0xFF9A9A9A), lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            // Remote-driven nav: ‹ back, › forward, with position dots between.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TipNavButton("‹", enabled = idx > 0) { if (idx > 0) idx-- }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    cards.indices.forEach { i ->
                        Box(
                            Modifier.size(if (i == idx) 8.dp else 6.dp)
                                .background(
                                    when { i == idx -> Color.White; i <= maxSeen -> Color(0xFF6A6A6A); else -> Color(0xFF333333) },
                                    RoundedCornerShape(50),
                                ),
                        )
                    }
                }
                TipNavButton("›", enabled = idx < cards.lastIndex, focusRequester = nextFocus) { if (idx < cards.lastIndex) idx++ }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (maxSeen >= cards.lastIndex) "All caught up — press down to start." else "Use ‹ › to see the rest.",
                fontSize = 12.sp, color = Color(0xFF666666),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TipNavButton(label: String, enabled: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) Color(0xFF1C1C1E) else Color(0xFF141414),
            focusedContainerColor = Color(0xFFFA233B),
            disabledContainerColor = Color(0xFF141414),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = if (enabled) Color.White else Color(0xFF444444),
        )
    }
}

/** A rounded "TV screen" bezel that hosts a mock. */
@Composable
private fun ScreenFrame(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xFF0C0C0E), RoundedCornerShape(14.dp))
            .padding(2.dp)
            .background(Color(0xFF141418), RoundedCornerShape(12.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun TipMockContent(mock: TipMock, pulse: Float) {
    when (mock) {
        TipMock.LONG_PRESS -> MockLongPress(pulse)
        TipMock.LYRICS -> MockLyrics(pulse)
        TipMock.SCRUB -> MockScrub(pulse)
        TipMock.DOTS_MENU -> MockDotsMenu(pulse)
        TipMock.LOADING -> MockLoading(pulse)
        TipMock.PROJECTOR -> MockProjector(pulse)
        TipMock.MUSIC_VIDEO -> MockMusicVideo(pulse)
    }
}

/** Ambient projector: a black screen with soft coloured blobs drifting, no chrome. */
@Composable
private fun MockProjector(pulse: Float) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
        val blobs = listOf(
            Triple(0.30f, 0.35f, Color(0xFFFA233B)),
            Triple(0.72f, 0.42f, Color(0xFF6E8BFF)),
            Triple(0.50f, 0.74f, Color(0xFF00C2A8)),
        )
        blobs.forEachIndexed { i, (fx, fy, c) ->
            val drift = (if (i % 2 == 0) pulse else 1f - pulse) * 0.06f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to c.copy(alpha = 0.55f), 1f to Color.Transparent,
                    center = androidx.compose.ui.geometry.Offset((fx + drift) * size.width, (fy - drift) * size.height),
                    radius = size.minDimension * 0.55f,
                ),
                radius = size.minDimension * 0.55f,
                center = androidx.compose.ui.geometry.Offset((fx + drift) * size.width, (fy - drift) * size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.Screen,
            )
        }
    }
}

/** A 16:9 video frame with a play glyph and a red MV tag. */
@Composable
private fun MockMusicVideo(pulse: Float) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0xFF101014)), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth(0.82f).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2A38), Color(0xFF15151C)))), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(34.dp)) {
                val a = 0.6f + 0.4f * pulse
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2f); lineTo(size.width * 0.2f, size.height); close()
                }
                drawPath(p, Color.White.copy(alpha = a))
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFA233B)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text("MV", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/** Three song rows; the middle one held down, a context menu popped over it. */
@Composable
private fun MockLongPress(pulse: Float) {
    Box(Modifier.fillMaxSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MockRow(false, 0f)
            Box {
                MockRow(true, pulse)
            }
            MockRow(false, 0f)
        }
        // Floating menu over the held row.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
                .background(Color(0xFF232326), RoundedCornerShape(8.dp))
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Play Next", "Add to Queue", "Go to Artist").forEachIndexed { i, t ->
                Text(
                    t, fontSize = 10.sp, color = if (i == 0) Color.White else Color(0xFFAAAAAA),
                    modifier = Modifier
                        .then(if (i == 0) Modifier.background(Color(0xFF3A3A3D), RoundedCornerShape(5.dp)) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun MockRow(highlighted: Boolean, pulse: Float) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (highlighted) Color(0xFFFA233B).copy(alpha = 0.14f + 0.18f * pulse) else Color(0xFF1C1C20),
                RoundedCornerShape(7.dp),
            )
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).background(Color(0xFF3A3A40), RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.width(110.dp).height(7.dp).background(Color(0xFF6A6A72), RoundedCornerShape(3.dp)))
            Box(Modifier.width(64.dp).height(5.dp).background(Color(0xFF44444A), RoundedCornerShape(3.dp)))
        }
    }
}

/** Lyric lines (one lit) + a transport row with the lyrics/queue toggle glowing. */
@Composable
private fun MockLyrics(pulse: Float) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            LyricBar(0.55f, Color(0xFF44444A))
            LyricBar(0.78f, Color.White)
            LyricBar(0.62f, Color(0xFF55555C))
            LyricBar(0.40f, Color(0xFF3A3A40))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            MockCircle(18.dp, Color(0xFF2A2A2E))
            Spacer(Modifier.width(10.dp))
            MockCircle(26.dp, Color.White)
            Spacer(Modifier.width(10.dp))
            MockCircle(18.dp, Color(0xFF2A2A2E))
            Spacer(Modifier.width(14.dp))
            // The lines/queue toggle — glowing.
            Box(
                Modifier.size(22.dp)
                    .background(Color(0xFFFA233B).copy(alpha = 0.25f + 0.35f * pulse), RoundedCornerShape(6.dp)),
                Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(3) { Box(Modifier.width(11.dp).height(2.dp).background(Color.White, RoundedCornerShape(2.dp))) }
                }
            }
        }
    }
}

@Composable
private fun LyricBar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth(fraction).height(9.dp).background(color, RoundedCornerShape(4.dp)))
}

/** A lit progress bar with a cursor knob and time readouts. */
@Composable
private fun MockScrub(pulse: Float) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFF2A2A2E), RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(0.45f).height(8.dp).background(Color.White, RoundedCornerShape(4.dp)))
            // Scrub cursor knob.
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 150.dp)
                    .size(16.dp)
                    .background(Color(0xFFFA233B).copy(alpha = 0.55f + 0.45f * pulse), RoundedCornerShape(50)),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1:24", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
            Text("−1:52", fontSize = 11.sp, color = Color(0xFF777777), fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        Text("◄  10s  ►", fontSize = 12.sp, color = Color(0xFFAAAAAA), modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/** A vertical settings list, like the ··· menu. */
@Composable
private fun MockDotsMenu(pulse: Float) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("···", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(2.dp))
        listOf("Sleep Timer", "Shuffle", "Repeat", "Full-Screen Lyrics").forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        if (i == 0) Color(0xFFFA233B).copy(alpha = 0.14f + 0.16f * pulse) else Color(0xFF1C1C20),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(t, fontSize = 11.sp, color = if (i == 0) Color.White else Color(0xFFAAAAAA))
                Box(Modifier.width(28.dp).height(6.dp).background(Color(0xFF44444A), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center) {}
            }
        }
    }
}

/** A play button wrapped in a spinning-style ring to signal the first-play wait. */
@Composable
private fun MockLoading(pulse: Float) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Box(Modifier.size(84.dp), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(
                    color = Color(0xFF3A3A40),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f),
                )
                drawArc(
                    color = Color(0xFFFA233B),
                    startAngle = -90f, sweepAngle = 90f + 200f * pulse, useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
            MockCircle(50.dp, Color.White)
        }
        Text("15–20s first time", fontSize = 11.sp, color = Color(0xFF888888),
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MockCircle(size: androidx.compose.ui.unit.Dp, color: Color) {
    Box(Modifier.size(size).background(color, RoundedCornerShape(50)))
}

/** A row of pill dots — the current step's pill stretches wide and lights red, done steps stay lit,
 *  upcoming ones are dim. Width + colour animate so stepping forward/back feels smooth. */
@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 1..total) {
            val active = i == current
            val done = i < current
            val width by androidx.compose.animation.core.animateDpAsState(if (active) 26.dp else 8.dp, androidx.compose.animation.core.tween(300), label = "dotW")
            val color by androidx.compose.animation.animateColorAsState(
                when { active -> Color(0xFFFA233B); done -> Color(0xFF9A9A9A); else -> Color(0xFF333333) },
                androidx.compose.animation.core.tween(300), label = "dotC",
            )
            Box(Modifier.height(8.dp).width(width).background(color, RoundedCornerShape(50)))
        }
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String) {
    Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, fontSize = 15.sp, color = Color(0xFF999999))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RemoteOption(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF1F1F1F) else Color(0xFF0E0E0E),
            focusedContainerColor = Color(0xFF2E2E2E),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
        modifier = Modifier.fillMaxWidth(0.6f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (selected) Color(0xFFFA233B) else Color(0xFF333333)),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text(body, fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OnbButton(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color(0xFFFA233B) else Color(0xFF1C1C1E),
            focusedContainerColor = if (primary) Color(0xFFFF3B50) else Color(0xFF2E2E2E),
            disabledContainerColor = Color(0xFF161616),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
            fontSize = 15.sp, color = if (enabled) Color.White else Color(0xFF666666), fontWeight = FontWeight.Medium,
        )
    }
}
