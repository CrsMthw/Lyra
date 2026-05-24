package com.crsmthw.lyra.ui.screens.player

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.crsmthw.lyra.util.toTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun PlayerScreen(
    viewModel             : PlayerViewModel,
    onBack                : () -> Unit,
    onFullScreen          : (() -> Unit)? = null,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    @Suppress("SpellCheckingInspection")
    val snackbarHostState = remember { SnackbarHostState() }
    val isLandscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

    // ── Dynamic color extraction ──────────────────────────────────────────────
    val context     = LocalContext.current
    val isDarkTheme = MaterialTheme.colorScheme.background.let {
        0.299f * it.red + 0.587f * it.green + 0.114f * it.blue < 0.5f
    }
    var rawDominantColor      by remember { mutableStateOf<Color?>(null) }
    var rawSurfaceAccentColor by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(state.currentTrack?.artUrl, isDarkTheme) {
        val url = state.currentTrack?.artUrl.takeIf { !it.isNullOrBlank() }
            ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.IO) {
            try {
                val loader  = SingletonImageLoader.get(context)
                val request = ImageRequest.Builder(context).data(url).build()
                val result  = loader.execute(request)
                if (result is SuccessResult) {
                    val raw    = result.image.toBitmap()
                    val bitmap = raw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    Palette.from(bitmap).generate()
                } else null
            } catch (_: Exception) { null }
        }
        if (palette != null) {
            val fallback     = palette.getDominantColor(0xFF1DB954.toInt())
            rawDominantColor = Color(palette.getVibrantColor(fallback))
            rawSurfaceAccentColor = if (isDarkTheme) {
                Color(palette.getLightVibrantColor(palette.getVibrantColor(palette.getLightMutedColor(fallback))))
            } else {
                Color(palette.getDarkVibrantColor(palette.getVibrantColor(palette.getDarkMutedColor(fallback))))
            }
        }
    }

    val surfaceBg = MaterialTheme.colorScheme.background
    val primary   = MaterialTheme.colorScheme.primary
    val accentColor by animateColorAsState(
        targetValue   = rawDominantColor ?: primary,
        animationSpec = tween(800),
        label         = "accentColor",
    )
    val surfaceAccentColor by animateColorAsState(
        targetValue   = rawSurfaceAccentColor ?: primary,
        animationSpec = tween(800),
        label         = "surfaceAccentColor",
    )
    val onAccentColor by animateColorAsState(
        targetValue = run {
            val c = rawDominantColor ?: primary
            if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue < 0.5f) Color.White else Color.Black
        },
        animationSpec = tween(800),
        label         = "onAccentColor",
    )
    val gradientTop by animateColorAsState(
        targetValue   = (rawDominantColor ?: MaterialTheme.colorScheme.surfaceContainer)
            .copy(alpha = 0.85f),
        animationSpec = tween(800),
        label         = "gradientTop",
    )

    // ── Art transition state ──────────────────────────────────────────────────
    val scope          = rememberCoroutineScope()
    val artOffsetX     = remember { Animatable(0f) }
    var displayedTrack by remember { mutableStateOf(state.currentTrack) }
    var skipDirection  by remember { mutableIntStateOf(1) }
    var artDragX       by remember { mutableFloatStateOf(0f) }
    val density        = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 80.dp.toPx() } }

    LaunchedEffect(state.currentTrack?.id) {
        state.currentTrack?.id?.let { viewModel.recheckLiked(it) }
        val incoming = state.currentTrack
        if (incoming?.id != displayedTrack?.id) {
            if (incoming != null && displayedTrack != null) {
                artOffsetX.snapTo(if (skipDirection >= 0) 1500f else -1500f)
            }
            displayedTrack = incoming
            artOffsetX.animateTo(0f, animationSpec = tween(350, easing = FastOutSlowInEasing))
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) }
    }

    val onSkipNext: () -> Unit = {
        skipDirection = 1
        scope.launch {
            artOffsetX.animateTo(-1500f, animationSpec = tween(250, easing = FastOutLinearInEasing))
        }
        viewModel.skipNext()
    }
    val onSkipPrev: () -> Unit = {
        if (state.progressMs > 3_000L) {
            viewModel.seekTo(0f)
        } else {
            skipDirection = -1
            scope.launch {
                artOffsetX.animateTo(1500f, animationSpec = tween(250, easing = FastOutLinearInEasing))
            }
            viewModel.skipPrevious()
        }
    }

    // ── Play/pause button shape (M3 Expressive cookie) ───────────────────────
    val squigglyShape = MaterialShapes.Cookie12Sided.toShape()

    // ── Gradient background overlay (rendered outside Scaffold so it fills everything) ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(gradientTop, surfaceBg)))
    )

    Scaffold(
        snackbarHost        = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
        containerColor      = Color.Transparent,
        topBar = {
            TopAppBar(
                modifier     = Modifier
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                windowInsets = WindowInsets(0),
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier            = Modifier.fillMaxWidth(),
                    ) {
                        Text("NOW PLAYING",
                            style = MaterialTheme.typography.labelSmall,
                            color = onAccentColor)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close",
                            tint = onAccentColor)
                    }
                },
                actions = {
                    if (onFullScreen != null) {
                        IconButton(onClick = onFullScreen) {
                            Icon(Icons.Default.OpenInFull, contentDescription = "Full screen",
                                tint = onAccentColor)
                        }
                    }
                    IconButton(onClick = { showSleepTimerDialog = true }) {
                        if (state.sleepTimerMinutes > 0) {
                            val timerProgress = state.sleepTimerMinutes.toFloat() /
                                state.sleepTimerTotalMinutes.coerceAtLeast(1).toFloat()
                            Box(
                                modifier         = Modifier.size(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    progress    = { timerProgress },
                                    modifier    = Modifier.fillMaxSize(),
                                    strokeWidth = 2.5.dp,
                                    color       = onAccentColor,
                                    trackColor  = onAccentColor.copy(alpha = 0.2f),
                                )
                                Text(
                                    text  = "${state.sleepTimerMinutes}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onAccentColor,
                                )
                            }
                        } else {
                            Icon(Icons.Default.Timer, contentDescription = "Sleep timer",
                                tint = onAccentColor)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { paddingValues ->

        if (isLandscape) {
            // ── Landscape: art left, controls right ───────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .navigationBarsPadding(),
            ) {
                // Left: album art — square, constrained by available height
                BoxWithConstraints(
                    modifier         = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .padding(start = 16.dp, end = 8.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth, maxHeight)

                    val artMod = if (sharedTransitionScope != null && animatedContentScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                sharedContentState      = rememberSharedContentState("album-art"),
                                animatedVisibilityScope = animatedContentScope,
                            )
                        }
                    } else Modifier

                    AsyncImage(
                        model              = displayedTrack?.artUrl,
                        contentDescription = "Album art",
                        contentScale       = ContentScale.Crop,
                        modifier           = artMod
                            .size(side)
                            .clip(RoundedCornerShape(16.dp))
                            .graphicsLayer {
                                translationX = (artDragX * 0.3f).coerceIn(-80f, 80f) + artOffsetX.value
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragStart      = { artDragX = 0f },
                                    onDragEnd        = {
                                        when {
                                            artDragX < -swipeThresholdPx -> {
                                                val s = (artDragX * 0.3f).coerceIn(-80f, 80f)
                                                artDragX = 0f; skipDirection = 1
                                                scope.launch {
                                                    artOffsetX.snapTo(s)
                                                    artOffsetX.animateTo(-1500f, tween(250, easing = FastOutLinearInEasing))
                                                }
                                                viewModel.skipNext()
                                            }
                                            artDragX > swipeThresholdPx -> {
                                                val s = (artDragX * 0.3f).coerceIn(-80f, 80f)
                                                artDragX = 0f; skipDirection = -1
                                                scope.launch {
                                                    artOffsetX.snapTo(s)
                                                    artOffsetX.animateTo(1500f, tween(250, easing = FastOutLinearInEasing))
                                                }
                                                viewModel.skipPrevious()
                                            }
                                            else -> artDragX = 0f
                                        }
                                    },
                                    onDragCancel     = { artDragX = 0f },
                                    onHorizontalDrag = { _, amount -> artDragX += amount },
                                )
                            },
                    )
                }

                // Right: controls
                Column(
                    modifier            = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PlayerControls(
                        state              = state,
                        accentColor        = accentColor,
                        surfaceAccentColor = surfaceAccentColor,
                        squigglyShape      = squigglyShape,
                        onSkipPrev         = onSkipPrev,
                        onSkipNext         = onSkipNext,
                        onPlayPause        = viewModel::playPause,
                        onToggleLike       = viewModel::toggleLike,
                        onToggleShuffle    = viewModel::toggleShuffle,
                        onCycleRepeat      = viewModel::cycleRepeat,
                        onSeek             = viewModel::seekTo,
                    )
                }
            }
        } else {
            // ── Portrait: art top, controls bottom ────────────────────────────
            Column(
                modifier            = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Album art
                BoxWithConstraints(
                    modifier         = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth, maxHeight)

                    val artMod = if (sharedTransitionScope != null && animatedContentScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                sharedContentState      = rememberSharedContentState("album-art"),
                                animatedVisibilityScope = animatedContentScope,
                            )
                        }
                    } else Modifier

                    AsyncImage(
                        model              = displayedTrack?.artUrl,
                        contentDescription = "Album art",
                        contentScale       = ContentScale.Crop,
                        modifier           = artMod
                            .size(side)
                            .clip(RoundedCornerShape(16.dp))
                            .graphicsLayer {
                                translationX = (artDragX * 0.3f).coerceIn(-80f, 80f) + artOffsetX.value
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragStart      = { artDragX = 0f },
                                    onDragEnd        = {
                                        when {
                                            artDragX < -swipeThresholdPx -> {
                                                val s = (artDragX * 0.3f).coerceIn(-80f, 80f)
                                                artDragX = 0f; skipDirection = 1
                                                scope.launch {
                                                    artOffsetX.snapTo(s)
                                                    artOffsetX.animateTo(-1500f, tween(250, easing = FastOutLinearInEasing))
                                                }
                                                viewModel.skipNext()
                                            }
                                            artDragX > swipeThresholdPx -> {
                                                val s = (artDragX * 0.3f).coerceIn(-80f, 80f)
                                                artDragX = 0f; skipDirection = -1
                                                scope.launch {
                                                    artOffsetX.snapTo(s)
                                                    artOffsetX.animateTo(1500f, tween(250, easing = FastOutLinearInEasing))
                                                }
                                                viewModel.skipPrevious()
                                            }
                                            else -> artDragX = 0f
                                        }
                                    },
                                    onDragCancel     = { artDragX = 0f },
                                    onHorizontalDrag = { _, amount -> artDragX += amount },
                                )
                            },
                    )
                }

                // Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 12.dp),
                ) {
                    PlayerControls(
                        state              = state,
                        accentColor        = accentColor,
                        surfaceAccentColor = surfaceAccentColor,
                        squigglyShape      = squigglyShape,
                        onSkipPrev         = onSkipPrev,
                        onSkipNext         = onSkipNext,
                        onPlayPause        = viewModel::playPause,
                        onToggleLike       = viewModel::toggleLike,
                        onToggleShuffle    = viewModel::toggleShuffle,
                        onCycleRepeat      = viewModel::cycleRepeat,
                        onSeek             = viewModel::seekTo,
                    )
                }
            }
        }
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            currentMinutes = state.sleepTimerMinutes,
            onSelect       = { minutes -> viewModel.setSleepTimer(minutes); showSleepTimerDialog = false },
            onDismiss      = { showSleepTimerDialog = false },
        )
    }
}

// ── Shared controls composable (used in portrait + landscape) ─────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerControls(
    state              : PlayerUiState,
    accentColor        : Color,
    surfaceAccentColor : Color,
    squigglyShape      : androidx.compose.ui.graphics.Shape,
    onSkipPrev      : () -> Unit,
    onSkipNext      : () -> Unit,
    onPlayPause     : () -> Unit,
    onToggleLike    : () -> Unit,
    onToggleShuffle : () -> Unit,
    onCycleRepeat   : () -> Unit,
    onSeek          : (Float) -> Unit,
) {
    // Track name + like
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = state.currentTrack?.name ?: "Nothing playing",
                style    = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text     = state.currentTrack?.primaryArtist ?: "–",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleLike) {
            Icon(
                imageVector        = if (state.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Like",
                tint               = if (state.isLiked) surfaceAccentColor else LocalContentColor.current,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    // Wavy seek bar
    var isDragging by remember { mutableStateOf(false) }
    var dragValue  by remember { mutableFloatStateOf(0f) }
    val waveAmplitude by animateFloatAsState(
        targetValue   = if (state.isPlaying) 1f else 0f,
        animationSpec = tween(400),
        label         = "waveAmplitude",
    )

    Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
        LinearWavyProgressIndicator(
            progress   = { if (isDragging) dragValue else state.progress },
            modifier   = Modifier.fillMaxWidth().align(Alignment.Center),
            color      = surfaceAccentColor,
            trackColor = surfaceAccentColor.copy(alpha = 0.25f),
            amplitude  = { p -> WavyProgressIndicatorDefaults.indicatorAmplitude(p) * waveAmplitude },
        )
        Slider(
            value                 = if (isDragging) dragValue else state.progress,
            onValueChange         = { isDragging = true; dragValue = it },
            onValueChangeFinished = { onSeek(dragValue); isDragging = false },
            modifier              = Modifier.fillMaxWidth().align(Alignment.Center),
            colors                = SliderDefaults.colors(
                thumbColor         = Color.Transparent,
                activeTrackColor   = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(state.progressMs.toTimeString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.durationMs.toTimeString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(12.dp))

    // Playback controls
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle",
                tint = if (state.shuffleEnabled) surfaceAccentColor
                       else MaterialTheme.colorScheme.onSurfaceVariant)
        }

        IconButton(onClick = onSkipPrev, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous",
                modifier = Modifier.size(36.dp))
        }

        // Cookie play/pause button — rotates slowly while playing
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
            onClick  = onPlayPause,
            modifier = Modifier.size(68.dp).graphicsLayer { rotationZ = cookieRotation.value },
            shape    = squigglyShape,
            colors   = IconButtonDefaults.filledIconButtonColors(
                containerColor = accentColor,
                contentColor   = Color.White,
            ),
        ) {
            Icon(
                imageVector        = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier           = Modifier.size(34.dp).graphicsLayer { rotationZ = -cookieRotation.value },
            )
        }

        IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next",
                modifier = Modifier.size(36.dp))
        }

        IconButton(onClick = onCycleRepeat) {
            Icon(
                imageVector = when (state.repeatMode) {
                    RepeatMode.TRACK -> Icons.Default.RepeatOne
                    else             -> Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                tint = if (state.repeatMode != RepeatMode.OFF) surfaceAccentColor
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SleepTimerDialog(
    currentMinutes: Int,
    onSelect      : (Int) -> Unit,
    onDismiss     : () -> Unit,
) {
    val options = listOf(0, 5, 15, 30, 45, 60)
    val labels  = listOf("Off", "5 min", "15 min", "30 min", "45 min", "1 hour")

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Sleep Timer") },
        text             = {
            Column {
                options.zip(labels).forEach { (minutes, label) ->
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentMinutes == minutes,
                            onClick = { onSelect(minutes) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
