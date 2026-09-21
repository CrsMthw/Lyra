package com.crsmthw.lyra.util

import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TabIndicatorScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.abs

/**
 * Makes a `PrimaryTabRow`'s indicator follow a [PagerState] per frame, so a swipe re-places and
 * re-measures the bar via the layout phase with no recomposition. A tap rides the same path:
 * `animateScrollToPage` moves the pager and the bar moves with it.
 *
 * Geometry reproduces what `TabRowImpl` does for the stock indicator:
 *  - `width` is the lerped `TabPosition.contentWidth` plus [extraWidth] — M3's `matchContentSize`
 *    look, the bar hugging the label and morphing between two labels' widths mid-swipe. Pass
 *    [extraWidth] when the tab uses a custom content lambda with padding narrower than M3's default
 *    16dp per side (the Library's halved label padding costs 16dp of indicator width that must be
 *    added back); leave it at `0.dp` for tabs using the `text =` slot, whose intrinsic width
 *    carries the full padding `contentWidth` assumes.
 *  - The bar ends up centred in the tab via M3's `apparentToRealOffset` mechanism, so no explicit
 *    centring offset is added.
 *  - The RTL negation is `TabIndicatorOffsetNode`'s own pattern.
 *
 * `width = Dp.Unspecified` on the `PrimaryIndicator` remains mandatory at the call site so its
 * `requiredWidth` is a pass-through.
 */
@ExperimentalMaterial3Api
fun TabIndicatorScope.pagerTrackingIndicator(
    pagerState : PagerState,
    extraWidth : Dp = 0.dp,
): Modifier = Modifier.tabIndicatorLayout { measurable, constraints, tabPositions ->
    // Stock `TabIndicatorOffsetNode`'s own guard: `TabRowImpl` publishes the tab positions from
    // inside its own measure pass, so a measure that runs before that has nothing to place.
    if (tabPositions.isEmpty()) return@tabIndicatorLayout layout(0, 0) {}
    val lastTab  = tabPositions.lastIndex
    val page     = pagerState.currentPage.coerceIn(0, lastTab)
    // Within +/-0.5: past that `currentPage` flips and the sign inverts, and because
    // `lerp(a, b, 0.5) == lerp(b, a, 0.5)` the bar is continuous across the flip.
    // Do NOT rescale it to reach 1.0 — |fraction| already IS the distance travelled
    // towards the neighbour, and rescaling would overshoot past it.
    val fraction = pagerState.currentPageOffsetFraction
    val towards  = when {
        fraction > 0f -> page + 1
        fraction < 0f -> page - 1
        else          -> page
    }.coerceIn(0, lastTab)
    val t     = abs(fraction).coerceIn(0f, 1f)
    val left  = lerp(tabPositions[page].left, tabPositions[towards].left, t)
    val width = lerp(
        tabPositions[page].contentWidth    + extraWidth,
        tabPositions[towards].contentWidth + extraWidth,
        t,
    )
    val widthPx   = width.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minWidth = widthPx, maxWidth = widthPx))
    val x = left.roundToPx()
        .let { if (layoutDirection == LayoutDirection.Ltr) it else -it }
    layout(placeable.width, placeable.height) { placeable.place(x, 0) }
}
