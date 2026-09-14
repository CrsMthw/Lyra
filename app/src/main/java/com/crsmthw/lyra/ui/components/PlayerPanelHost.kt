package com.crsmthw.lyra.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionDefaults
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.SharedContentState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.crsmthw.lyra.util.NavTransitionMillis
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.screenTransitionSpec
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// THE app's one floating player surface: a mini player bar and (on wide non-short screens) a
// pop-out player panel, hosted ONCE by `LyraNavGraph` around the whole `NavHost` — not per screen.
//
// Hoisted here (2026-09-13) because a per-screen host meant the bar exited and re-entered on every
// browse→browse navigation: "ideally I prefer that mini-player be universally on top of all pages,
// so it does not go away and come back immediately every time I change pages". Now it simply stays
// put while the screens slide underneath it.
//
// `visible` is the caller's route gate — true on the browse routes, false on Player/Queue (which
// ARE the player), Settings and Auth. `visibleAfterBack` is the same predicate applied to the entry
// BELOW the current one; it is what lets a predictive-back GESTURE seek the bar in with the finger
// instead of dropping it in at commit — see "The mini player's presence" below.
//
// Because the host outlives every destination, a per-route difference that used to be a constructor
// argument is now an ANIMATED property: `miniPlayerFullWidth` (Search/Stats: a single full-width
// list, no right pane for a 58 % bar to line up with) resizes the bar in place with the app's finite
// `screenTransitionSpec()` instead of taking it off screen and putting a different one back.
// The bar's bottom inset simply unions `WindowInsets.ime` on every route — see `miniBottomInset`.
//
// The `onRequestPlayer` lambda passed to `content` opens the panel on wide screens and calls
// `onOpenPlayer` on narrow ones, so track-tap handlers don't need to know which mode they're in.
// `LyraNavGraph` threads it into each browse screen's own `onOpenPlayer`/`onNavigateToPlayer`.
//
// On EXTRA-WIDE screens (≥1200dp, e.g. a tablet in landscape) the player lives in a permanent
// docked third pane hosted by `LyraNavGraph`, beside this host. The mini player and the pop-out are
// then suppressed so nothing competes with that pane for `"album-art"` — but the host still runs in
// full and still emits `content(...)` from the SAME call site it does at every other width. It must:
// an early return put the content in a different composition group, so a window CROSSING the gate
// (a tablet rotating — `android:configChanges` keeps the Activity alive) re-parented the whole
// NavHost and wiped every destination's `rememberSaveable` state (browser scroll, the Search field
// / tabs / guards, the panel flag). Only the two siblings AFTER the content — the scrim and the
// mini/pop-out `SharedTransitionLayout` — are conditional.
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

/** Non-snapshot cell for the last browse surface the panel was seen over — see its use site. */
private class SurfaceKeyHolder(var value: String?)

@Composable
fun PlayerPanelHost(
    playerViewModel         : PlayerViewModel,
    onOpenPlayer            : () -> Unit,
    modifier                : Modifier = Modifier,
    onOpenQueue             : () -> Unit = {},
    /** Does the CURRENT route get the floating player surface? False on Player/Queue/Settings/Auth. */
    visible                 : Boolean = true,
    /**
     * The same question asked of the route a BACK would land on (the entry below the current one).
     * Defaults to [visible] — "a back here changes nothing about the surface" — which is also the
     * right answer when there is no entry below, i.e. when back leaves the app.
     */
    visibleAfterBack        : Boolean = visible,
    /**
     * Identity of the browse surface currently on screen — `NavBackStackEntry.id`, which is stable
     * for the life of an entry and is itself saved/restored across process death
     * (`NavBackStackEntryStateImpl` persists it and hands it back to `NavBackStackEntry.create`).
     *
     * The pop-out panel is scoped to the entry it was OPENED over: `visible` alone only says
     * whether the current ROUTE shows the surface, so a panel opened on the Library used to re-open
     * itself, scrim and all, over any browse screen the user later reached — including one they
     * navigated FORWARD into (panel → full-screen → tap an artist name → the panel slides in over
     * the artist screen). Null (the default) keeps the pre-2026-09-14 behaviour.
     */
    surfaceKey              : String? = null,
    miniPlayerFullWidth     : Boolean = false,
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
    // Mirror of LyraNavGraph's docked-pane gate: when the docked third pane is up, drop the mini
    // player + pop-out entirely. Measured width (not isWidthAtLeastBreakpoint(1200), whose default
    // V1 width buckets cap at 840dp).
    val isExtraWide    = screenWidthDp >= 1200.dp && screenHeightDp >= 600.dp
    // `!isExtraWide` is part of this rather than an early return, so that the panel is structurally
    // impossible at that width while `content(...)` keeps ONE call site (see the header comment).
    val canShowPanel   = isWideScreen && !isShortScreen && !isExtraWide

    // The docked pane IS the player at this width, so a track tap must do nothing extra. A plain
    // `onRequestPlayer` would fall through to `onOpenPlayer()` and push PlayerScreen BESIDE the
    // docked pane — a behaviour change the old early return (which passed `{}`) did not have.
    // Remembered so the lambda identity is stable and taps don't recompose every hosted screen.
    val noPlayerRequest: () -> Unit = remember { {} }

    val maxPanelHeight = screenHeightDp * 0.8f
    val focusManager   = LocalFocusManager.current
    val keyboard       = LocalSoftwareKeyboardController.current

    // The user's INTENT to have the panel open, independent of whether the current route shows it.
    // Still `rememberSaveable` — no longer because Navigation disposes this composable (the host now
    // sits OUTSIDE the NavHost and is never disposed by a navigation), but so the panel survives
    // process death / Activity recreation like any other user-visible UI state.
    var showPlayerPanel by rememberSaveable { mutableStateOf(false) }

    // …and WHICH browse surface that intent belongs to. `showPlayerPanel` is one app-wide flag, and
    // the route only ever HID the panel, so it re-opened with its 0.45 scrim over any browse screen
    // the user later reached — including one navigated FORWARD into: panel open on the Library → its
    // full-screen button pushes Player → tap an artist name → ArtistDetail is a surface route, so
    // the panel and scrim slid in over the screen the user had just opened. Scoping the intent to
    // the back-stack ENTRY it was opened over fixes that without clearing the flag on `!visible` —
    // which must not happen, because the push to Player is exactly the push where the exiting panel
    // has to survive as the "album-art" EXIT participant.
    var panelOwnerKey by rememberSaveable { mutableStateOf<String?>(null) }

    // The owner is compared against a FROZEN copy of `surfaceKey`: while `visible` is false the
    // current entry is Player/Queue/Settings, and taking those as "a different surface" would read
    // the push to the full player as a change of owner and kill the panel mid-morph. Frozen means
    // the last surface entry the panel was actually seen over. Assigned DURING composition and
    // deliberately not snapshot-backed (the `MiniWidthHolder` / `PaneStateHolder` idiom): the scope
    // that writes it also reads `visible`, so it can never be stale, and a `MutableState` written
    // and read in the same pass would schedule an extra recomposition.
    val surfaceOwner = remember { SurfaceKeyHolder(surfaceKey) }
    if (visible) surfaceOwner.value = surfaceKey

    // ONE ownership value, feeding BOTH consumers below. A null owner behaves exactly as the code
    // did before this existed, so a saveable lost to a restore can never strand a panel nobody can
    // reach.
    val panelOwned = showPlayerPanel && (panelOwnerKey == null || surfaceOwner.value == panelOwnerKey)

    /** The one way the panel closes for good: intent and its owner are cleared together. */
    fun closePanel() {
        showPlayerPanel = false
        panelOwnerKey   = null
    }

    // Ownership is not something the user can navigate back INTO: once the panel has been left
    // behind on another surface, returning to the original entry must not resurrect it. Keyed on
    // the frozen value, which is safe DESPITE it not being snapshot-backed: the only write to it is
    // the line above, inside this composable's own body, so any pass that changes it is by
    // definition a recomposition of this host — and this key is re-read on that same pass. A push
    // to Player/Queue does not write it at all, so it never triggers there.
    LaunchedEffect(surfaceOwner.value) {
        if (panelOwnerKey != null && surfaceOwner.value != panelOwnerKey) closePanel()
    }

    // …and the panel's presence ON SCREEN, which the route also gates: navigating into Settings or
    // the full player must not leave the panel floating over them. It is HIDDEN, not closed —
    // `showPlayerPanel` is untouched, so coming back restores it, and on the push to PlayerScreen
    // the exiting panel is the "album-art" EXIT participant the big art morphs out of (and the
    // ENTER participant on the way back). `currentBackStackEntryAsState` flips at the start of a
    // push and of a COMMITTED pop and is stable during a predictive gesture, so on the pop back off
    // Player the id matches again on the same frame `visible` does — the morph timing is unchanged.
    // `canShowPanel` is in it so the panel — and with it `LocalPopOutPanelOpen`, the scrim and this
    // host's BackHandler — is structurally false wherever the panel is not composed at all, which
    // now includes the extra-wide docked-pane width.
    val panelVisible = panelOwned && visible && canShowPanel

    // Single source of truth for the pop-out panel's presence on screen. Drives both the panel's
    // own AnimatedVisibility (so the gate below reads the SAME animation that's rendering) and the
    // mini player's nav-scope gate. Using this Transition avoids the scrim-tween-vs-panel-slide
    // desync that a separate timer would have.
    val panelTransition = updateTransition(panelVisible, label = "panelPresence")

    // ── The mini player's presence, as a SEEKABLE transition ─────────────────────────────────────
    //
    // Before the hoist the bar lived INSIDE the Library destination, which `NavHost` composes at the
    // START of a back gesture and seeks with the finger — so the bar and the mini↔big "album-art"
    // morph tracked the drag and unwound on an early release. Hosted out here the bar's visibility
    // became a plain Boolean flipped by `currentBackStackEntryAsState()`, which only moves when the
    // pop COMMITS: the bar was absent for the whole drag and slid in afterwards, and the art morph
    // snapped partway instead of following the thumb. This restores the old feel by driving the bar
    // from a `SeekableTransitionState` the same way `NavHost.kt` drives its own AnimatedContent.
    //
    // Track presence is read here (not only in `MiniPlayerHolder`) because it belongs in the ONE
    // Boolean the transition animates over. SEEDED SYNCHRONOUSLY from the StateFlow's current value
    // — `collectAsStateWithLifecycle` bakes its initial value into its own `remember`, and a literal
    // `false` would make the bar animate in from nothing on every Activity recreation.
    val hasTrack by remember {
        playerViewModel.uiState.map { it.currentTrack != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(playerViewModel.uiState.value.currentTrack != null)

    // `barWanted` is everything about the bar that does NOT depend on the route, so the same
    // expression can be evaluated for the current route and for the one a back would land on.
    // `!panelOwned` is in it because on a wide screen with the pop-out OPEN the panel — not the
    // bar — is the "album-art" partner `PlayerScreen`'s art pops back into; a bar that seeked in
    // during the drag would have to slide straight back out at commit as the panel arrived.
    // It MUST be the same `panelOwned` that feeds `panelVisible`, never the raw `showPlayerPanel`:
    // on a surface the panel does not own, the panel is gone and the bar is what the user gets —
    // the two values diverging would leave `barWanted` false there and the mini player would simply
    // vanish for the rest of the session.
    val barWanted   = hasTrack && !panelOwned
    val showBar     = barWanted && visible
    val barAfterBack = barWanted && visibleAfterBack  // what a committed back would make it
    // On extra-wide the bar is simply not composed (the whole `SharedTransitionLayout` below is
    // skipped), so this transition runs with no children: `totalDurationNanos` is 0, which the
    // cancel branch below already floors at `NavTransitionMillis`. Gating `showBar` on
    // `!isExtraWide` as well would work too, and would cost an extra state change on a rotation
    // that crosses the gate; leaving it alone keeps the Boolean route-derived and nothing renders
    // either way.

    // A read-only mirror of the gesture every back handler already sees. Observing this StateFlow
    // registers NO handler, so it cannot steal the gesture from `NavHost`'s own
    // (`rememberNavHostEventHandler`) — deliberately NOT `rememberNavigationEventState`, which owns
    // one and would intercept. Any dispatcher in the hierarchy answers identically:
    // `NavigationEventDispatcher.transitionState` delegates to `sharedProcessor`, and a child
    // dispatcher is constructed with `parent?.sharedProcessor` (NavigationEventDispatcher.kt).
    val idleGesture  = remember { MutableStateFlow<NavigationEventTransitionState>(NavigationEventTransitionState.Idle) }
    val gestureFlow  = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher?.transitionState
        ?: idleGesture
    val gestureState by gestureFlow.collectAsStateWithLifecycle()

    // Only a BACK gesture is followed. `TRANSITIONING_FORWARD` is somebody else's motion, and the
    // third value (`TRANSITIONING_UNKNOWN`, internal to the library) cannot arrive from Android's
    // own input — `NavigationEventInput.dispatchOnBackStarted/Progressed` always pass
    // `TRANSITIONING_BACK` — so an unknown direction is treated as "no seek" and simply falls back
    // to the committed behaviour, which is what this code did before.
    val inProgress   = gestureState as? NavigationEventTransitionState.InProgress
    val backProgress = inProgress
        ?.takeIf { it.direction == NavigationEventTransitionState.TRANSITIONING_BACK }
        // `seekTo` has a `requirePrecondition` that THROWS outside 0..1, and BackEvent.progress is
        // not reliably clamped across OEM builds.
        ?.latestEvent?.progress?.coerceIn(0f, 1f)

    val barState      = remember { SeekableTransitionState(showBar) }
    val barTransition = rememberTransition(barState, label = "miniPlayerPresence")

    if (backProgress != null && barAfterBack != showBar) {
        // The gesture changes whether the surface is shown, so the bar rides it. `NavHost` seeks its
        // own transition from the same `progress`, so the two stay in step frame for frame — and
        // because the bar's AnimatedVisibility scope is a child of this transition, so does the
        // nav-scope "album-art" bounds animation. Written generically: a gesture OUT of a surface
        // route into one that hides it would seek the bar away just as well (it can't happen today —
        // PlayerScreen is never below a browse route — but nothing here assumes that).
        LaunchedEffect(backProgress, barAfterBack) { barState.seekTo(backProgress, barAfterBack) }
    } else {
        LaunchedEffect(showBar) {
            if (barState.currentState != showBar) {
                // Ordinary change (a push to Player, a button/committed back, Settings↔browse) AND
                // the commit of a seeked gesture, which is the same thing one frame later: the seek
                // already set `targetState`, so this finds the target UNCHANGED and resumes from the
                // gesture's fraction over the REMAINING duration instead of restarting.
                //
                // Deliberately NO `animationSpec`. With one, `SeekableTransitionState` runs the
                // FRACTION through that spec (Transition.kt: `newAnimation.animationSpec = newSpec`)
                // while every child is still seeked by that fraction through its own curve — the
                // easing would apply twice and the bar would read visibly slower than the nav slide
                // beside it. With none, `animationSpecDuration = totalDurationNanos * (1 - fraction)`
                // and the fraction advances LINEARLY, so the children play at their natural rate.
                // The finite spec THE HARD RULE (docs/MOTION.md) asks for lives on those children:
                // MiniPlayer's slide in/out is `screenTransitionSpec()`, so `totalDurationNanos` is
                // `NavTransitionMillis`. This is exactly what `NavHost.kt` and the Library's
                // single-pane predictive back do.
                //
                // Note this branch, not the dispatcher's `Idle`, is what commits a gesture: the two
                // arrive through separate flow collections (the pop happens inside
                // `dispatchOnCompleted` BEFORE it writes `Idle`, but they reach composition
                // independently), and keying the branch on `showBar` means whichever lands first is
                // the one that decides. Idle-first costs at most one frame of the unwind below
                // before the route flip restarts this effect and resumes the entrance.
                barState.animateTo(showBar)
            } else if (barState.targetState != showBar) {
                // Released without committing. The seek has to be wound back by hand, and the
                // duration is scaled by how far the gesture actually got — `NavHost.kt`'s cancel
                // formula, mirrored in LibrarySinglePaneLayout — or a cancel at 5 % would take a
                // full transition to snap back. Floored so a transition that momentarily reports no
                // duration can't compute `tween(0)` and snap.
                val totalMillis = (barTransition.totalDurationNanos / 1_000_000)
                    .coerceAtLeast(NavTransitionMillis.toLong())
                animate(
                    initialValue  = barState.fraction,
                    targetValue   = 0f,
                    animationSpec = tween((barState.fraction * totalMillis).toInt()),
                ) { value, _ ->
                    // seekTo/snapTo suspend, `animate`'s callback does not — hand the work back to
                    // this effect's own scope, as NavHost does internally.
                    this@LaunchedEffect.launch {
                        if (value > 0f)  barState.seekTo(value)
                        if (value == 0f) barState.snapTo(showBar)
                    }
                }
            }
        }
    }

    // Dismiss panel when folding — canShowPanel goes false on narrow screens, and (since the
    // extra-wide early return was removed) also when a window grows PAST 1200dp into the docked
    // pane's territory. Both are the same statement: the pop-out cannot be composed here, so the
    // user's intent to have it open is spent. The docked pane is the player at that width.
    LaunchedEffect(canShowPanel) {
        if (!canShowPanel) closePanel()
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
    // — a no-op on Library/Album/Artist, where nothing is focused. (The mini player needs no such
    // per-route knob: its inset unions the IME on every route, see `miniBottomInset`.)
    val onRequestPlayer: () -> Unit = {
        if (canShowPanel) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            showPlayerPanel = true
            // The surface this intent belongs to. The PARAMETER, not the frozen holder — they are
            // equal while `visible` (and a tap can only arrive from a visible surface), but the
            // parameter is the one that is obviously right at click time.
            panelOwnerKey   = surfaceKey
        } else onOpenPlayer()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ONE content call site at every width — see the header comment on why an early return for
        // the extra-wide case was a state-wiping trap.
        CompositionLocalProvider(LocalPopOutPanelOpen provides panelVisible) {
            content(if (isExtraWide) noPlayerRequest else onRequestPlayer)
        }

        // Everything from here to the matching brace is the floating player surface itself, and it
        // is the ONLY thing the docked-pane width drops. These are SIBLINGS of `content` inside this
        // Box, so making them conditional cannot touch the content's composition group identity —
        // which is exactly what the old `if (isExtraWide) { … ; return }` did: it re-parented the
        // whole NavHost and wiped every destination's rememberSaveable state on a tablet rotation
        // across 1200dp.
        if (!isExtraWide) {

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
            //
            // `visible &&` is a cosmetic rider on the same idea: the fade-out tween outlives the
            // route flip, so on a push to PlayerScreen the dimming wash would otherwise be drawn
            // over the INCOMING player for ~300ms. Gating the draw on the route trades that for the
            // outgoing browse screen losing its dim abruptly rather than fading — the better of the
            // two, since the outgoing screen is sliding out under it anyway.
            if (visible && scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrimAlpha))
                        .then(
                            if (panelVisible)
                                Modifier.clickable { haptics.confirm(); closePanel() }
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

                    // Bottom inset for the mini player — ONE modifier that unions the IME
                    // UNCONDITIONALLY, on every route.
                    //
                    // It used to be two different modifiers switched by a `miniPlayerAvoidsIme` flag
                    // that `LyraNavGraph` derived from `currentBackStackEntryAsState()` — which flips
                    // on the FIRST FRAME of a push. So leaving Search with the keyboard still up (tap
                    // a result row, or the back arrow) swapped the bar to the no-IME inset immediately:
                    // it snapped down behind the still-retracting keyboard and popped back up ~250ms
                    // later when the IME inset finally reached zero. A Boolean that changes a frame
                    // before the inset it describes cannot be made to agree with it; unioning the IME
                    // always makes the bar ride the keyboard down instead, with no flag to be out of
                    // step with. (Search, which auto-focuses its field, is why the lift exists at all.)
                    //
                    // `union` takes the LARGER of the two rather than stacking: the IME inset already
                    // spans the nav bar, so imePadding() + navigationBarsPadding() would leave a
                    // nav-bar-sized gap above the keyboard. The pop-out panel below deliberately keeps
                    // plain nav bar + cutout — it is capped at 80 % of the screen height, so lifting it
                    // above the keyboard would squash it and make it jump on every IME toggle.
                    // Horizontal stays in the side list: the IME has no horizontal inset, so this keeps
                    // the side nav-bar clearance navigationBarsPadding() gives in landscape. The
                    // DISPLAY CUTOUT is in the union because in landscape the hole-punch camera sits on
                    // a side edge and screen content clears it via horizontalSystemBarsPadding() — the
                    // mini player must indent the same way or it pokes out past the content on that
                    // side (device pass 2026-09-12, item 36). Horizontal + Bottom only: the top stays
                    // with the content above.
                    //
                    // Honest note: the activity window still receives IME insets while a DIALOG above
                    // it shows the keyboard (AddToPlaylistSheet's create-playlist dialog runs in its
                    // own window), so the bar lifts behind that sheet + its scrim. It is invisible and
                    // harmless — stated here so nobody re-introduces the conditional to "fix" it.
                    val miniBottomInset = Modifier.windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets.displayCutout)
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

                    // The mini player's own AnimatedVisibility — a child of `barTransition` above — is
                    // its shared-element scope in BOTH layers (MiniPlayer falls back to its inner scope,
                    // which is now the only one). That is what keeps the nav-level mini↔PlayerScreen
                    // morph working with the bar living outside every destination: the bar is the EXIT
                    // participant on a push to Player (already composed when `showBar` flips false) and
                    // the ENTER participant on the way back — composing fresh into a live transition at
                    // the first frame of the back GESTURE, not at commit, which is the whole point of
                    // the seek. Matching is per key within a SharedTransitionScope and independent of
                    // each participant's parent transition (SharedTransitionScope.kt `sharedElementsFor`:
                    // `sharedElements.getOrPut(key)`; the AnimatedVisibilityScope only supplies
                    // `parentTransition` to `sharedBoundsImpl`), so an AnimatedVisibility-scoped bar and
                    // an AnimatedContent-scoped PlayerScreen do match — and because both parents are
                    // being seeked by the same gesture progress, the morph tracks the finger.
                    MiniPlayerHolder(
                        playerViewModel          = playerViewModel,
                        onExpand                 = onRequestPlayer,
                        barTransition            = barTransition,
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
                            onClose                    = { closePanel() },
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
        }   // if (!isExtraWide) — scrim + mini/pop-out only

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
        // untouched. That includes the extra-wide width, where this now registers (it did not
        // before the early return was removed) but `panelVisible` is structurally false, so it
        // always yields — and `DockedPlayerPane`'s own handler is composed after this host anyway.
        BackHandler(enabled = panelVisible) { closePanel() }
    }
}
