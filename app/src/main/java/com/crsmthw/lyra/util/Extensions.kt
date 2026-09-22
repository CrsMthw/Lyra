package com.crsmthw.lyra.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * The ONE idiom for "keep this screen's content clear of a system bar that sits on a SIDE edge".
 *
 * With 3-button navigation in landscape the navigation bar moves to the left **or** the right edge
 * (it follows the rotation direction), and `enableEdgeToEdge()` means the app owns that inset. A
 * screen that only handles top/bottom insets therefore runs its rows, lists and floating pills
 * underneath the nav buttons on whichever edge they happen to be — the Stats "TOP ARTISTS" row hid
 * its 8th artist behind them. `union(displayCutout)` additionally keeps content off a hole-punch
 * camera, which lands on a side edge in landscape too.
 *
 * **Apply it ONCE, on a screen's outermost content container** (the `Box`/`Row` inside the
 * `Scaffold` body), so every child — lists, `contentPadding`-inset `LazyRow`s and the floating
 * top pills alike — clears the bar without each of them repeating the inset. Per CLAUDE.md's inset
 * rules, never combine it with `navigationBarsPadding()` on the SAME element: that modifier already
 * carries the horizontal sides, so the pair double-pads. Elements that need the bottom inset as
 * well keep plain `navigationBarsPadding()` instead of this (see `PlayerScreen`'s content column).
 *
 * Full-bleed decoration (top/bottom scrims, the visualizer wave) is fine either inside or outside
 * the padded container — it is drawn, never touched or read.
 */
@Composable
fun Modifier.horizontalSystemBarsPadding(): Modifier = this.windowInsetsPadding(
    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
)

/** Clickable with built-in debounce – prevents double-tap actions. */
@Composable
fun Modifier.debouncedClickable(
    debounceMs       : Long    = 400L,
    enabled          : Boolean = true,
    role             : Role?   = null,
    onClick          : () -> Unit,
): Modifier {
    var lastClick by remember { mutableLongStateOf(0L) }
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        enabled           = enabled,
        interactionSource = interactionSource,
        indication        = ripple(),
        role              = role,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastClick >= debounceMs) {
            lastClick = now
            onClick()
        }
    }
}

/** Format milliseconds as m:ss */
fun Long.toTimeString(): String {
    val totalSeconds = this / 1000
    val minutes      = totalSeconds / 60
    val seconds      = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Format milliseconds as Xh Ym or Ym Zs */
fun Long.toDurationString(): String {
    val totalSeconds = this / 1000
    val hours        = totalSeconds / 3600
    val minutes      = (totalSeconds % 3600) / 60
    return when {
        hours > 0  -> "${hours}h ${minutes}m"
        else       -> "${minutes}m"
    }
}
