@file:Suppress("ConfigurationScreenWidthHeight")

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
import kotlin.math.abs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.crsmthw.lyra.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.crsmthw.lyra.ui.components.AddToPlaylistSheet
import com.crsmthw.lyra.ui.components.DevicePickerSheet
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
    onOpenQueue           : () -> Unit,
    onFullScreen          : (() -> Unit)? = null,
    onOpenAlbum           : ((albumId: String) -> Unit)? = null,
    onOpenArtist          : ((artistId: String) -> Unit)? = null,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickerState by viewModel.pickerState.collectAsStateWithLifecycle()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showPlaylistPicker   by rememberSaveable { mutableStateOf(false) }
    var showDevicePicker     by remember { mutableStateOf(false) }
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
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(state.deviceTransferError) {
        state.deviceTransferError?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val addResult = pickerState.addResult
    val addResultToastMsg = when (addResult) {
        is AddToPlaylistResult.Added          -> stringResource(R.string.player_add_to_playlist_success, addResult.playlistName)
        is AddToPlaylistResult.Removed        -> stringResource(R.string.player_remove_from_playlist_success, addResult.playlistName)
        is AddToPlaylistResult.NeedsReconnect -> stringResource(R.string.player_add_to_playlist_403)
        is AddToPlaylistResult.Error          -> addResult.message ?: stringResource(R.string.error_generic)
        null                                  -> null
    }
    LaunchedEffect(addResult) {
        addResultToastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearPickerResult()
        }
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
                            Icon(Icons.Default.Timer, contentDescription = stringResource(R.string.player_sleep_timer),
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
                        onOpenQueue        = onOpenQueue,
                        onOpenAlbum        = onOpenAlbum,
                        onOpenArtist       = onOpenArtist,
                        onShare            = {
                            state.currentTrack?.id?.let { id ->
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                                        type = "text/plain"
                                    }, null
                                ))
                            }
                        },
                        onAddToPlaylist    = { viewModel.loadOwnedPlaylists(); showPlaylistPicker = true },
                        onOpenDevicePicker = { viewModel.loadAvailableDevices(); showDevicePicker = true },
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
                        onOpenQueue        = onOpenQueue,
                        onOpenAlbum        = onOpenAlbum,
                        onOpenArtist       = onOpenArtist,
                        onShare            = {
                            state.currentTrack?.id?.let { id ->
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                                        type = "text/plain"
                                    }, null
                                ))
                            }
                        },
                        onAddToPlaylist    = { viewModel.loadOwnedPlaylists(); showPlaylistPicker = true },
                        onOpenDevicePicker = { viewModel.loadAvailableDevices(); showDevicePicker = true },
                    )
                }
            }
        }
    }

    if (showPlaylistPicker) {
        AddToPlaylistSheet(
            pickerState = pickerState,
            onSelect    = viewModel::togglePlaylistTrack,
            onDismiss   = { showPlaylistPicker = false },
        )
    }

    if (showDevicePicker) {
        DevicePickerSheet(
            isLoading      = state.devicePickerLoading,
            devices        = state.availableDevices,
            error          = state.devicePickerError,
            onSelectDevice = { deviceId ->
                showDevicePicker = false
                viewModel.transferToDevice(deviceId)
            },
            onThisDevice   = {
                showDevicePicker = false
                viewModel.transferToThisDevice()
            },
            onDismiss      = { showDevicePicker = false },
            onRetry        = { viewModel.loadAvailableDevices() },
        )
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
    onSkipPrev         : () -> Unit,
    onSkipNext         : () -> Unit,
    onPlayPause        : () -> Unit,
    onToggleLike       : () -> Unit,
    onToggleShuffle    : () -> Unit,
    onCycleRepeat      : () -> Unit,
    onSeek             : (Float) -> Unit,
    onOpenQueue        : () -> Unit,
    onOpenAlbum        : ((albumId: String) -> Unit)? = null,
    onOpenArtist       : ((artistId: String) -> Unit)? = null,
    onShare            : () -> Unit,
    onAddToPlaylist    : () -> Unit,
    onOpenDevicePicker : () -> Unit,
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
            val artists = state.currentTrack?.artists.orEmpty()
            if (artists.isNotEmpty()) {
                FlowRow {
                    artists.forEachIndexed { index, artist ->
                        if (index > 0) {
                            Text(
                                text  = " · ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text     = artist.name,
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onOpenArtist != null)
                                Modifier.clickable { onOpenArtist(artist.id) }
                            else Modifier,
                        )
                    }
                }
            } else {
                Text(
                    text  = "–",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val albumId   = state.currentTrack?.album?.id
            val albumName = state.currentTrack?.album?.name
            if (albumName != null && albumId != null && onOpenAlbum != null) {
                Text(
                    text     = albumName,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenAlbum(albumId) },
                )
            }
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onToggleShuffle) {
                Icon(Icons.Default.Shuffle, contentDescription = stringResource(R.string.player_shuffle),
                    tint = if (state.shuffleEnabled) surfaceAccentColor
                           else MaterialTheme.colorScheme.onSurfaceVariant)
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
            Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.player_previous),
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
            if (state.isWakingUp) {
                LoadingIndicator(
                    modifier = Modifier.size(44.dp).graphicsLayer { rotationZ = -cookieRotation.value },
                    color    = Color.White,
                )
            } else {
                Icon(
                    imageVector        = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                    modifier           = Modifier.size(34.dp).graphicsLayer { rotationZ = -cookieRotation.value },
                )
            }
        }

        IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.player_next),
                modifier = Modifier.size(36.dp))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    imageVector = when (state.repeatMode) {
                        RepeatMode.TRACK -> Icons.Default.RepeatOne
                        else             -> Icons.Default.Repeat
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

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        val deviceIcon = when (state.currentDevice?.type?.lowercase()) {
            "computer"               -> Icons.Default.Computer
            "smartphone"             -> Icons.Default.PhoneAndroid
            "speaker"                -> Icons.Default.Speaker
            "tv"                     -> Icons.Default.Tv
            "castaudio", "castvideo" -> Icons.Default.Cast
            "automobile"             -> Icons.Default.DirectionsCar
            else                     -> Icons.Default.Cast
        }
        AssistChip(
            onClick    = onOpenDevicePicker,
            enabled    = state.currentTrack != null,
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
            colors = AssistChipDefaults.assistChipColors(
                containerColor          = surfaceAccentColor.copy(alpha = 0.12f),
                labelColor              = surfaceAccentColor,
                leadingIconContentColor = surfaceAccentColor,
            ),
            border   = BorderStroke(1.dp, surfaceAccentColor.copy(alpha = 0.4f)),
            modifier = Modifier.widthIn(max = 160.dp),
        )
        // S-size (12dp padding, 18dp icon) connected icon buttons via customItem
        val queueLabel         = stringResource(R.string.player_queue)
        val shareLabel         = stringResource(R.string.player_share)
        val addToPlaylistLabel = stringResource(R.string.player_add_to_playlist)
        val accentButtonColors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = surfaceAccentColor.copy(alpha = 0.12f),
            contentColor   = surfaceAccentColor,
        )
        ButtonGroup(overflowIndicator = {}) {
            customItem(
                buttonGroupContent = @Composable {
                    FilledTonalIconButton(
                        onClick  = onOpenQueue,
                        enabled  = state.currentTrack != null,
                        modifier = Modifier.size(40.dp),
                        shape    = ButtonGroupDefaults.connectedLeadingButtonShape,
                        colors   = accentButtonColors,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, queueLabel, Modifier.size(18.dp))
                    }
                },
                menuContent = @Composable { _ -> },
            )
            customItem(
                buttonGroupContent = @Composable {
                    FilledTonalIconButton(
                        onClick  = onShare,
                        enabled  = state.currentTrack != null,
                        modifier = Modifier.size(40.dp),
                        shape    = RoundedCornerShape(2.dp),
                        colors   = accentButtonColors,
                    ) {
                        Icon(Icons.Default.Share, shareLabel, Modifier.size(18.dp))
                    }
                },
                menuContent = @Composable { _ -> },
            )
            customItem(
                buttonGroupContent = @Composable {
                    FilledTonalIconButton(
                        onClick  = onAddToPlaylist,
                        enabled  = state.currentTrack != null,
                        modifier = Modifier.size(40.dp),
                        shape    = ButtonGroupDefaults.connectedTrailingButtonShape,
                        colors   = accentButtonColors,
                    ) {
                        Icon(Icons.Default.LibraryAdd, addToPlaylistLabel, Modifier.size(18.dp))
                    }
                },
                menuContent = @Composable { _ -> },
            )
        }
    }
}

@Composable
private fun SleepTimerDialog(
    currentMinutes: Int,
    onSelect      : (Int) -> Unit,
    onDismiss     : () -> Unit,
) {
    val options = listOf(0, 5, 15, 30, 45, 60)
    val labels  = listOf(
        stringResource(R.string.sleep_timer_off),
        stringResource(R.string.sleep_timer_5),
        stringResource(R.string.sleep_timer_15),
        stringResource(R.string.sleep_timer_30),
        stringResource(R.string.sleep_timer_45),
        stringResource(R.string.sleep_timer_60),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(stringResource(R.string.player_sleep_timer)) },
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
