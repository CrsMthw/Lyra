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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Pane height at or above which a root screen gets the **large flexible** app bar. Below it — the
 * folded outer screen in landscape, a ≈380dp-tall pane — a 152/120dp expanded bar would eat a third
 * of the pane before a single row (and over HALF of it on the Library, which adds a 48dp tab row
 * under the bar), so that case gets the small pinned bar. 600dp is the M3 medium-height boundary
 * and clears portrait on every screen as well as an unfolded / tablet landscape pane (≈800dp+).
 *
 * MEASURED height, not a window-size-class breakpoint: the height buckets are `[0, 480, 900]` and
 * have no 600dp boundary, so `isHeightAtLeastBreakpoint(600)` cannot express this (the same reason
 * the docked third pane keeps its `LocalWindowInfo` read).
 *
 * `internal` because `LibraryBrowserPane` reads it too: that pane still hand-rolls its own
 * large/small bar pair, since [RootTopBar] has no slot for the pinned `PrimaryTabRow` the Library
 * puts between the bar and the content. The gate itself must not fork, so there is one constant.
 */
internal val LargeBarMinPaneHeight = 600.dp

/**
 * The shared **root-screen** app bar: a [LargeFlexibleTopAppBar] that compresses to the small bar
 * as the content scrolls and stays small until the content is back at the top (M3's own rule for
 * the flexible bars), or a plain small pinned [TopAppBar] on a pane too short for it.
 *
 * `internal` only because all three callers (Stats, Settings, Queue) are in this module — widen it
 * to public, like its neighbours in this package, whenever something outside needs it.
 *
 * **Not** used by the Library browser pane, which keeps its own copy of the large/small pair: it
 * pins a `PrimaryTabRow` between the bar and the content and this component has no slot for one.
 * The two share the [LargeBarMinPaneHeight] gate so at least that cannot drift; adopting this
 * composable there is a pure de-duplication waiting on such a slot.
 *
 * Call it as the FIRST child of the screen's own `Column`, and hang the returned behaviour's
 * connection on the scroller inside the `Box(weight(1f))` below it:
 *
 * ```kotlin
 * Column(Modifier.fillMaxSize().horizontalSystemBarsPadding()) {
 *     val scrollBehavior = RootTopBar(title = …, barState = barState)
 *     Box(Modifier.fillMaxWidth().weight(1f)) {
 *         LazyColumn(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)) { … }
 *     }
 * }
 * ```
 *
 * `Column { bar; … }` is valid rather than an overlay because `TopAppBarLayout` reports
 * `layout(maxWidth, (maxLayoutHeight + heightOffset).coerceAtLeast(0))` — a collapsing bar's
 * MEASURED height shrinks, it does not merely translate its content, so the content below moves up
 * with no dead gap. Nothing below it needs a top INSET: the bar owns that strip via
 * [appBarWindowInsets] (and never the M3 default, which would apply the horizontal sides a second
 * time on top of the screen's one `horizontalSystemBarsPadding()`).
 *
 * ### The gap and the fade under the bar
 *
 * Every caller gives its scroller [BarContentGap] of TOP `contentPadding` (a `verticalScroll`
 * `Column` takes it as a `padding` applied AFTER the scroller, which is the same thing) and composes
 * a [TopBarFade] as a late child of the weighted `Box`, so the first rows dissolve into the bar
 * instead of sliding past its title. Neither is an inset: the gap scrolls away with the content, and
 * because that `Box`'s own top edge already tracks this bar's collapse, the strip needs no offset —
 * a detail screen's OVERLAY bar is the case that does ([DetailTopBarFade]).
 *
 * ### Nothing resets the large bar's collapse
 *
 * The bar is where the user's last drag left it — across a range/filter change, a navigation round
 * trip and a re-entry of the screen (Cris's verdict, 2026-09-14: "the bar should stay collapsed or
 * stay expanded, until user scrolls the list"). A collapsed bar is never a trap, because a
 * scroller that can consume NOTHING still dispatches its whole drag through nested scroll
 * (`ScrollingLogic.performScroll` always calls `dispatchPreScroll`/`dispatchPostScroll`, and
 * `CanDragCalculation` only excludes a mouse) and `ExitUntilCollapsedScrollBehavior.onPostScroll`
 * re-expands from `available.y > 0` — so one downward drag brings the big title back even over an
 * empty page. Callers must therefore NOT reset the bar from a control that swaps their content.
 *
 * The one state no large bar can produce is an offset inherited from the SMALL branch's pane, so
 * that is cleared where it is created: the small branch zeroes [barState] as it enters, since a
 * pinned bar cannot own a collapse in the first place. **Accepted cost:** a collapse earned in
 * portrait does not survive a round trip through the folded outer screen in landscape — the bar
 * comes back expanded, and one upward drag re-collapses it.
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
 *   `rememberTopAppBarState()` so the collapse survives navigating away and back. The LARGE branch
 *   renders from it; the small branch only CLEARS it (see below).
 * @return the behaviour whose `nestedScrollConnection` the caller must put on its scroller.
 */
/** The 600dp height gate, read from the WINDOW (see the KDoc above) — one reading for the bar and its behaviour. */
@Composable
private fun rootBarIsLarge(): Boolean {
    val paneHeightDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    return paneHeightDp >= LargeBarMinPaneHeight
}

/**
 * The scroll behaviour a root screen hangs on its own scroller (`Modifier.nestedScroll(
 * behavior.nestedScrollConnection)`), decided by the same height gate [RootTopBar] uses: an
 * `exitUntilCollapsed` behaviour over the hoisted [barState] for the large bar, a `pinned` one over
 * a throwaway state for the small bar. Split out of [RootTopBar] on 2026-09-22 (lint
 * `ComposableNaming`): a composable that EMITS UI must not also RETURN a value. Call this first,
 * then pass its result to [RootTopBar] and to the content's `nestedScroll`.
 *
 * Separate `if` branches on purpose — they are separate composition groups, so each keeps its own
 * state. The small branch must NOT inherit the hoisted [barState]: `PinnedScrollBehavior` never
 * writes `heightOffset` while `SingleRowTopAppBar` still passes `scrolledOffset = { heightOffset }`,
 * so a collapsed value carried across a portrait → folded landscape rotation would draw the small
 * bar shifted up and clipped (`adjustHeightOffsetLimit` fixes only the LIMIT, not the offset).
 *
 * The small branch is also the ONE bar reset that survives (CLAUDE.md → "a content switch never
 * resets an app bar"): it clears the hoisted state so that state only ever describes the LARGE bar
 * — without it a collapse earned in portrait would still be sitting there when a rotation back
 * re-entered the large branch, over content that may have scrolled to the top meanwhile. A pinned
 * bar cannot own a collapse, which is why this is the one place a reset belongs. It runs from a
 * `SideEffect`: same frame, after this composition is applied and before measure, so there is no
 * frame of a shifted bar; and nothing in this branch READS the hoisted state, so the write cannot
 * invalidate the composition that performs it. (Until 2026-09-22 it was a `remember(barState) { … }`
 * write-during-composition, retired by lint `RememberReturnType`.) The large branch has NO entry
 * reset: the collapse the user last dragged is simply rendered — an entry reset there would fire
 * on every ordinary re-entry of the screen and undo it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberRootTopBarScrollBehavior(
    barState : TopAppBarState = rememberTopAppBarState(),
): TopAppBarScrollBehavior =
    if (rootBarIsLarge()) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = barState)
    } else {
        SideEffect {
            barState.heightOffset  = 0f
            barState.contentOffset = 0f
        }
        TopAppBarDefaults.pinnedScrollBehavior(state = rememberTopAppBarState())
    }

/**
 * The bar itself. Takes the behaviour [rememberRootTopBarScrollBehavior] returned — the two read
 * the same height gate, so they always agree on large vs small.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RootTopBar(
    title          : String,
    scrollBehavior : TopAppBarScrollBehavior,
    modifier       : Modifier = Modifier,
    subtitle       : String? = null,
    navigationIcon : @Composable () -> Unit = {},
    actions        : @Composable RowScope.() -> Unit = {},
    containerColor : Color = MaterialTheme.colorScheme.background,
) {
    val useLargeBar  = rootBarIsLarge()

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

    // Two branches, two composition groups — see `rememberRootTopBarScrollBehavior` for why.
    if (useLargeBar) {
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
            scrollBehavior = scrollBehavior,
        )
    } else {
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
                scrollBehavior = scrollBehavior,
            )
        } else {
            TopAppBar(
                title          = titleSlot,
                modifier       = modifier,
                navigationIcon = navigationIcon,
                actions        = actions,
                windowInsets   = appBarWindowInsets,
                colors         = barColors,
                scrollBehavior = scrollBehavior,
            )
        }
    }
}
