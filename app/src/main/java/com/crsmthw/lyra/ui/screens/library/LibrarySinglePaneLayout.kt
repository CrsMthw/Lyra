@file:Suppress("ConfigurationScreenWidthHeight")

package com.crsmthw.lyra.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.NavTransitionMillis
import com.crsmthw.lyra.util.screenTransitionSpec
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// ── Single pane (phone / folded) ─────────────────────────────────────────────

/**
 * What the single-pane detail↔browser transition should do on the next state emission.
 *
 * The gesture and the ViewModel emission that commits it are asynchronous with respect to each
 * other, so the driving effect needs to know WHY the state looks the way it does — a detail key
 * that is still set means "unwind" after a cancelled gesture but "wait" after a committed one.
 * Three booleans could encode this, but they admit combinations that don't exist.
 */
private enum class BackPhase { Idle, Seeking, Committing, Cancelled }

/**
 * Holds the last [LibraryUiState] a given detail pane saw, so an outgoing pane keeps rendering its
 * own tracks after the selection has been cleared from the live state.
 *
 * Deliberately NOT snapshot-backed: it is assigned during composition, and a `MutableState` write
 * to something read in the same pass would schedule an extra recomposition. Staleness isn't a risk
 * because the scope that assigns it also reads the live state, so any change recomposes it.
 */
private class PaneStateHolder(var value: LibraryUiState)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
internal fun SinglePaneLayout(
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
    val isShowingDetail = state.isShowingDetail
    val detailKey       = state.detailKey
    val isLandscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val context   = LocalContext.current
    val mosaicDir = remember { File(context.filesDir, "mosaics") }

    // Only track presence of a current track — changes infrequently, not every second.
    val hasCurrentTrack by remember {
        playerViewModel.uiState.map { it.currentTrack != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)

    // ── Predictive back: detail → browser ────────────────────────────────────────────────────
    // Backing out of a playlist / Liked Songs is GESTURE-DRIVEN, so the container transform (the
    // art flying back into its browser card) tracks the finger instead of firing as a fixed
    // animation after the gesture commits — the same treatment the nav-level screens get from
    // NavHost's predictivePopEnter/ExitTransition.
    //
    // The pane swap is not a nav destination (both panes live in this one screen), so there is no
    // NavHost to do this for us: we drive the AnimatedContent from a SeekableTransitionState the
    // same way NavHost does internally (navigation-compose, NavHost.kt).
    //
    // THE LOAD-BEARING CHOICE: the transition's state is `detailKey` (a String?), NOT the whole
    // LibraryUiState. Animating over the state object would mean every ordinary emission — track
    // pagination, a refresh landing, a mosaic finishing — is a new target, and SeekableTransition-
    // State compares targets with `equals`: a same-pane emission mid-animation would reset
    // `fraction` to 0 and restart the swap (the blank-screen class of bug in docs/MOTION.md),
    // while suppressing those emissions would freeze the pane at stale data. Keying on identity
    // makes both impossible — data reaches the panes as ordinary state reads (see the content
    // lambda), completely outside the transition.
    val transitionState = remember { SeekableTransitionState(detailKey) }
    val transition      = rememberTransition(transitionState, label = "library_detail_transition")

    var backPhase    by remember { mutableStateOf(BackPhase.Idle) }
    var backProgress by remember { mutableFloatStateOf(0f) }

    // Multi-select owns back while it is active: leaving the mode keeps the user IN the playlist, so
    // the pane-swap seek must not start at all — hence the plain handler here AND the `!selectionMode`
    // in the predictive handler's `enabled` below. The two are mutually exclusive on that flag, so
    // the dispatcher never has to pick between them.
    BackHandler(enabled = state.selectionMode) { viewModel.exitSelectionMode() }

    PredictiveBackHandler(enabled = isShowingDetail && !state.selectionMode) { events ->
        backProgress = 0f
        backPhase    = BackPhase.Seeking   // set BEFORE collecting, so the first progress frame
        try {                              // can't race an animateTo against the seek
            events.collect { event -> backProgress = event.progress }
            // Committed. Phase flips first so the effect below parks instead of unwinding during
            // the frame or two before clearSelection's emission arrives.
            backPhase = BackPhase.Committing
            viewModel.clearSelection()
        } catch (_: CancellationException) {
            backPhase = BackPhase.Cancelled
        }
    }

    if (backPhase == BackPhase.Seeking) {
        LaunchedEffect(backProgress) {
            transitionState.seekTo(backProgress.coerceIn(0f, 1f), targetState = null)
        }
    } else {
        LaunchedEffect(detailKey, backPhase) {
            when (backPhase) {
                // Waiting for clearSelection's emission. When it lands detailKey becomes null and
                // this effect re-runs; animateTo(null) then finds the target UNCHANGED (the seek
                // already set it), so it resumes from the gesture's fraction with only the
                // remaining duration instead of restarting from 0.
                BackPhase.Committing -> if (detailKey == null) {
                    transitionState.animateTo(null)
                    backPhase = BackPhase.Idle
                }
                // Released without committing: unwind the seek back to the detail pane. The
                // duration is scaled by how far the gesture actually got, so a cancel at 5%
                // snaps back quickly instead of taking a full transition.
                BackPhase.Cancelled -> {
                    // totalDurationNanos is the max across the transition's children. The slide and
                    // fade always contribute, so this is normally ~NavTransitionMillis — but floor it
                    // anyway: a zero total would compute tween(0) and snap back instead of easing.
                    val totalMillis = (transition.totalDurationNanos / 1_000_000)
                        .coerceAtLeast(NavTransitionMillis.toLong())
                    animate(
                        initialValue  = transitionState.fraction,
                        targetValue   = 0f,
                        animationSpec = tween((transitionState.fraction * totalMillis).toInt()),
                    ) { value, _ ->
                        // seekTo/snapTo suspend, but `animate`'s callback does not — so the work
                        // is handed back to the effect's own scope (as NavHost does internally).
                        this@LaunchedEffect.launch {
                            if (value > 0f) transitionState.seekTo(value)
                            if (value == 0f) transitionState.snapTo(detailKey)
                        }
                    }
                    backPhase = BackPhase.Idle
                }
                // Ordinary pane change (tapping a playlist, tapping a different one, or a
                // non-gesture back). Same-key emissions fall through to Unit — they carry data,
                // not a pane change, and must not touch the transition.
                BackPhase.Idle -> if (transitionState.targetState != detailKey) {
                    transitionState.animateTo(detailKey)
                }
                BackPhase.Seeking -> Unit   // unreachable: guarded by the branch above
            }
        }
    }


    Box(modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
    ) {
        // Browser ↔ detail swap as a CONTAINER TRANSFORM: a local SharedTransitionLayout wraps the
        // AnimatedContent so the tapped card's art (`lib-art-<id>`) flies into the detail hero and
        // morphs square→cookie (the hero side morphs the clip — see `TrackListHero`). The pane swap
        // itself stays a gentle slide+fade so the morphing art carries the motion; the slide uses the
        // app-wide `screenTransitionSpec()` (see `util/Motion.kt` — finite, so the outgoing pane is
        // disposed the frame the motion ends and cannot keep catching touches while invisible) and the
        // cross-fade keeps a tween (alpha must not overshoot). Keys are namespaced "lib-art-*" so they
        // never collide with the nav-level
        // "album-art" morph; the FAB + scrims sit OUTSIDE this STL (they use the nav-level scope).
        val slideSpec = screenTransitionSpec<IntOffset>()
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            val libSharedScope = this
            // Driven by `transition` (the SeekableTransitionState above) rather than by a
            // targetState of its own — that is what lets the back gesture seek this swap frame by
            // frame. The label lives on rememberTransition; this overload has no `label` param.
            transition.AnimatedContent(
                modifier     = Modifier.fillMaxSize(),
                contentKey   = { it },
                transitionSpec = {
                    val enteringDetail = targetState != null
                    if (enteringDetail) {
                        (slideInHorizontally(slideSpec) { it / 10 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 12 } + fadeOut(tween(220)))
                    } else {
                        (slideInHorizontally(slideSpec) { -it / 10 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(slideSpec) { it / 12 } + fadeOut(tween(220)))
                    }
                },
            ) { paneKey ->
                val acScope = this
                if (paneKey != null) {
                    // The live `state` describes the INCOMING pane, so the outgoing detail pane —
                    // still on screen, sliding away, its tracks already cleared from the state —
                    // has to render from the last state that was actually its own. Each pane key
                    // gets its own holder, refreshed while it is the current pane and frozen once
                    // it isn't. Written during composition (not from an effect) so a pane is never
                    // shown a stale value on its first frame; the enclosing scope reads `state`,
                    // so live data still recomposes the current pane.
                    val paneState = remember(paneKey) { PaneStateHolder(state) }
                    if (state.detailKey == paneKey) paneState.value = state
                    RightPaneContent(
                        state           = paneState.value,
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
                        state          = state,
                        viewModel      = viewModel,
                        onOpenSettings = onOpenSettings,
                        isLandscape    = isLandscape,
                        onOpenAlbum    = onOpenAlbum,
                        onOpenArtist   = onOpenArtist,
                        onOpenStats    = onOpenStats,
                        onPlayTopTrack = { idx ->
                            state.topTracks.getOrNull(idx)?.let { tapped ->
                                playerViewModel.playTrack(
                                    uri  = tapped.uri,
                                    uris = state.topTracks.drop(idx).map { it.uri },
                                )
                            }
                        },
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
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search),
                    modifier = Modifier.size(FloatingActionButtonDefaults.MediumIconSize))
            }
        }
    }
}
