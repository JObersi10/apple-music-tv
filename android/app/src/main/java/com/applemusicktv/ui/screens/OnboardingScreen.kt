package com.applemusicktv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.applemusicktv.data.CrossfadePreferences
import com.applemusicktv.data.OnboardingPreferences
import com.applemusicktv.ui.viewmodel.OnboardingViewModel
import com.applemusicktv.ui.viewmodel.ServerCheck
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

    /**
     * Drop the IME and the text field's focus. Compose's SoftwareKeyboardController
     * alone does not close the Fire TV IME — it stays on screen over the next step —
     * so go through the platform InputMethodManager as well.
     */
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
    LaunchedEffect(s.step, s.serverCheck) {
        // The soft keyboard from the IP field stays on screen across a step change and
        // covers the next step, so tear it down before moving focus.
        dismissKeyboard()
        kotlinx.coroutines.delay(120)
        runCatching { primaryFocus.requestFocus() }
    }

    // Step 2 advances by itself when the token lands, so the user doesn't have to
    // walk back to the TV after pasting on their phone.
    LaunchedEffect(s.step) {
        if (s.step == 2) vm.startMutPolling() else vm.stopMutPolling()
    }
    DisposableEffect(Unit) { onDispose { vm.stopMutPolling() } }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .padding(horizontal = 96.dp, vertical = 56.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text("Step ${s.step} of ${vm.totalSteps}", fontSize = 13.sp, color = Color(0xFF666666))
            Spacer(Modifier.height(6.dp))

            when (s.step) {
                1 -> StepServer(vm, s, ::dismissKeyboard)
                2 -> StepAccount(vm, s)
                3 -> StepRemote(vm, s, TvDevice.isFireTv(context))
                4 -> StepPreferences(vm, s)
                else -> StepTips(TvDevice.needsOnScreenMenuToggle(context, s.remoteChoice))
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.step > 1) OnbButton("Back", onClick = vm::back)
                when (s.step) {
                    1 -> {
                        // Advancing needs a reachable server — everything downstream
                        // (browse, library, lyrics, playback) goes through the proxy.
                        if (s.serverCheck == ServerCheck.Ok) {
                            OnbButton("Continue", primary = true, modifier = Modifier.focusRequester(primaryFocus), onClick = vm::next)
                        } else {
                            OnbButton(
                                if (s.serverCheck == ServerCheck.Checking) "Checking…" else "Test connection",
                                primary = true,
                                modifier = Modifier.focusRequester(primaryFocus),
                                onClick = vm::checkServer,
                            )
                            OnbButton("Skip for now", onClick = vm::next)
                        }
                    }
                    2 -> OnbButton(if (s.hasMut) "Continue" else "Skip — preview only", primary = s.hasMut, modifier = Modifier.focusRequester(primaryFocus), onClick = vm::next)
                    3, 4 -> OnbButton("Continue", primary = true, modifier = Modifier.focusRequester(primaryFocus), onClick = vm::next)
                    else -> OnbButton("Start listening", primary = true, modifier = Modifier.focusRequester(primaryFocus)) { dismissKeyboard(); vm.finish(); onDone() }
                }
            }
        }
    }
}

@Composable
private fun StepServer(
    vm: OnboardingViewModel,
    s: com.applemusicktv.ui.viewmodel.OnboardingState,
    dismissKeyboard: () -> Unit,
) {
    StepHeader("Connect to your computer", "The proxy server runs on your PC or Mac. Enter its local IP address.")

    // Not a live text field: focusing one on this screen popped the IME open by
    // itself and it then covered the buttons. Press OK on the address to start
    // typing, press Done (or Back) to put the keyboard away again.
    var editing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (editing) {
            kotlinx.coroutines.delay(60)
            runCatching { fieldFocus.requestFocus() }
        }
    }

    // Back while typing should just close the editor, not fall through to the
    // app-exit dialog.
    BackHandler(enabled = editing) { editing = false; dismissKeyboard() }

    Box(Modifier.padding(top = 20.dp).fillMaxWidth(0.55f)) {
        if (editing) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = s.ipDraft,
                    onValueChange = vm::setIpDraft,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(Color(0xFFFA233B)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        editing = false
                        dismissKeyboard()
                        vm.checkServer()
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                    decorationBox = { inner ->
                        if (s.ipDraft.isEmpty()) {
                            Text("192.168.1.190", fontSize = 17.sp, color = Color(0xFF444444), fontFamily = FontFamily.Monospace)
                        }
                        inner()
                    },
                )
            }
        } else {
            val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
            Surface(
                onClick = { editing = true },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF111111),
                    focusedContainerColor = Color(0xFF262626),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    s.ipDraft.ifEmpty { "Press OK to enter your computer's IP" },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    fontSize = 17.sp,
                    fontFamily = if (s.ipDraft.isEmpty()) FontFamily.Default else FontFamily.Monospace,
                    color = if (s.ipDraft.isEmpty()) Color(0xFF777777) else Color.White,
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    when (s.serverCheck) {
        ServerCheck.Ok -> Text("Connected.", fontSize = 14.sp, color = Color(0xFF6BCB77))
        ServerCheck.Checking -> Text("Testing…", fontSize = 14.sp, color = Color(0xFF999999))
        ServerCheck.Failed -> Text(s.serverError ?: "Couldn't connect.", fontSize = 14.sp, color = Color(0xFFE05260))
        ServerCheck.Idle -> Text(
            "Port 3000 is assumed. Add it explicitly if you changed it.",
            fontSize = 13.sp, color = Color(0xFF666666),
        )
    }
    if (s.serverCheck != ServerCheck.Ok) {
        Spacer(Modifier.height(14.dp))
        Text(
            "Skipping leaves the app with no data — browse, search, library and lyrics all need the server. Only playback can work without it.",
            fontSize = 12.sp, color = Color(0xFF555555),
        )
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
                Modifier.padding(start = 40.dp).size(210.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(14.dp),
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

@Composable
private fun StepTips(onScreenToggle: Boolean) {
    StepHeader("How to use it", "Five things you won't find on your own.")

    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Tip(
            "Hold down OK for more options",
            "On any song, album or playlist. Keep the OK button pressed for a second and a menu appears: play it next, add it to the queue, jump to the artist. This is the big one — nothing on screen tells you it's there.",
        )
        Tip(
            if (onScreenToggle) "The button with three lines shows the words" else "The Menu button shows the words",
            if (onScreenToggle) "It sits next to play and skip. Press it to swap between song lyrics and the list of what's coming up."
            else "It's the button with three lines on your remote. Press it to swap between song lyrics and the list of what's coming up.",
        )
        Tip(
            "To jump around inside a song",
            "Press down until the thin progress bar lights up. Then left and right move through the song 10 seconds at a time, and OK jumps to that spot.",
        )
        Tip(
            "The ··· button is your settings",
            "Sleep timer, how strongly the background pulses to the beat, how long songs blend into each other, shuffle and repeat.",
        )
        Tip(
            "The first play of a song is slow. That's normal.",
            "Your computer has to unscramble it first, which takes about 15 to 20 seconds. The spinning ring around the play button means it's working. Play the same song again later and it starts instantly.",
        )
    }
}

@Composable
private fun Tip(title: String, body: String) {
    Column {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(body, fontSize = 13.sp, color = Color(0xFF888888))
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color(0xFFFA233B) else Color(0xFF1C1C1E),
            focusedContainerColor = if (primary) Color(0xFFFF3B50) else Color(0xFF2E2E2E),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
            fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium,
        )
    }
}
