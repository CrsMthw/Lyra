package com.crsmthw.lyra.ui.components

import androidx.activity.compose.BackHandler
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
import com.crsmthw.lyra.util.screenTransitionSpec
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel

// THE app's one floating player surface: a mini player bar and (on wide non-short screens) a
// pop-out player panel, hosted ONCE by `LyraNavGraph` around the whole `NavHost` — not per screen.
//
// Hoisted here (2026-09-13) because a per-screen host meant the bar exited and re-entered on every
// browse→browse navigation: "ideally I prefer that mini-player be universally on top of all pages,
// so it does not go away and come back immediately every time I change pages". Now it simply stays
// put while the screens slide underneath it.
//
// `visible` is the caller's route gate — true on the browse routes, false on Player/Queue (which
// ARE the player), Settings and Auth. Because the host outlives every destination, a per-route
// difference that used to be a constructor argument is now an ANIMATED property: `miniPlayerFullWidth`
// (Search/Stats: a single full-width list, no right pane for a 58 % bar to line up with) resizes the
// bar in place with the app's finite `screenTransitionSpec()` instead of taking it off screen and
// putting a different one back. `miniPlayerAvoidsIme` (Search: the field auto-focuses) lifts it
// above the software keyboard.
//
// The `onRequestPlayer` lambda passed to `content` opens the panel on wide screens and calls
// `onOpenPlayer` on narrow ones, so track-tap handlers don't need to know which mode they're in.
// `LyraNavGraph` threads it into each browse screen's own `onOpenPlayer`/`onNavigateToPlayer`.
//
// On EXTRA-WIDE screens (≥1200dp, e.g. a tablet in landscape) the player lives in a permanent
// docked third pane hosted by `LyraNavGraph`, beside this host. Here we just render the content and
// suppress the mini player + pop-out so nothing competes with that pane for `"album-art"`.
@OptIn(ExperimentalSharedTransitionApi::class)
/**
 * True while this host's pop-out player panel is showing. A screen inside the host that registers
 * its own `BackHandler` MUST gate it on `!LocalPopOutPanelOpen.current`: `BackHandler` priority is
 * registration order (the handler composed LAST among the enabled ones wins), and that order is
 * not stable — a layout (re)composed after this host already exists (unfolding from single- to
 * two-pane composes `TwoPaneLayout` fresh) registers AFTER the host's handler and outranks it, so
 * back cancelled a Library selection behind the panel's scrim while the panel stayed open (device
 * pass 2026-09-12; a nav-mode switch recreated the Activity and "fixed" it). Making the two
 * handlers mutually exclusive on this flag holds regardless of composition order.
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

/** Non-snapshot cell for the mini player's last on-screen width fraction — see its use site. */
private class MiniWidthHolder(var value: Float)

@Composable
fun PlayerPanelHost(
    playerViewModel         : PlayerViewModel,
    onOpenPlayer            : () -> Unit,
    modifier                : Modifier = Modifier,
    onOpenQueue             : () -> Unit = {},
    /** Does the CURRENT route get the floating player surface? False on Player/Queue/Settings/Auth. */
    visible                 : Boolean = true,
    miniPlayerFullWidth     : Boolean = false,
    miniPlayerAvoidsIme     : Boolean = false,
    navSharedTransitionScope: SharedTransitionScope? = null,
    content                 : @Composable (onRequestPlayer: () -> Unit) -> Unit,
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
    // V1 width buckets cap at 840dp).
    val isExtraWide    = screenWidthDp >= 1200.dp && screenHeightDp >= 600.dp

    if (isExtraWide) {
        Box(modifier = modifier.fillMaxSize()) { content {} }   // player is the docked pane
        return
    }

    val maxPanelHeight = screenHeightDp * 0.8f
    val focusManager   = LocalFocusManager.current
    val keyboard       = LocalSoftwareKeyboardController.current

    // The user's INTENT to have the panel open, independent of whether the current route shows it.
    // Still `rememberSaveable` — no longer because Navigation disposes this composable (the host now
    // sits OUTSIDE the NavHost and is never disposed by a navigation), but so the panel survives
    // process death / Activity recreation like any other user-visible UI state.
    var showPlayerPanel by rememberSaveable { mutableStateOf(false) }

    // …and its presence ON SCREEN, which the route also gates: navigating into Settings or the full
    // player must not leave the panel floating over them. It is HIDDEN, not closed — `showPlayerPanel`
    // is untouched, so coming back restores it, and on the push to PlayerScreen the exiting panel is
    // the "album-art" EXIT participant the big art morphs out of (and the ENTER participant on the
    // way back).
    val panelVisible = showPlayerPanel && visible

    // Single source of truth for the pop-out panel's presence on screen. Drives both the panel's
    // own AnimatedVisibility (so the gate below reads the SAME animation that's rendering) and the
    // mini player's nav-scope gate. Using this Transition avoids the scrim-tween-vs-panel-slide
    // desync that a separate timer would have.
    val panelTransition = updateTransition(panelVisible, label = "panelPresence")

    // Dismiss panel when folding — canShowPanel goes false on narrow screens
    LaunchedEffect(canShowPanel) {
        if (!canShowPanel) showPlayerPanel = false
    }

    val scrimAlpha by animateFloatAsState(
        targetValue   = if (panelVisible) 0.45f else 0f,
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

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPopOutPanelOpen provides panelVisible) {
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
                        if (panelVisible)
                            Modifier.clickable { haptics.confirm(); showPlayerPanel = false }
                        else Modifier
                    )
            )
        }

        // The LOCAL shared-transition scope for the mini↔pop-out morph. It wraps ONLY those two —
        // deliberately not the content — so the NavHost is not dragged into an extra lookahead pass
        // it never needed (before the hoist this STL lived inside each hosted screen, so only
        // Player/Queue/Settings/Auth were outside one; keeping the content out preserves that for
        // every screen). Laying it full-screen over the content costs nothing: a Box with no
        // pointer-input modifier is not hit-tested, so taps outside the bar/panel fall straight
        // through, exactly as they do through the fading scrim above.
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Mini player placement. The bar is pinned to the BottomEnd and its WIDTH is the
                // per-route knob: 58 % on a wide screen (matching the two-pane right pane it sits
                // under) and full width on narrow, or on the screens that are a single full-width
                // list at every width (Stats, Search — no right pane for a 58 % bar to line up
                // with, so pinned-right reads as a bug on the unfolded screen). At fraction 1f
                // BottomEnd is identical to the BottomCenter the narrow case used before the hoist,
                // so this is one placement instead of two.
                // It ANIMATES because the host now outlives the route change: Library→Search used
                // to take the 58 % bar off screen and slide a full-width one back in. A finite
                // `screenTransitionSpec()`, never a spring — it runs during a nav content swap
                // (docs/MOTION.md → THE HARD RULE). `animateFloatAsState` does not animate its
                // first value, so the bar is born at the right width.
                //
                // The target is FROZEN while the bar is hidden. Without that, pushing Search →
                // Player on a wide screen retargets 1f → 0.58f (the Player route is not
                // `miniPlayerFullWidth`) at the exact moment the bar starts sliding out, so it
                // shrinks diagonally on the way off and grows on the way back — the same
                // retarget-mid-transition bug as the search FAB's bottom padding. Held, the resize
                // only ever runs between two routes that both show the bar, which is the case it
                // exists for. Assigned DURING composition and deliberately NOT snapshot-backed: the
                // scope that writes it also reads `visible`/`miniPlayerFullWidth`, so it can never
                // be stale, and a `MutableState` write read in the same pass would schedule an
                // extra recomposition (same reasoning as the Library's `PaneStateHolder`).
                val widthTarget = if (isWideScreen && !miniPlayerFullWidth) 0.58f else 1f
                val heldWidth   = remember { MiniWidthHolder(widthTarget) }
                if (visible) heldWidth.value = widthTarget
                val miniWidthFraction by animateFloatAsState(
                    targetValue   = heldWidth.value,
                    animationSpec = screenTransitionSpec(),
                    label         = "miniWidth",
                )

                // Bottom inset for the mini player. `miniPlayerAvoidsIme` lifts it above the
                // keyboard — Search auto-focuses its field, so the keyboard is up on entry and a
                // mini player left at the window bottom would spend most of the screen's life
                // hidden behind it. The inset is the UNION of the IME and nav bar, which takes the
                // larger of the two instead of stacking: the IME inset already spans the nav bar,
                // so imePadding() + navigationBarsPadding() would leave a nav-bar-sized gap above
                // the keyboard. The pop-out panel below deliberately keeps plain
                // navigationBarsPadding() — it is capped at 80 % of the screen height, so lifting
                // it above the keyboard would squash it and make it jump every time the IME
                // toggles. Horizontal stays in the side list: the IME has no horizontal inset, so
                // this keeps the side nav-bar clearance navigationBarsPadding() gives in landscape.
                // Both branches also union the DISPLAY CUTOUT: in landscape the hole-punch camera
                // sits on a side edge and screen content clears it via horizontalSystemBarsPadding()
                // — the mini player must indent the same way or it pokes out past the content on
                // that side (device pass 2026-09-12, item 36). Horizontal + Bottom only: the top
                // stays with the content above.
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

                // The mini player's SECONDARY nav scope (wide screens only) is held CONTINUOUSLY
                // while the pop-out panel is fully closed, and dropped only while it is
                // open/animating. Continuous-when-closed is required for the morph to work on POP:
                // a shared element added after a pop transition has already begun is too late to be
                // captured as the EXIT participant (that is why an isRunning gate morphed on push
                // but not back). Dropping it while the panel is present is the actual conflict
                // window — it stops the mini and the panel from both claiming "album-art" in the
                // nav scope and fighting the local mini↔panel morph.
                // On NARROW screens the mini's PRIMARY scope (below) is ALREADY the nav scope, so
                // this stays false to avoid a double registration that breaks the morph
                // asymmetrically.
                val panelPresent = panelTransition.currentState || panelTransition.targetState ||
                                   panelTransition.isRunning
                val miniNeedsNavScope = canShowPanel && !panelPresent

                // ...and WHICH nav transition it may morph across is a second, independent gate. The
                // scope above stays put (see why in [rememberMatchWhenConfig]); this config decides
                // whether the entry may MATCH, and only PlayerScreen's big art is a wanted partner.
                // With ONE hoisted mini player the browse→browse double match this was introduced
                // for (two screens each carrying a bar, both claiming "album-art", the art pinned in
                // the transition overlay while the bar slid away — device pass 2026-09-12) can no
                // longer happen; the gate is kept because it is free and keeps the key quiet on
                // every navigation that has no morph. It must stay a `SharedContentConfig` and never
                // become an add/remove of the modifier.
                // Deliberately NOT applied to the mini's primary scope on WIDE screens: there the
                // primary is the LOCAL mini↔pop-out morph, which has nothing to do with nav
                // transitions.
                val navArtConfig = rememberMatchWhenConfig(LocalPlayerRouteVisible.current)

                // The mini player's own AnimatedVisibility is its shared-element scope in BOTH
                // layers (MiniPlayer falls back to its inner scope, which is now the only one). That
                // is what keeps the nav-level mini↔PlayerScreen morph working with the bar living
                // outside every destination: `visible` flips at the START of a push to Player (the
                // bar is the EXIT participant and is already composed) and at the START of a
                // committed pop back off it (the bar is the ENTER participant, composing fresh into
                // a live transition) — exactly the two roles that work. Matching is per key within
                // a SharedTransitionScope and independent of each participant's parent transition
                // (SharedTransitionScope.kt `sharedElementsFor`: `sharedElements.getOrPut(key)`;
                // the AnimatedVisibilityScope only supplies `parentTransition` to `sharedBoundsImpl`),
                // so an AnimatedVisibility-scoped bar and an AnimatedContent-scoped PlayerScreen do
                // match.
                MiniPlayerHolder(
                    playerViewModel          = playerViewModel,
                    onExpand                 = onRequestPlayer,
                    visible                  = visible && !panelVisible,
                    modifier                 = Modifier
                        .align(Alignment.BottomEnd)
                        .fillMaxWidth(miniWidthFraction)
                        .then(miniBottomInset),
                    sharedTransitionScope    = if (canShowPanel) this@SharedTransitionLayout else navSharedTransitionScope,
                    sharedContentConfig      = if (canShowPanel) SharedTransitionDefaults.SharedContentConfig else navArtConfig,
                    navSharedTransitionScope = if (miniNeedsNavScope) navSharedTransitionScope else null,
                    navSharedContentConfig   = navArtConfig,
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
                        modifier                   = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(start = 8.dp, end = 16.dp, bottom = 16.dp)
                            .fillMaxWidth(0.54f)
                            .heightIn(max = maxPanelHeight)
                            // nav bar + camera cutout on the side/bottom edges — never the IME
                            .windowInsetsPadding(
                                WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                            ),
                    )
                }
            }
        }

        // Back closes the pop-out panel. Composed LAST, AFTER `content` — i.e. after the NavHost and
        // everything inside it — so it is the most recently added enabled handler and wins. Both
        // this and NavHost's own predictive-back handler register on the SAME
        // `NavigationEventDispatcher` via `addHandler` from an effect (navigation-compose 2.10.0's
        // `rememberNavHostEventHandler`; activity-compose 1.13.0's `BackHandler`), and
        // `NavigationEventProcessor.findHandler` resolves most-to-least recently added — on both
        // `ActivityFlags.isOnBackPressedLifecycleOrderMaintained` branches.
        // Order alone is still NOT the guard for handlers inside `content`: a layout composed after
        // this host already exists (unfolding into TwoPaneLayout) registers later and outranks it.
        // That is what [LocalPopOutPanelOpen] is for. Keep this call unconditional (BackHandler's
        // KDoc warns that conditional calls change composition order); `enabled` alone makes it
        // yield when the panel is absent, so the Library's single-pane PredictiveBackHandler is
        // untouched.
        BackHandler(enabled = panelVisible) { showPlayerPanel = false }
    }
}
