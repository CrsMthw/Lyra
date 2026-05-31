package com.crsmthw.lyra.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.screens.player.RepeatMode
import com.crsmthw.lyra.util.toTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerCardContent(
    playerViewModel         : PlayerViewModel,
    onClose                 : () -> Unit,
    onFullScreen            : () -> Unit,
    // Local scope — mini player ↔ panel expansion
    sharedTransitionScope   : SharedTransitionScope? = null,
    animatedVisibilityScope : AnimatedVisibilityScope? = null,
    // Nav scope — panel → full PlayerScreen
    navSharedTransitionScope  : SharedTransitionScope? = null,
    navAnimatedContentScope   : AnimatedContentScope? = null,
) {
    val state by playerViewModel.uiState.collectAsStateWithLifecycle()

    // ── Dynamic color extraction ───────────────────────────────────────────
    val context     = LocalContext.current
    val isDarkTheme = MaterialTheme.colorScheme.background.let {
        0.299f * it.red + 0.587f * it.green + 0.114f * it.blue < 0.5f
    }
    var rawDominantColor      by remember { mutableStateOf<Color?>(null) }
    var rawSurfaceAccentColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(state.currentTrack?.artUrl, isDarkTheme) {
        val url = state.currentTrack?.artUrl.takeIf { !it.isNullOrBlank() } ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.IO) {
            try {
                val loader  = SingletonImageLoader.get(context)
                val result  = loader.execute(ImageRequest.Builder(context).data(url).build())
                if (result is SuccessResult) {
                    val bmp = result.image.toBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    Palette.from(bmp).generate()
                } else null
            } catch (_: Exception) { null }
        }
        if (palette != null) {
            val fallback = palette.getDominantColor(0xFF1DB954.toInt())
            rawDominantColor = Color(palette.getVibrantColor(fallback))
            rawSurfaceAccentColor = if (isDarkTheme) {
                Color(palette.getLightVibrantColor(palette.getVibrantColor(palette.getLightMutedColor(fallback))))
            } else {
                Color(palette.getDarkVibrantColor(palette.getVibrantColor(palette.getDarkMutedColor(fallback))))
            }
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val accentColor by animateColorAsState(
        targetValue   = rawDominantColor ?: primary,
        animationSpec = tween(800), label = "cardAccent",
    )
    val surfaceAccentColor by animateColorAsState(
        targetValue   = rawSurfaceAccentColor ?: primary,
        animationSpec = tween(800), label = "cardSurfaceAccent",
    )
    val onAccentColor by animateColorAsState(
        targetValue = run {
            val c = rawDominantColor ?: primary
            if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue < 0.5f) Color.White else Color.Black
        },
        animationSpec = tween(800), label = "cardOnAccent",
    )
    val gradientTop by animateColorAsState(
        targetValue   = (rawDominantColor ?: MaterialTheme.colorScheme.surfaceContainer).copy(alpha = 0.7f),
        animationSpec = tween(800), label = "cardGradient",
    )
    val surfaceBg = MaterialTheme.colorScheme.surfaceContainerHigh

    // ── Art animation ──────────────────────────────────────────────────────
    val scope            = rememberCoroutineScope()
    val artOffsetX       = remember { Animatable(0f) }
    var displayedTrack   by remember { mutableStateOf(state.currentTrack) }
    var skipDirection    by remember { mutableIntStateOf(1) }
    var artDragX         by remember { mutableFloatStateOf(0f) }
    val density          = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 80.dp.toPx() } }

    LaunchedEffect(state.currentTrack?.id) {
        val incoming = state.currentTrack
        if (incoming?.id != displayedTrack?.id) {
            if (incoming != null && displayedTrack != null) {
                artOffsetX.snapTo(if (skipDirection >= 0) 1500f else -1500f)
            }
            displayedTrack = incoming
            artOffsetX.animateTo(0f, tween(350, easing = FastOutSlowInEasing))
        }
    }

    val onSkipNext: () -> Unit = {
        skipDirection = 1
        scope.launch { artOffsetX.animateTo(-1500f, tween(250, easing = FastOutLinearInEasing)) }
        playerViewModel.skipNext()
    }
    val onSkipPrev: () -> Unit = {
        if (state.progressMs > 3_000L) {
            playerViewModel.seekTo(0f)
        } else {
            skipDirection = -1
            scope.launch { artOffsetX.animateTo(1500f, tween(250, easing = FastOutLinearInEasing)) }
            playerViewModel.skipPrevious()
        }
    }

    // ── Play/pause button shape (M3 Expressive cookie) ───────────────────────
    val squigglyShape = MaterialShapes.Cookie12Sided.toShape()

    Box(modifier = Modifier.fillMaxWidth()) {
        // Gradient background
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(gradientTop, surfaceBg)))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, "Close", tint = onAccentColor)
                }
                Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall,
                    color = onAccentColor)
                IconButton(onClick = onFullScreen) {
                    Icon(Icons.Default.OpenInFull, "Full screen", tint = onAccentColor)
                }
            }

            // Album art — participates in two independent shared element transitions:
            // 1. Local scope: mini player ↔ panel expansion
            // 2. Nav scope: panel → full PlayerScreen navigation
            val localArtMod = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState      = rememberSharedContentState(key = "album-art"),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            } else Modifier
            val navArtMod = if (navSharedTransitionScope != null && navAnimatedContentScope != null) {
                with(navSharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState      = rememberSharedContentState(key = "album-art"),
                        animatedVisibilityScope = navAnimatedContentScope,
                    )
                }
            } else Modifier
            val artSharedMod = localArtMod.then(navArtMod)
            AsyncImage(
                model              = displayedTrack?.artUrl,
                contentDescription = "Album art",
                contentScale       = ContentScale.Crop,
                modifier           = artSharedMod
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .graphicsLayer { translationX = (artDragX * 0.3f).coerceIn(-80f, 80f) + artOffsetX.value }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart      = { artDragX = 0f },
                            onDragEnd        = {
                                when {
                                    artDragX < -swipeThresholdPx -> {
                                        val s = (artDragX * 0.3f).coerceIn(-80f, 80f); artDragX = 0f; skipDirection = 1
                                        scope.launch { artOffsetX.snapTo(s); artOffsetX.animateTo(-1500f, tween(250, easing = FastOutLinearInEasing)) }
                                        playerViewModel.skipNext()
                                    }
                                    artDragX > swipeThresholdPx -> {
                                        val s = (artDragX * 0.3f).coerceIn(-80f, 80f); artDragX = 0f; skipDirection = -1
                                        scope.launch { artOffsetX.snapTo(s); artOffsetX.animateTo(1500f, tween(250, easing = FastOutLinearInEasing)) }
                                        playerViewModel.skipPrevious()
                                    }
                                    else -> artDragX = 0f
                                }
                            },
                            onDragCancel     = { artDragX = 0f },
                            onHorizontalDrag = { _, amount -> artDragX += amount },
                        )
                    },
            )

            Spacer(Modifier.height(16.dp))

            // Track info + like
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.currentTrack?.name ?: "Nothing playing",
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Clip, modifier = Modifier.basicMarquee())
                    Text(state.currentTrack?.allArtists ?: "–",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = playerViewModel::toggleLike) {
                    Icon(
                        imageVector        = if (state.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Like",
                        tint               = if (state.isLiked) surfaceAccentColor else LocalContentColor.current,
                    )
                }
            }

            // Wavy seek bar
            var isDragging by remember { mutableStateOf(false) }
            var dragValue  by remember { mutableFloatStateOf(0f) }
            Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                LinearWavyProgressIndicator(
                    progress   = { if (isDragging) dragValue else state.progress },
                    modifier   = Modifier.fillMaxWidth().align(Alignment.Center),
                    color      = surfaceAccentColor,
                    trackColor = surfaceAccentColor.copy(alpha = 0.25f),
                    amplitude  = { p -> if (state.isPlaying) WavyProgressIndicatorDefaults.indicatorAmplitude(p) else 0f },
                )
                Slider(
                    value                 = if (isDragging) dragValue else state.progress,
                    onValueChange         = { isDragging = true; dragValue = it },
                    onValueChangeFinished = { playerViewModel.seekTo(dragValue); isDragging = false },
                    modifier              = Modifier.fillMaxWidth().align(Alignment.Center),
                    colors                = SliderDefaults.colors(thumbColor = Color.Transparent,
                        activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.progressMs.toTimeString(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.durationMs.toTimeString(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))

            // Controls
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = playerViewModel::toggleShuffle) {
                        Icon(Icons.Default.Shuffle, stringResource(R.string.player_shuffle),
                            tint = if (state.shuffleEnabled) surfaceAccentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier
                            .size(5.dp)
                            .offset(y = (-10).dp)
                            .background(
                                color = if (state.shuffleEnabled) surfaceAccentColor else Color.Transparent,
                                shape = CircleShape,
                            )
                    )
                }
                IconButton(onClick = onSkipPrev, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, stringResource(R.string.player_previous), modifier = Modifier.size(36.dp))
                }
                val cookieRotation = remember { Animatable(0f) }
                LaunchedEffect(state.isPlaying) {
                    if (state.isPlaying) {
                        while (true) {
                            cookieRotation.animateTo(cookieRotation.value + 360f, tween(8000, easing = LinearEasing))
                            cookieRotation.snapTo(0f)
                        }
                    }
                }
                FilledIconButton(
                    onClick  = playerViewModel::playPause,
                    modifier = Modifier.size(60.dp).graphicsLayer { rotationZ = cookieRotation.value },
                    shape    = squigglyShape,
                    colors   = IconButtonDefaults.filledIconButtonColors(
                        containerColor = accentColor, contentColor = Color.White),
                ) {
                    Icon(
                        imageVector        = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                        modifier           = Modifier.size(30.dp).graphicsLayer { rotationZ = -cookieRotation.value },
                    )
                }
                IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.player_next), modifier = Modifier.size(36.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = playerViewModel::cycleRepeat) {
                        Icon(
                            imageVector = when (state.repeatMode) {
                                RepeatMode.TRACK -> Icons.Default.RepeatOne else -> Icons.Default.Repeat
                            },
                            contentDescription = stringResource(R.string.player_repeat),
                            tint = if (state.repeatMode != RepeatMode.OFF) surfaceAccentColor
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(5.dp)
                            .offset(y = (-10).dp)
                            .background(
                                color = if (state.repeatMode != RepeatMode.OFF) surfaceAccentColor else Color.Transparent,
                                shape = CircleShape,
                            )
                    )
                }
            }
        }
    }
}
