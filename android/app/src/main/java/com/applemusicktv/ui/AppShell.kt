package com.applemusicktv.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.applemusicktv.ui.components.NowPlayingBar
import com.applemusicktv.ui.components.TopNavBar
import com.applemusicktv.ui.navigation.Screen
import com.applemusicktv.ui.navigation.TopNavTab
import com.applemusicktv.ui.screens.*
import java.net.URLDecoder
import com.applemusicktv.ui.viewmodel.LibraryViewModel
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@Composable
fun AppShell(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var selectedTab   by remember { mutableStateOf(TopNavTab.ListenNow) }
    val playerVm: PlayerViewModel  = hiltViewModel()
    val navVm: NavigationViewModel = hiltViewModel()
    // Hoist LibraryViewModel so library loads on startup, not when tab is first opened
    val libraryVm: LibraryViewModel = hiltViewModel()

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val isOnNowPlaying = currentRoute == Screen.NowPlaying.route

    LaunchedEffect(isOnNowPlaying) { navVm.isOnNowPlaying = isOnNowPlaying }

    // Keep screen on while music is playing; stay on indefinitely on Now Playing tab.
    val playerState by playerVm.state.collectAsState()
    val keepScreenOn = playerState.isPlaying || isOnNowPlaying
    val activity = LocalContext.current as? Activity
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val goToNowPlaying by navVm.goToNowPlaying.collectAsState()
    LaunchedEffect(goToNowPlaying) {
        if (goToNowPlaying) {
            selectedTab = TopNavTab.NowPlaying
            navController.navigate(Screen.NowPlaying.route) {
                popUpTo(Screen.Home.route) { saveState = true }
                launchSingleTop = true; restoreState = true
            }
            navVm.consumeNowPlayingNavigation()
        }
    }

    // Overlay layout: content fills the whole screen (so Now Playing's
    // background can run fullscreen to the very top), with the nav bar drawn
    // last → it sits on a higher layer. Non-fullscreen screens get top padding
    // equal to the bar so their content isn't hidden underneath it.
    val navBarHeight = 64.dp
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
                SearchScreen(playerVm = playerVm, onAlbumClick = { navController.navigate(Screen.AlbumDetail.route(it)) })
            }
            composable(Screen.NowPlaying.route) { NowPlayingScreen(playerVm = playerVm, navVm = navVm) }
            composable(Screen.DevMenu.route)    { DevMenuScreen(playerVm = playerVm) }
            composable(
                route     = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                AlbumDetailScreen(playerVm = playerVm, onBack = { navController.popBackStack() },
                    onArtistClick = { navController.navigate(Screen.ArtistDetail.route(it)) })
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
                    playlistId   = id,
                    playlistName = name,
                    artworkUrl   = artworkUrl.ifEmpty { null },
                    playerVm     = playerVm,
                    onBack       = { navController.popBackStack() },
                )
            }
        }
        // Nav bar on top layer (drawn after content).
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            TopNavBar(
                selected = selectedTab,
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

        if (!isOnNowPlaying) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                NowPlayingBar(playerVm = playerVm)
            }
        }
    }
}
