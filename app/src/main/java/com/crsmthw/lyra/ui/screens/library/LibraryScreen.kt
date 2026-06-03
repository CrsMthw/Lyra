@file:Suppress("ConfigurationScreenWidthHeight")

package com.crsmthw.lyra.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.crsmthw.lyra.ui.components.MiniPlayer
import com.crsmthw.lyra.ui.components.MiniPlayerHolder
import com.crsmthw.lyra.ui.components.PlayerPopOutPanel
import com.crsmthw.lyra.ui.components.PlaylistCard
import com.crsmthw.lyra.ui.components.TrackRow
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.screens.player.RepeatMode
import com.crsmthw.lyra.util.toTimeString
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HERO_HEIGHT = 220.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(
    viewModel             : LibraryViewModel,
    playerViewModel       : PlayerViewModel,
    onOpenPlayer          : () -> Unit,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state        by viewModel.uiState.collectAsStateWithLifecycle()
    val isWideScreen  = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)

    if (isWideScreen) {
        TwoPaneLayout(
            state                 = state,
            viewModel             = viewModel,
            playerViewModel       = playerViewModel,
            onOpenPlayer          = onOpenPlayer,
            onOpenSearch          = onOpenSearch,
            onOpenSettings        = onOpenSettings,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope  = animatedContentScope,
        )
    } else {
        SinglePaneLayout(
            state                 = state,
            viewModel             = viewModel,
            playerViewModel       = playerViewModel,
            onOpenPlayer          = onOpenPlayer,
            onOpenSearch          = onOpenSearch,
            onOpenSettings        = onOpenSettings,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope  = animatedContentScope,
        )
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
    onOpenPlayer          : () -> Unit,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
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
                    (slideInHorizontally { it } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut(tween(200)))
                } else {
                    (slideInHorizontally { -it } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally { it / 4 } + fadeOut(tween(200)))
                }
            },
            label = "library_detail_transition",
        ) { snapshot ->
            val isDetailSnapshot = snapshot.currentPlaylist != null ||
                                   snapshot.isLoadingTracks ||
                                   snapshot.currentTracks.isNotEmpty()
            if (isDetailSnapshot) {
                RightPaneContent(
                    state           = snapshot,
                    viewModel       = viewModel,
                    playerViewModel = playerViewModel,
                    mosaicDir       = mosaicDir,
                    onTrackClick    = onOpenPlayer,
                    onRefresh       = viewModel::refreshCurrentTracks,
                    onBack          = { viewModel.clearSelection() },
                    barWindowInsets = WindowInsets.statusBars,
                )
            } else {
                LibraryBrowserPane(
                    state          = snapshot,
                    viewModel      = viewModel,
                    onOpenSettings = onOpenSettings,
                    isLandscape    = isLandscape,
                )
            }
        }

        // MiniPlayer floats on top — state collection is isolated inside MiniPlayerHolder.
        val fabBottomPadding by animateDpAsState(
            targetValue   = if (hasCurrentTrack) 90.dp else 16.dp,
            animationSpec = tween(300),
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
        }

        MiniPlayerHolder(
            playerViewModel         = playerViewModel,
            onExpand                = onOpenPlayer,
            modifier                = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            sharedTransitionScope   = sharedTransitionScope,
            animatedVisibilityScope = animatedContentScope,
        )

        if (!isShowingDetail) {
            FloatingActionButton(
                onClick  = onOpenSearch,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = fabBottomPadding),
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }
    }
}

// ── Library browser pane ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryBrowserPane(
    state                 : LibraryUiState,
    viewModel             : LibraryViewModel,
    onOpenSettings        : () -> Unit,
    isLandscape           : Boolean,
    modifier              : Modifier = Modifier,
    applyStatusBarPadding : Boolean = true,   // false when parent already applied statusBarsPadding
) {
    var showRefreshErrorDialog by remember { mutableStateOf(false) }
    val listState      = rememberLazyListState()
    val density        = LocalDensity.current
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val context        = LocalContext.current
    val mosaicDir = remember { File(context.filesDir, "mosaics") }

    val likedSongsSelected = state.currentPlaylist == null &&
        (state.isLoadingTracks || state.currentTracks.isNotEmpty())
    val selectedPlaylistId = state.currentPlaylist?.id

    val userId      = state.user?.id
    val myPlaylists = if (userId != null) state.playlists.filter { it.owner.id == userId } else state.playlists
    val following   = if (userId != null) state.playlists.filter { it.owner.id != userId } else emptyList()

    // Shared list items — identical in both portrait and landscape LazyColumns
    val listBody: LazyListScope.() -> Unit = {
        item(key = "liked") {
            LikedSongsCard(
                count      = state.likedSongCount,
                isSelected = likedSongsSelected,
                onOpen     = { viewModel.selectLikedSongs() },
                onPlay     = { viewModel.playPlaylist("spotify:user:${state.user?.id}:collection") },
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
                        PlaylistCard(
                            playlist   = playlist,
                            mosaicFile = if (playlist.id in state.playlistsWithMosaics)
                                File(mosaicDir, "${playlist.id}.png") else null,
                            onClick    = { viewModel.selectPlaylist(playlist) },
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
                PlaylistListCard(
                    playlist   = playlist,
                    mosaicFile = if (playlist.id in state.playlistsWithMosaics)
                        File(mosaicDir, "${playlist.id}.png") else null,
                    isSelected = playlist.id == selectedPlaylistId,
                    onClick    = { viewModel.selectPlaylist(playlist) },
                    onPlay     = { viewModel.playPlaylist(playlist.uri) },
                )
            }
        }
        if (following.isNotEmpty()) {
            item(key = "following_header") {
                Text("Following", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            }
            items(following, key = { it.id }) { playlist ->
                PlaylistListCard(
                    playlist   = playlist,
                    mosaicFile = if (playlist.id in state.playlistsWithMosaics)
                        File(mosaicDir, "${playlist.id}.png") else null,
                    isSelected = playlist.id == selectedPlaylistId,
                    onClick    = { viewModel.selectPlaylist(playlist) },
                    onPlay     = { viewModel.playPlaylist(playlist.uri) },
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
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }

    if (isLandscape) {
        // Landscape: overlay TopAppBar over scrollable content (always-on bar)
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

            TopAppBar(
                modifier     = Modifier
                    .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                windowInsets = WindowInsets(0),
                title        = { Text("Lyra") },
                actions      = actionsBar,
                colors       = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    } else {
        // Portrait: LargeTopAppBar physically moves "Lyra" title from hero into the bar
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        Column(modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)) {
            LargeTopAppBar(
                title          = {
                    val fraction     = scrollBehavior.state.collapsedFraction
                    val expandedSp   = MaterialTheme.typography.displayLarge.fontSize.value
                    val collapsedSp  = MaterialTheme.typography.titleLarge.fontSize.value
                    Text(
                        text     = "Lyra",
                        fontSize = (expandedSp + (collapsedSp - expandedSp) * fraction).sp,
                    )
                },
                actions        = actionsBar,
                scrollBehavior = scrollBehavior,
                expandedHeight = HERO_HEIGHT,
                windowInsets   = if (applyStatusBarPadding) WindowInsets.statusBars else WindowInsets(0),
                colors         = TopAppBarDefaults.topAppBarColors(
                    containerColor         = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            Box(modifier = Modifier
                .weight(1f)
                .fillMaxWidth()) {
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
                                        .padding(top = 12.dp),
                                )
                            } else {
                                PullToRefreshDefaults.Indicator(
                                    state        = portraitPtrState,
                                    isRefreshing = false,
                                    modifier     = Modifier.align(Alignment.TopCenter),
                                )
                            }
                        },
                    ) {
                        LazyColumn(
                            state          = listState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) { listBody() }
                    }
                }
            }
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
    onOpenPlayer          : () -> Unit,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    var showPlayerPanel by rememberSaveable { mutableStateOf(false) }
    val config       = LocalConfiguration.current
    val isLandscape  = config.screenWidthDp > config.screenHeightDp
    val isShortScreen = config.screenHeightDp < 500
    val context      = LocalContext.current
    val mosaicDir    = remember { File(context.filesDir, "mosaics") }
    val density      = LocalDensity.current
    val navBarPx     = WindowInsets.navigationBars.getBottom(density)

    val scrimAlpha by animateFloatAsState(
        targetValue   = if (showPlayerPanel) 0.45f else 0f,
        animationSpec = tween(300),
        label         = "scrim",
    )

    // Auto-select Liked Songs on first load so right pane is never blank
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading &&
            state.currentPlaylist == null &&
            state.currentTracks.isEmpty() &&
            !state.isLoadingTracks) {
            viewModel.selectLikedSongs()
        }
    }

    BackHandler(enabled = showPlayerPanel) { showPlayerPanel = false }

    SharedTransitionLayout {
    Box(modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
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
                        applyStatusBarPadding = false,
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
                    FloatingActionButton(
                        onClick  = onOpenSearch,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
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
                    // Full state as targetState so each scope gets its own frozen snapshot:
                    // outgoing holds old playlist data, incoming holds new playlist data.
                    // Slide-only (no alpha/fade) means neither scope ever becomes transparent —
                    // eliminating the white flash that alpha-based transitions cause in light mode.
                    AnimatedContent(
                        targetState    = state,
                        contentKey     = { s -> s.currentPlaylist?.id to (s.currentPlaylist == null) },
                        modifier       = Modifier.fillMaxSize(),
                        transitionSpec = {
                            slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 5 } togetherWith
                            slideOutHorizontally(tween(200, easing = FastOutLinearInEasing)) { -it / 5 }
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
                                onTrackClick    = { showPlayerPanel = true },
                                onRefresh       = viewModel::refreshCurrentTracks,
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

                    // visible=!showPlayerPanel drives the mini player's own AnimatedVisibility,
                    // giving its scope to the shared element so it exits when the panel opens.
                    MiniPlayerHolder(
                        playerViewModel       = playerViewModel,
                        onExpand              = { if (!isShortScreen) showPlayerPanel = true else onOpenPlayer() },
                        visible               = !showPlayerPanel,
                        modifier              = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                        sharedTransitionScope = this@SharedTransitionLayout,
                    )
                }
            }
        }
    }   // closes inset-padded Box

        // ── Pop-out player (hidden in short landscape) ────────────────────────
        if (!isShortScreen) {
            if (scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrimAlpha))
                        .clickable(enabled = showPlayerPanel) { showPlayerPanel = false },
                )
            }
            PlayerPopOutPanel(
                showPanel                  = showPlayerPanel,
                playerViewModel            = playerViewModel,
                onClose                    = { showPlayerPanel = false },
                onFullScreen               = onOpenPlayer,
                localSharedTransitionScope = this@SharedTransitionLayout,
                navSharedTransitionScope   = sharedTransitionScope,
                navAnimatedContentScope    = animatedContentScope,
                modifier                   = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = 8.dp, end = 16.dp, bottom = 16.dp)
                    .fillMaxWidth(0.54f)
                    .navigationBarsPadding(),
            )
        }
    }
    } // SharedTransitionLayout
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RightPaneContent(
    state           : LibraryUiState,
    viewModel       : LibraryViewModel,
    playerViewModel : PlayerViewModel,
    mosaicDir       : File,
    onTrackClick    : () -> Unit,
    onRefresh       : () -> Unit,
    onBack          : (() -> Unit)? = null,
    barWindowInsets : WindowInsets = WindowInsets(0),
) {
    val currentTrackId by remember {
        playerViewModel.uiState.map { it.currentTrack?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    val isPlayingState by remember {
        playerViewModel.uiState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
    val playlist     = state.currentPlaylist
    val isLikedSongs = playlist == null
    val mosaicFile   = playlist?.let { p ->
        if (p.id in state.playlistsWithMosaics) File(mosaicDir, "${p.id}.png") else null
    }
    val artUrl       = mosaicFile?.absolutePath
        ?: playlist?.thumbnailUrl?.takeIf { it.isNotBlank() }
    val likedSongsStr = stringResource(R.string.liked_songs)
    val playlistName  = playlist?.name ?: likedSongsStr
    val trackCount   = when {
        isLikedSongs && state.likedSongsTotal > 0 -> state.likedSongsTotal
        isLikedSongs                              -> state.likedSongCount
        state.currentTracks.isNotEmpty()          -> state.currentTracks.size
        else                                      -> playlist.trackCount   // metadata fallback — avoids layout shift
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
    val headerTopPadding = with(density) { barWindowInsets.getTop(this).toDp() }
    val thresholdPx      = with(density) { 80.dp.toPx() }
    val snapThreshold    = thresholdPx / 2f
    val listState        = rememberLazyListState()
    val pullToRefreshState   = rememberPullToRefreshState()
    var barHeightPx   by remember { mutableIntStateOf(0) }
    val collapseProgress = remember(listState) {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> (listState.firstVisibleItemScrollOffset.toFloat() / thresholdPx).coerceIn(0f, 1f)
            }
        }
    }

    LaunchedEffect(listState, pullToRefreshState) {
        snapshotFlow { listState.isScrollInProgress to pullToRefreshState.distanceFraction }
            .collect { (isScrolling, ptrFraction) ->
                if (isScrolling) return@collect
                if (ptrFraction > 0f) return@collect
                if (state.isRefreshing) return@collect
                if (listState.firstVisibleItemIndex > 0) return@collect
                val offset = listState.firstVisibleItemScrollOffset
                if (offset == 0) return@collect
                val headerInfo = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == "playlist_header" }
                val targetOffset = if (headerInfo != null)
                    (headerInfo.size - barHeightPx).coerceAtLeast(0) else 0
                if (offset.toFloat() >= snapThreshold) {
                    val info        = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()
                    val canReachTarget = if (lastVisible == null || lastVisible.index < info.totalItemsCount - 1) {
                        true // items below viewport — can still scroll
                    } else {
                        val remaining = (lastVisible.offset + lastVisible.size + info.afterContentPadding - info.viewportSize.height).coerceAtLeast(0)
                        offset + remaining >= targetOffset
                    }
                    if (canReachTarget) {
                        try { listState.animateScrollToItem(0, scrollOffset = targetOffset) }
                        catch (_: CancellationException) { }
                    }
                    // else: list too short to collapse header — leave it wherever it settled
                } else {
                    try { listState.animateScrollToItem(0) }
                    catch (_: CancellationException) { }
                }
            }
    }

    val canLoadMore          = isLikedSongs && state.likedSongsTotal > 0 &&
                               state.likedSongsOffset < state.likedSongsTotal

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
            onLoadMore     = viewModel::loadMoreLikedSongs,
            onTrackClick   = { track ->
                if (playlist != null) {
                    val idx = state.currentTracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
                    playerViewModel.playTrack(track.uri, contextUri = playlist.uri, index = idx)
                } else {
                    playerViewModel.playFromLikedSongs(track.uri)
                }
                onTrackClick()
            },
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
            listState      = listState,
            headerContent  = {
                if (headerTopPadding > 0.dp) Spacer(Modifier.height(headerTopPadding))
                RightPaneHeader(
                    artUrl       = artUrl,
                    isLikedSongs = isLikedSongs,
                    name         = playlistName,
                    trackCount   = trackCount,
                    onPlay       = { viewModel.playPlaylist(playUri) },
                    onShuffle    = { viewModel.shufflePlaylist(playUri) },
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
        CollapsingDetailBar(
            name             = playlistName,
            collapseProgress = collapseProgress,
            onBack           = onBack,
            windowInsets     = barWindowInsets,
            modifier         = Modifier.onSizeChanged { barHeightPx = it.height },
        )
        if (onBack != null) {
            IconButton(
                onClick  = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = headerTopPadding, start = 4.dp)
                    .graphicsLayer { alpha = 1f - collapseProgress.value },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    }
}

// ── Right pane header (square art, scrollable) ────────────────────────────────

@Composable
private fun RightPaneHeader(
    artUrl      : String?,
    isLikedSongs: Boolean,
    name        : String,
    trackCount  : Int,
    onPlay      : () -> Unit,
    onShuffle   : () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Square album art — capped at 200dp so it doesn't grow huge in wide landscape panes
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val artSize = (maxWidth * 0.65f).coerceAtMost(200.dp)
        Box(
            modifier = Modifier
                .size(artSize)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp)),
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
                        modifier = Modifier.size(72.dp))
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp))
                }
            }
        }
        } // BoxWithConstraints

        Spacer(Modifier.height(12.dp))

        Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            text  = if (trackCount > 0) "$trackCount tracks" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.player_shuffle))
            }
            Button(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.player_play))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp), thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ── Liked Songs card ──────────────────────────────────────────────────────────

@Composable
private fun LikedSongsCard(
    count      : Int,
    onOpen     : () -> Unit,
    onPlay     : () -> Unit,
    isSelected : Boolean = false,
) {
    Card(
        onClick   = onOpen,
        modifier  = Modifier
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
                Text("$count songs", style = MaterialTheme.typography.bodySmall,
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
    playlist   : SpotifyPlaylist,
    mosaicFile : File?,
    isSelected : Boolean = false,
    onClick    : () -> Unit,
    onPlay     : () -> Unit,
) {
    Card(
        onClick   = onClick,
        modifier  = Modifier
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
            val thumbModel = mosaicFile ?: playlist.thumbnailUrl.takeIf { it.isNotBlank() }
            if (thumbModel != null) {
                AsyncImage(
                    model              = thumbModel,
                    contentDescription = playlist.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(56.dp).clip(thumbShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
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
                val subtitle = when {
                    playlist.trackCount > 0 -> "${playlist.trackCount} tracks"
                    playlist.owner.displayName != null -> playlist.owner.displayName
                    else -> ""
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
    contentPadding : PaddingValues = PaddingValues(bottom = 100.dp),
    listState      : androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    headerContent  : (@Composable () -> Unit)? = null,
    emptyContent   : (@Composable () -> Unit)? = null,
) {

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
                track     = track,
                isPlaying = currentTrackId == track.id && isPlaying,
                onClick   = { onTrackClick(track) },
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

// ── Collapsing detail top bar ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsingDetailBar(
    name             : String,
    collapseProgress : androidx.compose.runtime.State<Float>,
    modifier         : Modifier = Modifier,
    onBack           : (() -> Unit)? = null,
    windowInsets     : WindowInsets = WindowInsets(0),
) {
    // Read in composition so recomposition (not just draw phase) drives the alpha update.
    // The draw-phase-only graphicsLayer pattern fails inside AnimatedContent's slide layer
    // at alpha=0 — the state observation never registers on the first render.
    val progress by collapseProgress
    TopAppBar(
        modifier     = modifier.graphicsLayer { alpha = progress },
        windowInsets = windowInsets,
        navigationIcon = if (onBack != null) {
            { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            } }
        } else ({}),
        title = {
            Text(
                text     = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - progress) * 24.dp.toPx()
                },
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
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

