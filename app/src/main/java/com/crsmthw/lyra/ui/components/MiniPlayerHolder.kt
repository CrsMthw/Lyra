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
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    var rawAccentColor        by remember { mutableStateOf<Color?>(null) }
    var rawSurfaceAccentColor by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(playerState.currentTrack?.artUrl, isDarkTheme) {
        val url = playerState.currentTrack?.artUrl.takeIf { !it.isNullOrBlank() }
            ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.IO) {
            try {
                val loader  = SingletonImageLoader.get(context)
                val result  = loader.execute(ImageRequest.Builder(context).data(url).build())
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    Palette.from(bitmap).generate()
                } else null
            } catch (_: Exception) { null }
        }
        if (palette != null) {
            val fallback = palette.getDominantColor(0xFF1DB954.toInt())
            rawAccentColor = Color(palette.getVibrantColor(fallback))
            rawSurfaceAccentColor = if (isDarkTheme) {
                Color(palette.getLightVibrantColor(palette.getVibrantColor(palette.getLightMutedColor(fallback))))
            } else {
                Color(palette.getDarkVibrantColor(palette.getVibrantColor(palette.getDarkMutedColor(fallback))))
            }
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val accentColor by animateColorAsState(
        targetValue   = rawAccentColor ?: primary,
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
