package com.crsmthw.lyra.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import com.crsmthw.lyra.util.confirm
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel

// Wraps a screen's content with a floating mini player and (on wide screens) a pop-out
// player panel. The `onRequestPlayer` lambda passed to `content` opens the panel on wide
// screens and calls `onOpenPlayer` on narrow screens, so track-tap handlers don't need to
// know which mode they're in.
//
// On EXTRA-WIDE screens (≥1200dp, e.g. a tablet in landscape) the player lives in a permanent
// docked third pane hosted by `LyraNavGraph` — OUTSIDE the per-screen nav transition, so it
// doesn't slide/fade when navigating between browse screens. Here we just render the screen
// content and suppress the mini player + pop-out so nothing competes with that docked pane.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerPanelHost(
    playerViewModel         : PlayerViewModel,
    onOpenPlayer            : () -> Unit,
    modifier                : Modifier = Modifier,
    onOpenQueue             : () -> Unit = {},
    navSharedTransitionScope: SharedTransitionScope? = null,
    navAnimatedContentScope : AnimatedContentScope? = null,
    content                 : @Composable BoxScope.(onRequestPlayer: () -> Unit) -> Unit,
) {
    val density        = LocalDensity.current
    val haptics        = LocalHapticFeedback.current
    val containerSize  = LocalWindowInfo.current.containerSize
    val screenWidthDp  = with(density) { containerSize.width.toDp() }
    val screenHeightDp = with(density) { containerSize.height.toDp() }
    val isWideScreen   = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)
    val isShortScreen  = screenHeightDp < 500.dp
    val canShowPanel   = isWideScreen && !isShortScreen
    // Mirror of LyraNavGraph's docked-pane gate: when the docked third pane is up, drop the mini
    // player + pop-out entirely. Measured width (not isWidthAtLeastBreakpoint(1200), whose default
    // V1 width buckets cap at 840dp). `containerSize` is the WINDOW, so this still reads 1280dp even
    // though this host is laid out into the narrower left region.
    val isExtraWide    = screenWidthDp >= 1200.dp && screenHeightDp >= 600.dp

    if (isExtraWide) {
        Box(modifier = modifier.fillMaxSize()) { content {} }   // player is the docked pane
        return
    }

    val maxPanelHeight = screenHeightDp * 0.8f

    var showPlayerPanel by rememberSaveable { mutableStateOf(false) }

    // Single source of truth for the pop-out panel's presence on screen. Drives both the panel's
    // own AnimatedVisibility (so the gate below reads the SAME animation that's rendering) and the
    // mini player's nav-scope gate. Using this Transition avoids the scrim-tween-vs-panel-spring
    // desync that a separate timer would have.
    val panelTransition = updateTransition(showPlayerPanel, label = "panelPresence")

    // Dismiss panel when folding — canShowPanel goes false on narrow screens
    LaunchedEffect(canShowPanel) {
        if (!canShowPanel) showPlayerPanel = false
    }

    val scrimAlpha by animateFloatAsState(
        targetValue   = if (showPlayerPanel) 0.45f else 0f,
        animationSpec = tween(300),
        label         = "panelHostScrim",
    )

    BackHandler(enabled = showPlayerPanel) { showPlayerPanel = false }

    val onRequestPlayer: () -> Unit = { if (canShowPanel) showPlayerPanel = true else onOpenPlayer() }

    SharedTransitionLayout {
        Box(modifier = modifier.fillMaxSize()) {
            content(onRequestPlayer)

            // Scrim behind the panel
            if (scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrimAlpha))
                        .clickable(enabled = showPlayerPanel) { haptics.confirm(); showPlayerPanel = false }
                )
            }

            // Mini player placement: right 58% pane on any wide screen (isWideScreen, regardless of
            // isShortScreen), full-width on narrow. This matches the two-pane layout used by Album/Artist
            // screens whenever screenWidthDp >= 600.
            //
            // The mini player's SECONDARY nav scope (wide screens only) is held CONTINUOUSLY while the
            // pop-out panel is fully closed, and dropped only while it is open/animating. Continuous-
            // when-closed is required for the morph to work on POP: a shared element added after a pop
            // transition has already begun is too late to be captured as the EXIT participant (that is
            // why an isRunning gate morphed on push but not back). Dropping it while the panel is present
            // is the actual conflict window — it stops the mini and the panel from both claiming
            // "album-art" in the nav scope and fighting the local mini↔panel morph.
            // On NARROW screens the mini's PRIMARY scope (below) is ALREADY the nav scope, so this stays
            // false to avoid a double-registration that breaks the morph asymmetrically.
            val panelPresent = panelTransition.currentState || panelTransition.targetState ||
                               panelTransition.isRunning
            val miniNeedsNavScope = canShowPanel && !panelPresent
            val miniNavScope: SharedTransitionScope? = if (miniNeedsNavScope) navSharedTransitionScope else null
            val miniNavVisScope: AnimatedVisibilityScope? = if (miniNeedsNavScope) navAnimatedContentScope as? AnimatedVisibilityScope else null
            MiniPlayerHolder(
                playerViewModel            = playerViewModel,
                onExpand                   = onRequestPlayer,
                visible                    = !showPlayerPanel,
                modifier                   = if (isWideScreen)
                    Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.58f).navigationBarsPadding()
                else
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                sharedTransitionScope      = if (canShowPanel) this@SharedTransitionLayout else navSharedTransitionScope,
                animatedVisibilityScope    = if (canShowPanel) null else navAnimatedContentScope as? AnimatedVisibilityScope,
                navSharedTransitionScope   = miniNavScope,
                navAnimatedVisibilityScope = miniNavVisScope,
            )

            // Pop-out panel (wide non-short screens only)
            if (canShowPanel) {
                PlayerPopOutPanel(
                    panelTransition            = panelTransition,
                    playerViewModel            = playerViewModel,
                    onClose                    = { showPlayerPanel = false },
                    onFullScreen               = onOpenPlayer,
                    onOpenQueue                = onOpenQueue,
                    localSharedTransitionScope = this@SharedTransitionLayout,
                    navSharedTransitionScope   = navSharedTransitionScope,
                    navAnimatedContentScope    = navAnimatedContentScope,
                    modifier                   = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 8.dp, end = 16.dp, bottom = 16.dp)
                        .fillMaxWidth(0.54f)
                        .heightIn(max = maxPanelHeight)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}
