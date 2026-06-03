package com.crsmthw.lyra.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel

// Wraps a screen's content with a floating mini player and (on wide screens) a pop-out
// player panel. The `onRequestPlayer` lambda passed to `content` opens the panel on wide
// screens and calls `onOpenPlayer` on narrow screens, so track-tap handlers don't need to
// know which mode they're in.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerPanelHost(
    playerViewModel         : PlayerViewModel,
    onOpenPlayer            : () -> Unit,
    modifier                : Modifier = Modifier,
    navSharedTransitionScope: SharedTransitionScope? = null,
    navAnimatedContentScope : AnimatedContentScope? = null,
    content                 : @Composable BoxScope.(onRequestPlayer: () -> Unit) -> Unit,
) {
    val config        = LocalConfiguration.current
    val isWideScreen  = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)
    val isShortScreen = config.screenHeightDp < 500
    val canShowPanel  = isWideScreen && !isShortScreen

    var showPlayerPanel by rememberSaveable { mutableStateOf(false) }
    val density  = LocalDensity.current

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
                        .clickable(enabled = showPlayerPanel) { showPlayerPanel = false }
                )
            }

            // Mini player placement: right 58% pane on any wide screen (isWideScreen, regardless of
            // isShortScreen), full-width on narrow. This matches the two-pane layout used by Album/Artist
            // screens whenever screenWidthDp >= 600.
            //
            // Nav scope on the mini player is active ONLY while a nav transition is running
            // (PlayerScreen ↔ this screen). This avoids the conflict where both the mini player and
            // the panel simultaneously hold key "album-art" in the nav scope:
            // – Panel open/close (no nav transition): isRunning = false → mini player has local scope
            //   only → local mini↔panel shared element runs without interference.
            // – Nav transition active: isRunning = true → mini player gets nav scope → art morphs.
            // – Narrow / folded landscape (canShowPanel = false): nav scope always active (no panel).
            val navTransitionRunning = navAnimatedContentScope?.transition?.isRunning == true
            val miniNeedsNavScope = !canShowPanel || navTransitionRunning
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
                    showPanel                  = showPlayerPanel,
                    playerViewModel            = playerViewModel,
                    onClose                    = { showPlayerPanel = false },
                    onFullScreen               = onOpenPlayer,
                    localSharedTransitionScope = this@SharedTransitionLayout,
                    navSharedTransitionScope   = navSharedTransitionScope,
                    navAnimatedContentScope    = navAnimatedContentScope,
                    modifier                   = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 8.dp, end = 16.dp, bottom = 16.dp)
                        .fillMaxWidth(0.54f)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}
