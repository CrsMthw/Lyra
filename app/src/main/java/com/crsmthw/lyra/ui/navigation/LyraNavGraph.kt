package com.crsmthw.lyra.ui.navigation

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.crsmthw.lyra.util.NavTransitionMillis
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.crsmthw.lyra.R
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.components.LocalPlayerRouteVisible
import com.crsmthw.lyra.ui.components.PlayerPanelHost
import com.crsmthw.lyra.ui.screens.album.AlbumDetailScreen
import com.crsmthw.lyra.ui.screens.deeplink.LinkResolverScreen
import com.crsmthw.lyra.ui.screens.deeplink.LinkResolverViewModel
import com.crsmthw.lyra.ui.screens.deeplink.LinkResolverViewModelFactory
import com.crsmthw.lyra.ui.screens.album.AlbumDetailViewModel
import com.crsmthw.lyra.ui.screens.album.AlbumDetailViewModelFactory
import com.crsmthw.lyra.ui.screens.artist.ArtistDetailScreen
import com.crsmthw.lyra.ui.screens.artist.ArtistDetailViewModel
import com.crsmthw.lyra.ui.screens.artist.ArtistDetailViewModelFactory
import com.crsmthw.lyra.ui.screens.show.ShowDetailScreen
import com.crsmthw.lyra.ui.screens.show.ShowDetailViewModel
import com.crsmthw.lyra.ui.screens.show.ShowDetailViewModelFactory
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
import com.crsmthw.lyra.ui.screens.stats.StatsScreen
import com.crsmthw.lyra.ui.screens.stats.StatsViewModel
import com.crsmthw.lyra.ui.screens.stats.StatsViewModelFactory
import com.crsmthw.lyra.ui.screens.settings.SettingsScreen
import com.crsmthw.lyra.ui.screens.settings.SettingsViewModel
import com.crsmthw.lyra.ui.screens.settings.SettingsViewModelFactory

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyraNavGraph(container: AppContainer, pendingDeepLinkIntent: Intent? = null) {
    val navController: NavHostController = rememberNavController()
    val playerVm = viewModel<PlayerViewModel>(factory = PlayerViewModelFactory(container))

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

    // ── Incoming Spotify links ───────────────────────────────────────────────
    // Every VIEW intent the manifest's filters accept is funnelled through ONE resolver
    // destination instead of per-route navDeepLink patterns. Those patterns were exact and
    // matched only the four plain `open.spotify.com/<type>/<id>` shapes — not the `spotify.link`
    // short links Spotify's own share sheet produces today, not the `/intl-xx/` locale form, and
    // not `spotify:` URIs — and they cannot be extended to cover a link whose type is only
    // knowable after a network round trip. Keeping them ALONGSIDE the resolver would also
    // double-navigate at cold start, since NavHost handles the launch intent by itself.
    //
    // The back stack is built explicitly here, which fixes a second bug: `handleDeepLink` (the
    // path the navDeepLinks used) navigates with `popUpTo(graph.id, inclusive = true)` when the
    // link arrives on another app's task, i.e. it clears the ENTIRE stack including the start
    // destination — so back out of a deep-linked screen used to leave the app. Pushing the
    // resolver over the Library and letting it pop itself leaves exactly [Library, destination].
    //
    // Deliberately NOT routed through safePush: that drops any nav within 350 ms of the last
    // one, and an incoming link is not a user tap racing a transition — swallowing it would
    // silently do nothing. The Auth→Library and sessionExpired handlers navigate raw for the
    // same reason.
    //
    // Skipped while signed out: the resolver's destinations all need the API, and popUpTo
    // (Library) has nothing to pop to. The user lands on Auth.
    val deepLinksEnabled = startDestination == Screen.Library.route
    LaunchedEffect(pendingDeepLinkIntent) {
        if (!deepLinksEnabled) return@LaunchedEffect
        val intent = pendingDeepLinkIntent ?: return@LaunchedEffect
        if (intent.action != Intent.ACTION_VIEW) return@LaunchedEffect
        val rawUrl = intent.data?.toString().orEmpty()
        if (rawUrl.isBlank()) return@LaunchedEffect
        navController.navigate(Screen.LinkResolver.createRoute(rawUrl)) {
            popUpTo(Screen.Library.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    // When the refresh token dies (Spotify invalid_grant — 6-month expiry / revoked), the auth
    // manager has already discarded the tokens and emits here. Route the user back to sign-in
    // (clearing the back stack) and flag the Auth screen to explain why. NavHost is always
    // composed while foregrounded; an event missed in the background is re-fired by the next
    // API call, and a cold start already lands on Auth via isAuthenticated() == false.
    var sessionExpired by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.authManager.sessionExpired.collect {
            sessionExpired = true
            navController.navigate(Screen.Auth.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

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

    // Is PlayerScreen one of the two ends of the nav transition that is running right now?
    //
    // The app's ONE mini player (hosted below, around the whole NavHost) registers the "album-art"
    // shared element in THIS SharedTransitionLayout, and the only partner that match is ever wanted
    // against is PlayerScreen's big art — so gate it on that. `visibleEntries` is what NavHost itself
    // renders from and, per its KDoc, keeps an entry listed for the whole of its exit transition —
    // including one already popped off the back stack — so this is true from the first frame of a
    // push to Player AND for the whole pop back off it. It reaches the mini player as
    // [LocalPlayerRouteVisible]; PlayerPanelHost turns it into a SharedContentConfig rather than
    // adding/removing the modifier, which would be too late to be captured on a pop (see the Nav
    // Scope Gate comment there). It used to be load-bearing, because every hosted screen carried its
    // own bar and a browse→browse navigation put TWO matched entries on screen; with a single hoisted
    // bar that cannot happen any more, and this is now belt-and-braces that keeps the key quiet on
    // navigations with no morph.
    val navVisibleEntries by navController.visibleEntries.collectAsStateWithLifecycle()
    val playerRouteVisible = navVisibleEntries.any { it.destination.route == Screen.Player.route }

    CompositionLocalProvider(
        LocalFftData provides container.visualizerManager.fftData,
        LocalVisualizerAccentColor provides visualizerAccentColor,
        LocalVisualizerBottomEnabled provides bottomVisualizerEnabled,
        LocalVisualizerConfig provides visualizerConfig,
        LocalPlayerRouteVisible provides playerRouteVisible,
    ) {
    // Screen push/pop transitions use FINITE, MATCHED durations — deliberately NOT a spring, and a
    // considered exception to the "route spatial motion through motionScheme.*SpatialSpec()" rule in
    // CLAUDE.md. This is how platform navigation behaves everywhere (Compose Navigation's own default
    // is fadeIn(tween(700))), and springs actively break it here:
    //
    // MaterialExpressiveTheme's defaultSpatialSpec is an UNDERdamped spring (dampingRatio 0.8 /
    // stiffness 380), so the slide overshoots and keeps oscillating for ~400-700ms, while fadeOut()'s
    // default effects spring settles in ~280ms. Navigation disposes the outgoing entry only once the
    // WHOLE transition settles (onTransitionComplete fires at currentState == targetState), it
    // z-orders the outgoing entry ABOVE the incoming one during a pop, and in Compose alpha does NOT
    // affect hit testing. Net effect: the spring's invisible tail left a few hundred ms where the
    // screen looked settled but taps still hit the previous screen's controls.
    //
    // One shared duration for the slide AND the fade makes "looks finished" == "is finished", so the
    // entry is disposed the instant the motion stops. That removes the dead window at its source
    // rather than blocking input to paper over it (which just traded it for unresponsive touches).
    // Also better for predictive back, which SEEKS the transition by gesture progress — a fraction-
    // driven tween seeks cleanly, an oscillating spring does not.
    val navSlideSpec = tween<IntOffset>(NavTransitionMillis, easing = FastOutSlowInEasing)
    val navFadeSpec  = tween<Float>(NavTransitionMillis, easing = FastOutSlowInEasing)

    // ── Docked third pane (tablet in landscape) ──────────────────────────────
    // Gate on the MEASURED window width — NOT isWidthAtLeastBreakpoint(1200), whose default V1
    // width buckets cap at 840dp. The pane is hosted HERE, beside the NavHost and OUTSIDE the
    // per-destination slide/fade, so it stays put while the browse screens animate. It shows on
    // exactly the routes that get the floating mini player at narrower widths — see
    // [routeShowsPlayerSurface], the single predicate both read. Keeping Search in that set also
    // keeps the NavHost width constant across the Library FAB→search-bar container transform, so
    // the "search-bar" morph is measured in one pane geometry, not two.
    val windowContainer = LocalWindowInfo.current.containerSize
    val isExtraWide = with(LocalDensity.current) {
        windowContainer.width.toDp() >= 1200.dp && windowContainer.height.toDp() >= 600.dp
    }
    // ONE route predicate for the whole floating/docked player surface, so the two can never drift:
    // it gates the mini player + pop-out at narrow/wide widths AND the docked third pane at ≥1200dp.
    // Read from `currentBackStackEntryAsState()`, which flips at the START of a push and of a
    // committed pop, so the bar exits while PlayerScreen enters and enters while it exits — the two
    // roles the "album-art" morph needs.
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val showsPlayerSurface = routeShowsPlayerSurface(currentRoute)
    // The SAME predicate for the entry a back would land on, so `PlayerPanelHost` can seek the mini
    // player in with a back GESTURE rather than dropping it in at commit (the committed flip above
    // is a frame or two too late to track a finger). `previousBackStackEntry` is a plain property,
    // but it is read here beside `currentBackStackEntryAsState()`, whose change is what makes it
    // move — and it is stable during a predictive gesture, which only calls `prepareForTransition`
    // and pops at commit (navigation-compose `NavHostEventHandler`). Null (a back that LEAVES the
    // app, e.g. from the Library root) falls back to "unchanged", so nothing is ever seeked for a
    // gesture that isn't an in-app pop.
    val backEntryRoute = navController.previousBackStackEntry?.destination?.route
    val showsPlayerSurfaceAfterBack =
        if (backEntryRoute == null) showsPlayerSurface else routeShowsPlayerSurface(backEntryRoute)
    // Per-route mini-player shape, animated in place by the host instead of swapped by remounting.
    val miniFullWidth = currentRoute == Screen.Search.route || currentRoute == Screen.Stats.route
    val miniAvoidsIme = currentRoute == Screen.Search.route

    SharedTransitionLayout {
      Row(modifier = Modifier.fillMaxSize()) {
        // THE app's single mini player / pop-out panel, wrapped around the whole NavHost so it
        // survives every browse→browse navigation instead of exiting and re-entering with each
        // screen. It sits INSIDE this Row (beside the docked third pane) and inside this
        // SharedTransitionLayout, so the ≥1200dp docked-pane geometry and the nav-scope
        // "album-art" morph are both unchanged. `onRequestPlayer` is threaded into the browse
        // screens' own onOpenPlayer/onNavigateToPlayer below: pop-out on a wide screen, a push to
        // PlayerScreen on a narrow one.
        PlayerPanelHost(
            playerViewModel          = playerVm,
            onOpenPlayer             = { safePush(Screen.Player.route) },
            onOpenQueue              = { safePush(Screen.Queue.route) },
            visible                  = showsPlayerSurface,
            visibleAfterBack         = showsPlayerSurfaceAfterBack,
            miniPlayerFullWidth      = miniFullWidth,
            miniPlayerAvoidsIme      = miniAvoidsIme,
            navSharedTransitionScope = this@SharedTransitionLayout,
            modifier                 = Modifier.weight(1f).fillMaxHeight(),
        ) { onRequestPlayer ->
        NavHost(
            navController        = navController,
            startDestination     = startDestination,
            modifier             = Modifier.fillMaxSize(),
            // Every fade is pinned to navFadeSpec so it ends on the same frame as the slide. Leaving
            // these as bare fadeIn()/fadeOut() is what desynced them: their default is a spring.
            enterTransition      = { slideInHorizontally(navSlideSpec)  { it / 4 } + fadeIn(navFadeSpec)  },
            exitTransition       = { slideOutHorizontally(navSlideSpec) { -(it / 4) } + fadeOut(navFadeSpec) },
            popEnterTransition   = { slideInHorizontally(navSlideSpec)  { -(it / 4) } + fadeIn(navFadeSpec)  },
            popExitTransition    = { slideOutHorizontally(navSlideSpec) { it / 4 } + fadeOut(navFadeSpec) },
            // Predictive back (the BACK GESTURE) runs its own pair of transitions — new parameters in
            // Navigation 2.10.0. Leave them out and Navigation's defaults silently take over for every
            // gesture back, because they are defaulted rather than inherited from popEnter/popExit:
            // `DefaultNavTransitions.predictivePopExitTransition` is `scaleOut(targetScale = 0.7f)` with
            // NO paired fade, so the outgoing screen shrinks to 70%, sits there fully opaque, then pops
            // out — while a button/programmatic back still slid correctly. Mirror the pop slides so both
            // back paths look identical.
            // The lambda's own parameter is `swipeEdge` (unused); the inner `it` is still the pane width.
            predictivePopEnterTransition = { _ -> slideInHorizontally(navSlideSpec)  { -(it / 4) } + fadeIn(navFadeSpec)  },
            predictivePopExitTransition  = { _ -> slideOutHorizontally(navSlideSpec) { it / 4 } + fadeOut(navFadeSpec) },
        ) {

            composable(Screen.Auth.route) {
                AuthScreen(
                    encryptedPrefs    = container.encryptedPrefs,
                    authManager       = container.authManager,
                    showExpiredNotice = sessionExpired,
                    onAuthenticated   = {
                        sessionExpired = false
                        navController.navigate(Screen.Library.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Screen.Library.route) { backStackEntry ->
                val vm = viewModel<LibraryViewModel>(factory = LibraryViewModelFactory(container))

                // A deep-linked playlist, handed back by the link resolver (see above). Only a
                // playlist in the user's OWN library can be opened: LibraryViewModel.selectPlaylist
                // takes a loaded SpotifyPlaylist, not an id, so a curated or not-followed playlist
                // has nothing to select and gets the same "can't open" toast the resolver uses.
                //
                // The wait watches for the PLAYLIST, not for `isLoading` to clear: loadLibrary
                // flips isLoading false on its CACHED emission and only then refreshes from the
                // network, so a playlist added on another device is not in that first snapshot and
                // gating on the flag would toast a link that is about to become openable. The
                // timeout is therefore what reports a genuinely absent playlist; the usual case
                // answers from cache in well under a second.
                val pendingPlaylistFlow = remember(backStackEntry) {
                    backStackEntry.savedStateHandle.getStateFlow<String?>(PENDING_PLAYLIST_KEY, null)
                }
                val pendingPlaylistId by pendingPlaylistFlow.collectAsStateWithLifecycle()
                LaunchedEffect(pendingPlaylistId) {
                    val id = pendingPlaylistId ?: return@LaunchedEffect
                    backStackEntry.savedStateHandle[PENDING_PLAYLIST_KEY] = null
                    val playlist = withTimeoutOrNull(PENDING_PLAYLIST_TIMEOUT_MS) {
                        vm.uiState.mapNotNull { s -> s.playlists.firstOrNull { it.id == id } }.first()
                    }
                    if (playlist != null) vm.selectPlaylist(playlist)
                    else Toast.makeText(
                        context,
                        context.getString(R.string.deeplink_unsupported),
                        Toast.LENGTH_LONG,
                    ).show()
                }

                LibraryScreen(
                    viewModel             = vm,
                    playerViewModel       = playerVm,
                    onOpenPlayer          = onRequestPlayer,
                    onOpenSearch          = { safePush(Screen.Search.route) },
                    onOpenSettings        = { safePush(Screen.Settings.route) },
                    onOpenAlbum           = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                    onOpenArtist          = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                    onOpenShow            = { showId -> safePush(Screen.ShowDetail.createRoute(showId)) },
                    onOpenStats           = { safePush(Screen.Stats.route) },
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

            composable(Screen.Stats.route) {
                val vm = viewModel<StatsViewModel>(factory = StatsViewModelFactory(container))
                StatsScreen(
                    viewModel       = vm,
                    playerViewModel = playerVm,
                    onBack          = ::safeNavigateUp,
                    onOpenAlbum     = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                    onOpenArtist    = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                )
            }

            composable(Screen.Search.route) {
                val vm = viewModel<SearchViewModel>(factory = SearchViewModelFactory(container))
                SearchScreen(
                    viewModel             = vm,
                    playerViewModel       = playerVm,
                    onBack                = ::safeNavigateUp,
                    onOpenPlayer          = { safePush(Screen.Player.route) },
                    onAlbumClick          = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                    onArtistClick         = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                    onShowClick           = { showId -> safePush(Screen.ShowDetail.createRoute(showId)) },
                    onTrackClick          = { uri, uris ->
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
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("id") ?: return@composable
                val vm = viewModel<AlbumDetailViewModel>(
                    factory = AlbumDetailViewModelFactory(container, albumId)
                )
                AlbumDetailScreen(
                    viewModel          = vm,
                    playerViewModel    = playerVm,
                    onBack             = ::safeNavigateUp,
                    onNavigateToPlayer = onRequestPlayer,
                    onOpenArtist       = { artistId -> safePush(Screen.ArtistDetail.createRoute(artistId)) },
                )
            }

            composable(
                route      = Screen.ArtistDetail.route,
                arguments  = listOf(navArgument("id") { type = NavType.StringType }),
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString("id") ?: return@composable
                val vm = viewModel<ArtistDetailViewModel>(
                    factory = ArtistDetailViewModelFactory(container, artistId)
                )
                ArtistDetailScreen(
                    viewModel       = vm,
                    playerViewModel = playerVm,
                    onBack          = ::safeNavigateUp,
                    onOpenAlbum     = { albumId -> safePush(Screen.AlbumDetail.createRoute(albumId)) },
                )
            }

            composable(
                route      = Screen.ShowDetail.route,
                arguments  = listOf(navArgument("id") { type = NavType.StringType }),
            ) { backStackEntry ->
                val showId = backStackEntry.arguments?.getString("id") ?: return@composable
                val vm = viewModel<ShowDetailViewModel>(
                    factory = ShowDetailViewModelFactory(container, showId)
                )
                ShowDetailScreen(
                    viewModel          = vm,
                    playerViewModel    = playerVm,
                    onBack             = ::safeNavigateUp,
                    onNavigateToPlayer = onRequestPlayer,
                )
            }

            // The one landing pad for every incoming Spotify link. It resolves the raw url — a
            // spotify.link short link needs a network round trip, everything else is instant —
            // then hands off and pops itself, so back from the destination lands on the Library.
            //
            // Every navigation out of here bypasses safePush on purpose: see the funnel comment
            // above. A hand-off popping the resolver inclusive is also what keeps the Library
            // underneath from being duplicated.
            composable(
                route     = Screen.LinkResolver.route,
                arguments = listOf(navArgument("url") { type = NavType.StringType }),
            ) { backStackEntry ->
                val encoded = backStackEntry.arguments?.getString("url") ?: return@composable
                val rawUrl  = remember(encoded) { Screen.LinkResolver.decodeUrl(encoded) }
                val vm = viewModel<LinkResolverViewModel>(
                    factory = LinkResolverViewModelFactory(container)
                )
                fun handOff(route: String) {
                    navController.navigate(route) {
                        popUpTo(Screen.LinkResolver.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                LinkResolverScreen(
                    rawUrl          = rawUrl,
                    viewModel       = vm,
                    playerViewModel = playerVm,
                    onOpenAlbum     = { albumId  -> handOff(Screen.AlbumDetail.createRoute(albumId)) },
                    onOpenArtist    = { artistId -> handOff(Screen.ArtistDetail.createRoute(artistId)) },
                    onOpenShow      = { showId   -> handOff(Screen.ShowDetail.createRoute(showId)) },
                    // A playlist has no hosted destination — its track list lives INSIDE the
                    // Library. Stash the id on the Library's own back-stack entry and pop back
                    // to it; the Library block below picks it up and opens the playlist in place.
                    // getBackStackEntry throws when the route isn't on the stack. The resolver is
                    // only ever pushed on top of the Library, but a session-expired wipe could in
                    // principle clear it mid-resolve, and an incoming link must never crash.
                    onOpenPlaylist  = { playlistId ->
                        runCatching {
                            navController.getBackStackEntry(Screen.Library.route)
                                .savedStateHandle[PENDING_PLAYLIST_KEY] = playlistId
                        }
                        navController.popBackStack(Screen.LinkResolver.route, inclusive = true)
                    },
                    onOpenPlayer    = { handOff(Screen.Player.route) },
                    // Nothing to open: the screen has already shown its toast, so just get out
                    // of the way rather than leaving a spinner on screen.
                    onUnsupported   = {
                        navController.popBackStack(Screen.LinkResolver.route, inclusive = true)
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
        } // PlayerPanelHost

        if (isExtraWide && showsPlayerSurface) {
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

/**
 * Routes that get the app's floating player surface — the mini player and (on wide non-short
 * screens) the pop-out panel — and, at ≥1200dp, the docked third pane instead. ONE list for both so
 * they cannot drift apart as screens are added.
 *
 * Asked TWICE per composition: of the current entry (does the surface show now?) and of the entry
 * below it (would a back show it?). The second answer is what `PlayerPanelHost` seeks the mini
 * player against during a predictive-back gesture, so it must stay a pure function of the route —
 * no reads of anything that only the current destination knows.
 *
 * Excluded: **Player** and **Queue** (they ARE the player), **Settings** and **Auth**. The link
 * **resolver is INCLUDED** deliberately: it is pushed over the Library and pops itself, usually in
 * well under a second, so hiding the bar there would slide it out and straight back in for a screen
 * nobody sees — exactly the churn this hoist exists to remove.
 */
private fun routeShowsPlayerSurface(route: String?): Boolean = when (route) {
    Screen.Library.route,
    Screen.AlbumDetail.route,
    Screen.ArtistDetail.route,
    Screen.ShowDetail.route,
    Screen.Stats.route,
    Screen.Search.route,
    Screen.LinkResolver.route -> true
    else                      -> false
}

/**
 * Key under which the link resolver stashes a deep-linked playlist id on the Library's own
 * back-stack entry, for the Library destination to pick up once it is on top again. There is no
 * hosted playlist-detail destination to navigate to — the track list lives inside LibraryScreen.
 */
private const val PENDING_PLAYLIST_KEY = "deeplink_playlist_id"

/** How long a deep-linked playlist waits to show up in the user's library before Lyra reports it
 *  as one it can't open. Reached only when the playlist genuinely isn't in the library (curated,
 *  or followed by nobody) — a cached hit answers in well under a second. */
private const val PENDING_PLAYLIST_TIMEOUT_MS = 10_000L

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
