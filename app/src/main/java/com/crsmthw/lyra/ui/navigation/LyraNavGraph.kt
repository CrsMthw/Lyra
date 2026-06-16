package com.crsmthw.lyra.ui.navigation

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsmthw.lyra.util.loadAlbumArtColors
import com.crsmthw.lyra.util.visualizer.LocalFftData
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor
import com.crsmthw.lyra.util.visualizer.LocalVisualizerBottomEnabled
import com.crsmthw.lyra.util.visualizer.LocalVisualizerConfig
import com.crsmthw.lyra.util.visualizer.VisualizerConfig
import com.crsmthw.lyra.util.visualizer.VisualizerStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.pow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
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
    // The bottom visualizer wave must stay legible against the screen, so it uses the contrast-safe
    // surface accent — NOT the edge colour the player backgrounds use. See util/AlbumArtColor.kt.
    var rawVisualizerAccentColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(artUrl, isDarkTheme) {
        val colors = loadAlbumArtColors(context, artUrl, isDarkTheme) ?: return@LaunchedEffect
        rawVisualizerAccentColor = Color(colors.surfaceAccent)
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

    // Per-surface visualizer config (resolution + gain offset, each with a sync toggle that
    // splits circle/bottom only when style is BOTH). Built from the settings and pushed to the
    // painters via LocalVisualizerConfig. Gain offset n maps to a ×1.4^n multiplier on base gain.
    val vStyle    by container.settingsRepository.visualizerStyle.collectAsStateWithLifecycle(VisualizerStyle.BOTH)
    val vResC     by container.settingsRepository.visualizerResolution.collectAsStateWithLifecycle(24)
    val vResB     by container.settingsRepository.visualizerResolutionBottom.collectAsStateWithLifecycle(24)
    val vResSync  by container.settingsRepository.visualizerResolutionSync.collectAsStateWithLifecycle(true)
    val vGainC    by container.settingsRepository.visualizerGain.collectAsStateWithLifecycle(0)
    val vGainB    by container.settingsRepository.visualizerGainBottom.collectAsStateWithLifecycle(0)
    val vGainSync by container.settingsRepository.visualizerGainSync.collectAsStateWithLifecycle(true)
    val vDramatic by container.settingsRepository.visualizerDramatic.collectAsStateWithLifecycle(false)
    val bothSurfaces = vStyle == VisualizerStyle.BOTH
    val visualizerConfig = VisualizerConfig(
        circleBands   = vResC,
        bottomBands   = if (bothSurfaces && !vResSync) vResB else vResC,
        circleGainMul = 1.4f.pow(vGainC),
        bottomGainMul = 1.4f.pow(if (bothSurfaces && !vGainSync) vGainB else vGainC),
        dramatic      = vDramatic,
    )

    CompositionLocalProvider(
        LocalFftData provides container.visualizerManager.fftData,
        LocalVisualizerAccentColor provides visualizerAccentColor,
        LocalVisualizerBottomEnabled provides bottomVisualizerEnabled,
        LocalVisualizerConfig provides visualizerConfig,
    ) {
    // Screen push/pop slides settle via the expressive `motionScheme` so navigation springs in
    // instead of the framework's flat default spring. Read here (composable scope) and captured —
    // the transition lambdas below aren't composable contexts. Cross-fades stay default (alpha).
    val navSlideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()

    // ── Docked third pane (tablet in landscape) ──────────────────────────────
    // Gate on the MEASURED window width — NOT isWidthAtLeastBreakpoint(1200), whose default V1
    // width buckets cap at 840dp. The pane is hosted HERE, beside the NavHost and OUTSIDE the
    // per-destination slide/fade, so it stays put while the browse screens animate. It shows only
    // on the browse routes that have a left list to pair with (not on Player/Queue/Search/etc.).
    val windowContainer = LocalWindowInfo.current.containerSize
    val isExtraWide = with(LocalDensity.current) {
        windowContainer.width.toDp() >= 1200.dp && windowContainer.height.toDp() >= 600.dp
    }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val isBrowse = currentRoute == Screen.Library.route ||
                   currentRoute == Screen.AlbumDetail.route ||
                   currentRoute == Screen.ArtistDetail.route

    SharedTransitionLayout {
      Row(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController        = navController,
            startDestination     = startDestination,
            modifier             = Modifier.weight(1f).fillMaxHeight(),
            enterTransition      = { slideInHorizontally(navSlideSpec)  { it / 4 } + fadeIn()  },
            exitTransition       = { slideOutHorizontally(navSlideSpec) { -(it / 4) } + fadeOut() },
            popEnterTransition   = { slideInHorizontally(navSlideSpec)  { -(it / 4) } + fadeIn()  },
            popExitTransition    = { slideOutHorizontally(navSlideSpec) { it / 4 } + fadeOut() },
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
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope  = this@composable,
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

        if (isExtraWide && isBrowse) {
            DockedPlayerPane(
                playerViewModel = playerVm,
                container        = container,
                onExpand         = { safePush(Screen.Player.route) },
                onOpenAlbum      = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                onOpenArtist     = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                modifier         = Modifier.width(DOCKED_PLAYER_WIDTH).fillMaxHeight(),
            )
        }
      } // Row
    }
    } // CompositionLocalProvider
}

/** Fixed width of the docked full-player pane (M3 recommends ~360–412dp for a fixed pane /
 *  side sheet; 380 keeps the portrait player comfortable on a ~1280dp tablet). */
private val DOCKED_PLAYER_WIDTH = 380.dp

/**
 * Permanent docked player pane shown on the right of browse screens at extra-wide widths.
 * Reuses the full `PlayerScreen` (docked mode = portrait, expand button, options menu) and can
 * swap to an embedded `QueueScreen` in-place — so the queue opens inside the pane instead of as a
 * full-screen destination. Lives outside the NavHost so it doesn't ride the nav slide/fade.
 */
@Composable
private fun DockedPlayerPane(
    playerViewModel: PlayerViewModel,
    container      : AppContainer,
    onExpand       : () -> Unit,
    onOpenAlbum    : (String) -> Unit,
    onOpenArtist   : (String) -> Unit,
    modifier       : Modifier = Modifier,
) {
    var showQueue by remember { mutableStateOf(false) }
    BackHandler(enabled = showQueue) { showQueue = false }
    Crossfade(targetState = showQueue, label = "dockedQueue", modifier = modifier) { queue ->
        if (queue) {
            val queueVm = viewModel<QueueViewModel>(factory = QueueViewModelFactory(container))
            QueueScreen(
                viewModel    = queueVm,
                onBack       = { showQueue = false },
                onOpenAlbum  = onOpenAlbum,
                onOpenArtist = onOpenArtist,
            )
        } else {
            PlayerScreen(
                viewModel    = playerViewModel,
                onBack       = {},
                onOpenQueue  = { showQueue = true },
                onFullScreen = onExpand,
                onOpenAlbum  = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                docked       = true,
            )
        }
    }
}
