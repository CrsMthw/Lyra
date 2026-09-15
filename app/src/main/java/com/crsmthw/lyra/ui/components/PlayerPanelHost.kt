package com.crsmthw.lyra.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionDefaults
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.SharedContentState
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.ui.layout.layout
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
import com.crsmthw.lyra.util.morphLog
import com.crsmthw.lyra.util.screenTransitionSpec
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
// BELOW the current one; it is what lets a predictive-back GESTURE seek the surface in with the
// finger — the bar, or the pop-out panel it was hidden behind — instead of dropping it in at
// commit. See "The floating player surface, as ONE seekable transition" below; the panel's OWN
// close gesture is the `PredictiveBackHandler` at the bottom.
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

/** The shared-element key of the app-wide album art, before the settle generation is appended. */
private const val AlbumArtKeyBase = "album-art"

/**
 * The KEY every `"album-art"` shared-element participant must register under — the app's ONE
 * floating-player morph. [PlayerPanelHost] provides it as `"album-art#<generation>"` and bumps the
 * generation after every SETTLE of the floating surface, so each morph runs on a shared element
 * with **no history**.
 *
 * **Why identity rather than another repair of the state machine.** A cancelled predictive-back
 * gesture disposes the participant the abandoned direction was heading for (the mini player for a
 * cancelled panel close, the bar or the pop-out panel for a cancelled back off `PlayerScreen`), and
 * the NEXT gesture then had no flight at all: the art in the pop-out panel (device report 44,
 * unfolded) or the full player's big art (report 48, folded) simply disappeared, while a COMMITTED
 * gesture in between cured it. `SharedElement` keeps per-key state that outlives the participants —
 * `SharedTransitionStateMachine.state`, its `targetBoundsProvider` (the rect the next morph starts
 * FROM), and a configured match's `targetData`/`currentBounds` — and a cancelled seek can strand any
 * of them. The 2026-09-15 repair targeted exactly one (the provider) by forcing a re-measure of the
 * survivor; it did not fix the device (see [LocalPlayerArtSettleCount], which still carries it as
 * belt-and-braces). A fresh key does not need to know WHICH field went stale:
 * `rememberSharedContentState` is `remember(key)` and `sharedBoundsImpl` wraps everything in
 * `key(key) { remember { sharedElementsFor(key) } }` (SharedTransitionScope.kt), so a new key means a
 * new `SharedElement` with a new `SharedTransitionStateMachine` at `NoMatchFound`, no
 * `targetBoundsProvider`, no `targetData`, no `currentBounds` — i.e. exactly the state the app is in
 * before the FIRST gesture, which is the one that always works.
 *
 * With a null provider `ActiveMatchFoundConfigPending.configureActiveMatch` falls back to
 * `allEntries.fastFirstOrNull { enabledEntries.contains(it) }` and morphs from THAT copy's last
 * bounds. **That fallback is only correct because a settled surface never leaves two participants
 * composed in one scope**, so the first entry is always the one that was already there — the
 * OUTGOING copy — in all four directions (bar→player, player→bar, bar→panel, panel→bar). The two
 * `if (!currentState && !targetState) return@AnimatedVisibility` bail-outs in [MiniPlayer] and
 * [PlayerPopOutPanel] are what enforce that (they exist for the two-target rule; see [PlayerSurface]).
 * If either is ever "simplified" away, an idle surface composed in `PreEnter` becomes the first
 * entry and the fallback picks the INCOMING copy — a WRONG flight, which is harder to spot than no
 * flight at all.
 *
 * **When the generation may change.** Only from a composition where the surface transition is
 * settled AND no back gesture is in progress AND the panel's close phase is `Idle` AND the nav
 * `SharedTransitionScope` reports no active shared transition — see the bump site. Re-keying while a
 * morph is live would replace the element (and the `createChildTransition` its bounds animation
 * hangs off) mid-flight and drop the rest of the flight.
 *
 * Defaults to the bare key so that anywhere the provider is out of scope the behaviour is exactly
 * what it was before this existed — a preview, and the docked third pane's `PlayerScreen`, which is
 * composed BESIDE this host and is handed no shared scopes at all.
 */
val LocalPlayerArtKey: ProvidableCompositionLocal<Any> = compositionLocalOf { AlbumArtKeyBase }

/** The [LocalPlayerArtSettleCount] default: nothing ever bumps it. */
private val NoArtSettles: IntState = mutableIntStateOf(0)

/**
 * How many times this host's floating player surface has SETTLED (reached
 * `currentState == targetState`). Bumped by [PlayerPanelHost]; read by all three `"album-art"`
 * participants — the pop-out panel's art (`PlayerCardContent`), the full player's (`PlayerScreen`)
 * and the mini player's (`MiniPlayer`) — through [rememberArtSettleInvalidation], which turns each
 * bump into one no-op re-measure of that art.
 *
 * The first two are the participants that provably OUTLIVE a cancelled seek. The mini player's is
 * INSURANCE rather than a traced failure — it used to deliberately have no reader. The one path on
 * which the bar would be the survivor of a cancelled seek that had a PARTNER is a back off an
 * album/artist opened FROM the full player (stack `[…, Player, AlbumDetail]`, where the surface
 * seeks `Bar → None` with the finger), and there the bar's nav-scope entry is DISABLED for the
 * whole gesture, so no match ever forms and there is no provider to strand:
 * `LocalPlayerRouteVisible` is false, because navigation-compose 2.10.0's `prepareForTransition`
 * emits no `_visibleEntries` — the prepared entry BELOW the top one does not become visible during
 * a predictive-back gesture (`NavHost.kt` says exactly that where it falls back to the
 * `AnimatedContent` target instead of a `visibleEntries` lookup). That gate, NOT the old claim that
 * "`Bar → None` leaves a single participant so nothing matches", is the real argument. Since the
 * whole argument rests on it, and a `visibleEntries` leak that left `Player` listed while the user
 * sits on a detail route would quietly re-enable the entry, the bar carries the reader too: one
 * no-op re-measure of a 44dp art per surface settle, against a class of bug that is invisible until
 * the SECOND gesture.
 *
 * **What the re-measure is for, and what it is NOT.** A CANCELLED seek disposes the participant the
 * abandoned direction was heading for: the mini player for a cancelled panel close, the bar or the
 * pop-out panel for a cancelled back off `PlayerScreen`. `SharedTransitionStateMachine` keeps that
 * participant's `BoundsProvider` as its `targetBoundsProvider`, which is the rect the NEXT morph
 * starts FROM (`ActiveMatchFoundConfigPending.configureActiveMatch` →
 * `obtainBoundsFromLastTarget`). That field is re-read in exactly one place,
 * `updateTargetBoundsProvider()`, reached only from `SharedContentNode`'s lookahead placement
 * (`onLookaheadPlaced`) or approach measure (`tryInitializingCurrentBounds`) — an entry being
 * forgotten merely POSTS the request. So with nothing left to re-measure, the pointer can survive
 * the participant: `obtainBoundsFromLastTarget` then returns null (that provider is no longer in
 * `allEntries`), `configureActiveMatch` falls back to `Rect(topLeft, lookaheadSize)` — the
 * INCOMING art's own bounds — and the gesture morphs from the target to itself, i.e. no flight at
 * all, with the outgoing copy hidden (`SharedElementEntry.shouldRenderAtAll` is false for a
 * non-target while a match is configured).
 *
 * **That was shipped (2026-09-15) as THE repair for device reports 44/48, and it did not fix them.**
 * Cris, on a build carrying both it and the direct `snapTo` in `unwindSeek`: "the full player to mini
 * player container morph is still broken if I cancel a predictive back and then go back". The trace
 * above is sound about what the state machine DOES, but a stranded provider is only one of the
 * states a cancelled seek can leave behind (the match state itself, a configured match's
 * `targetData`/`currentBounds`), and two paths in the sources say even that one should self-heal
 * within a frame. So the actual fix is [LocalPlayerArtKey]: give the element a FRESH IDENTITY after
 * every settle, which cannot depend on knowing which field went stale. This counter and its
 * re-measure are kept as **belt-and-braces** — they are cheap, they are idempotent
 * (`updateTargetBoundsProvider` is gated on a request id, and `invalidateTargetBoundsProvider`
 * early-returns while the live target's provider already matches), and they still repair the one
 * case a fresh key cannot reach: a stale provider on an element whose key did NOT change because the
 * bump gate was closed. Its trigger is deliberately left exactly as it was — the strict
 * "nothing is moving anywhere" gate belongs to the key, not to a no-op re-measure.
 *
 * Defaults to a counter that never changes, so where the provider is out of scope — a preview, or
 * the docked third pane, which is composed BESIDE this host — the modifier is inert.
 */
val LocalPlayerArtSettleCount: ProvidableCompositionLocal<IntState> =
    staticCompositionLocalOf { NoArtSettles }

/**
 * A pass-through layout modifier that re-measures its `LayoutNode` whenever
 * [LocalPlayerArtSettleCount] is bumped. Chain it onto every `"album-art"` participant that can
 * outlive a transition; see that counter's KDoc for what the re-measure repairs.
 *
 * The counter is read INSIDE the measure block on purpose: measure-scope reads are observed
 * (`OwnerSnapshotObserver.observeMeasureSnapshotReadsAffectingLookahead`), so a bump runs
 * `LayoutNode.invalidateMeasurements()` → `requestLookaheadRemeasure()` (the node lives in a
 * lookahead scope) → the whole modifier chain's lookahead measure + placement re-runs, including
 * the `sharedElement` node's own — which is what calls `processPendingRequest()` →
 * `updateTargetBoundsProvider()`. Reading it in composition instead would not do: the shared node
 * is only re-measured when this node is, and the two must be the same `LayoutNode`, which is why
 * this is a modifier on the art rather than anything around it.
 */
@Composable
fun rememberArtSettleInvalidation(): Modifier {
    val settles = LocalPlayerArtSettleCount.current
    return remember(settles) {
        Modifier.layout { measurable, constraints ->
            // The counter only ever grows from 0, so this is always 0. It is used (rather than
            // read and discarded) so the read cannot be optimised away — and it can never move
            // the art.
            val settleNudge = settles.intValue.coerceAtMost(0)
            val placeable   = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(settleNudge, 0) }
        }
    }
}

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

/**
 * Which of the app's two floating player surfaces is on screen. They are mutually exclusive, so ONE
 * state describes both — and, crucially, ONE seekable transition animates over it.
 *
 * That is not a tidiness choice, it is the fix for a broken container transform (device pass
 * 2026-09-14): the bar used to have its own `SeekableTransitionState` whose target was flipped from
 * a `LaunchedEffect`, i.e. one frame AFTER the composition in which the panel's own
 * `updateTransition(panelVisible)` had already started entering. For that one frame BOTH
 * `"album-art"` entries reported `boundsAnimation.target == true`, and a shared element with two
 * simultaneous targets has no defined target bounds — `SharedElement`'s KDoc says it outright ("we
 * expect there to be only 1 state that is becoming visible, which we will use to derive target
 * bounds"), and `invalidateTargetBoundsProvider` just takes `enabledEntries.fastFirstOrNull { it
 * .target }`, which was the *mini player* (added first). So the art was configured against its own
 * geometry and simply appeared in the panel instead of flying into it.
 *
 * A child transition's target is computed in the SAME composition pass as its parent's
 * (`createChildTransition` reads `parentTransition.targetState` directly), so with both halves as
 * children of one transition the bar's hide and the panel's show can no longer be a frame apart.
 * It also means a single `seekTo` drives both ends of the morph — which is what lets a
 * predictive-back gesture seek the panel in from the full player, and seek it back out to the bar.
 */
private enum class PlayerSurface { None, Bar, Panel }

/**
 * Phase of the pop-out panel's OWN predictive-back close gesture.
 *
 * Same reasoning as the Library's `BackPhase` (docs/MOTION.md → Predictive back): the gesture and
 * the state change that commits it are asynchronous, so the effect that drives the transition has
 * to know WHY the state looks the way it does — a panel that is still open means "unwind" after a
 * cancelled gesture but "wait for `closePanel()`" after a committed one. It also tells the
 * ROUTE-driven seek to stand down: `NavigationEventProcessor` writes `InProgress` for every
 * predictive gesture regardless of which handler won it, so without this the two would both drive
 * the same transition.
 *
 * `Seeking` is set before the progress flow is collected, so the first progress frame cannot race
 * an `animateTo` against the seek. All the suspending work lives in the effects below, never in the
 * handler's own lambda: `PredictiveBackHandler` CANCELS that lambda's job on a cancelled gesture
 * (`ComposePredictiveBackHandler.onBackCancelled` → `activeJob?.cancel()`), so an unwind animation
 * written there would never run.
 *
 * `Committing` has TWO exits, and the second one is not optional: the phase parks the effect while
 * the panel is still what the app wants (waiting for `closePanel()` to be read back), but a
 * re-OPEN during the committed close must drop the phase so the reversal can play — see the branch
 * itself for why an unhandled re-open froze both surfaces mid-slide behind a live scrim.
 */
private enum class PanelBackPhase { Idle, Seeking, Committing, Cancelled }

/** Non-snapshot cell for the mini player's last on-screen width fraction — see its use site. */
private class MiniWidthHolder(var value: Float)

/**
 * Non-snapshot scratch for the TEMPORARY morph diagnostics (`util/MorphDiag.kt`) — it keeps a
 * per-gesture "already logged" flag so the seek branches can log their FIRST progress frame
 * without logging every frame. Delete with the diagnostics.
 */
private class MorphDiagState(var seekLogged: Boolean = false)

/** Non-snapshot cell for the last browse surface the panel was seen over — see its use site. */
private class SurfaceKeyHolder(var value: String?)

// `createChildTransition` — the one experimental API here; the shared-transition API itself is
// stable as of compose-animation 1.12.
@OptIn(ExperimentalTransitionApi::class)
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

    // ── The floating player surface, as ONE seekable transition ──────────────────────────────────
    //
    // Before the hoist the bar lived INSIDE the Library destination, which `NavHost` composes at the
    // START of a back gesture and seeks with the finger — so the bar and the mini↔big "album-art"
    // morph tracked the drag and unwound on an early release. Hosted out here its visibility became
    // a plain Boolean flipped by `currentBackStackEntryAsState()`, which only moves when the pop
    // COMMITS: the bar was absent for the whole drag and slid in afterwards, and the art morph
    // snapped partway instead of following the thumb. That is why this is a `SeekableTransitionState`
    // driven the way `NavHost.kt` drives its own AnimatedContent.
    //
    // It animates over [PlayerSurface] — BOTH surfaces, not just the bar — for the reason spelled
    // out on that enum: a bar whose target flipped one frame after the panel's left the shared
    // element with two simultaneous targets and no defined target bounds, so the art appeared in
    // the panel instead of flying into it. One state also means one `seekTo` moves both ends of the
    // morph, which is what the two predictive-back gestures below need.
    //
    // Track presence is read here (not only in `MiniPlayerHolder`) because it belongs in the state
    // the transition animates over. SEEDED SYNCHRONOUSLY from the StateFlow's current value —
    // `collectAsStateWithLifecycle` bakes its initial value into its own `remember`, and a literal
    // `false` would make the bar animate in from nothing on every Activity recreation.
    val hasTrack by remember {
        playerViewModel.uiState.map { it.currentTrack != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(playerViewModel.uiState.value.currentTrack != null)

    // ONE expression for which surface is wanted, parameterised by the two things that change
    // between the questions asked of it: the ROUTE gate, and whether the panel still owns this
    // surface. `surfaceFor(visible) == Panel` is exactly `panelVisible`, and `== Bar` is exactly
    // the old `hasTrack && !panelOwned && visible` — they can no longer drift apart.
    // `panelOwned` (never the raw `showPlayerPanel`) is the value here for the same reason it feeds
    // `panelVisible`: on a surface the panel does not own, the panel is gone and the bar is what
    // the user gets, so the two diverging would make the mini player vanish for the session.
    fun surfaceFor(routeShows: Boolean, owned: Boolean = panelOwned): PlayerSurface = when {
        owned && canShowPanel && routeShows -> PlayerSurface.Panel
        hasTrack && !owned && routeShows    -> PlayerSurface.Bar
        else                                -> PlayerSurface.None
    }
    /** What the app wants on screen right now. */
    val surfaceTarget          = surfaceFor(visible)
    /** …what a committed back would make it (the entry BELOW the current one). */
    val surfaceAfterBack       = surfaceFor(visibleAfterBack)
    /** …and what closing the panel on this route would make it: the bar takes over. */
    val surfaceAfterPanelClose = surfaceFor(visible, owned = false)
    // On extra-wide the bar and the panel are simply not composed (the whole
    // `SharedTransitionLayout` below is skipped), so the only child left on this transition is the
    // scrim's `animateFloat` — still a finite 300 ms, and the cancel branch floors the duration
    // anyway. Gating `surfaceTarget` on `!isExtraWide` as well would work too, and would cost an
    // extra state change on a rotation that crosses the gate; leaving it alone keeps the state
    // route-derived and nothing renders either way.
    val surfaceState      = remember { SeekableTransitionState(surfaceTarget) }
    val surfaceTransition = rememberTransition(surfaceState, label = "playerSurface")

    // The two halves as CHILD transitions, so `MiniPlayer` and `PlayerPopOutPanel` still take a
    // plain `Transition<Boolean>` and render inside its `AnimatedVisibility`. A child's target is
    // derived from its parent's IN THE SAME COMPOSITION (`createChildTransition` reads
    // `parentTransition.targetState`), which is the whole point — and `seekTo` reaches them
    // (`seekToFraction()` → `Transition.seekAnimations`, which recurses into `_transitions`), so
    // both the bar's slide, the panel's slide and the `"album-art"` bounds animation hanging off
    // either `AnimatedVisibility` follow one gesture fraction.
    val barTransition   = surfaceTransition.createChildTransition(label = "miniPlayerPresence") {
        it == PlayerSurface.Bar
    }
    val panelTransition = surfaceTransition.createChildTransition(label = "panelPresence") {
        it == PlayerSurface.Panel
    }

    // ONE no-op re-measure of all three `"album-art"` participants (pop-out card, full player, and
    // the bar as insurance), after every SETTLE of the surface. It shipped as the repair for the
    // morph breaking on the gesture that follows a CANCELLED one (device reports 44 + 48) and did
    // NOT fix it; the fix is the fresh element identity below ([LocalPlayerArtKey]), and this stays
    // as belt-and-braces for the one case a fresh key cannot reach — a stale target bounds provider
    // on an element whose key did not change because the bump gate was closed. The mechanism, and
    // why the bar has a reader at all, is in [LocalPlayerArtSettleCount]'s KDoc;
    // [rememberArtSettleInvalidation] is the reader.
    //
    // Its trigger is deliberately UNCHANGED (the strict gate belongs to the key, not to a no-op
    // re-measure): keyed on the pair rather than fired from the cancel path itself, so it runs only
    // from a composition in which `currentState == targetState`, i.e. one where the surface the
    // gesture abandoned already reports `target == false` and the state machine can only re-read a
    // provider that is still on screen. That also covers every other way the surface settles (a
    // commit, a button back, a fold, the third-state snap) at the cost of one pass-through
    // re-measure of three art chains, and it needs no knowledge of WHICH path settled it.
    val artSettles = remember { mutableIntStateOf(0) }
    LaunchedEffect(surfaceState.currentState, surfaceState.targetState) {
        if (surfaceState.currentState == surfaceState.targetState) {
            artSettles.intValue++
            morphLog {   // TEMPORARY
                "settle bump artSettles=${artSettles.intValue} at ${surfaceState.currentState}"
            }
        } else {
            morphLog {   // TEMPORARY
                "settle hold cur=${surfaceState.currentState} tgt=${surfaceState.targetState}"
            }
        }
    }

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

    // The pop-out panel's own close gesture. Written by the `PredictiveBackHandler` at the very
    // bottom of this composable (registration order is its precedence, so it must stay there);
    // read here, because every suspending call has to live outside that handler's cancellable job.
    var panelBack         by remember { mutableStateOf(PanelBackPhase.Idle) }
    var panelBackProgress by remember { mutableFloatStateOf(0f) }

    // ── A FRESH IDENTITY for the `"album-art"` element after every settle ────────────────────────
    //
    // The key every participant registers under is `"album-art#<generation>"`, and the generation is
    // bumped whenever nothing is moving. A new key is a new `SharedElement` with a new state machine
    // at `NoMatchFound` — no target bounds provider, no target data, no current bounds — so each
    // morph runs from the same standing start as the FIRST one, which is the only one that never
    // broke. The full argument, and the load-bearing dependency on the two
    // `!currentState && !targetState` bail-outs, are in [LocalPlayerArtKey]'s KDoc.
    //
    // The gate is four things, and each is needed:
    //   * the surface transition is SETTLED — `seekTo` never assigns `currentState`, so no seek
    //     frame can satisfy this, and neither can a running bar/panel slide;
    //   * no back GESTURE is in progress — a gesture that seeks nothing (browse→browse) would
    //     otherwise re-key in the middle of the user's drag;
    //   * the panel's close phase is `Idle` — `Committing` is a wait for `closePanel()` to be read
    //     back, i.e. a change still in flight;
    //   * the nav `SharedTransitionScope` reports no active shared transition. This is the one that
    //     covers `PlayerScreen`'s art, whose bounds animation hangs off the NAV transition rather
    //     than off the surface: `BoundsAnimation.isRunning` walks to the ROOT transition, so
    //     `isTransitionActive` stays true for the whole of a nav morph even after the surface has
    //     settled. Re-keying there would replace the element and the `createChildTransition` its
    //     bounds animation hangs off, mid-flight. (The LOCAL mini↔panel morph needs no equivalent
    //     term: both its participants' parent transitions are children of the surface transition,
    //     so "settled" already implies that scope is inactive.)
    // If some future bug pinned `isTransitionActive` true for good the generation would simply stop
    // advancing, i.e. exactly today's behaviour — never a wedge.
    val artKeyGen = remember { mutableIntStateOf(0) }
    val artKey    = remember(artKeyGen.intValue) { "$AlbumArtKeyBase#${artKeyGen.intValue}" }
    val morphQuiet = surfaceState.currentState == surfaceState.targetState &&
        panelBack == PanelBackPhase.Idle &&
        backProgress == null &&
        navSharedTransitionScope?.isTransitionActive != true
    LaunchedEffect(morphQuiet, surfaceState.currentState) {
        if (!morphQuiet) {
            morphLog {   // TEMPORARY
                "artKey hold gen=${artKeyGen.intValue} settled=" +
                    "${surfaceState.currentState == surfaceState.targetState}" +
                    " panelBack=$panelBack gesture=${backProgress != null}" +
                    " navMorph=${navSharedTransitionScope?.isTransitionActive}"
            }
            return@LaunchedEffect
        }
        artKeyGen.intValue++
        morphLog {   // TEMPORARY
            "artKey bump -> $AlbumArtKeyBase#${artKeyGen.intValue} at ${surfaceState.currentState}"
        }
    }

    // TEMPORARY diagnostics scratch + one line per PHASE change of the back gesture (never per
    // progress frame: the key is a Boolean, and the direction is carried in the message). See
    // util/MorphDiag.kt — delete both with it.
    val diag          = remember { MorphDiagState() }
    val gestureActive = inProgress != null
    LaunchedEffect(gestureActive) {
        morphLog {
            val phase = if (gestureActive) "InProgress(dir=${inProgress.direction})" else "Idle"
            "gesture $phase target=$surfaceTarget afterBack=$surfaceAfterBack" +
                " cur=${surfaceState.currentState} tgt=${surfaceState.targetState}" +
                " panelVisible=$panelVisible panelBack=$panelBack"
        }
    }

    // ── What drives the transition: the panel's close gesture, a route gesture, or a plain change ─
    when {
        // (1) The panel's own close gesture: the panel retreats and the bar rises with the finger,
        //     the art flying between them because both are children of this one transition.
        panelBack == PanelBackPhase.Seeking ->
            LaunchedEffect(panelBackProgress, surfaceAfterPanelClose) {
                // TEMPORARY: the FIRST progress frame only (the flag is cleared by branch 3).
                if (!diag.seekLogged) {
                    diag.seekLogged = true
                    morphLog {
                        "seek(panel) -> $surfaceAfterPanelClose p=$panelBackProgress" +
                            " cur=${surfaceState.currentState} tgt=${surfaceState.targetState}" +
                            " f=${surfaceState.fraction} panelBack=$panelBack"
                    }
                }
                surfaceState.seekTo(panelBackProgress.coerceIn(0f, 1f), surfaceAfterPanelClose)
            }

        // (2) A route back GESTURE that changes which surface the app shows, so the surface rides
        //     it. `NavHost` seeks its own transition from the same `progress`, so the two stay in
        //     step frame for frame — including the `"album-art"` morph, whose two ends are children
        //     of the two seeked transitions. This covers the bar sliding in as a browse route comes
        //     back AND the pop-out panel coming in under the full player it was hidden behind.
        //     It stands down while the panel is open: the handler at the bottom consumes that
        //     gesture and branch (1) drives it, but `NavigationEventProcessor` writes `InProgress`
        //     for EVERY predictive gesture regardless of which handler won, so without these two
        //     guards both branches would fight over one transition. (`panelBack` covers the frames
        //     after the finger lifts, where `panelVisible` has already flipped.)
        panelBack == PanelBackPhase.Idle && !panelVisible &&
            backProgress != null && surfaceAfterBack != surfaceTarget ->
            LaunchedEffect(backProgress, surfaceAfterBack) {
                // TEMPORARY: the FIRST progress frame only (the flag is cleared by branch 3).
                if (!diag.seekLogged) {
                    diag.seekLogged = true
                    morphLog {
                        "seek(route) -> $surfaceAfterBack p=$backProgress" +
                            " cur=${surfaceState.currentState} tgt=${surfaceState.targetState}" +
                            " f=${surfaceState.fraction} panelBack=$panelBack"
                    }
                }
                surfaceState.seekTo(backProgress, surfaceAfterBack)
            }

        // (3) Everything else: the commit or cancel of either gesture, and every ordinary change.
        else -> LaunchedEffect(surfaceTarget, panelBack) {
            val effectScope = this

            // TEMPORARY: one line per entry of this branch — i.e. per commit, cancel or ordinary
            // change — and re-arm the seek branches' first-frame log. See util/MorphDiag.kt.
            diag.seekLogged = false
            morphLog {
                "settled-branch panelBack=$panelBack target=$surfaceTarget" +
                    " cur=${surfaceState.currentState} tgt=${surfaceState.targetState}" +
                    " f=${surfaceState.fraction}"
            }

            // Wind a released-but-uncommitted seek back to `currentState`. The duration is scaled
            // by how far the gesture actually got — `NavHost.kt`'s cancel formula, mirrored in
            // LibrarySinglePaneLayout — or a cancel at 5 % would take a full transition to snap
            // back. Floored so a transition that momentarily reports no duration can't compute
            // `tween(0)` and snap.
            suspend fun unwindSeek() {
                val totalMillis = (surfaceTransition.totalDurationNanos / 1_000_000)
                    .coerceAtLeast(NavTransitionMillis.toLong())
                morphLog {   // TEMPORARY
                    "unwind start f=${surfaceState.fraction} cur=${surfaceState.currentState}" +
                        " tgt=${surfaceState.targetState} snapTarget=$surfaceTarget" +
                        " totalMs=$totalMillis"
                }
                animate(
                    initialValue  = surfaceState.fraction,
                    targetValue   = 0f,
                    animationSpec = tween((surfaceState.fraction * totalMillis).toInt()),
                ) { value, _ ->
                    // seekTo suspends, `animate`'s callback does not — hand the work back to this
                    // effect's own scope, as NavHost does internally.
                    effectScope.launch { if (value > 0f) surfaceState.seekTo(value) }
                }
                // The final snap is made HERE, in this function's own suspend body, and this is a
                // DELIBERATE deviation from `NavHost.kt`'s template, which launches it into the
                // effect's scope alongside the seeks above. That is safe there because NavHost's
                // effect key (the back-stack entry) does not change when a gesture is cancelled.
                // Ours does: the `Cancelled` branch writes `panelBack = Idle` the instant this
                // function returns, which re-keys the effect and cancels anything still queued in
                // its scope — and a snap lost that way leaves the transition parked with
                // `targetState` still pointing at the surface the gesture abandoned, so nothing
                // ever settles and the `"album-art"` match is never re-resolved. A `seekTo` that
                // lands after this snap is inert by construction: `currentState == targetState`
                // makes `seekTo` return without touching anything.
                surfaceState.snapTo(surfaceTarget)
                morphLog {   // TEMPORARY
                    "unwind end snapTo=$surfaceTarget cur=${surfaceState.currentState}" +
                        " tgt=${surfaceState.targetState} f=${surfaceState.fraction}"
                }
            }

            // Deliberately NO `animationSpec` on any `animateTo` below. With one,
            // `SeekableTransitionState` runs the FRACTION through that spec (Transition.kt:
            // `newAnimation.animationSpec = newSpec`) while every child is still seeked by that
            // fraction through its own curve — the easing would apply twice and the surface would
            // read visibly slower than the nav slide beside it. With none,
            // `animationSpecDuration = totalDurationNanos * (1 - fraction)` and the fraction
            // advances LINEARLY, so the children play at their natural rate and a resume costs
            // exactly the remaining duration. The finite spec THE HARD RULE (docs/MOTION.md) asks
            // for lives on those children: both slides are `screenTransitionSpec()`. This is
            // exactly what `NavHost.kt` and the Library's single-pane predictive back do.
            when (panelBack) {
                // The close gesture committed. `closePanel()` fires in the same dispatch as this
                // phase, but the two reach composition as separate state reads, so wait until the
                // panel is no longer what the app wants before finishing — animating while
                // `surfaceTarget` is still `Panel` would REVERSE the gesture. The seek already set
                // `targetState`, so `animateTo` finds it unchanged and plays only the remainder.
                PanelBackPhase.Committing -> if (surfaceTarget != PlayerSurface.Panel) {
                    morphLog { "commit: animateTo($surfaceTarget)" }   // TEMPORARY
                    surfaceState.animateTo(surfaceTarget)
                    panelBack = PanelBackPhase.Idle
                } else {
                    // `Panel` HERE means the user RE-OPENED it during the ~300 ms close (a tap on
                    // the rising mini player, or a track tap on a hosted screen — both are
                    // `onRequestPlayer`). It cannot mean "the close hasn't landed yet":
                    // `Committing` and `closePanel()` are written in the SAME dispatch, so a
                    // composition that sees this phase has already seen the close.
                    //
                    // Dropping the phase is the whole fix — it hands the reversal to `Idle` below,
                    // which is the path the panel's close BUTTON already takes. Mid-`animateTo` the
                    // re-open re-keys this effect and CANCELS it, and `animateTo` assigns
                    // `currentState` only AFTER `runAnimations()` (Transition.kt), so
                    // `currentState` never left `Panel` while the seek's `targetState` is `Bar`:
                    // `Idle`'s `targetState != surfaceTarget` arm unwinds the exit back into the
                    // panel. In the trailing-`waitForComposition()` variant (the motion finished,
                    // `currentState == targetState == Bar`) `Idle`'s `currentState != surfaceTarget`
                    // arm animates the panel in again. Without this arm the phase stayed
                    // `Committing` for good and nothing ever drove the transition again: bar and
                    // panel frozen mid-slide, `scrimAlpha` frozen part-way, and — because
                    // `panelVisible` is true again — the scrim composed WITH its `clickable`, i.e.
                    // a permanent half-opacity tap sink over the browse screen with
                    // `LocalPopOutPanelOpen` true, so every hosted back handler yielded to it.
                    morphLog { "commit: panel re-opened mid-close -> Idle" }   // TEMPORARY
                    panelBack = PanelBackPhase.Idle
                }
                // Released without committing: unwind, leaving the panel open.
                PanelBackPhase.Cancelled -> {
                    morphLog { "cancel(panel): unwind" }   // TEMPORARY
                    unwindSeek()
                    panelBack = PanelBackPhase.Idle
                }
                PanelBackPhase.Idle -> when {
                    // Ordinary change (a tap on the mini player, the panel's close button, a push
                    // to Player, a button/committed back, Settings↔browse) AND the commit of a
                    // seeked route gesture, which is the same thing one frame later.
                    //
                    // Note this branch, not the dispatcher's `Idle`, is what commits a route
                    // gesture: the two arrive through separate flow collections (the pop happens
                    // inside `dispatchOnCompleted` BEFORE it writes `Idle`, but they reach
                    // composition independently), and keying the branch on `surfaceTarget` means
                    // whichever lands first is the one that decides. Idle-first costs at most one
                    // frame of the unwind below before the route flip restarts this effect and
                    // resumes the entrance.
                    surfaceState.currentState != surfaceTarget -> {
                        // A change that arrives while a change to a THIRD state is still in flight
                        // must not redirect it: both surfaces would then have `target == false`,
                        // the shared element would lose its target entirely
                        // (`VisibleContentAbsentDuringTransition` → `calculateAlternativeTargetBounds`)
                        // and the art would drift back toward the geometry it came from and vanish.
                        // That is device report 48 — close the panel and open Settings immediately,
                        // and the art flew back toward the panel and disappeared. Completing the
                        // in-flight change first resolves the match, then the new one plays from a
                        // settled state.
                        //
                        // Deliberately NOT applied to a plain REVERSAL (push to Player, then pop
                        // straight back): there the two participants' targets flip together, so
                        // there is always exactly one target and nothing is stranded — and snapping
                        // would yank the bar fully off screen before sliding it back in. A reversal
                        // lands in the `targetState != surfaceTarget` branch below and unwinds.
                        if (surfaceState.currentState != surfaceState.targetState &&
                            surfaceTarget != surfaceState.targetState &&
                            surfaceTarget != surfaceState.currentState
                        ) {
                            morphLog { "idle: snapTo in-flight ${surfaceState.targetState}" }   // TEMPORARY
                            surfaceState.snapTo(surfaceState.targetState)
                        }
                        morphLog { "idle: animateTo($surfaceTarget)" }   // TEMPORARY
                        surfaceState.animateTo(surfaceTarget)
                    }
                    // A route gesture released without committing, or a reversal of a running
                    // change back to where it started.
                    surfaceState.targetState != surfaceTarget -> {
                        morphLog { "idle: cancel/reversal -> unwind" }   // TEMPORARY
                        unwindSeek()
                    }
                    else -> morphLog { "idle: nothing to do" }   // TEMPORARY
                }
                // Driven by branch (1); this effect is not even composed then.
                PanelBackPhase.Seeking -> Unit
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

    // The scrim's alpha is an animation ON THE PANEL'S OWN TRANSITION, not a separate
    // `animateFloatAsState`: it therefore cannot desync from the panel's slide, and — the reason it
    // changed — it SEEKS with a predictive-back gesture like everything else on that transition, so
    // it fades in with the finger as the panel comes in from under the full player and fades out
    // with the finger as the panel is dragged away. `screenTransitionSpec()` is the same
    // `tween(300, FastOutSlowInEasing)` this used to pass literally; never a spring, an alpha would
    // overshoot a valid range (docs/MOTION.md).
    val scrimAlpha by panelTransition.animateFloat(
        transitionSpec = { screenTransitionSpec() },
        label          = "panelHostScrim",
    ) { shown -> if (shown) 0.45f else 0f }

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
        // The `"album-art"` KEY and the settle counter are provided here for `PlayerScreen`, which
        // lives inside the NavHost; the mini player and the pop-out panel are SIBLINGS of the
        // content, so both are provided again around each of them below rather than by re-parenting
        // this whole Box. EVERY registration must read the same key — the mini player's two scopes,
        // the pop-out card's two, and `PlayerScreen`'s (one per orientation). They cannot disagree:
        // one dynamic `compositionLocalOf` invalidates all of its readers together, and they all
        // then read the same snapshot value.
        CompositionLocalProvider(
            LocalPopOutPanelOpen       provides panelVisible,
            LocalPlayerArtSettleCount  provides artSettles,
            LocalPlayerArtKey          provides artKey,
        ) {
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
            // `visible ||` is a cosmetic rider on the same idea: the fade-out outlives the route
            // flip, so on a push to PlayerScreen the dimming wash would otherwise be drawn over the
            // INCOMING player for ~300ms. Gating the draw on the route trades that for the outgoing
            // browse screen losing its dim abruptly rather than fading — the better of the two,
            // since the outgoing screen is sliding out under it anyway.
            //
            // The second term is what lets the scrim seek IN under the full player: during a back
            // gesture off `PlayerScreen` the route still reads as Player (`currentBackStackEntry`
            // only moves at commit) while the panel's target is already `true` from the seek, and
            // without it the scrim would pop in at 0.45 the instant the gesture committed. It
            // cannot re-open the hole this gate exists to close: `panelTransition.targetState` is
            // true only when `surfaceTarget == Panel` — which requires `visible` — or during
            // exactly that gesture.
            if ((visible || panelTransition.targetState) && scrimAlpha > 0f) {
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
                    //
                    // The freeze exists for a ROUTE change mid-slide, NOT for a FOLD. Keyed on
                    // `isWideScreen` so unfolding re-seeds it even while the bar is hidden:
                    // without that, opening the full player folded and then unfolding left the
                    // held value at the folded 1f, so backing out brought the bar in at full
                    // width — art morphing to the far left — and only then shrank it to the
                    // right pane's 0.58f (device report 45).
                    val widthTarget = if (isWideScreen && !miniPlayerFullWidth) 0.58f else 1f
                    val heldWidth   = remember(isWideScreen) { MiniWidthHolder(widthTarget) }
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
                    // Read off the panel's own child transition, which is still the exact animation
                    // rendering the panel — so this stays true until the panel is genuinely gone,
                    // and it is now true from the first frame of a gesture that seeks the panel in
                    // (the raw Boolean and a separate tween could each be a frame or a spring out).
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
                    val routeVisible = LocalPlayerRouteVisible.current
                    val navArtConfig = rememberMatchWhenConfig(routeVisible)

                    // TEMPORARY: one line whenever the scope routing or the match gate changes —
                    // this is what says WHICH participants were eligible on a given gesture.
                    // Read inside this scope (not at host-body scope) so the diagnostics do not
                    // move where `LocalPlayerRouteVisible` is subscribed. See util/MorphDiag.kt.
                    LaunchedEffect(routeVisible, canShowPanel, miniNeedsNavScope, panelPresent) {
                        morphLog {
                            "gates routeVisible=$routeVisible canShowPanel=$canShowPanel" +
                                " miniNavScope=$miniNeedsNavScope panelPresent=$panelPresent"
                        }
                    }

                    // The mini player's own AnimatedVisibility — a child of `barTransition` above — is
                    // its shared-element scope in BOTH layers (MiniPlayer falls back to its inner scope,
                    // which is now the only one). That is what keeps the nav-level mini↔PlayerScreen
                    // morph working with the bar living outside every destination: the bar is the EXIT
                    // participant on a push to Player (already composed when the surface leaves `Bar`)
                    // and the ENTER participant on the way back — composing fresh into a live transition at
                    // the first frame of the back GESTURE, not at commit, which is the whole point of
                    // the seek. Matching is per key within a SharedTransitionScope and independent of
                    // each participant's parent transition (SharedTransitionScope.kt `sharedElementsFor`:
                    // `sharedElements.getOrPut(key)`; the AnimatedVisibilityScope only supplies
                    // `parentTransition` to `sharedBoundsImpl`), so an AnimatedVisibility-scoped bar and
                    // an AnimatedContent-scoped PlayerScreen do match — and because both parents are
                    // being seeked by the same gesture progress, the morph tracks the finger.
                    //
                    // The art key and the settle counter are provided here because the bar, like
                    // the panel below, is a SIBLING of `content` above — so without this wrapper the
                    // bar's art would register under the bare default key (never matching the
                    // generation the full player and the panel use) and would read the inert
                    // `NoArtSettles`. UNCONDITIONAL on purpose: a conditional provider would
                    // flip the holder's composition group every time the condition changed and
                    // reset everything it remembers (the art slide `Animatable` included), which is
                    // the same trap as the old `if (isExtraWide) { …; return }` in the header
                    // comment.
                    CompositionLocalProvider(
                        LocalPlayerArtSettleCount provides artSettles,
                        LocalPlayerArtKey         provides artKey,
                    ) {
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
                    }

                    // Pop-out panel (wide non-short screens only). The art key and the settle
                    // counter are provided again here because the panel is a SIBLING of `content`
                    // above, and its art is the participant that SURVIVES a cancelled close gesture.
                    if (canShowPanel) {
                        CompositionLocalProvider(
                            LocalPlayerArtSettleCount provides artSettles,
                            LocalPlayerArtKey         provides artKey,
                        ) {
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
            }
        }   // if (!isExtraWide) — scrim + mini/pop-out only

        // Back closes the pop-out panel, and the panel FOLLOWS THE FINGER doing it: the panel and
        // its scrim retreat, the mini player rises and the art flies between them, all seeked by
        // the gesture, unwinding on an early release. A plain `BackHandler` could only close it
        // after the gesture committed. The handler itself does nothing but record the phase and
        // the progress — `PredictiveBackHandler` CANCELS this lambda's job when the gesture is
        // cancelled (`ComposePredictiveBackHandler.onBackCancelled` → `activeJob?.cancel()`), so an
        // unwind animation written here would never run; the suspending work lives in the effects
        // above, exactly as in `LibrarySinglePaneLayout`. A NON-predictive back (button, 3-button
        // nav) still arrives here: `onBackCompleted` opens a session whose flow completes with zero
        // events, so it lands straight in `Committing` and the close plays as an animation.
        //
        // Composed LAST, AFTER `content` — i.e. after the NavHost and everything inside it — so it
        // is the most recently added enabled handler and wins. This, `BackHandler` and NavHost's
        // own predictive-back handler all register on the SAME `NavigationEventDispatcher` via
        // `addHandler` at `PRIORITY_DEFAULT` from an effect (navigation-compose 2.10.0's
        // `rememberNavHostEventHandler`; activity-compose 1.13.0's `BackHandler` /
        // `PredictiveBackHandler`), and `NavigationEventProcessor.findHandler` resolves
        // most-to-least recently added within a priority — on both
        // `ActivityFlags.isOnBackPressedLifecycleOrderMaintained` branches.
        // Order alone is still NOT the guard for handlers inside `content`: a layout composed after
        // this host already exists (unfolding into TwoPaneLayout) registers later and outranks it.
        // That is what [LocalPopOutPanelOpen] is for — and it stays true for the whole gesture,
        // since `closePanel()` only runs at commit. Keep this call unconditional (the KDoc warns
        // that conditional calls change composition order); `enabled` alone makes it yield when the
        // panel is absent, so the Library's single-pane PredictiveBackHandler is untouched. That
        // includes the extra-wide width, where this now registers (it did not before the early
        // return was removed) but `panelVisible` is structurally false, so it always yields — and
        // `DockedPlayerPane`'s own handler is composed after this host anyway.
        PredictiveBackHandler(enabled = panelVisible) { events ->
            panelBackProgress = 0f
            panelBack         = PanelBackPhase.Seeking   // BEFORE collecting, so the first progress
            try {                                        // frame can't race an animateTo
                events.collect { event -> panelBackProgress = event.progress }
                // Committed. The phase flips first so the effect parks instead of unwinding during
                // the frame or two before `closePanel()`'s state change is read back.
                panelBack = PanelBackPhase.Committing
                closePanel()
            } catch (_: CancellationException) {
                panelBack = PanelBackPhase.Cancelled
            }
        }
    }
}
