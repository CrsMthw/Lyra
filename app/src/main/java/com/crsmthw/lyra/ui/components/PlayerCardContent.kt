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
import kotlin.math.abs
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import com.crsmthw.lyra.ui.components.AddToPlaylistSheet
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.screens.player.RepeatMode
import com.crsmthw.lyra.util.toTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerCardContent(
    playerViewModel          : PlayerViewModel,
    onClose                  : () -> Unit,
    onFullScreen             : () -> Unit,
    onOpenQueue              : () -> Unit = {},
    // Local scope — mini player ↔ panel expansion
    sharedTransitionScope   : SharedTransitionScope? = null,
    animatedVisibilityScope : AnimatedVisibilityScope? = null,
    // Nav scope — panel → full PlayerScreen
    navSharedTransitionScope  : SharedTransitionScope? = null,
    navAnimatedContentScope   : AnimatedContentScope? = null,
) {
    val state by playerViewModel.uiState.collectAsStateWithLifecycle()
    val pickerState by playerViewModel.pickerState.collectAsStateWithLifecycle()
    var showDevicePicker by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

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
            val progressAnim  = remember { Animatable(state.progress) }
            val snapThreshold = if (state.durationMs > 0L) (3000f / state.durationMs.toFloat()).coerceAtMost(0.5f) else 0.05f
            val prevTrackIdRef = remember { arrayOf(state.currentTrack?.id) }
            LaunchedEffect(state.progress, state.currentTrack?.id, state.isPlaying, isDragging) {
                val target = if (isDragging) dragValue else state.progress
                val trackChanged = state.currentTrack?.id != prevTrackIdRef[0]
                if (trackChanged) prevTrackIdRef[0] = state.currentTrack?.id
                if (trackChanged || !state.isPlaying || isDragging || abs(target - progressAnim.value) > snapThreshold) {
                    progressAnim.snapTo(target)
                } else {
                    progressAnim.animateTo(target, tween(1100, easing = LinearEasing))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                if (state.isWakingUp) {
                    LinearWavyProgressIndicator(
                        modifier   = Modifier.fillMaxWidth().align(Alignment.Center),
                        color      = surfaceAccentColor,
                        trackColor = surfaceAccentColor.copy(alpha = 0.25f),
                    )
                } else {
                    LinearWavyProgressIndicator(
                        progress   = { if (isDragging) dragValue else progressAnim.value },
                        modifier   = Modifier.fillMaxWidth().align(Alignment.Center),
                        color      = surfaceAccentColor,
                        trackColor = surfaceAccentColor.copy(alpha = 0.25f),
                        amplitude  = { p -> if (state.isPlaying) WavyProgressIndicatorDefaults.indicatorAmplitude(p) else 0f },
                    )
                }
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
                    if (state.isWakingUp) {
                        LoadingIndicator(
                            modifier = Modifier.size(40.dp).graphicsLayer { rotationZ = -cookieRotation.value },
                            color    = Color.White,
                        )
                    } else {
                        Icon(
                            imageVector        = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                            modifier           = Modifier.size(30.dp).graphicsLayer { rotationZ = -cookieRotation.value },
                        )
                    }
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

            Spacer(Modifier.height(16.dp))

            // Action bar — device chip left, connected button group right
            val enabled = state.currentTrack != null
            val deviceIcon = when (state.currentDevice?.type?.lowercase()) {
                "computer"               -> Icons.Default.Computer
                "smartphone"             -> Icons.Default.PhoneAndroid
                "speaker"                -> Icons.Default.Speaker
                "tv"                     -> Icons.Default.Tv
                "castaudio", "castvideo" -> Icons.Default.Cast
                "automobile"             -> Icons.Default.DirectionsCar
                else                     -> Icons.Default.Cast
            }
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick    = { playerViewModel.loadAvailableDevices(); showDevicePicker = true },
                    enabled    = enabled,
                    label      = {
                        Text(
                            text     = state.currentDevice?.name ?: stringResource(R.string.player_no_device),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector        = deviceIcon,
                            contentDescription = null,
                            modifier           = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                val labelQueue         = stringResource(R.string.player_queue)
                val labelShare         = stringResource(R.string.player_share)
                val labelAddToPlaylist = stringResource(R.string.player_add_to_playlist)
                ButtonGroup(overflowIndicator = {}) {
                    clickableItem(
                        onClick  = onOpenQueue,
                        label    = labelQueue,
                        icon     = @Composable { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                        enabled  = enabled,
                    )
                    clickableItem(
                        onClick = {
                            state.currentTrack?.id?.let { id ->
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                                        type = "text/plain"
                                    }, null
                                ))
                            }
                        },
                        label   = labelShare,
                        icon    = @Composable { Icon(Icons.Default.Share, contentDescription = null) },
                        enabled = enabled,
                    )
                    clickableItem(
                        onClick  = { playerViewModel.loadOwnedPlaylists(); showPlaylistPicker = true },
                        label    = labelAddToPlaylist,
                        icon     = @Composable { Icon(Icons.Default.LibraryAdd, contentDescription = null) },
                        enabled  = enabled,
                    )
                }
            }
        }
    }

    if (showDevicePicker) {
        DevicePickerSheet(
            isLoading      = state.devicePickerLoading,
            devices        = state.availableDevices,
            error          = state.devicePickerError,
            onSelectDevice = { deviceId ->
                showDevicePicker = false
                playerViewModel.transferToDevice(deviceId)
            },
            onThisDevice   = {
                showDevicePicker = false
                playerViewModel.transferToThisDevice()
            },
            onDismiss      = { showDevicePicker = false },
            onRetry        = { playerViewModel.loadAvailableDevices() },
        )
    }

    if (showPlaylistPicker) {
        AddToPlaylistSheet(
            pickerState = pickerState,
            onSelect    = playerViewModel::togglePlaylistTrack,
            onDismiss   = { showPlaylistPicker = false },
        )
    }
}
