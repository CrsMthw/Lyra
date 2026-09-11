@file:Suppress("ConfigurationScreenWidthHeight")

package com.crsmthw.lyra.ui.screens.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.screenTransitionSpec
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor
import java.io.File

// ── Two-pane (unfolded / tablet) ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
internal fun TwoPaneLayout(
    state                 : LibraryUiState,
    viewModel             : LibraryViewModel,
    playerViewModel       : PlayerViewModel,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
    onRequestPlayer       : () -> Unit,
    onOpenAlbum           : (String) -> Unit = {},
    onOpenArtist          : (String) -> Unit = {},
    onOpenStats           : () -> Unit = {},
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
                        onOpenAlbum           = onOpenAlbum,
                        onOpenArtist          = onOpenArtist,
                        onOpenStats           = onOpenStats,
                        onPlayTopTrack        = { idx ->
                            state.topTracks.getOrNull(idx)?.let { tapped ->
                                playerViewModel.playTrack(
                                    uri  = tapped.uri,
                                    uris = state.topTracks.drop(idx).map { it.uri },
                                )
                            }
                        },
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
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search),
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
                    // "white-flash"). The slide uses the app-wide `screenTransitionSpec()`, matching every
                    // other pane/screen swap. Standard `AnimatedContent` retains the exiting pane correctly now
                    // that `selectPlaylist` flips to the detail content-ready (no mid-transition emission
                    // to make it cull the outgoing) — see `LibraryViewModel.selectPlaylist`.
                    val rightPaneSlideSpec = screenTransitionSpec<IntOffset>()
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
                                    Text(stringResource(R.string.library_select_playlist),
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
