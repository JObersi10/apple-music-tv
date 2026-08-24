package com.applemusicktv.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.applemusicktv.ui.components.TopNavBar
import com.applemusicktv.ui.navigation.Screen
import com.applemusicktv.ui.navigation.TopNavTab
import com.applemusicktv.ui.screens.*
import java.net.URLDecoder
import com.applemusicktv.ui.viewmodel.LibraryViewModel
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Song
import androidx.compose.material3.Text

@Composable
fun AppShell(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var selectedTab   by remember { mutableStateOf(TopNavTab.ListenNow) }
    val playerVm: PlayerViewModel  = hiltViewModel()
    val navVm: NavigationViewModel = hiltViewModel()
    // Hoisted so the video survives navigation: fullscreen on Now Playing, in-app PiP elsewhere.
    val mvVm: com.applemusicktv.ui.viewmodel.MusicVideoViewModel = hiltViewModel()
    val navBarFocus = remember { FocusRequester() }
    val videoFocus = remember { FocusRequester() }
    // Hoist LibraryViewModel so library loads on startup, not when tab is first opened
    val libraryVm: LibraryViewModel = hiltViewModel()
    // Hoisted so a server re-check from the Dev menu can refetch both screens —
    // reconnecting is useless if the stale "server down" content stays on screen.
    val homeVm: com.applemusicktv.ui.viewmodel.HomeViewModel = hiltViewModel()

    // Silently poll GitHub Releases once per launch. If a newer build exists, a red dot
    // shows on the ⚙ tab and the Settings → Software section is pre-filled with it.
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var pendingUpdate by remember { mutableStateOf<com.applemusicktv.util.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        val beta = com.applemusicktv.util.UpdatePreferences.betaEnabled(appCtx)
        com.applemusicktv.util.UpdateChecker.check(beta).onSuccess { pendingUpdate = it }
    }

    // First run: setup owns the whole screen. Nothing behind it is usable until the
    // server and token are configured, so there is no nav bar and nothing to browse.
    val onboardingVm: com.applemusicktv.ui.viewmodel.OnboardingViewModel = hiltViewModel()
    var showOnboarding by remember { mutableStateOf(!playerVm.onboardingCompleted()) }
    // "Replay Setup" in Settings resets the pref and fires this — bring setup up right away
    // instead of only on the next launch.
    val replay by playerVm.replayOnboarding.collectAsState()
    LaunchedEffect(replay) {
        if (replay) { showOnboarding = true; playerVm.consumeReplayOnboarding() }
    }
    if (showOnboarding) {
        OnboardingScreen(
            vm = onboardingVm,
            onDone = {
                showOnboarding = false
                playerVm.onOnboardingFinished()
                homeVm.load()
                libraryVm.refresh()
            },
            modifier = modifier,
        )
        return
    }

    // Transient errors surface as a toast: a TV user is across the room and won't
    // find an inline banner, and silence reads as a crash.
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        playerVm.toasts.collect { msg ->
            android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val isOnNowPlaying = currentRoute == Screen.NowPlaying.route
    val videoActive by mvVm.active.collectAsState()
    val mvState by mvVm.state.collectAsState()
    // Integrated queue: PlayerViewModel owns one queue of songs AND videos. When the current
    // item is a video it emits it here; we hand it to the video player and jump to Now Playing.
    // The video's prev/next/auto-advance drive the same queue back through PlayerViewModel.
    val videoReq by playerVm.videoRequest.collectAsState()
    LaunchedEffect(Unit) {
        mvVm.onRequestNext = { playerVm.next() }
        mvVm.onRequestPrev = { playerVm.prev() }
    }
    LaunchedEffect(videoReq?.song?.id) {
        val v = videoReq
        if (v != null) {
            mvVm.show(v.song.id, v.song.title, v.song.artistName, startPaused = v.startPaused)
            // Only an explicit pick jumps to Now Playing. Auto-advance / skip keep the user
            // on whatever page they're on — the video keeps playing and shows when they visit.
            if (v.autoOpen) {
                selectedTab = TopNavTab.NowPlaying
                navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
            }
        } else {
            mvVm.close()
        }
    }
    // Video plays fullscreen ON the Now Playing tab; anywhere else it's an in-app PiP window.
    val videoFullscreen = videoActive && isOnNowPlaying

    // Keep the nav-bar highlight in sync with the ACTUAL route — otherwise popping back from
    // a pushed screen (e.g. artist opened from the video player) left the white pill stuck on
    // Now Playing. Detail routes leave the pill on whatever top tab we came from.
    LaunchedEffect(currentRoute) {
        when (currentRoute) {
            Screen.Home.route      -> selectedTab = TopNavTab.ListenNow
            Screen.Browse.route    -> selectedTab = TopNavTab.Browse
            Screen.Library.route   -> selectedTab = TopNavTab.Library
            Screen.Search.route    -> selectedTab = TopNavTab.Search
            Screen.NowPlaying.route -> selectedTab = TopNavTab.NowPlaying
            Screen.DevMenu.route   -> selectedTab = TopNavTab.Dev
            else -> {}
        }
    }
    LaunchedEffect(isOnNowPlaying) { navVm.isOnNowPlaying = isOnNowPlaying }
    // Leaving Now Playing keeps the video's AUDIO playing (like a song) but disables the video
    // track — that frees the secure decoder and its SurfaceFlinger overlay so nothing bleeds onto
    // Library/Browse. Returning re-enables video and the recomposed PlayerView reattaches the
    // surface, so the picture comes back at position without ever stopping the audio.
    //
    // NOTE: attachVideo()/detachVideo() are driven ONLY from the video-surface AndroidView `update`
    // lambda below, where the codec-release and surface-teardown steps are ordered deterministically
    // against the visibility flip. Driving them from here as well raced that ordering (the bleed).
    // This effect is intentionally left as just a marker of the navigation transition.
    // Whenever a video is active, media keys must drive it (not the paused audio player) —
    // MainActivity reads this. Pause audio the moment a video starts.
    LaunchedEffect(videoActive) {
        navVm.isOnMusicVideo = videoActive
        if (videoActive) playerVm.pause()
    }

    // Keep the screen awake ONLY while music is actually playing. When paused, drop the
    // flag so Fire TV's own screensaver / sleep can take over (our ambient screensaver only
    // ever runs while playing).
    val playerState by playerVm.state.collectAsState()
    // A regular song starting up takes over — the video player closes immediately so the
    // song's Now Playing (dynamic/projector) shows instead of a video stuck on top.
    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying && videoActive) mvVm.close()
    }
    // Prefetch the next queue item's video metadata whenever it's a video — whether the current
    // track is a song or a video — so a song→video or video→video advance is fast. This runs in
    // parallel with PlayerViewModel's audio N+1 prefetch (which skips video items), so the two
    // prefetch paths cover the whole mixed queue together.
    LaunchedEffect(playerState.queueIndex, playerState.queue, videoActive) {
        val next = playerState.queue.getOrNull(playerState.queueIndex + 1) ?: return@LaunchedEffect
        if (next.isMusicVideo) mvVm.prefetch(next.id)
        else if (videoActive) playerVm.prefetchAudio(next)   // video playing → warm the next song
    }
    val keepScreenOn = playerState.isPlaying
    val activity = LocalContext.current as? Activity
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // In Picture-in-Picture the window is a tiny thumbnail — swap the whole UI for a
    // minimal card: darkened album art + song title, no controls, no beat animation.
    if (playerState.isInPip) {
        PipView(playerState.currentSong)
        return
    }

    val goToNowPlaying by navVm.goToNowPlaying.collectAsState()
    LaunchedEffect(goToNowPlaying) {
        if (goToNowPlaying) {
            selectedTab = TopNavTab.NowPlaying
            // If we got here *from* Now Playing (e.g. Now Playing → Artist, then
            // Menu), pop back to that instance so it keeps its state instead of
            // pushing a second copy on top and growing the back stack.
            if (!navController.popBackStack(Screen.NowPlaying.route, inclusive = false)) {
                navController.navigate(Screen.NowPlaying.route) {
                    popUpTo(Screen.Home.route) { saveState = true }
                    launchSingleTop = true; restoreState = true
                }
            }
            navVm.consumeNowPlayingNavigation()
        }
    }

    // Overlay layout: content fills the whole screen (so Now Playing's
    // background can run fullscreen to the very top), with the nav bar drawn
    // last → it sits on a higher layer. Non-fullscreen screens get top padding
    // equal to the bar so their content isn't hidden underneath it.
    val navBarHeight = 64.dp

    // Exit confirmation on back from root
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = showExitDialog) { showExitDialog = false }
    BackHandler(enabled = !showExitDialog && currentRoute == Screen.Home.route) {
        showExitDialog = true
    }
    // Every other top-level tab returns to Listen Now first — only Listen Now itself
    // offers to exit. Detail screens keep the normal pop behaviour.
    val topLevelTabs = setOf(
        Screen.Browse.route, Screen.Library.route, Screen.Search.route,
        Screen.NowPlaying.route, Screen.Radio.route, Screen.DevMenu.route,
    )
    BackHandler(enabled = !showExitDialog && currentRoute in topLevelTabs) {
        selectedTab = TopNavTab.ListenNow
        navController.navigate(Screen.Home.route) {
            popUpTo(Screen.Home.route) { inclusive = true }
            launchSingleTop = true
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier
                .fillMaxSize()
                .padding(top = if (isOnNowPlaying) 0.dp else navBarHeight),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    playerVm = playerVm,
                    vm = homeVm,
                    onAlbumClick = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        navController.navigate(Screen.PlaylistDetail.route(id, name, artworkUrl))
                    },
                )
            }
            composable(Screen.Browse.route) {
                BrowseScreen(
                    playerVm       = playerVm,
                    onAlbumClick   = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> navController.navigate(Screen.PlaylistDetail.route(id, name, art)) },
                    onGenreClick   = { id, name -> navController.navigate(Screen.Genre.route(id, name)) },
                )
            }
            composable(
                route     = Screen.Genre.route,
                arguments = listOf(
                    navArgument("genreId")   { type = NavType.StringType },
                    navArgument("genreName") { type = NavType.StringType },
                ),
            ) { back ->
                val gid  = URLDecoder.decode(back.arguments?.getString("genreId")   ?: "", "UTF-8")
                val gnm  = URLDecoder.decode(back.arguments?.getString("genreName") ?: "", "UTF-8")
                GenreScreen(
                    genreId = gid, genreName = gnm, playerVm = playerVm,
                    onAlbumClick    = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> navController.navigate(Screen.PlaylistDetail.route(id, name, art)) },
                )
            }
            composable(Screen.Category.route) {
                CategoryScreen(
                    onAlbumClick    = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> navController.navigate(Screen.PlaylistDetail.route(id, name, art)) },
                )
            }
            composable(Screen.Library.route) {
                LibraryScreen(
                    vm         = libraryVm,
                    playerVm   = playerVm,
                    onAlbumClick    = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        navController.navigate(Screen.PlaylistDetail.route(id, name, artworkUrl ?: ""))
                    },
                    onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                )
            }
            composable(Screen.Search.route) {
                SearchScreen(
                    playerVm = playerVm,
                    onAlbumClick  = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        navController.navigate(Screen.PlaylistDetail.route(id, name, artworkUrl))
                    },
                    onCuratorClick = { id, kind ->
                        val prefix = when (kind) {
                            "multiroom"     -> "mr-"
                            "apple-curator" -> "ac-"
                            else            -> "c-"
                        }
                        navController.navigate(Screen.Category.route(prefix + id))
                    },
                )
            }
            composable(Screen.NowPlaying.route) {
                // When a video is active it fills this tab via the AppShell overlay. Don't
                // compose the audio screen behind it — its focusable controls would steal the
                // D-pad (moving the song UI instead of the video).
                if (!videoActive) {
                    NowPlayingScreen(
                        playerVm = playerVm,
                        navVm = navVm,
                        onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                        onAlbumClick  = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    )
                }
            }
            composable(Screen.Radio.route) {
                RadioScreen(playerVm = playerVm)
            }
            composable(Screen.DevMenu.route)    {
                DevMenuScreen(
                    playerVm = playerVm,
                    onDataRefresh = { homeVm.load(); libraryVm.refresh() },
                    initialUpdate = pendingUpdate,
                )
            }
            composable(
                route     = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                AlbumDetailScreen(playerVm = playerVm, onBack = { navController.popBackStack() },
                    onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                    onAlbumClick  = { navController.navigate(Screen.AlbumDetail.route(it)) })
            }
            composable(
                route     = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                ArtistDetailScreen(
                    playerVm = playerVm,
                    onAlbumClick  = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                )
            }
            composable(
                route     = Screen.PlaylistDetail.route,
                arguments = listOf(
                    navArgument("playlistId")   { type = NavType.StringType },
                    navArgument("playlistName") { type = NavType.StringType },
                    navArgument("artworkUrl")   { type = NavType.StringType },
                ),
            ) { back ->
                val id         = URLDecoder.decode(back.arguments?.getString("playlistId")   ?: "", "UTF-8")
                val name       = URLDecoder.decode(back.arguments?.getString("playlistName") ?: "", "UTF-8")
                val artworkUrl = URLDecoder.decode(back.arguments?.getString("artworkUrl")   ?: "", "UTF-8")
                PlaylistDetailScreen(
                    playlistId    = id,
                    playlistName  = name,
                    artworkUrl    = artworkUrl.ifEmpty { null },
                    playerVm      = playerVm,
                    onBack        = { navController.popBackStack() },
                    onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                    onAlbumClick  = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onMusicVideoClick = { s ->
                        mvVm.show(s.id, s.title, s.artistName)
                        selectedTab = TopNavTab.NowPlaying
                        navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                    },
                )
            }
        }
        // MUT expired banner
        if (playerState.mutExpired) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = navBarHeight + 8.dp).fillMaxWidth(0.5f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFB22222))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text("Token expired — re-enter at :8080", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                    androidx.tv.material3.Surface(
                        onClick = { playerVm.dismissMutExpired() },
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(50)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color(0x33FFFFFF), focusedContainerColor = androidx.compose.ui.graphics.Color(0x55FFFFFF)),
                    ) { androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.CLOSE, size = 13.dp, color = androidx.compose.ui.graphics.Color.White)
                    } }
                }
            }
        }

        // Preview mode banner
        if (!playerState.isFullStream && playerState.currentSong != null) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFB22222))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                androidx.compose.material3.Text(
                    "PLAYING PREVIEWS — set token at :8080",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
        }

        // Exit dialog
        if (showExitDialog) {
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
            // A plain overlay Box let D-pad focus walk out to the nav bar behind it.
            // Dialog owns its own window, so focus is trapped where it belongs.
            androidx.compose.ui.window.Dialog(onDismissRequest = { showExitDialog = false }) {
                Box(
                    modifier = Modifier.background(Color(0x00000000)),
                    contentAlignment = Alignment.Center,
                ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1C1C1E))
                        .padding(horizontal = 48.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    androidx.compose.material3.Text(
                        "Exit Apple Music TV?",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        androidx.tv.material3.Surface(
                            onClick = { showExitDialog = false },
                            modifier = Modifier.focusRequester(cancelFocus),
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF3A3A3C),
                                focusedContainerColor = Color(0xFF5A5A5C),
                            ),
                        ) {
                            androidx.compose.material3.Text(
                                "Cancel",
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                            )
                        }
                        androidx.tv.material3.Surface(
                            onClick = {
                                showExitDialog = false
                                // A real exit must stop the audio: the player lives in the ViewModel,
                                // which survives moveTaskToBack/finish, so without this the music kept
                                // playing after "Exit".
                                playerVm.stopPlayback()
                                // Also stop a music video — it runs on its own player, so stopPlayback
                                // (audio only) left its audio going after Exit.
                                playerVm.clearVideoRequest(); mvVm.close()
                                // If there's no activity to background (or the task is
                                // already at the root), fall back to finish() so the
                                // popup never just sits there doing nothing.
                                if (activity?.moveTaskToBack(true) != true) activity?.finish()
                            },
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFFB22222),
                                focusedContainerColor = Color(0xFFD44040),
                            ),
                        ) {
                            androidx.compose.material3.Text(
                                "Exit",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                            )
                        }
                        }
                    }
                }
            }
        }

        // Nav bar on top layer (drawn after content). Hidden entirely on the
        // fullscreen music-video route so it can't steal D-pad focus.
        // Single persistent video surface. ONE PlayerView node that resizes between fullscreen
        // (Now Playing tab) and an in-app PiP corner — never torn down while a video is active,
        // so the codec never loses its surface (that was the "Can't play this video" crash).
        // A secure (HDCP) SurfaceView can't be confined to a Compose corner — it draws across
        // whatever screen you're on. So the video surface renders ONLY fullscreen on Now Playing.
        // On other tabs the video KEEPS PLAYING (audio; the picture is simply not drawn) — it is
        // not closed — and reappears when you return to Now Playing. On-screen PiP over the app
        // isn't possible for protected video; the OS system PiP is the "picture while browsing".
        // Secure video surface — the "video in library" bleed, finally understood. A secure (HDCP)
        // SurfaceView is a whole-screen hardware overlay. The bleed was Compose DISPOSING that
        // SurfaceView when we left Now Playing (the old `&& isOnNowPlaying` gate): on Fire TV,
        // destroying a SurfaceView mid-secure-frame ORPHANS its SurfaceFlinger layer, which then
        // lingers as a frozen top overlay over Library/Browse (the black flash on return is a new
        // surface replacing it). The fix: keep the PlayerView COMPOSED the entire time a video is
        // active (gate on videoActive only) and merely toggle VISIBILITY. The view keeps managing
        // its layer, so GONE reaps it cleanly instead of orphaning it. detachVideo() (via the
        // LaunchedEffect above) disables the video track off-screen so the secure decoder stops while
        // audio keeps playing; attachVideo() re-enables it on return.
        if (videoActive) {
            val mvPlayer by mvVm.playerFlow.collectAsState()
            // The library "bleed" is a Fire TV compositor bug: a secure (HDCP) SurfaceView placed on
            // the media-overlay plane (setZOrderMediaOverlay) keeps its LAST protected frame latched in
            // that hardware plane. View flags (GONE) and track-disable don't reap it, so it lingers on
            // top of Library/Browse. The only reliable teardown is to DESTROY the SurfaceView — but that
            // orphans the plane too if a secure frame is still latched at destroy time. So the sequence
            // is: (1) detachVideo() rebuilds the player audio-only SYNCHRONOUSLY, releasing the secure
            // decoder and clearing the surface, THEN (2) unmount the PlayerView so its now content-free
            // SurfaceView is destroyed cleanly and the plane is freed. Audio never stops.
            var surfaceMounted by remember { mutableStateOf(false) }
            LaunchedEffect(isOnNowPlaying) {
                if (isOnNowPlaying) {
                    surfaceMounted = true             // mount; attachVideo runs once the surface exists
                } else {
                    mvVm.detachVideo()                // release secure decoder + clear surface (sync)
                    surfaceMounted = false            // then destroy the content-free SurfaceView
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (surfaceMounted) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            androidx.media3.ui.PlayerView(ctx).apply {
                                useController = false
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setKeepScreenOn(true)
                                // DO NOT setZOrderMediaOverlay/OnTop(true). That puts the secure SurfaceView
                                // on a hardware plane ABOVE the window, which bleeds over Library and can't be
                                // reaped by any View teardown. DEFAULT z-order sits it BEHIND the window,
                                // punching a hole only where mounted — unmounting it off Now Playing removes
                                // the hole, so nothing can draw over other tabs.
                                (videoSurfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(false)
                                player = mvPlayer
                            }
                        },
                        update = { pv ->
                            android.util.Log.i("AMMV", "surface mounted: route=$currentRoute")
                            pv.player = mvPlayer
                            pv.visibility = android.view.View.VISIBLE
                            mvVm.attachVideo()        // rebuild WITH video (no-op on first play)
                        },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isOnNowPlaying) {
                    MusicVideoScreen(
                        vm = mvVm,
                        // Back leaves the SCREEN but KEEPS the video playing — pop to the previous
                        // route (e.g. the playlist). The video is hidden while its audio continues,
                        // and reappears on return. It closes only on a regular song / queue end.
                        onExit = { if (!navController.popBackStack()) { selectedTab = TopNavTab.ListenNow; navController.navigate(Screen.Home.route) { launchSingleTop = true } } },
                        onFocusUp = { runCatching { navBarFocus.requestFocus() } },
                        onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                        // Google TV remotes lack media keys → draw prev/play/next on screen.
                        showOnScreenControls = com.applemusicktv.util.TvDevice.needsOnScreenMenuToggle(appContext, playerVm.remoteOverride()),
                        queue = playerState.queue,
                        queueIndex = playerState.queueIndex,
                        onPickQueueItem = { playerVm.playFromQueue(it) },
                        focusRequester = videoFocus,
                    )
                }
            }
        }

        Box(
            modifier = Modifier.align(Alignment.TopCenter).focusRequester(navBarFocus).focusGroup()
                // When a fullscreen video is up, Down from the nav bar returns to the video controls.
                .focusProperties { if (videoActive && isOnNowPlaying) down = videoFocus },
        ) {
            TopNavBar(
                selected  = selectedTab,
                // Waveform animates for video playback too, not just audio.
                isPlaying = playerState.isPlaying || (videoActive && mvState.playing),
                updateAvailable = pendingUpdate != null,
                onSelect = { tab ->
                    selectedTab = tab
                    val route = when (tab) {
                        TopNavTab.ListenNow  -> Screen.Home.route
                        TopNavTab.Browse     -> Screen.Browse.route
                        TopNavTab.Library    -> Screen.Library.route
                        TopNavTab.Search     -> Screen.Search.route
                        TopNavTab.NowPlaying -> Screen.NowPlaying.route
                        TopNavTab.Dev        -> Screen.DevMenu.route
                    }
                    // Don't re-navigate to the tab we're already on — that
                    // rebuilds the screen and resets Library's sub-section back
                    // to the default. Only navigate when actually switching.
                    val onThisTab = currentRoute == route ||
                        (route == Screen.Library.route && currentRoute?.startsWith("library") == true)
                    if (!onThisTab) {
                        navController.navigate(route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    }
                },
            )
        }

    }
}

/** Minimal Picture-in-Picture card: the album art darkened, with the title + artist over it. */
@Composable
private fun PipView(song: Song?) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (song?.artworkUrl != null) {
            AsyncImage(
                model = song.artworkUrl(400),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Darken so the text stays legible at thumbnail size.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }
        if (song != null) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    song.title, maxLines = 2,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        shadow = Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 8f)),
                )
                Text(
                    song.artistName, maxLines = 1,
                    style = TextStyle(fontSize = 12.sp, color = Color(0xCCFFFFFF),
                        shadow = Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 8f)),
                )
            }
        }
    }
}
