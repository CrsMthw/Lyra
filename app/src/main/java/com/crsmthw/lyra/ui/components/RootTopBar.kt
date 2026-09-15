package com.crsmthw.lyra.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Pane height at or above which a root screen gets the **large flexible** app bar. Below it — the
 * folded outer screen in landscape, a ≈380dp-tall pane — a 152/120dp expanded bar would eat a third
 * of the pane before a single row, so that case gets the small pinned bar. 600dp is the M3
 * medium-height boundary and clears portrait on both screens as well as an unfolded / tablet
 * landscape pane (≈800dp+).
 *
 * MEASURED height, not a window-size-class breakpoint: the height buckets are `[0, 480, 900]` and
 * have no 600dp boundary, so `isHeightAtLeastBreakpoint(600)` cannot express this (the same reason
 * the docked third pane keeps its `LocalWindowInfo` read).
 */
private val LargeBarMinPaneHeight = 600.dp

/**
 * The shared **root-screen** app bar: a [LargeFlexibleTopAppBar] that compresses to the small bar
 * as the content scrolls and stays small until the content is back at the top (M3's own rule for
 * the flexible bars), or a plain small pinned [TopAppBar] on a pane too short for it.
 *
 * `internal` only because both callers (Stats, Settings) are in this module — widen it to public,
 * like its neighbours in this package, whenever something outside needs it.
 *
 * Call it as the FIRST child of the screen's own `Column`, and hang the returned behaviour's
 * connection on the scroller inside the `Box(weight(1f))` below it:
 *
 * ```kotlin
 * Column(Modifier.fillMaxSize().horizontalSystemBarsPadding()) {
 *     val scrollBehavior = RootTopBar(title = …, barState = barState, isContentAtTop = { … })
 *     Box(Modifier.fillMaxWidth().weight(1f)) {
 *         LazyColumn(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)) { … }
 *     }
 * }
 * ```
 *
 * `Column { bar; … }` is valid rather than an overlay because `TopAppBarLayout` reports
 * `layout(maxWidth, (maxLayoutHeight + heightOffset).coerceAtLeast(0))` — a collapsing bar's
 * MEASURED height shrinks, it does not merely translate its content, so the content below moves up
 * with no dead gap. Nothing below it needs a top inset: the bar owns that strip via
 * [appBarWindowInsets] (and never the M3 default, which would apply the horizontal sides a second
 * time on top of the screen's one `horizontalSystemBarsPadding()`).
 *
 * @param title the bar's title. One line, ellipsized.
 * @param subtitle optional second line. **If the text can only arrive later (a count landing from
 *   the network), pass a non-null — possibly empty — string from the first frame**: the expanded
 *   height is pinned from this parameter's NULLNESS (152dp with a subtitle, 120dp without), so a
 *   slot that appears later would resize the bar under the user.
 * @param navigationIcon start slot — typically a back [androidx.compose.material3.IconButton].
 * @param actions end slot.
 * @param containerColor the colour of **whatever is behind the bar**, used for both the resting and
 *   the scrolled container. Never leave M3's `scrolledContainerColor` default (`surfaceContainer`):
 *   the AMOLED overlay flattens only `background` / `surface` / `surfaceVariant`, so the default
 *   lights the bar up as a grey band on a pure-black theme the moment the content moves.
 * @param barState the bar's hoisted [TopAppBarState] — hoist it at screen level with
 *   `rememberTopAppBarState()` so the collapse survives navigating away and back. Used by the large
 *   branch only; see [isContentAtTop].
 * @param isContentAtTop "the scroller is at offset 0", read when the large branch (re)enters
 *   composition to clear a stale collapse. `ExitUntilCollapsedScrollBehavior` re-expands ONLY from
 *   the positive leftover of a downward scroll, which content already at 0 never produces — so a
 *   collapsed bar over content at the top is a state it can neither produce nor escape, and the big
 *   title would be stranded with no gesture left to bring it back. It can be ARRIVED at because
 *   `rememberTopAppBarState` is `rememberSaveable` while the small branch keeps its own state:
 *   collapse in portrait, rotate to the short pane, rotate back. Defaults to `{ false }` (never
 *   reset).
 * @return the behaviour whose `nestedScrollConnection` the caller must put on its scroller.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RootTopBar(
    title          : String,
    modifier       : Modifier = Modifier,
    subtitle       : String? = null,
    navigationIcon : @Composable () -> Unit = {},
    actions        : @Composable RowScope.() -> Unit = {},
    containerColor : Color = MaterialTheme.colorScheme.background,
    barState       : TopAppBarState = rememberTopAppBarState(),
    isContentAtTop : () -> Boolean = { false },
): TopAppBarScrollBehavior {
    val paneHeightDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val useLargeBar  = paneHeightDp >= LargeBarMinPaneHeight

    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor         = containerColor,
        scrolledContainerColor = containerColor,
    )
    val titleSlot: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val subtitleSlot: (@Composable () -> Unit)? = subtitle?.let { text ->
        { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }

    // Separate `if` branches on purpose — they are separate composition groups, so each keeps its
    // own state. The small branch must NOT inherit the hoisted [barState]: `PinnedScrollBehavior`
    // never writes `heightOffset` while `SingleRowTopAppBar` still passes
    // `scrolledOffset = { heightOffset }`, so a collapsed value carried across a portrait → folded
    // landscape rotation would draw the small bar shifted up and clipped (`adjustHeightOffsetLimit`
    // fixes only the LIMIT, not the offset).
    return if (useLargeBar) {
        // ENTRY RESET — see [isContentAtTop]. `remember`, not a `LaunchedEffect`: it runs once per
        // branch entry and BEFORE the bar composes, so there is no frame of a shifted bar (the same
        // "assign during composition" idiom as the Library's `PaneStateHolder`), and it is keyed on
        // [barState] so a newly hoisted instance re-arms it. It therefore also runs on every
        // ordinary re-entry of the screen, which is SAFE ONLY because the predicate is exactly the
        // impossible-state test — with the content restored part-way down it is false and the
        // restored collapse is untouched.
        remember(barState) {
            if (isContentAtTop()) {
                barState.heightOffset  = 0f
                barState.contentOffset = 0f
            }
        }
        val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = barState)
        LargeFlexibleTopAppBar(
            title          = titleSlot,
            modifier       = modifier,
            subtitle       = subtitleSlot,
            navigationIcon = navigationIcon,
            actions        = actions,
            // Pinned explicitly rather than left to the subtitle-dependent default, so the two
            // heights are stated where the nullness rule above is stated.
            expandedHeight = if (subtitleSlot != null)
                TopAppBarDefaults.LargeFlexibleAppBarWithSubtitleExpandedHeight
            else
                TopAppBarDefaults.LargeFlexibleAppBarWithoutSubtitleExpandedHeight,
            windowInsets   = appBarWindowInsets,
            colors         = barColors,
            scrollBehavior = behavior,
        )
        behavior
    } else {
        val behavior = TopAppBarDefaults.pinnedScrollBehavior(state = rememberTopAppBarState())
        // The small bar has no nullable-subtitle overload — the subtitle one takes a non-null slot,
        // so the two cases are two calls. [subtitle] is a screen-level decision, so this `if` never
        // flips under the user.
        if (subtitleSlot != null) {
            TopAppBar(
                title          = titleSlot,
                subtitle       = subtitleSlot,
                modifier       = modifier,
                navigationIcon = navigationIcon,
                actions        = actions,
                windowInsets   = appBarWindowInsets,
                colors         = barColors,
                scrollBehavior = behavior,
            )
        } else {
            TopAppBar(
                title          = titleSlot,
                modifier       = modifier,
                navigationIcon = navigationIcon,
                actions        = actions,
                windowInsets   = appBarWindowInsets,
                colors         = barColors,
                scrollBehavior = behavior,
            )
        }
        behavior
    }
}
