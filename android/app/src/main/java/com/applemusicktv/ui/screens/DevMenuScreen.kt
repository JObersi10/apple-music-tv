package com.applemusicktv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.applemusicktv.BuildConfig
import com.applemusicktv.ui.viewmodel.DevMenuViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.util.UpdateChecker
import com.applemusicktv.util.UpdateInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DevMenuScreen(
    playerVm: PlayerViewModel,
    onDataRefresh: () -> Unit = {},
    initialUpdate: UpdateInfo? = null,
    modifier: Modifier = Modifier,
) {
    val vm: DevMenuViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val pstate by playerVm.state.collectAsState()
    var pcIpDraft by remember(state.pcServerIp) { mutableStateOf(state.pcServerIp) }
    var showDev by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // Live network activity — polled from the shared NetworkLog buffer.
    var netLogs by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(showDev) {
        while (showDev) { netLogs = vm.networkLogs(); kotlinx.coroutines.delay(1000) }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)

            // ── SOFTWARE ──────────────────────────────────────────────────
            SectionLabel("Software")
            UpdatesSection(initialUpdate)
            BugReportRow(state.webServerUrl)

            // ── PLAYBACK ──────────────────────────────────────────────────
            SectionLabel("Playback")
            Stepper(
                label = "Crossfade", value = "%.1fs".format(state.crossfadeMs / 1000f),
                sub = "Overlap the end of one song into the next",
                onDec = { vm.setCrossfade(state.crossfadeMs - 500) },
                onInc = { vm.setCrossfade(state.crossfadeMs + 500) },
            )
            Toggle(
                label = "Standalone playback", on = state.standaloneOn,
                sub = if (state.standaloneOn) "Decode on this device — no PC required" else "Stream through the PC server",
                onToggle = { vm.toggleStandalone() },
            )
            Toggle(
                label = "Volume leveling", on = pstate.volumeLeveling,
                sub = if (pstate.volumeLeveling) "Even out loudness across tracks" else "Play each track at its own level",
                onToggle = { playerVm.toggleVolumeLeveling() },
            )
            Toggle(
                label = "Background play", on = pstate.backgroundPlayEnabled,
                sub = if (pstate.backgroundPlayEnabled) "Keep playing when you leave the app" else "Pause when the app goes to the background",
                onToggle = { playerVm.toggleBackgroundPlay() },
            )

            // ── NOW PLAYING ───────────────────────────────────────────────
            SectionLabel("Now Playing")
            Stepper(
                label = "Background",
                value = pstate.nowPlayingBackground.label,
                sub = "Colour orbs, projector glow, or solid black",
                onDec = { playerVm.stepNowPlayingBackground(-1) },
                onInc = { playerVm.stepNowPlayingBackground(1) },
            )
            Toggle(
                label = "Show info", on = pstate.showNowPlayingInfo,
                sub = if (pstate.showNowPlayingInfo) "Display the clock and panel hints" else "Hide them for an art-only view",
                onToggle = { playerVm.toggleNowPlayingInfo() },
            )
            // Intensity is the orb SIZE/reactivity; hidden on Black (no orbs). Remembered per mode.
            if (pstate.nowPlayingBackground.label != "Black") Stepper(
                label = "Intensity",
                value = intensityLabel(pstate.beatIntensity),
                sub = "How hard the background reacts to the beat",
                onDec = { playerVm.stepBeatIntensity(-1) },
                onInc = { playerVm.stepBeatIntensity(1) },
            )
            // Orb drift speed — only meaningful in Projector.
            if (pstate.nowPlayingBackground.label == "Projector") Stepper(
                label = "Orb speed",
                value = orbSpeedLabel(pstate.orbSpeed),
                sub = "How fast the orbs drift around",
                onDec = { playerVm.stepOrbSpeed(-1) },
                onInc = { playerVm.stepOrbSpeed(1) },
            )
            Stepper(
                label = "Lyrics size",
                value = lyricsScaleLabel(pstate.lyricsScale),
                sub = "Text size in the lyrics panel",
                onDec = { playerVm.stepLyricsScale(-1) },
                onInc = { playerVm.stepLyricsScale(1) },
            )
            Toggle(
                label = "Rounded artwork", on = pstate.artworkRounded,
                sub = if (pstate.artworkRounded) "Soft rounded album-art corners" else "Square album-art corners",
                onToggle = { playerVm.toggleArtworkRounded() },
            )
            Toggle(
                label = "Motion artwork", on = pstate.motionArtworkEnabled,
                sub = if (pstate.motionArtworkEnabled) "Play animated album art when available" else "Off — lighter on the device",
                onToggle = { playerVm.toggleMotionArtwork() },
            )
            Toggle(
                label = "Reduce motion", on = pstate.reduceMotion,
                sub = if (pstate.reduceMotion) "Background holds still" else "Background drifts and pulses",
                onToggle = { playerVm.toggleReduceMotion() },
            )
            Toggle(
                label = "Low Power Mode", on = pstate.lowPowerMode,
                sub = if (pstate.lowPowerMode) "Simpler visuals, less work for the device" else "Full-quality visuals",
                onToggle = { playerVm.toggleLowPowerMode() },
            )
            Stepper(
                label = "Screensaver",
                value = screensaverLabel(pstate.screensaverTimeoutMin),
                sub = "How long to wait before dimming the screen",
                onDec = { playerVm.stepScreensaverTimeout(-1) },
                onInc = { playerVm.stepScreensaverTimeout(1) },
            )
            Toggle(
                label = "Ambient screensaver", on = pstate.screensaverKeepBackground,
                sub = if (pstate.screensaverKeepBackground) "Keep the moving background while dimmed" else "Fade to plain black while dimmed",
                onToggle = { playerVm.toggleScreensaverKeepBackground() },
            )

            // ── A/V SYNC ──────────────────────────────────────────────────
            // One delay drives both the beat visuals and the lyric clock. A ~200 ms display baseline is
            // built in (so this normally reads 0); Automatic adds a Bluetooth estimate on top when a BT
            // output is live. "Extra" is your own nudge on top of all that.
            SectionLabel("A/V Sync")
            Text(
                "Output: ${pstate.avOutputLabel}",
                fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium,
            )
            Text(
                if (pstate.avSyncAuto && pstate.avOnBluetooth) "Bluetooth delay applied: +350 ms (estimated)"
                else "Standard timing",
                fontSize = 10.sp, color = Color(0xFF999999), modifier = Modifier.padding(bottom = 4.dp),
            )
            Toggle(
                label = "Automatic",
                on = pstate.avSyncAuto,
                sub = if (pstate.avSyncAuto) "Adds a Bluetooth estimate on top automatically"
                      else "Manual — only your extra below is added to the baseline",
                onToggle = { playerVm.setAvSyncAuto(!pstate.avSyncAuto) },
            )
            Stepper(
                label = "Extra offset", value = "${state.lyricsOffsetMs}ms",
                sub = "On top of the built-in baseline. Negative = earlier, positive = later",
                onDec = { vm.setLyricsOffset(state.lyricsOffsetMs - 50) },
                onInc = { vm.setLyricsOffset(state.lyricsOffsetMs + 50) },
            )

            Spacer(Modifier.height(6.dp))
            // ── Reveal dev menu ───────────────────────────────────────────
            ActionBtn(if (showDev) "▾ Hide Dev Menu" else "▸ Open Dev Menu", Color(0xFF1C1C1E)) { showDev = !showDev }

            if (showDev) {
                SectionLabel("Account")
                StatusChip("Bearer token", state.hasBearer, if (!state.hasBearer) "Refresh to scrape" else "Active")
                StatusChip("Music-User-Token", state.hasMUT, state.mutSetAt?.let { "Set $it" } ?: if (!state.hasMUT) "Set via phone web server" else "Active")
                StatusChip("Server", state.serverOk, if (state.serverOk) "Reachable" else "Unreachable — standalone")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionBtn("Re-check Server", Color(0xFF1A2A1A), small = true) { vm.recheckServer(playerVm); onDataRefresh() }
                    ActionBtn("Refresh", Color(0xFF2A2A2A), small = true) { vm.refresh(); onDataRefresh() }
                    ActionBtn("Replay Setup", Color(0xFF2A2A1A), small = true) { playerVm.resetOnboarding() }
                    ActionBtn("Clear Token", Color(0xFF3A1A1A), small = true) { vm.clearMUT() }
                }

                SectionLabel("Connection")
                if (state.webServerUrl.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xFF0D1F0D), RoundedCornerShape(10.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("PHONE WEB SERVER", fontSize = 9.sp, color = Color(0xFF6BCB77), fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(state.webServerUrl, fontSize = 15.sp, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Text("Open on phone → paste Music-User-Token", fontSize = 11.sp, color = Color(0xFF557755))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("PC SERVER", fontSize = 9.sp, color = Color(0xFF888888), fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            BasicTextField(
                                value = pcIpDraft, onValueChange = { pcIpDraft = it }, singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(Color(0xFFFA233B)),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { vm.setPcServerIp(pcIpDraft) }),
                                decorationBox = { inner ->
                                    if (pcIpDraft.isEmpty()) Text("192.168.x.x", fontSize = 13.sp, color = Color(0xFF444444), fontFamily = FontFamily.Monospace)
                                    inner()
                                },
                            )
                        }
                        ActionBtn("Set", Color(0xFF2A2A2A), small = true) { vm.setPcServerIp(pcIpDraft) }
                        if (pcIpDraft.isNotEmpty()) ActionBtn("Clear", Color(0xFF3A1A1A), small = true) { pcIpDraft = ""; vm.setPcServerIp("") }
                    }
                    if (state.pcServerIp.isNotEmpty())
                        Text("Active: ${state.pcServerIp}:3000", fontSize = 10.sp, color = Color(0xFF6BCB77), fontFamily = FontFamily.Monospace)
                }

                // ── LOGS (app + network) ──────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("Logs")
                    ActionBtn("Clear", Color(0xFF2A2A2A), small = true) { vm.clearLogs() }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                        .background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp)).padding(12.dp),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(netLogs.reversed()) { line ->
                        Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF6C8CD5), lineHeight = 13.sp)
                    }
                    items(state.logs.reversed()) { log ->
                        Text(log.message, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = when (log.level) {
                                "ERROR" -> Color(0xFFFF6B6B); "WARN" -> Color(0xFFFFD93D)
                                "OK"    -> Color(0xFF6BCB77); else  -> Color(0xFFAAAAAA)
                            }, lineHeight = 13.sp)
                    }
                }

                SectionLabel("Danger Zone")
                ActionBtn("Reset App", Color(0xFF4A1414)) { confirmReset = true }
                Text("Wipes token, pins & settings, then restarts.", fontSize = 10.sp, color = Color(0xFF775555))
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (confirmReset) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(360.dp).background(Color(0xFF1C1C1E), RoundedCornerShape(14.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Reset App?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("This erases your token, pins and all settings, then closes the app.", fontSize = 12.sp, color = Color(0xFF999999))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionBtn("Cancel", Color(0xFF2A2A2A)) { confirmReset = false }
                    ActionBtn("Reset", Color(0xFFB22222)) {
                        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        am.clearApplicationUserData()
                    }
                }
            }
        }
    }
}

@Composable
private fun BugReportRow(webServerUrl: String) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF161618), RoundedCornerShape(10.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Bug report / logs", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
        if (webServerUrl.isNotEmpty()) {
            Text("On your phone open:", fontSize = 10.sp, color = Color(0xFF777777))
            Text("$webServerUrl/report", fontSize = 13.sp, color = Color(0xFF6BCB77),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text("Downloads a text file with app version, device, the last crash and recent logs.",
                fontSize = 10.sp, color = Color(0xFF777777))
        } else {
            Text("Connect to Wi-Fi to expose the report page.", fontSize = 10.sp, color = Color(0xFF777777))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UpdatesSection(initialUpdate: UpdateInfo? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf(initialUpdate) }
    var progress by remember { mutableStateOf(-1f) } // -1 = not downloading
    var beta by remember { mutableStateOf(com.applemusicktv.util.UpdatePreferences.betaEnabled(ctx)) }

    // Fire OS often RECREATES MainActivity when the unknown-sources settings screen returns, which
    // wipes all Compose state (the old in-memory parked file was lost, forcing a re-check + re-download).
    // Survive that: the downloaded APK already lives at cacheDir/updates/update.apk, and a persisted
    // flag records that an install is waiting on the grant. On resume we re-fire from disk, so
    // recreation costs nothing.
    fun prefs() = ctx.getSharedPreferences("update_state", android.content.Context.MODE_PRIVATE)
    fun cachedApk() = java.io.File(java.io.File(ctx.cacheDir, "updates"), "update.apk")
    fun hasGrant() = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
        ctx.packageManager.canRequestPackageInstalls()

    fun installOrRequestGrant(apk: java.io.File) {
        if (hasGrant()) {
            prefs().edit().putBoolean("pending_install", false).apply()
            UpdateChecker.install(ctx, apk); return
        }
        // Park across a possible recreation: remember we owe an install, keep the APK on disk.
        prefs().edit().putBoolean("pending_install", true).apply()
        status = "Allow installs from this app, then come back"
        val pkgUri = android.net.Uri.parse("package:${ctx.packageName}")
        // The per-app screen isn't always present on Fire OS; fall back to the generic intent rather
        // than throwing ActivityNotFoundException at someone who was only trying to update.
        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri)
        runCatching { ctx.startActivity(intent) }.onFailure {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
        }
    }

    // On resume (including after a full recreation), if an install is owed, the grant is now held, and
    // the downloaded APK is still cached → install it. No re-check, no re-download.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                prefs().getBoolean("pending_install", false) && hasGrant()) {
                val apk = cachedApk()
                if (apk.exists() && apk.length() > 0) {
                    prefs().edit().putBoolean("pending_install", false).apply()
                    status = null
                    UpdateChecker.install(ctx, apk)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun runCheck() {
        if (checking) return
        checking = true; status = "Checking GitHub…"; update = null
        scope.launch {
            val res = UpdateChecker.check(beta)
            checking = false
            res.onSuccess { info ->
                if (info == null) status = "You're on the latest version"
                else { update = info; status = null }
            }.onFailure { status = "Check failed — no connection?" }
        }
    }

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF161618), RoundedCornerShape(10.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("App version", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    status ?: "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE} · ${BuildConfig.GIT_SHA})",
                    fontSize = 10.sp, color = Color(0xFF777777), modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (update == null && progress < 0f) {
                ActionBtn(if (checking) "Checking…" else "Check for Updates", Color(0xFF1C1C2E), small = true) { runCheck() }
            }
        }

        // Opt into prerelease builds. Re-checks immediately so the result reflects the channel.
        Toggle(
            label = "Beta updates", on = beta,
            sub = if (beta) "Includes prerelease builds" else "Stable releases only",
            onToggle = {
                beta = !beta
                com.applemusicktv.util.UpdatePreferences.setBeta(ctx, beta)
                runCheck()
            },
        )

        // An update is available → show version, notes and a download button.
        update?.let { info ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Update available: v${info.version}", fontSize = 13.sp, color = Color(0xFF6BCB77), fontWeight = FontWeight.SemiBold)
                if (info.notes.isNotEmpty())
                    Text(info.notes.take(400), fontSize = 10.sp, color = Color(0xFF999999), lineHeight = 14.sp)
                if (progress < 0f) {
                    val mb = if (info.sizeBytes > 0) " · %.1f MB".format(info.sizeBytes / 1024f / 1024f) else ""
                    ActionBtn("Download & Install$mb", Color(0xFF14351F)) {
                        progress = 0f
                        scope.launch {
                            UpdateChecker.download(ctx, info) { progress = it }
                                .onSuccess { apk -> progress = -1f; installOrRequestGrant(apk) }
                                .onFailure { progress = -1f; status = "Download failed"; update = null }
                        }
                    }
                } else {
                    Text("Downloading… ${(progress * 100).toInt()}%", fontSize = 12.sp, color = Color.White)
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF2A2A2C), RoundedCornerShape(50))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp)
                            .background(Color(0xFFFA233B), RoundedCornerShape(50)))
                    }
                }
            }
        }
    }
}

/** Calm / Normal / Strong / Crazy for the beat-reaction multiplier. */
internal fun intensityLabel(f: Float): String = when {
    f < 0.8f -> "Calm"
    f < 1.5f -> "Normal"
    f < 2.5f -> "Strong"
    else     -> "Crazy"
}

internal fun orbSpeedLabel(f: Float): String = when {
    f < 0.85f -> "Slow"
    f < 1.3f  -> "Normal"
    else      -> "Fast"
}

internal fun lyricsScaleLabel(f: Float): String = when {
    f < 0.95f -> "Small"
    f < 1.1f  -> "Normal"
    else      -> "Large"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Stepper(label: String, value: String, sub: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF161618), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 10.sp, color = Color(0xFF777777), modifier = Modifier.padding(top = 2.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepBtn("−", onDec)
            Text(value, fontSize = 15.sp, color = Color(0xFFFA233B), fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepBtn("+", onInc)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StepBtn(sym: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2A2A2C), focusedContainerColor = Color(0xFFFA233B)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        modifier = Modifier.size(38.dp),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(sym, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Toggle(label: String, on: Boolean, sub: String, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF161618), focusedContainerColor = Color(0xFF232325)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text(sub, fontSize = 10.sp, color = Color(0xFF777777), modifier = Modifier.padding(top = 2.dp))
            }
            Box(
                Modifier.width(56.dp).height(30.dp)
                    .background(if (on) Color(0xFFFA233B) else Color(0xFF3A3A3C), RoundedCornerShape(50)),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(3.dp).size(24.dp).background(Color.White, RoundedCornerShape(50)))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, color = Color(0xFF666666),
        fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun StatusChip(label: String, ok: Boolean, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).background(if (ok) Color(0xFF6BCB77) else Color(0xFFFF6B6B), RoundedCornerShape(50)))
        Column {
            Text(label, fontSize = 13.sp, color = Color(0xFFCCCCCC))
            if (sub != null) Text(sub, fontSize = 10.sp, color = Color(0xFF777777))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionBtn(label: String, color: Color, small: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = color, focusedContainerColor = color.copy(alpha = 0.7f)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier.height(if (small) 32.dp else 40.dp)) {
        Box(Modifier.padding(horizontal = if (small) 12.dp else 18.dp), Alignment.Center) {
            Text(label, fontSize = if (small) 11.sp else 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}
