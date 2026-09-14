package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Gap to leave above a fully-expanded modal bottom sheet — the **status-bar height**, so the sheet
 * sits right below the status bar (like Spotify) rather than edge-to-edge.
 *
 * Cap a sheet's scrollable content to the enclosing `BoxWithConstraints.maxHeight - sheetTopGap()`
 * so the sheet's measured height stays STRICTLY below the screen. The M3 sheet puts its Expanded
 * anchor at `(fullHeight - contentSize)`; when the content fills the screen exactly that anchor is at
 * offset 0, where the spring fling overshoots and bounces / wedges touch on the first pull-up — a
 * known M3 bug (https://issuetracker.google.com/issues/285847707). Any positive gap is bounce-safe;
 * the status-bar height just sets where the fully-expanded sheet stops. Falls back to 24.dp if the
 * inset reads 0 (e.g. an immersive/edge case) so the gap is never zero.
 */
@Composable
fun sheetTopGap(): Dp = with(LocalDensity.current) {
    WindowInsets.statusBars.getTop(this).toDp().coerceAtLeast(24.dp)
}

/**
 * Handle for closing the enclosing [CappedModalBottomSheet] **with its hide animation**, published
 * to the sheet's content as [LocalSheetDismissal].
 *
 * Why it exists: a modal sheet is its own window, so simply removing it from composition (nulling
 * the state that gates it) makes it *vanish* — there is no hide animation at all. The order M3
 * documents is the other way round: animate to `Hidden` first, leave composition once hidden. That
 * is what the scrim tap, the swipe-down and the back gesture already do internally.
 */
@Stable
class SheetDismissal internal constructor(
    private val hideThen: (then: () -> Unit) -> Unit,
) {
    /**
     * Animates the sheet down, then runs [then], then the sheet's own `onDismissRequest`.
     *
     * **That order is load-bearing** — [then] runs BEFORE `onDismissRequest`. Follow-up work often
     * reads the very state `onDismissRequest` clears (`LibraryViewModel.removeTrackFromCurrentPlaylist()`
     * reads `trackActions.state.value.target` synchronously), so dismissing first would silently
     * turn those actions into no-ops.
     *
     * Only one hide is ever in flight: the rows stay on screen and tappable for the whole
     * animation, so a second tap would otherwise queue a second [then] (two share choosers, two
     * navigations). The latch is a plain captured flag, not snapshot state — nothing renders from it.
     */
    fun dismiss(then: () -> Unit = {}) {
        hideThen(then)
    }
}

/**
 * The enclosing [CappedModalBottomSheet]'s [SheetDismissal].
 *
 * The fallback (read outside any sheet) runs `then` immediately: a stray call still performs its
 * action rather than crashing, but it dismisses nothing — there is nothing to dismiss.
 */
val LocalSheetDismissal = staticCompositionLocalOf { SheetDismissal { then -> then() } }

/**
 * [ModalBottomSheet] with the app-wide configuration shared by every sheet:
 *
 * 1. **Hidden ↔ Expanded only — no partial-expanded detent.** So the sheet expands fully on open
 *    (content-sized when short, near-full + internal scroll when long), and bottom actions are never
 *    hidden behind a half-height detent (which also snaps to a screen-relative position when it
 *    settles). Pair it with a content cap of `maxHeight - sheetTopGap()` on the single scrollable
 *    child to keep the sheet below the screen height — see [sheetTopGap].
 * 2. **Programmatic dismissal animates.** The content reaches [LocalSheetDismissal] to close the
 *    sheet the way the scrim tap / swipe-down / back gesture do — `hide()` first, leave composition
 *    after — instead of vanishing mid-air the moment the state gating it flips. Work that must
 *    follow the sheet being gone (navigation, a share chooser) goes in `dismiss(then = …)`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CappedModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        SheetValue.Hidden,
        setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val coroutineScope = rememberCoroutineScope()
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val dismissal = remember(sheetState, coroutineScope) {
        var hideInFlight = false
        SheetDismissal { then ->
            if (!hideInFlight) {
                hideInFlight = true
                coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                    then()
                    currentOnDismissRequest()
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState       = sheetState,
    ) {
        val columnScope = this
        CompositionLocalProvider(LocalSheetDismissal provides dismissal) {
            columnScope.content()
        }
    }
}
