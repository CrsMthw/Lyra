package com.crsmthw.lyra.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerPopOutPanel(
    showPanel                  : Boolean,
    playerViewModel            : PlayerViewModel,
    onClose                    : () -> Unit,
    onFullScreen               : () -> Unit,
    localSharedTransitionScope : SharedTransitionScope,
    modifier                   : Modifier = Modifier,
    navSharedTransitionScope   : SharedTransitionScope? = null,
    navAnimatedContentScope    : AnimatedContentScope? = null,
) {
    val density  = LocalDensity.current
    val navBarPx = WindowInsets.navigationBars.getBottom(density)

    AnimatedVisibility(
        visible  = showPanel,
        enter    = slideInVertically(tween(350, easing = FastOutSlowInEasing)) { it + navBarPx + with(density) { 16.dp.roundToPx() } },
        exit     = slideOutVertically(tween(350)) { it + navBarPx + with(density) { 16.dp.roundToPx() } },
        modifier = modifier,
    ) {
        val panelScope: AnimatedVisibilityScope = this
        Card(
            shape     = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 24.dp),
        ) {
            PlayerCardContent(
                playerViewModel          = playerViewModel,
                onClose                  = onClose,
                onFullScreen             = onFullScreen,
                sharedTransitionScope    = localSharedTransitionScope,
                animatedVisibilityScope  = panelScope,
                navSharedTransitionScope = navSharedTransitionScope,
                navAnimatedContentScope  = navAnimatedContentScope,
            )
        }
    }
}
