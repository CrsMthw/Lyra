package com.crsmthw.lyra.ui.navigation

import android.content.Intent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.crsmthw.lyra.util.visualizer.LocalFftData
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor
import com.crsmthw.lyra.util.visualizer.LocalVisualizerBottomEnabled
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.screens.album.AlbumDetailScreen
import com.crsmthw.lyra.ui.screens.deeplink.TrackDeepLinkScreen
import com.crsmthw.lyra.ui.screens.album.AlbumDetailViewModel
import com.crsmthw.lyra.ui.screens.album.AlbumDetailViewModelFactory
import com.crsmthw.lyra.ui.screens.artist.ArtistDetailScreen
import com.crsmthw.lyra.ui.screens.artist.ArtistDetailViewModel
import com.crsmthw.lyra.ui.screens.artist.ArtistDetailViewModelFactory
import com.crsmthw.lyra.ui.screens.auth.AuthScreen
import com.crsmthw.lyra.ui.screens.library.LibraryScreen
import com.crsmthw.lyra.ui.screens.library.LibraryViewModel
import com.crsmthw.lyra.ui.screens.library.LibraryViewModelFactory
import com.crsmthw.lyra.ui.screens.player.PlayerScreen
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.screens.player.PlayerViewModelFactory
import com.crsmthw.lyra.ui.screens.queue.QueueScreen
import com.crsmthw.lyra.ui.screens.queue.QueueViewModel
import com.crsmthw.lyra.ui.screens.queue.QueueViewModelFactory
import com.crsmthw.lyra.ui.screens.search.SearchScreen
import com.crsmthw.lyra.ui.screens.search.SearchViewModel
import com.crsmthw.lyra.ui.screens.search.SearchViewModelFactory
import com.crsmthw.lyra.ui.screens.settings.SettingsScreen
import com.crsmthw.lyra.ui.screens.settings.SettingsViewModel
import com.crsmthw.lyra.ui.screens.settings.SettingsViewModelFactory

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LyraNavGraph(container: AppContainer, pendingDeepLinkIntent: Intent? = null) {
    val navController: NavHostController = rememberNavController()
    val playerVm = viewModel<PlayerViewModel>(factory = PlayerViewModelFactory(container))

    // Handle deep links on warm start (activity already running, onNewIntent fired).
    // Cold-start deep links are handled automatically by NavHost reading Activity.intent.
    LaunchedEffect(pendingDeepLinkIntent) {
        pendingDeepLinkIntent?.let { navController.handleDeepLink(it) }
    }

    // ── Global back-tap debounce guard ───────────────────────────────────────
    var lastNavTime by remember { mutableLongStateOf(0L) }

    fun safeNavigateUp() {
        val now = System.currentTimeMillis()
        if (now - lastNavTime >= 350L) {
            lastNavTime = now
            navController.navigateUp()
        }
    }

    fun safePush(route: String) {
        val now = System.currentTimeMillis()
        if (now - lastNavTime >= 350L) {
            lastNavTime = now
            navController.navigate(route) {
                launchSingleTop = true
                restoreState    = true
            }
        }
    }

    val startDestination = if (container.authManager.isAuthenticated())
        Screen.Library.route
    else
        Screen.Auth.route

    val context = LocalContext.current
    val isDarkTheme = MaterialTheme.colorScheme.background.let {
        0.299f * it.red + 0.587f * it.green + 0.114f * it.blue < 0.5f
    }
    val artUrl by remember {
        playerVm.uiState.map { it.currentTrack?.artUrl }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    var rawVisualizerAccentColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(artUrl, isDarkTheme) {
        val url = artUrl.takeIf { !it.isNullOrBlank() } ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.IO) {
            try {
                val loader = SingletonImageLoader.get(context)
                val result = loader.execute(ImageRequest.Builder(context).data(url).build())
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    Palette.from(bitmap).generate()
                } else null
            } catch (_: Exception) { null }
        }
        if (palette != null) {
            val fallback = palette.getDominantColor(0xFF1DB954.toInt())
            rawVisualizerAccentColor = if (isDarkTheme) {
                Color(palette.getLightVibrantColor(palette.getVibrantColor(palette.getLightMutedColor(fallback))))
            } else {
                Color(palette.getDarkVibrantColor(palette.getVibrantColor(palette.getDarkMutedColor(fallback))))
            }
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val visualizerAccentColor by animateColorAsState(
        targetValue   = rawVisualizerAccentColor ?: primary,
        animationSpec = tween(800),
        label         = "visualizerAccent",
    )

    // Gate every bottom FftWaveCanvas (across all screens) from one place: shown only
    // when the visualizer is enabled and the chosen style includes the bottom wave.
    val bottomVisualizerEnabled by remember {
        combine(
            container.settingsRepository.visualizerEnabled,
            container.settingsRepository.visualizerStyle,
        ) { enabled, style -> enabled && style.showBottom }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)

    CompositionLocalProvider(
        LocalFftData provides container.visualizerManager.fftData,
        LocalVisualizerAccentColor provides visualizerAccentColor,
        LocalVisualizerBottomEnabled provides bottomVisualizerEnabled,
    ) {
    SharedTransitionLayout {
        NavHost(
            navController        = navController,
            startDestination     = startDestination,
            enterTransition      = { slideInHorizontally  { it / 4 } + fadeIn()  },
            exitTransition       = { slideOutHorizontally { -(it / 4) } + fadeOut() },
            popEnterTransition   = { slideInHorizontally  { -(it / 4) } + fadeIn()  },
            popExitTransition    = { slideOutHorizontally { it / 4 } + fadeOut() },
        ) {

            composable(Screen.Auth.route) {
                AuthScreen(
                    encryptedPrefs  = container.encryptedPrefs,
                    authManager     = container.authManager,
                    onAuthenticated = {
                        navController.navigate(Screen.Library.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Screen.Library.route) {
                val vm = viewModel<LibraryViewModel>(factory = LibraryViewModelFactory(container))
                LibraryScreen(
                    viewModel             = vm,
                    playerViewModel       = playerVm,
                    onOpenPlayer          = { safePush(Screen.Player.route) },
                    onOpenSearch          = { safePush(Screen.Search.route) },
                    onOpenSettings        = { safePush(Screen.Settings.route) },
                    onOpenQueue           = { safePush(Screen.Queue.route) },
                    onOpenAlbum           = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                    onOpenArtist          = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope  = this@composable,
                )
            }

            composable(Screen.Player.route) {
                PlayerScreen(
                    viewModel             = playerVm,
                    onBack                = ::safeNavigateUp,
                    onOpenQueue           = { safePush(Screen.Queue.route) },
                    onOpenAlbum           = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                    onOpenArtist          = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope  = this@composable,
                )
            }

            composable(Screen.Queue.route) {
                val vm = viewModel<QueueViewModel>(factory = QueueViewModelFactory(container))
                QueueScreen(
                    viewModel    = vm,
                    onBack       = ::safeNavigateUp,
                    onOpenAlbum  = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                    onOpenArtist = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                )
            }

            composable(Screen.Search.route) {
                val vm = viewModel<SearchViewModel>(factory = SearchViewModelFactory(container))
                SearchScreen(
                    viewModel     = vm,
                    onBack        = ::safeNavigateUp,
                    onOpenPlayer  = { safePush(Screen.Player.route) },
                    onAlbumClick  = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                    onArtistClick = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                    onTrackClick  = { uri, uris ->
                        playerVm.playTrack(uri, uris = uris)
                        safePush(Screen.Player.route)
                    },
                )
            }

            composable(
                route      = Screen.AlbumDetail.route,
                arguments  = listOf(navArgument("id") { type = NavType.StringType }),
                deepLinks  = listOf(navDeepLink { uriPattern = "https://open.spotify.com/album/{id}" }),
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("id") ?: return@composable
                val vm = viewModel<AlbumDetailViewModel>(
                    factory = AlbumDetailViewModelFactory(container, albumId)
                )
                AlbumDetailScreen(
                    viewModel             = vm,
                    playerViewModel       = playerVm,
                    onBack                = ::safeNavigateUp,
                    onNavigateToPlayer    = { safePush(Screen.Player.route) },
                    onOpenQueue           = { safePush(Screen.Queue.route) },
                    onOpenArtist          = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope  = this@composable,
                )
            }

            composable(
                route      = Screen.ArtistDetail.route,
                arguments  = listOf(navArgument("id") { type = NavType.StringType }),
                deepLinks  = listOf(navDeepLink { uriPattern = "https://open.spotify.com/artist/{id}" }),
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString("id") ?: return@composable
                val vm = viewModel<ArtistDetailViewModel>(
                    factory = ArtistDetailViewModelFactory(container, artistId)
                )
                ArtistDetailScreen(
                    viewModel             = vm,
                    playerViewModel       = playerVm,
                    onBack                = ::safeNavigateUp,
                    onOpenAlbum           = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                    onOpenPlayer          = { safePush(Screen.Player.route) },
                    onOpenQueue           = { safePush(Screen.Queue.route) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope  = this@composable,
                )
            }

            composable(
                route     = Screen.TrackDeepLink.route,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = "https://open.spotify.com/track/{id}" }),
            ) { backStackEntry ->
                val trackId = backStackEntry.arguments?.getString("id") ?: return@composable
                TrackDeepLinkScreen(
                    trackId            = trackId,
                    playerViewModel    = playerVm,
                    onNavigateToPlayer = {
                        navController.navigate(Screen.Player.route) {
                            popUpTo(Screen.TrackDeepLink.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Screen.Settings.route) {
                val vm = viewModel<SettingsViewModel>(factory = SettingsViewModelFactory(container))
                SettingsScreen(
                    viewModel = vm,
                    onBack    = ::safeNavigateUp,
                    onLogout  = {
                        container.authManager.logout()
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
    } // CompositionLocalProvider
}
