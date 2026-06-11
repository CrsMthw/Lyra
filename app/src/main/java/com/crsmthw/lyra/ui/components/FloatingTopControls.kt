package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Floating top controls — the OneUI-8.5-style replacement for a solid top app bar: a subtle top
 * scrim, a floating action pill, and a hero→label title pill. The caller owns positioning so each
 * screen can place these over its own scrolling content; per Lyra's inset rules every top overlay
 * carries `statusBarsPadding()` at the call site.
 *
 * The title pill and the action pill share [TopPillHeight] so they read as one matched set when
 * both are on screen (a small title pill next to an over-sized action cluster looked unbalanced).
 */

/** Shared height for [TitlePill] and [TopActionPill] so the two floating pills visually match. */
val TopPillHeight = 52.dp

/** Uniform height of the scroll-away hero band, shared by every screen using these controls. */
val HeroBandHeight = 300.dp

/**
 * Progress (0→1) of the **hero** — the first list item — scrolling out of the top of the viewport.
 * Drive a [TitlePill]'s alpha with it (`graphicsLayer { alpha = progress.value }`, draw-phase only)
 * so the small title fades in exactly as the big hero leaves, with no jump at the handoff. Works
 * for any screen whose hero is `item` index 0 (a text band or an album-art header).
 */
@Composable
fun rememberHeroScrollProgress(listState: LazyListState): State<Float> =
    remember(listState) {
        derivedStateOf {
            val hero = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            when {
                hero == null   -> if (listState.firstVisibleItemIndex > 0) 1f else 0f
                hero.size <= 0 -> 0f
                else           -> ((-hero.offset).toFloat() / hero.size).coerceIn(0f, 1f)
            }
        }
    }

/**
 * [rememberHeroScrollProgress] variant for a `Column(Modifier.verticalScroll(...))` screen (no
 * `LazyListState`/layout-info, e.g. Settings). Progress is the scroll offset over the [heroHeight]
 * band's height.
 */
@Composable
fun rememberHeroScrollProgress(scrollState: ScrollState, heroHeight: Dp): State<Float> {
    val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
    return remember(scrollState, heroPx) {
        derivedStateOf { (scrollState.value / heroPx).coerceIn(0f, 1f) }
    }
}

/**
 * The vertical mirror of the bottom scrim used across Lyra's screens. The bottom scrim fades
 * content toward [color] at the nav bar (`listOf(Transparent, color)` — a fast fade); this fades
 * content toward [color] at the status bar (`listOf(color, Transparent)`) so content scrolling
 * under the status icons stays legible. Spans the status-bar height plus a short tail; place it
 * `align(Alignment.TopCenter)` over the content (no `statusBarsPadding()` — it covers the bar).
 */
@Composable
fun TopScrim(color: Color, modifier: Modifier = Modifier) {
    val statusBarDp = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarDp + 24.dp)
            .background(Brush.verticalGradient(listOf(color, Color.Transparent)))
    )
}

/**
 * Floating action cluster — a [TopPillHeight] stadium holding the icon-button actions, used in
 * place of a top-app-bar `actions` row. The caller positions it (`align(TopEnd) +
 * statusBarsPadding()`). Sized to match [TitlePill] rather than the taller M3
 * `HorizontalFloatingToolbar`, so the title and action pills read as an even pair.
 */
@Composable
fun TopActionPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier        = modifier.height(TopPillHeight),
        shape           = CircleShape,
        color           = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier          = Modifier.fillMaxHeight().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content           = content,
        )
    }
}

/**
 * Small title label that fades in once the large hero title has scrolled away (OneUI Weather's
 * hero → pill transition). The caller drives visibility with `Modifier.graphicsLayer { alpha = … }`
 * tied to scroll progress, so the fade is draw-phase only (no per-frame recomposition).
 */
@Composable
fun TitlePill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier        = modifier.height(TopPillHeight),
        shape           = CircleShape,
        color           = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Box(
            modifier         = Modifier.fillMaxHeight().padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text     = text,
                style    = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
