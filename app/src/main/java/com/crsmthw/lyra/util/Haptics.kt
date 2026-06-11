package com.crsmthw.lyra.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
 * Fires [scrollTick] as [listState] scrolls — once each time a new item is revealed (or hidden)
 * at the **bottom** edge of the viewport, i.e. whenever the last visible item's index changes.
 *
 * Anchoring on the bottom edge (rather than the first visible item) means a tall item at the TOP —
 * a hero band or a collapsing header — never gates the feedback: items keep crossing the bottom
 * edge no matter how tall whatever sits at the top is, so the cadence stays ~one tick per item with
 * no "silent until the hero scrolls away" dead zone, and with no within-item notch (which would
 * otherwise add a second tick to any row taller than the notch). Gated on an in-progress scroll so
 * appending / lazy-loading items — which also grows the last index — stays silent (haptics fire
 * from the user's scroll, never from a data change). The initial value is dropped so opening a
 * screen doesn't buzz.
 */
@Composable
fun ListScrollHaptics(listState: LazyListState) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .drop(1)
            .collect { if (listState.isScrollInProgress) haptics.scrollTick() }
    }
}
