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
import com.crsmthw.lyra.util.horizontalSystemBarsPadding
import com.crsmthw.lyra.util.screenTransitionSpec
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import com.crsmthw.lyra.util.rememberSearchBarMorphClip
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor
import java.io.File
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import com.crsmthw.lyra.ui.components.LocalPopOutPanelOpen

// ── Single pane (phone / folded) ─────────────────────────────────────────────

/**
 * What the single-pane detail↔browser transition should do on the next state emission.
 *
 * The gesture and the ViewModel emission that commits it are asynchronous with respect to each
 * other, so the driving effect needs to know WHY the state looks the way it does — a detail key
 * that is still set means "unwind" after a cancelled gesture but "wait" after a committed one.
 * Three booleans could encode this, but they admit combinations that don't exist.
 *
 * **[Committing] and [Finishing] are two halves of ONE commit, and the split is the fix for a
 * wedge** (2026-09-14): a back commits, the browser pane slides in for ~300 ms with tappable cards,
 * and a card tapped during that slide sets `detailKey` again. The four cases the pair has to tell
 * apart — all of which reach the effect as "it re-ran with some `detailKey`":
 *
 * 1. **Button back, waiting for the emission** — [Committing] with `detailKey` still the OUTGOING
 *    key: park. `clearSelection()` fires in the same dispatch as the phase but is read back a frame
 *    or two later, and animating while the key still points at the detail would reverse the close.
 * 2. **Gesture commit, waiting for the emission** — identical, and identically parked. When the
 *    emission lands, the seek has already set `targetState = null`, so `animateTo(null)` finds the
 *    target unchanged and plays only the remainder from the gesture's `fraction`.
 * 3. **A redirect to a DIFFERENT pane mid-slide** — the emission has landed, so the phase is
 *    [Finishing] and any further `detailKey` can only be a new selection: drop to [Idle] and let
 *    its own branch animate there. The `animateTo(null)` the re-key cancelled had already set
 *    `targetState = null`, so `targetState != detailKey` holds and the swap runs forward into the
 *    new pane (with `moveAnimationToInitialState` smoothing the interruption).
 * 4. **A reselect of the SAME pane mid-slide** — same path: `targetState` is `null`, `detailKey` is
 *    the old key, so it animates back into that pane instead of freezing half-slid.
 *
 * Without [Finishing], cases 3 and 4 re-keyed the effect into the parked [Committing] branch:
 * nothing ran, the cancelled `animateTo` was never resumed, and the `AnimatedContent` sat frozen
 * with both panes partially offset (FAB gone, `isShowingDetail` true, only a back gesture out).
 * Merging the two — a bare `else -> Idle` on [Committing] — is the other broken shape: it fires in
 * cases 1 and 2 as well, where `detailKey` is the outgoing key and [Idle] would animate straight
 * back INTO the detail the user just left.
 *
 * Why [Idle] tests `targetState` and not `currentState`: a committed gesture never reaches [Idle]
 * (it goes [Committing] → [Finishing]), and every way a running `animateTo` is cancelled here also
 * changes `detailKey` or the phase — so there is no reachable state with `targetState == detailKey`,
 * `currentState != detailKey` and a frozen fraction for `currentState` to have to catch.
 *
 * Documented residual: [Committing] parks on `detailKey != null` unconditionally, so a card tap
 * landing inside the very emission that carries `clearSelection()` (`StateFlow` conflates, so the
 * effect would only ever see the new key) would still park. Deliberate — the alternative test
 * `detailKey != transitionState.currentState` ALSO matches a back committed while the pane was
 * still ENTERING (`currentState` is `null` then, since `seekTo` never assigns it), and would bounce
 * the detail back in against the user's own back.
 */
private enum class BackPhase { Idle, Seeking, Committing, Finishing, Cancelled }

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
    /** Browser scroll positions (one per filter tab), owned by `LibraryScreen` so they survive this
     *  pane being disposed while a detail is open (the `AnimatedContent` below does not save its
     *  children's state). */
    browserListStates     : LibraryBrowserListStates,
    /** Browser app-bar collapse state, owned by `LibraryScreen` for the same reason as
     *  [browserListStates] — this layout disposes the browser pane while a detail is open. */
    browserBarState       : TopAppBarState,
    viewModel             : LibraryViewModel,
    playerViewModel       : PlayerViewModel,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
    onRequestPlayer       : () -> Unit,
    onOpenAlbum           : (String) -> Unit = {},
    onOpenArtist          : (String) -> Unit = {},
    onOpenShow            : (String) -> Unit = {},
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
    //
    // SEEDED SYNCHRONOUSLY from the StateFlow's current value; do NOT "simplify" this back to a
    // literal `false`. `collectAsStateWithLifecycle` uses its initial value in its own `remember`,
    // so a literal one is re-applied every time this layout re-enters composition — i.e. on every
    // pop back INTO the Library. The flow then emits `true` a frame later, and `fabBottomPadding`
    // below SPRINGS the search FAB up 74dp while the Search screen's bar→FAB `sharedBounds` is
    // already animating toward it: the bounds animation retargets to the moving FAB and the morph
    // stutters twice on the way (device pass 2026-09-13, checklist 15 — "stops in two positions",
    // "only when miniplayer is on screen"). `animateDpAsState` does not animate its first value, so
    // with a correct seed a FAB whose target is 90.dp is BORN at 90.dp and the morph is smooth.
    // Springing `fabBottomPadding` stays correct (docs/MOTION.md puts it in the "spring it" column)
    // precisely BECAUSE of this seed.
    // (The seed now comes from the ViewModel's derived StateFlow's own current value.)
    val hasCurrentTrack by playerViewModel.hasCurrentTrack.collectAsStateWithLifecycle()

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
    // The pop-out panel can't open in single-pane, so the gate is inert here — kept for symmetry
    // with TwoPaneLayout (see LocalPopOutPanelOpen's KDoc).
    val panelOpen = LocalPopOutPanelOpen.current
    BackHandler(enabled = state.selectionMode && !panelOpen) { viewModel.exitSelectionMode() }
    BackHandler(enabled = state.reorderMode && !panelOpen) { viewModel.exitReorderMode() }

    PredictiveBackHandler(enabled = isShowingDetail && !state.selectionMode && !state.reorderMode) { events ->
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
                // Waiting for clearSelection's emission — park while the key still points at the
                // detail (cases 1 and 2 in BackPhase's KDoc). When the emission lands, hand over to
                // Finishing rather than animating here: that is what tells a LATER detailKey apart
                // from this one, so a card tapped during the ~300 ms slide is a redirect instead of
                // a wedge.
                BackPhase.Committing -> if (detailKey == null) backPhase = BackPhase.Finishing
                // The emission has landed, so the close can run. animateTo(null) finds the target
                // UNCHANGED after a gesture (the seek already set it) and resumes from the
                // gesture's fraction over the remaining duration instead of restarting from 0.
                // A detailKey HERE is a NEW selection arriving mid-slide (cases 3 and 4), so the
                // phase just drops and Idle's branch animates to it from wherever this one got to.
                // The phase is cleared AFTER animateTo, never before — clearing it first re-keys
                // this effect and cancels the very animation it just started.
                BackPhase.Finishing -> {
                    if (detailKey == null) transitionState.animateTo(null)
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
                // non-gesture back) — and the pane tapped during a committed close, handed here by
                // Finishing. Same-key emissions fall through to Unit — they carry data, not a pane
                // change, and must not touch the transition. `targetState` is the right side of the
                // comparison; see BackPhase's KDoc for why `currentState` never needs to be.
                BackPhase.Idle -> if (transitionState.targetState != detailKey) {
                    transitionState.animateTo(detailKey)
                }
                BackPhase.Seeking -> Unit   // unreachable: guarded by the branch above
            }
        }
    }


    Box(modifier = Modifier
        .fillMaxSize()
        .horizontalSystemBarsPadding()
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
                        listStates     = browserListStates,
                        barState       = browserBarState,
                        viewModel      = viewModel,
                        onOpenSettings = onOpenSettings,
                        onOpenAlbum    = onOpenAlbum,
                        onOpenArtist   = onOpenArtist,
                        onOpenShow     = onOpenShow,
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
            // clipInOverlayDuringTransition morphs the OUTLINE (SoftBurst ↔ stadium): sharedBounds
            // on its own only lerps the bounds and cross-fades the two contents, so the silhouette
            // stayed bar-shaped and then snapped to the full cookie. The bar end passes the same
            // clip, so it is continuous in both directions — see util/SearchBarMorph.kt.
            val fabSharedModifier: Modifier =
                if (sharedTransitionScope != null && animatedContentScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            sharedContentState      = rememberSharedContentState(key = SEARCH_BAR_SHARED_KEY),
                            animatedVisibilityScope = animatedContentScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                            clipInOverlayDuringTransition = rememberSearchBarMorphClip(),
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
