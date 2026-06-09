package com.crsmthw.lyra.util

import androidx.compose.foundation.lazy.LazyListState
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
