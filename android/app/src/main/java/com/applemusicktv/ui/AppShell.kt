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
import androidx.navigation.NavGraph.Companion.findStartDestination
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
    // When we navigate away from the fullscreen video to a pushed screen (Go to Artist), the new
    // screen shows a non-focusable spinner for a beat, so D-pad focus escapes UP to the nav bar and
    // the SAME OK press that opened the artist bleeds a click onto the leftmost tab (Listen Now) →
    // navigate(Home). That's the "MV → Go to Artist jumps to Home" bug. Swallow a nav-bar select that
    // lands within this window of such a navigation.
    var lastVideoNavAwayMs by remember { mutableStateOf(0L) }
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

    // Silently poll GitHub Releases at launch and every 3h after (a TV app stays open for
    // days, and the rolling `dev` build rolls forward constantly — a once-per-launch check
    // never sees those). If a newer build exists, a red dot shows on the ⚙ tab and the
    // Settings → Software section is pre-filled with it.
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var pendingUpdate by remember { mutableStateOf<com.applemusicktv.util.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val beta = com.applemusicktv.util.UpdatePreferences.betaEnabled(appCtx)
            com.applemusicktv.util.UpdateChecker.check(beta).onSuccess { pendingUpdate = it }
            kotlinx.coroutines.delay(3 * 60 * 60 * 1000L)
        }
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
            Screen.Radio.route     -> selectedTab = TopNavTab.Radio
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
            android.util.Log.i("AMHome", "goToNowPlaying effect FIRED (route=$currentRoute) — may popUpTo(home)")
            selectedTab = TopNavTab.NowPlaying
            // If we got here *from* Now Playing (e.g. Now Playing → Artist, then
            // Menu), pop back to that instance so it keeps its state instead of
            // pushing a second copy on top and growing the back stack.
            if (!navController.popBackStack(Screen.NowPlaying.route, inclusive = false)) {
                // Save the tab we're leaving (with its playlist/artist/etc.) so returning to it
                // restores that page — same per-tab-back-stack behaviour as the nav bar.
                navController.navigate(Screen.NowPlaying.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
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
    // TEMP trace: log every destination change to find the MV→artist "jumps to Home" path.
    DisposableEffect(navController) {
        android.util.Log.i("AMNav", "listener ATTACHED to nav#${System.identityHashCode(navController)}")
        val l = androidx.navigation.NavController.OnDestinationChangedListener { c, dest, _ ->
            android.util.Log.i("AMNav", "-> ${dest.route}  (videoActive=$videoActive) nav#${System.identityHashCode(c)}")
        }
        navController.addOnDestinationChangedListener(l)
        onDispose { navController.removeOnDestinationChangedListener(l) }
    }
    BackHandler(enabled = !showExitDialog && currentRoute == Screen.Home.route) {
        showExitDialog = true
    }
    // Every other top-level tab returns to Listen Now first — only Listen Now itself
    // offers to exit. Detail screens keep the normal pop behaviour.
    val topLevelTabs = setOf(
        Screen.Browse.route, Screen.Library.route, Screen.Search.route,
        Screen.Radio.route, Screen.DevMenu.route,
    )
    BackHandler(enabled = !showExitDialog && currentRoute in topLevelTabs) {
        selectedTab = TopNavTab.ListenNow
        navController.navigate(Screen.Home.route) {
            popUpTo(Screen.Home.route) { inclusive = true }
            launchSingleTop = true
        }
    }
    // Now Playing (for a SONG) pops back to wherever you opened it from — same as the video player —
    // instead of collapsing to Listen Now. The video path runs its own Back in MusicVideoScreen, so
    // this only handles the audio case. Nothing below → fall back to Listen Now.
    BackHandler(enabled = !showExitDialog && currentRoute == Screen.NowPlaying.route && !videoActive) {
        if (!navController.popBackStack()) {
            selectedTab = TopNavTab.ListenNow
            navController.navigate(Screen.Home.route) { popUpTo(Screen.Home.route) { inclusive = true }; launchSingleTop = true }
        }
    }

    // Animated artwork on EVERY shelf: cards resolve their motion loop lazily on focus. One shared
    // cache (positive + negative) so refocusing a card never refetches. Only the focused card ever
    // holds a decoder — Fire TV has no memory to spare.
    val motionCache = remember { mutableMapOf<String, String?>() }
    val cardMotionResolver: suspend (com.applemusicktv.data.model.Album) -> String? = remember {
        { album ->
            // cardMotion() returns null when the user's "Motion artwork" setting is off, so animated
            // card art follows that toggle too (no card video decoders spin up when it's off).
            if (motionCache.containsKey(album.id)) motionCache[album.id]
            else playerVm.cardMotion(album).also { motionCache[album.id] = it }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.applemusicktv.ui.components.LocalCardMotion provides cardMotionResolver,
    ) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier
                .fillMaxSize()
                .padding(top = if (isOnNowPlaying) 0.dp else navBarHeight),
        ) {
            composable(Screen.Home.route) {
                val newUi = playerVm.state.collectAsState().value.newUiEnabled
                val onAlbum: (String) -> Unit = { navController.navigate(Screen.AlbumDetail.route(it)) }
                val onPlaylist: (String, String, String) -> Unit = { id, name, artworkUrl ->
                    navController.navigate(Screen.PlaylistDetail.route(id, name, artworkUrl))
                }
                // "Find Your Mood" cards are already prefixed (ac-/c-/mr-) for CategoryScreen.
                val onCategory: (String) -> Unit = { navController.navigate(Screen.Category.route(it)) }
                if (newUi) {
                    com.applemusicktv.ui.screens.HomeScreenV2(
                        playerVm = playerVm, vm = homeVm,
                        onAlbumClick = onAlbum, onPlaylistClick = onPlaylist, onCategoryClick = onCategory,
                    )
                } else {
                    HomeScreen(
                        playerVm = playerVm, vm = homeVm,
                        onAlbumClick = onAlbum, onPlaylistClick = onPlaylist, onCategoryClick = onCategory,
                    )
                }
            }
            composable(Screen.Browse.route) {
                val newUi = playerVm.state.collectAsState().value.newUiEnabled
                val onAlbum: (String) -> Unit = { navController.navigate(Screen.AlbumDetail.route(it)) }
                val onPlaylist: (String, String, String) -> Unit = { id, name, art -> navController.navigate(Screen.PlaylistDetail.route(id, name, art)) }
                val onCurator: (String) -> Unit = { navController.navigate(Screen.Category.route(it)) }
                // "More" at the end of a shelf → that shelf's full editorial room page.
                val onSeeAll: (String) -> Unit = { navController.navigate(Screen.Category.route("room-$it")) }
                if (newUi) {
                    com.applemusicktv.ui.screens.BrowseScreenV2(
                        playerVm = playerVm,
                        onAlbumClick = onAlbum, onPlaylistClick = onPlaylist,
                        onCuratorClick = onCurator, onSeeAll = onSeeAll,
                        onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                    )
                } else {
                    BrowseScreen(
                        playerVm       = playerVm,
                        onAlbumClick   = onAlbum,
                        onPlaylistClick = onPlaylist,
                        onGenreClick   = { id, name -> navController.navigate(Screen.Genre.route(id, name)) },
                        onCuratorClick = onCurator,
                        onSeeAll       = onSeeAll,
                    )
                }
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
                    onCuratorClick  = { navController.navigate(Screen.Category.route(it)) },
                    onArtistClick   = { navController.navigate(Screen.ArtistDetail.route(it)) },
                    playerVm        = playerVm,
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
                    // Route videos through the SHARED PlayerViewModel queue (not mvVm.show directly) —
                    // otherwise playerState.queue stays the stale audio queue and the MV Up-Next panel
                    // shows no current video, no reg songs, and never updates. playAlbum sees the video,
                    // sets the queue, and emits the videoRequest that auto-opens Now Playing.
                    onMusicVideoClick = { s -> playerVm.playAlbum(listOf(s)) },
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
                            "room"          -> "room-"
                            "grouping"      -> "grouping-"
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
                RadioScreen(
                    playerVm        = playerVm,
                    onAlbumClick    = { navController.navigate(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> navController.navigate(Screen.PlaylistDetail.route(id, name, art)) },
                    onCuratorClick  = { navController.navigate(Screen.Category.route(it)) },
                    onArtistClick   = { navController.navigate(Screen.ArtistDetail.route(it)) },
                )
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
                val newUi = playerVm.state.collectAsState().value.newUiEnabled
                val onAlbum: (String) -> Unit = { navController.navigate(Screen.AlbumDetail.route(it)) }
                val onArtist: (String) -> Unit = { navController.navigate(Screen.ArtistDetail.route(it)) }
                if (newUi) {
                    com.applemusicktv.ui.screens.ArtistDetailScreenV2(
                        playerVm = playerVm, onAlbumClick = onAlbum, onArtistClick = onArtist,
                        onPlaylistClick = { id, name, art -> navController.navigate(Screen.PlaylistDetail.route(id, name, art)) },
                    )
                } else {
                    ArtistDetailScreen(
                        playerVm = playerVm, onAlbumClick = onAlbum, onArtistClick = onArtist,
                    )
                }
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
                    onMusicVideoClick = { s -> playerVm.playAlbum(listOf(s)) },
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
            val lowPower = playerState.lowPowerMode
            // Two strategies, chosen by the Low Power toggle:
            //  • Low Power ON  → FREE the secure decoder off Now Playing (detach video track + unmount
            //    the SurfaceView). Lightest on a starved Fire TV, but re-acquiring the codec blips ~0.5s
            //    on return.
            //  • Low Power OFF (default) → keep the decoder ALIVE: the PlayerView stays mounted the whole
            //    time a video is active, just shrunk to 1px BEHIND the window off Now Playing (default
            //    z-order = behind, so the opaque tab fully covers it — no bleed, no dispose-orphan). The
            //    surface keeps compositing (no latched stale frame), so returning is instant.
            var surfaceMounted by remember { mutableStateOf(false) }
            // ALWAYS free the secure decoder + unmount the SurfaceView when off Now Playing — the only
            // teardown that reliably stops the protected frame bleeding onto other tabs (Videos/Library
            // /Browse). The old seamless path (keep the decoder alive, shrink to 1px behind the window)
            // let the video show through the new Videos tab. Costs ~0.5s codec re-acquire on return;
            // worth it to kill the bleed for good. Audio never stops (detachVideo rebuilds audio-only).
            // Bleed fix v3 — the two earlier strategies both failed: (a) 1px-BEHIND-the-window kept the
            // secure layer compositing and it bled through the Videos tab; (b) DESTROY-on-leave orphaned
            // the secure SurfaceFlinger layer (Fire TV keeps the last protected buffer latched). This
            // path does neither: while a video is active the PlayerView stays MOUNTED (never destroyed →
            // no orphan), but off Now Playing we (1) rebuild audio-only so the secure video decoder is
            // released — there is NO protected frame left to latch — and (2) move the whole surface far
            // OFF-SCREEN (not 1px at the origin), so its compositor hole is nowhere on the visible screen.
            // Return to Now Playing re-attaches video in place. Audio never stops.
            LaunchedEffect(isOnNowPlaying, videoActive) {
                if (!videoActive) { surfaceMounted = false; return@LaunchedEffect }
                // Bleed fix, Avenue 4 (HARD STOP). This MTK chip forces the secure decoder → protected
                // plane bleed that nothing at the surface/track/DRM layer could reap. So on leaving Now
                // Playing we RELEASE the codec entirely (MV audio stops too — accepted) and UNMOUNT the
                // surface; returning rebuilds at the saved position (~1s). No live secure decoder off
                // Now Playing = no bleed, no Bluetooth stutter.
                if (isOnNowPlaying) { surfaceMounted = true; mvVm.resumeVideo() }
                else {
                    mvVm.hardStopVideo(); surfaceMounted = false
                    // MTK secure decoder can't be shown off Now Playing (bleed). Tell the user the
                    // picture paused; audio keeps going. Return to Now Playing to see it again.
                    android.widget.Toast.makeText(
                        appContext, "Video paused — audio still playing", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (surfaceMounted) {
                    // BLEED FIX — render the video to a TextureView, not a (secure) SurfaceView.
                    // This display has no HDCP link, so Widevine already falls back to L3 (software
                    // decrypt → non-secure output buffers). A TextureView draws entirely inside the
                    // Android view hierarchy, so it physically CANNOT punch a hole through the UI or
                    // latch a frame on a SurfaceFlinger overlay — the whole class of "bleed onto other
                    // tabs" goes away. Controls are drawn in Compose (MusicVideoScreen), so no
                    // Media3 PlayerView is needed; a raw TextureView + setVideoTextureView is enough.
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.view.TextureView(ctx).apply {
                                layoutParams = android.widget.FrameLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setKeepScreenOn(true)
                            }
                        },
                        update = { tv ->
                            // Bind the current player (rebuilt by detach/attach) to this TextureView.
                            runCatching { mvPlayer?.setVideoTextureView(tv) }
                        },
                        onRelease = { tv ->
                            runCatching { mvPlayer?.clearVideoTextureView(tv) }
                        },
                        // Off Now Playing: move it off-screen so the picture isn't visible on other
                        // tabs (a TextureView can't bleed, but it's still a live view — keep it out of
                        // the way). On Now Playing: fullscreen.
                        modifier = if (!isOnNowPlaying) Modifier.absoluteOffset(x = 6000.dp).size(1.dp)
                                   else Modifier.fillMaxSize(),
                    )
                }
                if (isOnNowPlaying) {
                    MusicVideoScreen(
                        vm = mvVm,
                        // Back leaves the SCREEN but KEEPS the video playing — pop to the previous
                        // route (e.g. the playlist). The video is hidden while its audio continues,
                        // and reappears on return. It closes only on a regular song / queue end.
                        onExit = {
                            // Only pop when we're STILL on Now Playing. navigate(artist) from the video's
                            // "Go to Artist" flips the route async — for a frame this screen is still
                            // composed and focused, and a Back arriving in that window would pop the just-
                            // opened artist back past Now Playing to Home. currentRoute is the freshest
                            // read, so ignore a Back that lands after we've already navigated away.
                            android.util.Log.i("AMHome", "onExit route=$currentRoute (pop=${currentRoute == Screen.NowPlaying.route})")
                            if (currentRoute == Screen.NowPlaying.route) {
                                if (!navController.popBackStack()) { selectedTab = TopNavTab.ListenNow; navController.navigate(Screen.Home.route) { launchSingleTop = true } }
                            }
                        },
                        onFocusUp = { runCatching { navBarFocus.requestFocus() } },
                        onArtistClick = { lastVideoNavAwayMs = System.currentTimeMillis(); navController.navigate(Screen.ArtistDetail.route(it)) },
                        // Google TV remotes lack media keys → draw prev/play/next on screen.
                        showOnScreenControls = com.applemusicktv.util.TvDevice.needsOnScreenMenuToggle(appContext, playerVm.remoteOverride()),
                        queue = playerState.queue,
                        queueIndex = playerState.queueIndex,
                        userQueue = playerState.userQueue,
                        onPickQueueItem = { playerVm.playFromQueue(it) },
                        onPickUserQueue = { playerVm.playFromUserQueue(it) },
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
                beatAnalyzer = playerVm.beatAnalyzer,
                newUi = playerState.newUiEnabled,
                onSelect = onSelect@ { tab ->
                    // Swallow a stray click bled onto the nav bar right after a video→pushed-screen
                    // navigation (focus escaped here while the new screen was still loading). Without
                    // this, "Go to Artist" from a video lands on Listen Now → Home.
                    if (System.currentTimeMillis() - lastVideoNavAwayMs < 700) {
                        android.util.Log.i("AMHome", "nav-bar select($tab) swallowed (stray click after video nav-away)")
                        return@onSelect
                    }
                    selectedTab = tab
                    val route = when (tab) {
                        TopNavTab.ListenNow  -> Screen.Home.route
                        TopNavTab.Browse     -> Screen.Browse.route
                        // New-UI-only tabs. Videos = the Music Videos grouping (34) rendered as a category.
                        TopNavTab.Videos     -> Screen.Category.route("grouping-34")
                        TopNavTab.Radio      -> Screen.Radio.route
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
                        // Per-tab back stacks: each tab remembers the page you left it on (a playlist,
                        // an artist, a category) and restores it when you return — including returning
                        // from Now Playing. This is the canonical multi-back-stack recipe. Crucially
                        // EVERY tab (Now Playing included) pops up to the start destination with
                        // saveState, so Now Playing gets its OWN saved stack and can never sit nested
                        // under another tab — which is what used to bleed the video screen into Library.
                        val startId = navController.graph.findStartDestination().id
                        navController.navigate(route) {
                            popUpTo(startId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        }

    }
    } // CompositionLocalProvider(LocalCardMotion)
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
