package com.crsmthw.lyra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Transition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.screenTransitionSpec

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerPopOutPanel(
    panelTransition            : Transition<Boolean>,
    playerViewModel            : PlayerViewModel,
    onClose                    : () -> Unit,
    onFullScreen               : () -> Unit,
    localSharedTransitionScope : SharedTransitionScope,
    modifier                   : Modifier = Modifier,
    onOpenQueue                : () -> Unit = {},
    navSharedTransitionScope   : SharedTransitionScope? = null,
) {
    val density  = LocalDensity.current
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    val panelSlideSpec = screenTransitionSpec<IntOffset>()

    // Driven by PlayerPanelHost's `panelTransition` so the host's nav-scope gate reads the exact
    // same animation that's on screen (no separate timer to desync from it). Since 2026-09-14 that
    // is a CHILD of the host's seekable `PlayerSurface` transition, so this enter/exit — and with
    // it the `"album-art"` bounds animation that hangs off the scope below — follows a
    // predictive-back gesture: the panel comes in with the finger from under the full player, and
    // goes back out to the mini player with it.
    panelTransition.AnimatedVisibility(
        visible  = { it },
        enter    = slideInVertically(panelSlideSpec)  { it + navBarPx + with(density) { 16.dp.roundToPx() } },
        exit     = slideOutVertically(panelSlideSpec) { it + navBarPx + with(density) { 16.dp.roundToPx() } },
        modifier = modifier,
    ) {
        // Compose nothing at all unless the panel is genuinely part of the current or the target
        // state. `AnimatedVisibility` ALSO composes its content while its transition reports
        // `hasInitialValueAnimations`, and since the panel and the mini player became two children
        // of ONE transition (see `PlayerSurface` in PlayerPanelHost) an INTERRUPTED change on the
        // other half sets that flag here too — `moveAnimationToInitialState()` hands the
        // interrupted animation to `Transition.setInitialAnimations`, which recurses into every
        // child transition. The panel would then compose in `PreEnter` and register a second,
        // non-target `"album-art"` participant in both scopes, which is the exact ambiguity that
        // broke the mini → panel morph in the first place.
        if (!panelTransition.currentState && !panelTransition.targetState) return@AnimatedVisibility

        // This AnimatedVisibility scope is the panel's enter/exit for BOTH shared-element layers:
        // the local mini↔panel morph and — now that the host lives outside the NavHost and the
        // panel is hidden (not closed) on the Player route — the nav-level panel↔PlayerScreen morph.
        val panelScope: AnimatedVisibilityScope = this
        Card(
            shape     = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 24.dp),
        ) {
            PlayerCardContent(
                playerViewModel          = playerViewModel,
                onClose                  = onClose,
                onFullScreen             = onFullScreen,
                onOpenQueue              = onOpenQueue,
                sharedTransitionScope    = localSharedTransitionScope,
                animatedVisibilityScope  = panelScope,
                navSharedTransitionScope = navSharedTransitionScope,
            )
        }
    }
}
