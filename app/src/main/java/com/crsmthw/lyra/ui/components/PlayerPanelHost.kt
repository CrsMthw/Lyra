package com.crsmthw.lyra.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionDefaults
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.SharedContentState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import com.crsmthw.lyra.util.confirm
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel

// Wraps a screen's content with a floating mini player and (on wide screens) a pop-out
// player panel. The `onRequestPlayer` lambda passed to `content` opens the panel on wide
// screens and calls `onOpenPlayer` on narrow screens, so track-tap handlers don't need to
// know which mode they're in.
//
// Two opt-ins exist for the screens that are a single full-width list rather than a two-pane
// browser (Stats, Search): `miniPlayerFullWidth` keeps the bar full-width on wide screens instead of
// pinning it to the right 58%, and `miniPlayerAvoidsIme` lifts it above the software keyboard.
// Both default to the two-pane behaviour Library/Album/Artist already had.
//
// On EXTRA-WIDE screens (≥1200dp, e.g. a tablet in landscape) the player lives in a permanent
// docked third pane hosted by `LyraNavGraph` — OUTSIDE the per-screen nav transition, so it
// doesn't slide/fade when navigating between browse screens. Here we just render the screen
// content and suppress the mini player + pop-out so nothing competes with that docked pane.
@OptIn(ExperimentalSharedTransitionApi::class)
/**
 * True while this host's pop-out player panel is open. A hosted screen that registers its own
 * `BackHandler` MUST gate it on `!LocalPopOutPanelOpen.current`: `BackHandler` priority is
 * registration order (the handler composed LAST among the enabled ones wins), and that order is
 * not stable — a screen whose layout is (re)composed after this host already exists (unfolding from
 * single- to two-pane composes `TwoPaneLayout` fresh) registers AFTER the host's handler and
 * outranks it, so back cancelled a Library selection behind the panel's scrim while the panel
 * stayed open (device pass 2026-09-12; a nav-mode switch recreated the Activity and "fixed" it).
 * Making the two handlers mutually exclusive on this flag holds regardless of composition order.
 */
val LocalPopOutPanelOpen: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * True while `PlayerScreen` is one of the two ends of the nav transition running right now — it
 * stays listed as a visible nav entry for the whole of its enter OR exit transition, so this covers
 * both directions. Provided by `LyraNavGraph`; read here to decide whether the mini player's
 * `"album-art"` shared element may MATCH — see the Nav Scope Gate comment inside [PlayerPanelHost].
 *
 * Defaults to `true` so that anywhere the provider is out of scope (a preview, a future host) the
 * mini player behaves exactly as it did before the gate existed.
 */
val LocalPlayerRouteVisible: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

/**
 * A [SharedTransitionScope.SharedContentConfig] that enables the shared element only while
 * [enabled] is true — WITHOUT ever adding or removing the modifier.
 *
 * That distinction is the whole point. A shared element added to an already-composed screen at the
 * instant a transition begins is too late to be captured as the EXIT participant (this is exactly
 * what made an earlier `isRunning` scope gate morph on push but not back). Here the node stays
 * attached for the screen's whole life and only its `isEnabled` flips; `SharedContentNode` observes
 * that read (`observeReads(sharedElement.observingVisibilityChange)`, which reads
 * `SharedElementEntry.isEnabled`), so the match is re-evaluated in the layout pass instead of
 * waiting on a recomposition to install a node.
 *
 * `shouldKeepEnabledForOngoingAnimation` is left at its default `true`, so flipping [enabled] off
 * mid-morph cannot strand a half-finished animation.
 */
@Composable
private fun rememberMatchWhenConfig(enabled: Boolean): SharedTransitionScope.SharedContentConfig {
    val enabledState = rememberUpdatedState(enabled)
    return remember {
        object : SharedTransitionScope.SharedContentConfig {
            override val SharedContentState.isEnabled: Boolean get() = enabledState.value
        }
    }
}

@Composable
fun PlayerPanelHost(
    playerViewModel         : PlayerViewModel,
    onOpenPlayer            : () -> Unit,
    modifier                : Modifier = Modifier,
    onOpenQueue             : () -> Unit = {},
    miniPlayerFullWidth     : Boolean = false,
    miniPlayerAvoidsIme     : Boolean = false,
    navSharedTransitionScope: SharedTransitionScope? = null,
    navAnimatedContentScope : AnimatedContentScope? = null,
    content                 : @Composable BoxScope.(onRequestPlayer: () -> Unit) -> Unit,
) {
    val density        = LocalDensity.current
    val haptics        = LocalHapticFeedback.current
    val containerSize  = LocalWindowInfo.current.containerSize
    val screenWidthDp  = with(density) { containerSize.width.toDp() }
    val screenHeightDp = with(density) { containerSize.height.toDp() }
    val isWideScreen   = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(600)
    val isShortScreen  = screenHeightDp < 500.dp
    val canShowPanel   = isWideScreen && !isShortScreen
    // Mirror of LyraNavGraph's docked-pane gate: when the docked third pane is up, drop the mini
    // player + pop-out entirely. Measured width (not isWidthAtLeastBreakpoint(1200), whose default
    // V1 width buckets cap at 840dp). `containerSize` is the WINDOW, so this still reads 1280dp even
    // though this host is laid out into the narrower left region.
    val isExtraWide    = screenWidthDp >= 1200.dp && screenHeightDp >= 600.dp

    if (isExtraWide) {
        Box(modifier = modifier.fillMaxSize()) { content {} }   // player is the docked pane
        return
    }

    val maxPanelHeight = screenHeightDp * 0.8f
    val focusManager   = LocalFocusManager.current
    val keyboard       = LocalSoftwareKeyboardController.current

    var showPlayerPanel by rememberSaveable { mutableStateOf(false) }

    // Single source of truth for the pop-out panel's presence on screen. Drives both the panel's
    // own AnimatedVisibility (so the gate below reads the SAME animation that's rendering) and the
    // mini player's nav-scope gate. Using this Transition avoids the scrim-tween-vs-panel-spring
    // desync that a separate timer would have.
    val panelTransition = updateTransition(showPlayerPanel, label = "panelPresence")

    // Dismiss panel when folding — canShowPanel goes false on narrow screens
    LaunchedEffect(canShowPanel) {
        if (!canShowPanel) showPlayerPanel = false
    }

    val scrimAlpha by animateFloatAsState(
        targetValue   = if (showPlayerPanel) 0.45f else 0f,
        animationSpec = tween(300),
        label         = "panelHostScrim",
    )

    // Opening the pop-out drops any text focus first. Under `enableEdgeToEdge()` the window is NOT
    // resized for the IME, and the panel below deliberately keeps plain `navigationBarsPadding()`
    // (see the comment there), so with a keyboard up the panel's lower half — seek bar, transport
    // row, action row — would sit underneath it. Search auto-focuses its field, so that is the
    // normal case there, not an edge case. `clearFocus(force = true)` is the load-bearing half:
    // `hide()` alone leaves the field focused, and dismissing a sheet the action row opened can
    // then hand focus back and re-show the IME under the panel. Unconditional inside `canShowPanel`
    // — a no-op on Library/Album/Artist, where nothing is focused — rather than keyed on
    // `miniPlayerAvoidsIme`, which describes the mini player's inset, not whether an IME is up.
    val onRequestPlayer: () -> Unit = {
        if (canShowPanel) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            showPlayerPanel = true
        } else onOpenPlayer()
    }

    SharedTransitionLayout {
        Box(modifier = modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalPopOutPanelOpen provides showPlayerPanel) {
                content(onRequestPlayer)
            }

            // Scrim behind the panel.
            //
            // The tap-to-dismiss modifier is only installed while the panel is actually OPEN, and is
            // dropped entirely while the scrim fades out. `clickable(enabled = false)` is NOT enough:
            // it still installs a PointerInputModifierNode (Clickable.kt gates only the gesture
            // recognition on `enabled`), Compose stops hit testing at the topmost hit sibling, and an
            // unconsumed event does not fall through to siblings beneath it. So a disabled scrim still
            // swallowed every tap aimed at the library underneath for the whole 300ms fade — long after
            // it was visually gone (~4% opacity by 250ms). With no pointer input at all, the fading
            // Box is just a draw modifier and taps reach the content behind it immediately.
            if (scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrimAlpha))
                        .then(
                            if (showPlayerPanel)
                                Modifier.clickable { haptics.confirm(); showPlayerPanel = false }
                            else Modifier
                        )
                )
            }

            // Mini player placement: right 58% pane on any wide screen (isWideScreen, regardless of
            // isShortScreen), full-width on narrow. This matches the two-pane layout used by Album/Artist
            // screens whenever screenWidthDp >= 600. `miniPlayerFullWidth` opts out for the screens that
            // are a SINGLE full-width list at every width (Stats, Search) — there is no right pane there
            // for a 58% bar to line up with, so pinned-right just reads as a bug on the unfolded screen.
            val miniPlacement = if (isWideScreen && !miniPlayerFullWidth)
                Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.58f)
            else
                Modifier.align(Alignment.BottomCenter)

            // Bottom inset for the mini player. `miniPlayerAvoidsIme` lifts it above the keyboard —
            // Search auto-focuses its field, so the keyboard is up on entry and a mini player left at
            // the window bottom would spend most of the screen's life hidden behind it. The inset is
            // the UNION of the IME and nav bar, which takes the larger of the two instead of stacking:
            // the IME inset already spans the nav bar, so imePadding() + navigationBarsPadding() would
            // leave a nav-bar-sized gap above the keyboard. The pop-out panel below deliberately keeps
            // plain navigationBarsPadding() — it is capped at 80% of the screen height, so lifting it
            // above the keyboard would squash it and make it jump every time the IME toggles.
            // Horizontal stays in the side list: the IME has no horizontal inset, so this keeps the
            // side nav bar clearance `navigationBarsPadding()` gives in landscape.
            // Both branches also union the DISPLAY CUTOUT: in landscape the hole-punch camera sits
            // on a side edge, and screen content clears it via horizontalSystemBarsPadding() — the
            // mini player must indent the same way or it pokes out past the content on that side
            // (device pass 2026-09-12, items 36). Horizontal + Bottom only: the top stays with the
            // content above.
            val miniBottomInset = if (miniPlayerAvoidsIme)
                Modifier.windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
            else
                Modifier.windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )

            // The mini player's SECONDARY nav scope (wide screens only) is held CONTINUOUSLY while the
            // pop-out panel is fully closed, and dropped only while it is open/animating. Continuous-
            // when-closed is required for the morph to work on POP: a shared element added after a pop
            // transition has already begun is too late to be captured as the EXIT participant (that is
            // why an isRunning gate morphed on push but not back). Dropping it while the panel is present
            // is the actual conflict window — it stops the mini and the panel from both claiming
            // "album-art" in the nav scope and fighting the local mini↔panel morph.
            // On NARROW screens the mini's PRIMARY scope (below) is ALREADY the nav scope, so this stays
            // false to avoid a double-registration that breaks the morph asymmetrically.
            val panelPresent = panelTransition.currentState || panelTransition.targetState ||
                               panelTransition.isRunning
            val miniNeedsNavScope = canShowPanel && !panelPresent
            val miniNavScope: SharedTransitionScope? = if (miniNeedsNavScope) navSharedTransitionScope else null
            val miniNavVisScope: AnimatedVisibilityScope? = if (miniNeedsNavScope) navAnimatedContentScope as? AnimatedVisibilityScope else null

            // ...and WHICH nav transition it may morph across is a second, independent gate. The scope
            // above stays put (see why in [rememberMatchWhenConfig]); this config decides whether the
            // entry may MATCH. Only PlayerScreen's big art is a wanted partner: every browse screen
            // carries this same mini player, so a browse→browse push/pop used to put two matched
            // "album-art" entries on screen, hoist the art into the shared-transition overlay and pin
            // it there while the bar it belongs to slid away underneath — a visible detach-and-snap,
            // worst over the search FAB↔bar morph, which sweeps right past it (device pass 2026-09-12).
            // It is deliberately NOT applied to the mini's primary scope on WIDE screens: there the
            // primary is the LOCAL mini↔pop-out morph, which has nothing to do with nav transitions.
            val navArtConfig = rememberMatchWhenConfig(LocalPlayerRouteVisible.current)
            MiniPlayerHolder(
                playerViewModel            = playerViewModel,
                onExpand                   = onRequestPlayer,
                visible                    = !showPlayerPanel,
                modifier                   = miniPlacement.then(miniBottomInset),
                sharedTransitionScope      = if (canShowPanel) this@SharedTransitionLayout else navSharedTransitionScope,
                animatedVisibilityScope    = if (canShowPanel) null else navAnimatedContentScope as? AnimatedVisibilityScope,
                sharedContentConfig        = if (canShowPanel) SharedTransitionDefaults.SharedContentConfig else navArtConfig,
                navSharedTransitionScope   = miniNavScope,
                navAnimatedVisibilityScope = miniNavVisScope,
                navSharedContentConfig     = navArtConfig,
            )

            // Pop-out panel (wide non-short screens only)
            if (canShowPanel) {
                PlayerPopOutPanel(
                    panelTransition            = panelTransition,
                    playerViewModel            = playerViewModel,
                    onClose                    = { showPlayerPanel = false },
                    onFullScreen               = onOpenPlayer,
                    onOpenQueue                = onOpenQueue,
                    localSharedTransitionScope = this@SharedTransitionLayout,
                    navSharedTransitionScope   = navSharedTransitionScope,
                    navAnimatedContentScope    = navAnimatedContentScope,
                    modifier                   = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 8.dp, end = 16.dp, bottom = 16.dp)
                        .fillMaxWidth(0.54f)
                        .heightIn(max = maxPanelHeight)
                        // nav bar + camera cutout on the side/bottom edges — never the IME (see above)
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        ),
                )
            }

            // Back closes the pop-out panel. Registered LAST on purpose (BackHandler priority is
            // registration order — the handler composed last among the ENABLED ones wins), but
            // that order alone is NOT the guard: a hosted layout composed after this host exists
            // (unfolding into TwoPaneLayout) registers later and outranks it. The real guard is
            // [LocalPopOutPanelOpen] — hosted handlers gate themselves on it, so the two can never
            // be enabled at once. Keep this call unconditional and last anyway (BackHandler's KDoc
            // warns that conditional calls change composition order); `enabled` alone makes it
            // yield when the panel is absent (on single-pane `canShowPanel` is false, so
            // `showPlayerPanel` can never be true and LibrarySinglePaneLayout's
            // PredictiveBackHandler still wins).
            BackHandler(enabled = showPlayerPanel) { showPlayerPanel = false }
        }
    }
}
