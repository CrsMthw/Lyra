@file:Suppress("ConfigurationScreenWidthHeight")

package com.crsmthw.lyra.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import androidx.compose.ui.res.stringResource
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.crsmthw.lyra.ui.components.DetailArtHero
import com.crsmthw.lyra.ui.components.PlayerPanelHost
import com.crsmthw.lyra.ui.components.PlaylistCard
import com.crsmthw.lyra.ui.components.HeroBandHeight
import com.crsmthw.lyra.ui.components.RemovablePlaylist
import com.crsmthw.lyra.ui.components.rememberHeroScrollProgress
import com.crsmthw.lyra.ui.components.TitlePill
import com.crsmthw.lyra.ui.components.TopActionPill
import com.crsmthw.lyra.ui.components.TopPillHeight
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.components.TrackRow
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.screens.player.RepeatMode
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import com.crsmthw.lyra.util.threshold
import com.crsmthw.lyra.util.toTimeString
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pairs the search FAB with the Search screen's bar for the container transform — must match the
 *  identical key in `SearchScreen`. */
private const val SEARCH_BAR_SHARED_KEY = "search-bar"

/** Shared-element key for the single-pane Library container transform (tapped card art → detail
 *  hero). Namespaced separately from the nav-level `"album-art"` morph; `id` is the playlist id or
 *  `"liked"`. The matching source card and the hero use the same key so only that pair morphs. */
private fun libArtKey(id: String?): String = "lib-art-${id ?: "liked"}"


@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(
    viewModel             : LibraryViewModel,
    playerViewModel       : PlayerViewModel,
    onOpenPlayer          : () -> Unit,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
    onOpenQueue           : () -> Unit = {},
    onOpenAlbum           : (String) -> Unit = {},
    onOpenArtist          : (String) -> Unit = {},
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state        by viewModel.uiState.collectAsStateWithLifecycle()
    val isWideScreen  = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)
    val haptics       = LocalHapticFeedback.current
    val onOpenSearchHaptic = { haptics.confirm(); onOpenSearch() }

    PlayerPanelHost(
        playerViewModel          = playerViewModel,
        onOpenPlayer             = onOpenPlayer,
        onOpenQueue              = onOpenQueue,
        navSharedTransitionScope = sharedTransitionScope,
        navAnimatedContentScope  = animatedContentScope,
    ) { onRequestPlayer ->
        if (isWideScreen) {
            TwoPaneLayout(
                state                 = state,
                viewModel             = viewModel,
                playerViewModel       = playerViewModel,
                onOpenSearch          = onOpenSearchHaptic,
                onOpenSettings        = onOpenSettings,
                onRequestPlayer       = onRequestPlayer,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope  = animatedContentScope,
            )
        } else {
            SinglePaneLayout(
                state                 = state,
                viewModel             = viewModel,
                playerViewModel       = playerViewModel,
                onOpenSearch          = onOpenSearchHaptic,
                onOpenSettings        = onOpenSettings,
                onRequestPlayer       = onRequestPlayer,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope  = animatedContentScope,
            )
        }
    }

    TrackActionsHost(
        controller           = viewModel.trackActions,
        onGoToAlbum          = onOpenAlbum,
        onGoToArtist         = onOpenArtist,
        onRemoveFromPlaylist = viewModel::removeTrackFromCurrentPlaylist,
    )
}

/** Fires a one-shot threshold haptic each time a pull-to-refresh drag crosses the trigger point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullThresholdHaptics(state: PullToRefreshState) {
    val haptics = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction }.collect { fraction ->
            when {
                fraction >= 1f && !armed -> { armed = true; haptics.threshold() }
                fraction < 1f && armed   -> armed = false
            }
        }
    }
}

// ── Single pane (phone / folded) ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
private fun SinglePaneLayout(
    state                 : LibraryUiState,
    viewModel             : LibraryViewModel,
    playerViewModel       : PlayerViewModel,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
    onRequestPlayer       : () -> Unit,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val isShowingDetail = state.currentPlaylist != null || state.isLoadingTracks || state.currentTracks.isNotEmpty()
    val isLandscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val context   = LocalContext.current
    val mosaicDir = remember { File(context.filesDir, "mosaics") }

    // Only track presence of a current track — changes infrequently, not every second.
    val hasCurrentTrack by remember {
        playerViewModel.uiState.map { it.currentTrack != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)

    BackHandler(enabled = isShowingDetail) { viewModel.clearSelection() }

    Box(modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
    ) {
        // Browser ↔ detail swap as a CONTAINER TRANSFORM: a local SharedTransitionLayout wraps the
        // AnimatedContent so the tapped card's art (`lib-art-<id>`) flies into the detail hero and
        // morphs square→cookie (the hero side morphs the clip — see `TrackListHero`). The pane swap
        // itself stays a gentle slide+fade so the morphing art carries the motion; the slide settles
        // via the expressive `motionScheme` (springs in) and the cross-fade keeps a tween (alpha must
        // not overshoot). Keys are namespaced "lib-art-*" so they never collide with the nav-level
        // "album-art" morph; the FAB + scrims sit OUTSIDE this STL (they use the nav-level scope).
        val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            val libSharedScope = this
            AnimatedContent(
                targetState  = state,
                contentKey   = { s ->
                    val isDetail = s.currentPlaylist != null || s.isLoadingTracks || s.currentTracks.isNotEmpty()
                    if (isDetail) (s.currentPlaylist?.id ?: "liked") else null
                },
                modifier     = Modifier.fillMaxSize(),
                transitionSpec = {
                    val enteringDetail = targetState.currentPlaylist != null ||
                                         targetState.isLoadingTracks ||
                                         targetState.currentTracks.isNotEmpty()
                    if (enteringDetail) {
                        (slideInHorizontally(slideSpec) { it / 10 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 12 } + fadeOut(tween(220)))
                    } else {
                        (slideInHorizontally(slideSpec) { -it / 10 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(slideSpec) { it / 12 } + fadeOut(tween(220)))
                    }
                },
                label = "library_detail_transition",
            ) { snapshot ->
                val acScope = this
                val isDetailSnapshot = snapshot.currentPlaylist != null ||
                                       snapshot.isLoadingTracks ||
                                       snapshot.currentTracks.isNotEmpty()
                if (isDetailSnapshot) {
                    RightPaneContent(
                        state           = snapshot,
                        viewModel       = viewModel,
                        playerViewModel = playerViewModel,
                        mosaicDir       = mosaicDir,
                        onTrackClick    = onRequestPlayer,
                        onRefresh       = viewModel::refreshCurrentTracks,
                        onBack          = { viewModel.clearSelection() },
                        sharedScope     = libSharedScope,
                        animScope       = acScope,
                    )
                } else {
                    LibraryBrowserPane(
                        state          = snapshot,
                        viewModel      = viewModel,
                        onOpenSettings = onOpenSettings,
                        isLandscape    = isLandscape,
                        sharedScope    = libSharedScope,
                        animScope      = acScope,
                    )
                }
            }
        }

        val fabBottomPadding by animateDpAsState(
            targetValue   = if (hasCurrentTrack) 90.dp else 16.dp,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label         = "fabBottom",
        )

        // Bottom scrim — portrait only (landscape has side navbar, no space for it).
        // Fades content toward background so the mini player and nav area stand out.
        if (!isLandscape) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        )
                    )
            )
            FftWaveCanvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.BottomCenter),
                color    = LocalVisualizerAccentColor.current,
                alpha    = 0.20f,
            )
        }

        if (!isShowingDetail) {
            // Container transform: shares bounds with the Search screen's floating bar (same
            // SEARCH_BAR_SHARED_KEY) so tapping expands the FAB into the bar. Null scopes → no morph.
            val fabSharedModifier: Modifier =
                if (sharedTransitionScope != null && animatedContentScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            sharedContentState      = rememberSharedContentState(key = SEARCH_BAR_SHARED_KEY),
                            animatedVisibilityScope = animatedContentScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        )
                    }
                } else Modifier
            MediumFloatingActionButton(
                onClick        = onOpenSearch,
                // Distinct from the playlist Play FABs (primaryContainer + default FAB squircle),
                // which sit right behind it — a tertiary tone + the expressive SoftBurst silhouette
                // so the two never read as the same control.
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor   = MaterialTheme.colorScheme.onTertiaryContainer,
                shape          = MaterialShapes.SoftBurst.toShape(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = fabBottomPadding)
                    .then(fabSharedModifier),
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search",
                    modifier = Modifier.size(FloatingActionButtonDefaults.MediumIconSize))
            }
        }
    }
}

// ── Library browser pane ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
private fun LibraryBrowserPane(
    state                 : LibraryUiState,
    viewModel             : LibraryViewModel,
    onOpenSettings        : () -> Unit,
    isLandscape           : Boolean,
    modifier              : Modifier = Modifier,
    containerColor        : Color = Color.Unspecified,   // top-scrim target; defaults to background
    sharedScope           : SharedTransitionScope? = null,   // non-null only in single pane (container transform)
    animScope             : AnimatedContentScope? = null,
) {
    var showRefreshErrorDialog by remember { mutableStateOf(false) }
    val haptics        = LocalHapticFeedback.current
    val scrimColor     = if (containerColor == Color.Unspecified)
                             MaterialTheme.colorScheme.background else containerColor
    val listState      = rememberLazyListState()
    ListScrollHaptics(listState)
    val density        = LocalDensity.current
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val context        = LocalContext.current
    val mosaicDir = remember { File(context.filesDir, "mosaics") }

    val likedSongsSelected = state.currentPlaylist == null &&
        (state.isLoadingTracks || state.currentTracks.isNotEmpty())
    val selectedPlaylistId = state.currentPlaylist?.id

    val userId      = state.user?.id
    val myPlaylists = if (userId != null) state.playlists.filter { it.owner?.id == userId } else state.playlists
    val following   = if (userId != null) state.playlists.filter { it.owner?.id != userId } else emptyList()

    // Shared list items — identical in both portrait and landscape LazyColumns
    val listBody: LazyListScope.() -> Unit = {
        item(key = "liked") {
            // Container-transform source: same inline `sharedBounds` form as the search-FAB morph.
            val likedArt = if (sharedScope != null && animScope != null)
                with(sharedScope) {
                    Modifier.sharedBounds(
                        sharedContentState      = rememberSharedContentState(key = libArtKey(null)),
                        animatedVisibilityScope = animScope,
                        boundsTransform         = rememberArtBoundsTransform(),
                    )
                } else Modifier
            LikedSongsCard(
                count             = state.likedSongCount,
                isSelected        = likedSongsSelected,
                onOpen            = { haptics.confirm(); viewModel.selectLikedSongs() },
                onPlay            = { viewModel.playPlaylist("spotify:user:${state.user?.id}:collection") },
                artSharedModifier = likedArt,
            )
        }
        if (state.featuredPlaylists.isNotEmpty()) {
            item(key = "featured_header") {
                Text("Featured", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            }
            item(key = "featured_row") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.featuredPlaylists, key = { it.id }) { playlist ->
                        // Only the featured card owns the `lib-art-<id>` source when no main-list
                        // card already does — a followed playlist can appear in BOTH featured and the
                        // list, and two sources for one key is a duplicate-shared-key crash. The
                        // main list (ownership-partitioned) + liked are already unique among themselves.
                        val featuredArtMod =
                            if (sharedScope != null && animScope != null &&
                                state.playlists.none { it.id == playlist.id })
                                with(sharedScope) {
                                    Modifier.sharedBounds(
                                        sharedContentState      = rememberSharedContentState(key = libArtKey(playlist.id)),
                                        animatedVisibilityScope = animScope,
                                        boundsTransform         = rememberArtBoundsTransform(),
                                    )
                                } else Modifier
                        PlaylistCard(
                            playlist          = playlist,
                            mosaicFile        = if (playlist.id in state.playlistsWithMosaics)
                                File(mosaicDir, "${playlist.id}.png") else null,
                            onClick           = { haptics.confirm(); viewModel.selectPlaylist(playlist) },
                            artSharedModifier = featuredArtMod,
                        )
                    }
                }
            }
        }
        if (myPlaylists.isNotEmpty()) {
            item(key = "mine_header") {
                Text("My Playlists", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            }
            items(myPlaylists, key = { it.id }) { playlist ->
                val playlistArt = if (sharedScope != null && animScope != null)
                    with(sharedScope) {
                        Modifier.sharedBounds(
                            sharedContentState      = rememberSharedContentState(key = libArtKey(playlist.id)),
                            animatedVisibilityScope = animScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        )
                    } else Modifier
                PlaylistListCard(
                    playlist          = playlist,
                    mosaicFile        = if (playlist.id in state.playlistsWithMosaics)
                        File(mosaicDir, "${playlist.id}.png") else null,
                    isSelected        = playlist.id == selectedPlaylistId,
                    isMine            = true,
                    onClick           = { haptics.confirm(); viewModel.selectPlaylist(playlist) },
                    onPlay            = { viewModel.playPlaylist(playlist.uri) },
                    artSharedModifier = playlistArt,
                )
            }
        }
        if (following.isNotEmpty()) {
            item(key = "following_header") {
                Text("Following", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            }
            items(following, key = { it.id }) { playlist ->
                val playlistArt = if (sharedScope != null && animScope != null)
                    with(sharedScope) {
                        Modifier.sharedBounds(
                            sharedContentState      = rememberSharedContentState(key = libArtKey(playlist.id)),
                            animatedVisibilityScope = animScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        )
                    } else Modifier
                PlaylistListCard(
                    playlist          = playlist,
                    mosaicFile        = if (playlist.id in state.playlistsWithMosaics)
                        File(mosaicDir, "${playlist.id}.png") else null,
                    isSelected        = playlist.id == selectedPlaylistId,
                    onClick           = { haptics.confirm(); viewModel.selectPlaylist(playlist) },
                    onPlay            = { viewModel.playPlaylist(playlist.uri) },
                    artSharedModifier = playlistArt,
                )
            }
        }
    }

    val actionsBar: @Composable RowScope.() -> Unit = {
        if (state.refreshError != null) {
            IconButton(onClick = { showRefreshErrorDialog = true }) {
                Icon(Icons.Default.Warning, contentDescription = "Refresh error",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = { haptics.press(); onOpenSettings() }) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }

    // The spacious "Lyra" hero needs vertical room. Show it whenever the pane is tall enough —
    // portrait on any device, and landscape on a tablet (≈800dp tall). Only a SHORT landscape pane
    // (a phone, ≈360–410dp tall) drops the hero for the compact title-pill layout.
    val compactNoHero = isLandscape && LocalConfiguration.current.screenHeightDp < 500

    if (compactNoHero) {
        // Compact (short landscape, e.g. phone): floating controls over scrollable content, no hero
        // — vertical space is tight, so the title pill is always shown rather than fading in.
        val statusBarTopDp     = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val listTopPadding     = statusBarTopDp + 64.dp
        val listContentPadding = remember(listTopPadding, navBarBottomDp) { PaddingValues(top = listTopPadding, bottom = 100.dp + navBarBottomDp) }

        Box(modifier = modifier.fillMaxSize()) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            } else if (state.error != null && state.playlists.isEmpty() && !state.isLoadingTracks) {
                val isRateLimit   = state.error.contains("429")
                val retryAfterSec = if (isRateLimit)
                    Regex("Retry-After=(\\d+)").find(state.error)?.groupValues?.get(1)?.toLongOrNull()
                else null
                val retryDisplay  = when {
                    retryAfterSec == null -> null
                    retryAfterSec >= 3600 -> "${retryAfterSec / 3600}h ${(retryAfterSec % 3600) / 60}m"
                    retryAfterSec >= 60   -> "${retryAfterSec / 60}m ${retryAfterSec % 60}s"
                    else                  -> "${retryAfterSec}s"
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)) {
                        Text(
                            text = if (isRateLimit) buildString {
                                append("Spotify is rate limiting requests.")
                                if (retryDisplay != null) append("\n\nRetry-After: $retryDisplay")
                                append("\n\nWait, then reopen.")
                            } else state.error,
                            color     = if (isRateLimit) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error,
                            style     = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::loadLibrary) { Text("Retry") }
                    }
                }
            } else {
                val landscapePtrState = rememberPullToRefreshState()
                PullThresholdHaptics(landscapePtrState)
                PullToRefreshBox(
                    isRefreshing = state.isLibraryRefreshing,
                    onRefresh    = viewModel::refreshLibrary,
                    state        = landscapePtrState,
                    modifier     = Modifier.fillMaxSize(),
                    indicator    = {
                        if (state.isLibraryRefreshing) {
                            ContainedLoadingIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 64.dp + 12.dp),
                            )
                        } else {
                            PullToRefreshDefaults.Indicator(
                                state        = landscapePtrState,
                                isRefreshing = false,
                                modifier     = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 64.dp),
                            )
                        }
                    },
                ) {
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding,
                    ) { listBody() }
                }
            }

            // Floating controls (matching portrait) — no solid bar. Landscape has no scroll-away
            // hero (vertical space is tight), so the title pill is always shown rather than fading in.
            TopScrim(color = scrimColor, modifier = Modifier.align(Alignment.TopCenter))
            TitlePill(
                text     = "Lyra",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp),
            )
            TopActionPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp),
                content  = actionsBar,
            )
        }
    } else {
        // Hero layout (portrait on any device, or a landscape pane tall enough — e.g. a tablet):
        // no solid top app bar (OneUI 8.5 style). The "Lyra" hero is the first list item
        // and scrolls away; a top scrim fades content under the status bar, a floating action pill
        // carries the settings/error actions, and a small title pill fades in once the hero is gone.
        val titlePillAlpha = rememberHeroScrollProgress(listState)
        val listContentPadding = remember(navBarBottomDp) {
            PaddingValues(bottom = 100.dp + navBarBottomDp)
        }

        Box(modifier = modifier.fillMaxSize()) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            } else if (state.error != null && state.playlists.isEmpty() && !state.isLoadingTracks) {
                val isRateLimit   = state.error.contains("429")
                val retryAfterSec = if (isRateLimit)
                    Regex("Retry-After=(\\d+)").find(state.error)?.groupValues?.get(1)?.toLongOrNull()
                else null
                val retryDisplay  = when {
                    retryAfterSec == null -> null
                    retryAfterSec >= 3600 -> "${retryAfterSec / 3600}h ${(retryAfterSec % 3600) / 60}m"
                    retryAfterSec >= 60   -> "${retryAfterSec / 60}m ${retryAfterSec % 60}s"
                    else                  -> "${retryAfterSec}s"
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)) {
                        Text(
                            text = if (isRateLimit) buildString {
                                append("Spotify is rate limiting requests.")
                                if (retryDisplay != null) append("\n\nRetry-After: $retryDisplay")
                                append("\n\nWait, then reopen.")
                            } else state.error,
                            color     = if (isRateLimit) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error,
                            style     = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::loadLibrary) { Text("Retry") }
                    }
                }
            } else {
                val portraitPtrState = rememberPullToRefreshState()
                PullThresholdHaptics(portraitPtrState)
                PullToRefreshBox(
                    isRefreshing = state.isLibraryRefreshing,
                    onRefresh    = viewModel::refreshLibrary,
                    state        = portraitPtrState,
                    modifier     = Modifier.fillMaxSize(),
                    indicator    = {
                        if (state.isLibraryRefreshing) {
                            ContainedLoadingIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 12.dp),
                            )
                        } else {
                            PullToRefreshDefaults.Indicator(
                                state        = portraitPtrState,
                                isRefreshing = false,
                                modifier     = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding(),
                            )
                        }
                    },
                ) {
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding,
                    ) {
                        item(key = "hero") {
                            // Spacious hero (OneUI Phone-app style): the title sits centred in a
                            // tall band with room above (where the action pill floats) and below.
                            // Boundary-only haptics mean its height adds no extra ticks.
                            Box(
                                modifier         = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .height(HeroBandHeight),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    text     = "Lyra",
                                    style    = MaterialTheme.typography.displayMedium,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                )
                            }
                        }
                        listBody()
                    }
                }
            }

            // Top scrim — fades content under the status bar (vertical mirror of the bottom scrim).
            TopScrim(
                color    = scrimColor,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // Small title pill — fades in once the hero title has scrolled away.
            TitlePill(
                text     = "Lyra",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp)
                    .graphicsLayer { alpha = titlePillAlpha.value },
            )

            // Floating action pill — settings (+ refresh-error warning when present).
            TopActionPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp),
                content  = actionsBar,
            )
        }
    }

    if (showRefreshErrorDialog && state.refreshError != null) {
        RefreshErrorDialog(error = state.refreshError, onDismiss = { showRefreshErrorDialog = false })
    }
}

// ── Two-pane (unfolded / tablet) ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
private fun TwoPaneLayout(
    state                 : LibraryUiState,
    viewModel             : LibraryViewModel,
    playerViewModel       : PlayerViewModel,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
    onRequestPlayer       : () -> Unit,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val config    = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp
    val context   = LocalContext.current
    val mosaicDir = remember { File(context.filesDir, "mosaics") }

    // Auto-select Liked Songs on first load so right pane is never blank
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading &&
            state.currentPlaylist == null &&
            state.currentTracks.isEmpty() &&
            !state.isLoadingTracks) {
            viewModel.selectLikedSongs()
        }
    }

    // No statusBarsPadding here — the panes go edge-to-edge under a transparent status bar (like the
    // single-pane screens). Each pane self-pads its top inset (hero `statusBarsPadding()` + TopScrim,
    // floating pills) so content fades under the bar instead of leaving an opaque background band.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Left pane card ────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight(),
                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors    = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LibraryBrowserPane(
                        state                 = state,
                        viewModel             = viewModel,
                        onOpenSettings        = onOpenSettings,
                        isLandscape           = isLandscape,
                        modifier              = Modifier.fillMaxSize(),
                        containerColor        = MaterialTheme.colorScheme.surface,
                    )
                    // Bottom scrim — matches right pane, fades content toward surface
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                                )
                            )
                    )
                    // Same FAB→search-bar container transform as single-pane (shared key). The FAB
                    // sits inside the left-pane Card, but sharedBounds renders in the overlay during
                    // the transition, so the Card clip doesn't truncate the morph.
                    val fabSharedModifier: Modifier =
                        if (sharedTransitionScope != null && animatedContentScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedBounds(
                                    sharedContentState      = rememberSharedContentState(key = SEARCH_BAR_SHARED_KEY),
                                    animatedVisibilityScope = animatedContentScope,
                                    boundsTransform         = rememberArtBoundsTransform(),
                                )
                            }
                        } else Modifier
                    MediumFloatingActionButton(
                        onClick        = onOpenSearch,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor   = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape          = MaterialShapes.SoftBurst.toShape(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .then(fabSharedModifier),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search",
                            modifier = Modifier.size(FloatingActionButtonDefaults.MediumIconSize))
                    }
                }
            }

            // ── Right pane card ───────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors    = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // M3 LATERAL (peer browse): a full-width filmstrip — the outgoing track list slides
                    // fully off the left as the incoming slides in from the right, both opaque, NO fade
                    // (M3 Lateral cautions against it; a full-width opaque slide has nothing to
                    // "white-flash"). The slide settles via the expressive `motionScheme` spring for the
                    // natural bounce. Standard `AnimatedContent` retains the exiting pane correctly now
                    // that `selectPlaylist` flips to the detail content-ready (no mid-transition emission
                    // to make it cull the outgoing) — see `LibraryViewModel.selectPlaylist`.
                    val rightPaneSlideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                    AnimatedContent(
                        targetState    = state,
                        contentKey     = { s -> s.currentPlaylist?.id to (s.currentPlaylist == null) },
                        modifier       = Modifier.fillMaxSize(),
                        transitionSpec = {
                            slideInHorizontally(rightPaneSlideSpec) { it } togetherWith
                            slideOutHorizontally(rightPaneSlideSpec) { -it }
                        },
                        label = "right_pane",
                    ) { snapshot ->
                        val showPlaceholder = snapshot.currentPlaylist == null &&
                            !snapshot.isLoadingTracks && snapshot.currentTracks.isEmpty()
                        if (showPlaceholder) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.LibraryMusic, null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                                    Spacer(Modifier.height(16.dp))
                                    Text("Select a playlist",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            RightPaneContent(
                                state           = snapshot,
                                viewModel       = viewModel,
                                playerViewModel = playerViewModel,
                                mosaicDir       = mosaicDir,
                                onTrackClick    = onRequestPlayer,
                                onRefresh       = viewModel::refreshCurrentTracks,
                                containerColor  = MaterialTheme.colorScheme.surface,
                            )
                        }
                    }

                    // Bottom scrim — fades track list content toward surface so mini player stands out.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                                )
                            )
                    )
                }
            }
        }
        FftWaveCanvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.BottomCenter),
            color    = LocalVisualizerAccentColor.current,
            alpha    = 0.20f,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
private fun RightPaneContent(
    state           : LibraryUiState,
    viewModel       : LibraryViewModel,
    playerViewModel : PlayerViewModel,
    mosaicDir       : File,
    onTrackClick    : () -> Unit,
    onRefresh       : () -> Unit,
    onBack          : (() -> Unit)? = null,            // non-null → show the back pill (single-pane)
    containerColor  : Color = Color.Unspecified,       // scrim target; defaults to background
    sharedScope     : SharedTransitionScope? = null,   // container-transform target (single pane)
    animScope       : AnimatedContentScope? = null,
) {
    val currentTrackId by remember {
        playerViewModel.uiState.map { it.currentTrack?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    val isPlayingState by remember {
        playerViewModel.uiState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
    val playlist     = state.currentPlaylist
    val isLikedSongs = playlist == null
    // Owned playlists only (never Liked Songs / followed) get the delete action.
    val canDelete    = playlist != null && playlist.owner?.id == state.user?.id
    val haptics      = LocalHapticFeedback.current
    var showOverflowMenu  by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val mosaicFile   = playlist?.let { p ->
        if (p.id in state.playlistsWithMosaics) File(mosaicDir, "${p.id}.png") else null
    }
    // Spotify's own cover wins; the locally-generated mosaic is only a fallback for when Spotify
    // hasn't provided art yet (e.g. a just-created playlist). Once Spotify fills it in, a library
    // refresh picks up the real URL and it replaces the stale mosaic.
    val artUrl       = playlist?.thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: mosaicFile?.absolutePath
    val likedSongsStr = stringResource(R.string.liked_songs)
    val playlistName  = playlist?.name ?: likedSongsStr
    val trackCount   = when {
        isLikedSongs && state.likedSongsTotal > 0 -> state.likedSongsTotal
        isLikedSongs                              -> state.likedSongCount
        state.playlistTracksTotal > 0             -> state.playlistTracksTotal   // authoritative total, not the loaded count
        state.currentTracks.isNotEmpty()          -> state.currentTracks.size
        else                                      -> playlist.trackCount         // metadata fallback — avoids layout shift
    }
    val playUri      = playlist?.uri ?: "spotify:user:${state.user?.id}:collection"

    // Delay the loading spinner so cache hits (< ~250ms) never flash it.
    val latestState = rememberUpdatedState(state)
    var showLoadingIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentPlaylist?.id) {
        showLoadingIndicator = false
        delay(250)
        val s = latestState.value
        if (s.isLoadingTracks && s.currentTracks.isEmpty()) showLoadingIndicator = true
    }
    LaunchedEffect(state.isLoadingTracks) {
        if (!state.isLoadingTracks) showLoadingIndicator = false
    }

    val density          = LocalDensity.current
    val navBarBottomDp   = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val listState        = rememberLazyListState()
    val titlePillAlpha   = rememberHeroScrollProgress(listState)
    val pullToRefreshState   = rememberPullToRefreshState()
    PullThresholdHaptics(pullToRefreshState)
    val scrimColor       = if (containerColor == Color.Unspecified)
                               MaterialTheme.colorScheme.background else containerColor

    val canLoadMore          = if (isLikedSongs)
                                   state.likedSongsTotal > 0 && state.likedSongsOffset < state.likedSongsTotal
                               else
                                   state.playlistTracksTotal > 0 && state.playlistTracksOffset < state.playlistTracksTotal

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh    = onRefresh,
            state        = pullToRefreshState,
            modifier     = Modifier.fillMaxSize(),
            indicator    = {
                if (state.isRefreshing) {
                    ContainedLoadingIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 12.dp),
                    )
                } else {
                    PullToRefreshDefaults.Indicator(
                        state        = pullToRefreshState,
                        isRefreshing = false,
                        modifier     = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding(),
                    )
                }
            },
        ) {
        TrackList(
            tracks         = state.currentTracks,
            currentTrackId = currentTrackId,
            isPlaying      = isPlayingState,
            isLoadingMore  = state.isLoadingMoreTracks,
            canLoadMore    = canLoadMore,
            onLoadMore     = if (isLikedSongs) viewModel::loadMoreLikedSongs else viewModel::loadMorePlaylistTracks,
            onTrackClick   = { track ->
                if (playlist != null) {
                    val idx = state.currentTracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
                    playerViewModel.playTrack(track.uri, contextUri = playlist.uri, index = idx)
                } else {
                    playerViewModel.playFromLikedSongs(track.uri)
                }
                onTrackClick()
            },
            onTrackLongClick = { track ->
                val removable = playlist?.takeIf { it.owner?.id == state.user?.id }
                    ?.let { RemovablePlaylist(it.id, it.name) }
                viewModel.trackActions.open(track.toTrackActionTarget(removable))
            },
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
            listState      = listState,
            headerContent  = {
                // The same cookie-art hero in both single- and two-pane (the two-pane right pane is
                // the single-pane track list minus the back pill); its name fades into the title pill.
                TrackListHero(
                    artUrl       = artUrl,
                    isLikedSongs = isLikedSongs,
                    name         = playlistName,
                    trackCount   = trackCount,
                    onPlay       = { haptics.press(); viewModel.playPlaylist(playUri) },
                    onShuffle    = { haptics.press(); viewModel.shufflePlaylist(playUri) },
                    playlistId   = playlist?.id,
                    sharedScope  = sharedScope,
                    animScope    = animScope,
                )
            },
            emptyContent = when {
                showLoadingIndicator && state.currentTracks.isEmpty() -> { {
                    Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                } }
                state.error != null && state.currentTracks.isEmpty() -> { {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier            = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
                    ) {
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        if (!isLikedSongs && playUri.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.playPlaylist(playUri) }) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.player_play))
                            }
                        }
                    }
                } }
                else -> null
            },
        )
        } // PullToRefreshBox

        // Floating controls — shared by single- and two-pane. The back pill shows only in
        // single-pane (the two-pane right pane sits beside the browser list, so no back is needed).
        TopScrim(color = scrimColor, modifier = Modifier.align(Alignment.TopCenter))
        if (onBack != null) {
            TopActionPill(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp),
            ) {
                IconButton(onClick = { haptics.confirm(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        }
        TitlePill(
            text     = playlistName,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(
                    start = if (onBack != null) 16.dp + TopPillHeight + 8.dp else 16.dp,
                    top   = 8.dp,
                )
                .widthIn(max = 220.dp)
                .graphicsLayer { alpha = titlePillAlpha.value },
        )
        if (canDelete) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp),
            ) {
                TopActionPill {
                    IconButton(onClick = { haptics.press(); showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                }
                DropdownMenu(
                    expanded         = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                ) {
                    DropdownMenuItem(
                        text        = { Text(stringResource(R.string.delete_playlist)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick     = { haptics.press(); showOverflowMenu = false; showDeleteConfirm = true },
                    )
                }
            }
        }
        if (showDeleteConfirm && playlist != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title   = { Text(stringResource(R.string.delete_playlist_confirm_title)) },
                text    = { Text(stringResource(R.string.delete_playlist_confirm_message, playlist.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        haptics.confirm()
                        viewModel.deletePlaylist(playlist)   // closes the detail view on success
                    }) {
                        Text(
                            text  = stringResource(R.string.delete_playlist_confirm_button),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { haptics.press(); showDeleteConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

// ── Track-list hero (single-pane) ─────────────────────────────────────────────
// The shared DetailArtHero with the playlist/Liked art (real cover, else the Liked gradient or a
// music-note fallback) and an "N tracks" subtitle.

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackListHero(
    artUrl      : String?,
    isLikedSongs: Boolean,
    name        : String,
    trackCount  : Int,
    onPlay      : () -> Unit,
    onShuffle   : () -> Unit,
    playlistId  : String? = null,
    sharedScope : SharedTransitionScope? = null,
    animScope   : AnimatedContentScope? = null,
) {
    // Container-transform TARGET (single pane): the hero art tile shares bounds with the tapped
    // browser card (`lib-art-<id>`, or `lib-art-liked`) and flies + cross-fades into the hero. Card
    // and hero are the same M3 square shape, so it's a real M3 container transform (no shape morph).
    val artModifier = if (sharedScope != null && animScope != null)
        with(sharedScope) {
            Modifier.sharedBounds(
                sharedContentState      = rememberSharedContentState(key = libArtKey(playlistId)),
                animatedVisibilityScope = animScope,
                boundsTransform         = rememberArtBoundsTransform(),
            )
        } else Modifier

    DetailArtHero(
        title       = name,
        subtitle    = if (trackCount > 0) "$trackCount tracks" else null,
        onPlay      = onPlay,
        onShuffle   = onShuffle,
        artModifier = artModifier,
    ) {
        if (!artUrl.isNullOrBlank()) {
            AsyncImage(
                model              = artUrl,
                contentDescription = name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else if (isLikedSongs) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF6A11CB), Color(0xFF2575FC)))
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Favorite, null, tint = Color.White.copy(0.5f),
                    modifier = Modifier.size(84.dp))
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp))
            }
        }
    }
}

// ── Liked Songs card ──────────────────────────────────────────────────────────

@Composable
private fun LikedSongsCard(
    count             : Int,
    onOpen            : () -> Unit,
    onPlay            : () -> Unit,
    modifier          : Modifier = Modifier,
    isSelected        : Boolean = false,
    artSharedModifier : Modifier = Modifier,   // container-transform source — applied to the art tile
) {
    Card(
        onClick   = onOpen,
        modifier  = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .then(artSharedModifier)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6A11CB), Color(0xFF2575FC)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(28.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.liked_songs), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("$count tracks", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SmallFloatingActionButton(
                onClick         = onPlay,
                containerColor  = MaterialTheme.colorScheme.primaryContainer,
                contentColor    = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation       = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                modifier        = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play",
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Playlist list card ────────────────────────────────────────────────────────

@Composable
private fun PlaylistListCard(
    playlist          : SpotifyPlaylist,
    mosaicFile        : File?,
    modifier          : Modifier = Modifier,
    isSelected        : Boolean = false,
    isMine            : Boolean = false,   // own playlist → count only (owner name is the user, redundant)
    onClick           : () -> Unit,
    onPlay            : () -> Unit,
    artSharedModifier : Modifier = Modifier,   // container-transform source — applied to the art tile
) {
    Card(
        onClick   = onClick,
        modifier  = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbShape = RoundedCornerShape(12.dp)
            // Spotify cover first; the local mosaic is only a fallback when Spotify has no art yet.
            val thumbModel = playlist.thumbnailUrl.takeIf { it.isNotBlank() } ?: mosaicFile
            if (thumbModel != null) {
                AsyncImage(
                    model              = thumbModel,
                    contentDescription = playlist.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(56.dp).then(artSharedModifier).clip(thumbShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .then(artSharedModifier)
                        .clip(thumbShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = if (isMine) {
                    // Own playlist — the owner is the user, so show just the count.
                    "${playlist.trackCount} tracks"
                } else {
                    // Following — show whose it is and the count together when both are known.
                    val countText = if (playlist.trackCount > 0) "${playlist.trackCount} tracks" else null
                    listOfNotNull(playlist.owner?.displayName, countText).joinToString(" · ").ifBlank { "Playlist" }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.width(8.dp))

            SmallFloatingActionButton(
                onClick        = onPlay,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation      = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                modifier       = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play",
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Paginated track list ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackList(
    tracks         : List<com.crsmthw.lyra.data.remote.model.SpotifyTrack>,
    currentTrackId : String?,
    isPlaying      : Boolean,
    isLoadingMore  : Boolean,
    canLoadMore    : Boolean,
    onLoadMore     : () -> Unit,
    onTrackClick   : (com.crsmthw.lyra.data.remote.model.SpotifyTrack) -> Unit,
    modifier       : Modifier = Modifier,
    onTrackLongClick: ((com.crsmthw.lyra.data.remote.model.SpotifyTrack) -> Unit)? = null,
    contentPadding : PaddingValues = PaddingValues(bottom = 100.dp),
    listState      : androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    headerContent  : (@Composable () -> Unit)? = null,
    emptyContent   : (@Composable () -> Unit)? = null,
) {

    ListScrollHaptics(listState)

    val reachedBottom by remember {
        derivedStateOf {
            val info        = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - 5
        }
    }

    LaunchedEffect(reachedBottom) {
        if (reachedBottom && canLoadMore && !isLoadingMore) onLoadMore()
    }

    LazyColumn(
        state          = listState,
        modifier       = modifier,
        contentPadding = contentPadding,
    ) {
        headerContent?.let { header ->
            item(key = "playlist_header") { header() }
        }
        if (tracks.isEmpty() && emptyContent != null) {
            item(key = "empty_state") { emptyContent() }
        }
        itemsIndexed(tracks, key = { i, t -> "${i}_${t.id}" }) { _, track ->
            TrackRow(
                track       = track,
                isPlaying   = currentTrackId == track.id && isPlaying,
                onClick     = { onTrackClick(track) },
                onLongClick = onTrackLongClick?.let { handler -> { handler(track) } },
            )
        }
        if (isLoadingMore) {
            item(key = "loading_more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

// ── Refresh-error dialog ──────────────────────────────────────────────────────

@Composable
private fun RefreshErrorDialog(error: String, onDismiss: () -> Unit) {
    val isRateLimit   = error.contains("429")
    val retryAfterSec = if (isRateLimit)
        Regex("Retry-After=(\\d+)").find(error)?.groupValues?.get(1)?.toLongOrNull() else null
    val retryDisplay  = when {
        retryAfterSec == null -> null
        retryAfterSec >= 3600 -> "${retryAfterSec / 3600}h ${(retryAfterSec % 3600) / 60}m"
        retryAfterSec >= 60   -> "${retryAfterSec / 60}m ${retryAfterSec % 60}s"
        else                  -> "${retryAfterSec}s"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (isRateLimit) "Rate Limited" else "Refresh Error") },
        text  = {
            Text(if (isRateLimit) buildString {
                append("Spotify is rate limiting requests.")
                if (retryDisplay != null) append("\n\nRetry-After: $retryDisplay")
                append("\n\nCached data is shown. Wait, then retry.")
            } else error)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

