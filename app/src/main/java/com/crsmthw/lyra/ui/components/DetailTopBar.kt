package com.crsmthw.lyra.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * The ONE top app bar every Lyra **detail** surface uses — album, artist, show, and the Library
 * playlist / Liked Songs pane, in single-pane, in a two-pane card, and in their loading / error
 * states. Lay it over the screen's own scrolling content (`align(TopCenter)`, composed LAST so it
 * draws and hit-tests above the rows); it is never a `Scaffold` `topBar` (it must overlap the hero,
 * and in two-pane it belongs to one pane only).
 *
 * ### It is SOLID, and it is the pane's own colour
 *
 * [paneColor] goes into **both** colour slots — `background` single-pane, the Card's `surface` in a
 * two-pane card — so the bar simply *is* the pane: no band at rest, none scrolled, and none on
 * AMOLED (`Theme.kt` forces `background`/`surface` to black; `surfaceContainer`, M3's default
 * `scrolledContainerColor`, is NOT flattened — never use it here).
 *
 * The app-bars trial (2026-09-14) passed `Color.Transparent → background` instead, and Cris
 * rejected that on device: **`Color.Transparent` is `0x00000000`, i.e. BLACK at zero alpha**, and
 * `SingleRowTopAppBar` thresholds its scrolled fraction to a binary 0/1 and hands it to an
 * `animateColorAsState` that lerps in RGBA — so for the length of that spec the container passed
 * through *translucent black*, a grey wash over the page in BOTH directions ("when I start to
 * scroll, the bar flashes grey"). At rest the transparent bar was indistinguishable from a solid
 * one anyway, because behind it there is only page background.
 *
 * ### No `scrollBehavior`, deliberately
 *
 * With both colour slots equal there is nothing for a behaviour to drive, and `AppBar.kt`
 * (1.5.0-alpha27) shows a null behaviour is the cheapest of the options: `overlappedFraction` is
 * never consulted, `scrolledOffset` resolves to `0f`, `adjustHeightOffsetLimit` is a no-op, and no
 * `appBarDragModifier` is installed. The one thing we *do* want is unconditional — the root `Box`
 * ends in `.pointerInput(Unit) {}`, so the bar consumes touches across its full bounds (and so
 * pull-to-refresh and list drags must start below it) whether or not a behaviour is passed. Callers
 * therefore need no `Modifier.nestedScroll` for this bar's sake.
 *
 * ### Hero clearance
 *
 * [DetailArtHero] bakes `statusBarsPadding() + TopAppBarDefaults.TopAppBarExpandedHeight + 8.dp`
 * onto its art tile, i.e. the bar's own collapsed height plus a small gap. Add **nothing** on top of
 * that — no list `contentPadding` top inset (it doubles) and no fork of the hero.
 *
 * @param paneColor the colour of whatever this bar sits on: the screen background, or the Card's
 *   colour in a two-pane card.
 * @param navigationIcon usually the back `IconButton` (debounced `onBack` + a `confirm()` haptic).
 * @param actions the screen's actions, as a `TopAppBar` `actions` row.
 * @param heroTitle the hero-title hand-off ([rememberHeroTitleHandoff], also handed to
 *   [DetailArtHero]). Non-null fades [title] in as the hero title slides under this bar; null keeps
 *   [title] permanently visible (the Library's contextual selection bar, and two-pane hero panes
 *   that pass no title at all).
 * @param title the bar title. It receives the alpha [Modifier] the hand-off drives — put it on the
 *   `Text`; ignore it for an always-visible title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(
    paneColor      : Color,
    modifier       : Modifier = Modifier,
    navigationIcon : @Composable () -> Unit = {},
    actions        : @Composable RowScope.() -> Unit = {},
    heroTitle      : HeroTitleHandoff? = null,
    title          : @Composable (Modifier) -> Unit = {},
) {
    // The bar's own bottom edge in root coordinates — the line the hero title disappears behind.
    // Measured rather than computed as "status bar + 64dp" so the hand-off is exact wherever the
    // bar is nested (a two-pane Card starts 8dp down inside the Row's padding).
    var barBottomPx by remember { mutableFloatStateOf(Float.NaN) }

    val titleModifier = if (heroTitle == null) Modifier else
        // Draw-phase only: the fraction is recomputed when the layer updates, so a scroll costs no
        // recomposition, and it seeks and reverses with the finger because it is pure geometry.
        Modifier.graphicsLayer { alpha = heroTitle.barTitleAlpha(barBottomPx) }

    TopAppBar(
        title          = { title(titleModifier) },
        navigationIcon = navigationIcon,
        actions        = actions,
        windowInsets   = appBarWindowInsets,
        colors         = TopAppBarDefaults.topAppBarColors(
            containerColor         = paneColor,
            scrolledContainerColor = paneColor,
        ),
        modifier       = if (heroTitle == null) modifier else modifier.onGloballyPositioned {
            barBottomPx = it.positionInRoot().y + it.size.height
        },
    )
}

/**
 * Material 3's own collapsed-title easing, copied because it is `internal`:
 * `AppBar.kt` (1.5.0-alpha27) `internal val TopTitleAlphaEasing = CubicBezierEasing(.8f, 0f, .8f, .15f)`,
 * which `TwoRowsTopAppBar` applies to its collapse fraction as `topTitleAlpha`. It holds the title
 * near zero for most of the ramp and brings it in over the last stretch, so the small title appears
 * as the large one *finishes* leaving rather than crossfading with it throughout. Re-check it on a
 * Material3 bump.
 */
private val HeroTitleAlphaEasing = CubicBezierEasing(.8f, 0f, .8f, .15f)

/**
 * The hero-title → bar-title hand-off: M3's mechanism applied to a hero that is **list content**
 * (the detail screens' item 0) instead of a flexible bar's expanded row.
 *
 * The fraction is defined over the **hero title's own exit** — 0 while its top edge is still below
 * [DetailTopBar]'s bottom edge, 1 once its bottom edge has passed under it — then shaped by
 * [HeroTitleAlphaEasing]. That is a ~35dp ramp (one or two lines of text), which is the point: the
 * trial drove the same crossfade off `rememberHeroScrollProgress`, i.e. the whole ~400dp hero, and
 * Cris rejected it on device as "way too slow".
 *
 * Both ends are geometry, reported from layout and read from the draw phase:
 *  - [DetailArtHero] reports its title's edges here from `onGloballyPositioned` (root coordinates),
 *    and calls [onHeroTitleGone] when the hero leaves composition — a lazy list disposes item 0
 *    once it is off screen, and a fling can dispose it before a final position lands, which would
 *    otherwise strand the bar title part-faded.
 *  - [DetailTopBar] reads [barTitleAlpha] inside a `graphicsLayer` block. **Only** from there: the
 *    values change every scrolled frame, so a composition-phase read would recompose the bar (and
 *    its slots) per frame.
 */
@Stable
class HeroTitleHandoff {
    private var heroTopPx    by mutableFloatStateOf(Float.NaN)
    private var heroBottomPx by mutableFloatStateOf(Float.NaN)
    private var heroGone     by mutableStateOf(false)

    /** Report the hero title's top / bottom edge in ROOT coordinates, in px. */
    fun onHeroTitleBounds(topPx: Float, bottomPx: Float) {
        heroTopPx    = topPx
        heroBottomPx = bottomPx
        heroGone     = false
    }

    /** The hero title left composition — treat it as fully gone under the bar. */
    fun onHeroTitleGone() {
        heroGone = true
    }

    /**
     * The bar title's alpha, given the bar's own bottom edge in root px. Read from a draw-phase
     * lambda only (see the class KDoc). Returns 0 until both ends have reported.
     */
    fun barTitleAlpha(barBottomPx: Float): Float {
        if (heroGone) return 1f
        val top    = heroTopPx
        val bottom = heroBottomPx
        if (top.isNaN() || bottom.isNaN() || barBottomPx.isNaN()) return 0f
        val height = bottom - top
        val raw    = if (height <= 0f) {
            if (bottom <= barBottomPx) 1f else 0f
        } else {
            ((barBottomPx - top) / height).coerceIn(0f, 1f)
        }
        return HeroTitleAlphaEasing.transform(raw)
    }
}

/** Remembers the [HeroTitleHandoff] shared by a screen's [DetailArtHero] and its [DetailTopBar]. */
@Composable
fun rememberHeroTitleHandoff(): HeroTitleHandoff = remember { HeroTitleHandoff() }
