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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
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
    private val isHidingProvider: () -> Boolean,
    private val hideThen: (then: () -> Unit) -> Unit,
) {
    /**
     * True while a [dismiss] hide animation is in flight. Snapshot-backed, so content that reads it
     * recomposes when it flips.
     *
     * Content MUST gate every row that acts WITHOUT going through [dismiss] on `!isHiding` — such a
     * row bypasses the latch below as well as the animation, and the rows stay on screen and
     * tappable for the whole hide (`TrackActionsSheet`'s "Add to playlist" is a sheet SWAP; "Add to
     * queue" and the like toggle act in place). Rows that DO go through [dismiss] need no gate.
     *
     * Resets to false when a hide is CANCELLED — a user drag, or M3's own `animateToDismiss`,
     * pre-empts `AnchoredDraggable`'s mutex and `hide()` throws `CancellationException` — so a
     * sheet the user drags and releases back to Expanded is fully usable again instead of a zombie
     * whose every row is permanently inert.
     */
    val isHiding: Boolean get() = isHidingProvider()

    /**
     * Animates the sheet down, then runs [then], then the sheet's own `onDismissRequest`.
     *
     * **That order is load-bearing** — [then] runs BEFORE `onDismissRequest`. Follow-up work often
     * reads the very state `onDismissRequest` clears (`LibraryViewModel.removeTrackFromCurrentPlaylist()`
     * reads `trackActions.state.value.target` synchronously), so dismissing first would silently
     * turn those actions into no-ops.
     *
     * Only one hide is in flight at a time: without the latch a second tap during the animation
     * would queue a second [then] (two share choosers, two navigations). The latch is [isHiding].
     *
     * **Neither [then] nor `onDismissRequest` runs if the hide is cancelled** — both live INSIDE
     * the coroutine after `hide()` returns, and code after a cancellation point does not run. That
     * is deliberate: a cancelled hide means the sheet is still up, and a follow-up firing then
     * would act against (and clear) state the visible sheet still owns. If the user's own drag
     * carries the sheet on to Hidden, M3 fires the sheet's own `onDismissRequest` and [then] is
     * abandoned with the action — correct, the user took it back.
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
val LocalSheetDismissal = staticCompositionLocalOf {
    SheetDismissal(isHidingProvider = { false }) { then -> then() }
}

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
        // Snapshot-backed so the content can disable the rows that bypass the handle while the
        // hide runs — see [SheetDismissal.isHiding].
        val hideInFlight = mutableStateOf(false)
        SheetDismissal(isHidingProvider = { hideInFlight.value }) { then ->
            if (!hideInFlight.value) {
                hideInFlight.value = true
                coroutineScope.launch {
                    // The follow-up runs INSIDE the coroutine, after `hide()` returns NORMALLY —
                    // never from `invokeOnCompletion`, which also fires on CANCELLATION. Both
                    // cancellations are real: `rememberCoroutineScope`'s scope dies when this sheet
                    // leaves composition (a sheet SWAP does exactly that — tapping "Add to
                    // playlist" while a hide is in flight disposed the actions sheet, and the
                    // completion handler then ran `then()` + `onDismissRequest`, clearing the
                    // picker state before the picker had rendered), and a user drag or M3's own
                    // `animateToDismiss` pre-empts `AnchoredDraggable`'s mutex, which makes
                    // `hide()` throw. Code after a cancellation point cannot run, so the two lines
                    // below are unreachable on cancellation by construction.
                    try {
                        sheetState.hide()
                    } catch (e: CancellationException) {
                        // Release the latch: a drag that pre-empts the hide and settles back to
                        // Expanded leaves the sheet on screen, and without this every row that
                        // gates on `isHiding` — and every further `dismiss()` — would stay dead.
                        hideInFlight.value = false
                        throw e
                    }
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
