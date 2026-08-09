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
    // Hoist LibraryViewModel so library loads on startup, not when tab is first opened
    val libraryVm: LibraryViewModel = hiltViewModel()
    // Hoisted so a server re-check from the Dev menu can refetch both screens —
    // reconnecting is useless if the stale "server down" content stays on screen.
    val homeVm: com.applemusicktv.ui.viewmodel.HomeViewModel = hiltViewModel()

    // First run: setup owns the whole screen. Nothing behind it is usable until the
    // server and token are configured, so there is no nav bar and nothing to browse.
    val onboardingVm: com.applemusicktv.ui.viewmodel.OnboardingViewModel = hiltViewModel()
    var showOnboarding by remember { mutableStateOf(!playerVm.onboardingCompleted()) }
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

    LaunchedEffect(isOnNowPlaying) { navVm.isOnNowPlaying = isOnNowPlaying }

    // Keep the screen awake ONLY while music is actually playing. When paused, drop the
    // flag so Fire TV's own screensaver / sleep can take over (our ambient screensaver only
    // ever runs while playing).
    val playerState by playerVm.state.collectAsState()
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
                )
            }
            composable(Screen.NowPlaying.route) {
                NowPlayingScreen(
                    playerVm = playerVm,
                    navVm = navVm,
                    onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) },
                    onAlbumClick  = { navController.navigate(Screen.AlbumDetail.route(it)) },
                )
            }
            composable(Screen.Radio.route) {
                RadioScreen(playerVm = playerVm)
            }
            composable(Screen.DevMenu.route)    {
                DevMenuScreen(
                    playerVm = playerVm,
                    onDataRefresh = { homeVm.load(); libraryVm.refresh() },
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
                    ) { androidx.compose.material3.Text("✕", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
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

        // Nav bar on top layer (drawn after content).
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            TopNavBar(
                selected  = selectedTab,
                isPlaying = playerState.isPlaying,
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
