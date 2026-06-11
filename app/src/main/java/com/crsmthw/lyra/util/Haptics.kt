package com.crsmthw.lyra.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop

/**
 * Material 3 Expressive haptics, mapped to semantic intents so call sites read clearly and the
 * whole app's haptic vocabulary lives in one place. Always fired from a user gesture (never from
 * reactive state changes, which would buzz on external playback sync).
 *
 * Every helper is gated on [HapticsConfig.enabled], which mirrors the Settings → Haptic feedback
 * toggle (kept in sync from the app root). When off, all haptics no-op.
 */
object HapticsConfig {
    @Volatile var enabled: Boolean = true
}

/** Distinct on/off feedback for a switch-like control (shuffle, like, settings switches…). */
fun HapticFeedback.toggle(enabled: Boolean) = perform(
    if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
)

/** Affirmative: open a playlist/track, add to playlist, set a timer, pick a device, confirm delete. */
fun HapticFeedback.confirm() = perform(HapticFeedbackType.Confirm)

/** Failed/blocked action. */
fun HapticFeedback.reject() = perform(HapticFeedbackType.Reject)

/** Long-press to reveal a menu. */
fun HapticFeedback.longPress() = perform(HapticFeedbackType.LongPress)

/** Light click: primary transport (play/pause/skip), opening a menu/sheet, secondary buttons. */
fun HapticFeedback.press() = perform(HapticFeedbackType.ContextClick)

/** A slider/seek bar crossing a notch. */
fun HapticFeedback.tick() = perform(HapticFeedbackType.SegmentTick)

/** A list crossing an item boundary while scrolling (fired per item). */
fun HapticFeedback.scrollTick() = perform(HapticFeedbackType.SegmentFrequentTick)

/** A drag crossing an activation threshold (pull-to-refresh trigger point). */
fun HapticFeedback.threshold() = perform(HapticFeedbackType.GestureThresholdActivate)

private fun HapticFeedback.perform(type: HapticFeedbackType) {
    if (HapticsConfig.enabled) performHapticFeedback(type)
}

/**
 * Fires [scrollTick] as [listState] scrolls — on every item-boundary crossing, and additionally
 * every ~80dp of offset *within* the current item. The within-item notch is what keeps a tall
 * collapsing hero/header (a single large item, so its index never changes while it collapses)
 * buzzing instead of going silent until the first row appears. The 80dp notch is larger than a
 * normal track row, so ordinary rows still get ~one tick each (from the boundary) and only tall
 * items add extra ticks. The initial state is captured before collecting so entering a screen
 * doesn't buzz.
 */
@Composable
fun ListScrollHaptics(listState: LazyListState) {
    val haptics = LocalHapticFeedback.current
    val notchPx = with(LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(listState, notchPx) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastNotch = (listState.firstVisibleItemScrollOffset / notchPx).toInt()
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { (index, offset) ->
                val notch = (offset / notchPx).toInt()
                if (index != lastIndex || notch != lastNotch) haptics.scrollTick()
                lastIndex = index
                lastNotch = notch
            }
    }
}

/**
 * [ListScrollHaptics] for a screen whose hero is a separate `LargeTopAppBar` driven by
 * `exitUntilCollapsedScrollBehavior` (the Library browser pane's collapsing "Lyra" hero), rather
 * than an item inside the list. While that bar collapses, its nested-scroll connection consumes
 * the scroll delta into [TopAppBarState.heightOffset] (1:1 with the finger) and the list stays
 * pinned at offset 0; once fully collapsed, the list consumes the delta instead (also 1:1). So
 * `(-heightOffset) + firstVisibleItemScrollOffset` is *total finger travel* across the whole
 * gesture — one continuous counter with no dead zone at the collapse→scroll handoff. Ticking off
 * that combined distance (plus item-boundary crossings, as in [ListScrollHaptics]) keeps an even
 * ~80dp cadence through the handoff in both directions. Use this *instead of* [ListScrollHaptics]
 * on such a screen — never both, or the post-collapse list phase double-buzzes. The initial state
 * is captured before collecting so entering the screen doesn't buzz.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingListScrollHaptics(listState: LazyListState, barState: TopAppBarState) {
    val haptics = LocalHapticFeedback.current
    val notchPx = with(LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(listState, barState, notchPx) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastNotch = ((-barState.heightOffset + listState.firstVisibleItemScrollOffset) / notchPx).toInt()
        snapshotFlow { Triple(barState.heightOffset, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .drop(1)
            .collect { (h, index, offset) ->
                val notch = ((-h + offset) / notchPx).toInt()
                if (index != lastIndex || notch != lastNotch) haptics.scrollTick()
                lastIndex = index
                lastNotch = notch
            }
    }
}
