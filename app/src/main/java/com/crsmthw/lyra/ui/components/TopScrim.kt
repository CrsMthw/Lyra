package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * [TopScrim] — all that is left of the file that used to hold Lyra's OneUI-style floating top
 * controls (`FloatingTopControls.kt`: a `TopActionPill` actions stadium, a `TitlePill` that faded
 * in as a 300dp `HeroBandHeight` band scrolled away, and the `rememberHeroScrollProgress` helper
 * that drove it). Every screen now wears a real M3 app bar instead — [RootTopBar] on the root
 * screens (Library, Stats, Settings, Queue) and [DetailTopBar] on the detail screens — so those
 * pieces had no callers left and are gone, along with the hero bands they titled.
 *
 * The scrim outlived them because it answers a different question: what to do where content
 * scrolls **under the status icons with no bar over it**. That is still the case on the two-pane
 * RIGHT panes (Album tracks, Artist discography, Show episodes — the bar belongs to the LEFT pane,
 * and the right pane takes a top `contentPadding` inset plus this scrim), and Search builds its own
 * three-stop variant of the same brush behind its floating input field.
 */

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
