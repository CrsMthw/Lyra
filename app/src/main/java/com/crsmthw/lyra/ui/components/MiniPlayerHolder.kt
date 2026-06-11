package com.crsmthw.lyra.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.loadAlbumArtColors

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayerHolder(
    playerViewModel            : PlayerViewModel,
    onExpand                   : () -> Unit,
    modifier                   : Modifier = Modifier,
    visible                    : Boolean = true,
    sharedTransitionScope      : SharedTransitionScope? = null,
    animatedVisibilityScope    : AnimatedVisibilityScope? = null,
    navSharedTransitionScope   : SharedTransitionScope? = null,
    navAnimatedVisibilityScope : AnimatedVisibilityScope? = null,
) {
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isDarkTheme = MaterialTheme.colorScheme.background.let {
        0.299f * it.red + 0.587f * it.green + 0.114f * it.blue < 0.5f
    }

    // The mini-player's bar wash + shadow use the edge colour (so the bar "merges" with the art the
    // way the full player background does); the progress ring uses the contrast-safe surface accent.
    // See util/AlbumArtColor.kt.
    var rawEdgeColor          by remember { mutableStateOf<Color?>(null) }
    var rawSurfaceAccentColor by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(playerState.currentTrack?.artUrl, isDarkTheme) {
        val colors = loadAlbumArtColors(context, playerState.currentTrack?.artUrl, isDarkTheme)
            ?: return@LaunchedEffect
        rawEdgeColor          = Color(colors.edge)
        rawSurfaceAccentColor = Color(colors.surfaceAccent)
    }

    val primary = MaterialTheme.colorScheme.primary
    val accentColor by animateColorAsState(
        targetValue   = rawEdgeColor ?: primary,
        animationSpec = tween(800),
        label         = "miniPlayerAccent",
    )
    val surfaceAccentColor by animateColorAsState(
        targetValue   = rawSurfaceAccentColor ?: primary,
        animationSpec = tween(800),
        label         = "miniPlayerSurfaceAccent",
    )

    MiniPlayer(
        currentTrack               = playerState.currentTrack,
        isPlaying                  = playerState.isPlaying,
        isWakingUp                 = playerState.isWakingUp,
        progress                   = playerState.progress,
        accentColor                = accentColor,
        surfaceAccentColor         = surfaceAccentColor,
        visible                    = visible,
        onPlayPause                = playerViewModel::playPause,
        onSkipNext                 = playerViewModel::skipNext,
        onExpand                   = onExpand,
        modifier                   = modifier,
        sharedTransitionScope      = sharedTransitionScope,
        animatedVisibilityScope    = animatedVisibilityScope,
        navSharedTransitionScope   = navSharedTransitionScope,
        navAnimatedVisibilityScope = navAnimatedVisibilityScope,
    )
}
