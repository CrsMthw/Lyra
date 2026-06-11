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
import androidx.compose.foundation.BorderStroke
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
import android.widget.Toast
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.components.AddToPlaylistSheet
import com.crsmthw.lyra.ui.screens.player.AddToPlaylistResult
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.screens.player.RepeatMode
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.loadAlbumArtColors
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.reject
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import com.crsmthw.lyra.util.tick
import com.crsmthw.lyra.util.toTimeString
import com.crsmthw.lyra.util.toggle
import kotlinx.coroutines.launch

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
    // edge feeds only the background gradient (cover "dissolves" into the panel); rawDominantColor is
    // the Vibrant accent; rawSurfaceAccentColor the contrast-safe tint. See util/AlbumArtColor.kt.
    var rawDominantColor      by remember { mutableStateOf<Color?>(null) }
    var rawSurfaceAccentColor by remember { mutableStateOf<Color?>(null) }
    var rawEdgeColor          by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(state.currentTrack?.artUrl, isDarkTheme) {
        val colors = loadAlbumArtColors(context, state.currentTrack?.artUrl, isDarkTheme)
            ?: return@LaunchedEffect
        rawDominantColor      = Color(colors.accent)
        rawEdgeColor          = Color(colors.edge)
        rawSurfaceAccentColor = Color(colors.surfaceAccent)
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
        targetValue   = (rawEdgeColor ?: MaterialTheme.colorScheme.surfaceContainer).copy(alpha = 0.7f),
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
    // Expressive spring for the incoming art's settle on track change (the fling-OFF stays a tween).
    val artSlideInSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // Crossfade so a cold art image fades up instead of popping in mid-slide; Coil skips the
    // crossfade on memory-cache hits. Remembered on the URL so the per-second tick doesn't reload.
    val artImageModel = remember(displayedTrack?.artUrl) {
        ImageRequest.Builder(context).data(displayedTrack?.artUrl).crossfade(200).build()
    }

    LaunchedEffect(state.currentTrack?.id) {
        val incoming = state.currentTrack
        if (incoming?.id != displayedTrack?.id) {
            if (incoming != null && displayedTrack != null) {
                artOffsetX.snapTo(if (skipDirection >= 0) 1500f else -1500f)
            }
            displayedTrack = incoming
            artOffsetX.animateTo(0f, artSlideInSpec)
        }
    }

    val haptics = LocalHapticFeedback.current

    // Toasts for actions taken from this pop-out panel. PlayerScreen (the full player) has its own
    // copy, but on wide/unfolded screens this panel is shown *instead of* it — without these, no
    // add-to-playlist / device / error toast would appear while the panel is up.
    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(state.deviceTransferError) {
        state.deviceTransferError?.let {
            haptics.reject()
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
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
            when (addResult) {
                is AddToPlaylistResult.Added, is AddToPlaylistResult.Removed -> haptics.confirm()
                else -> haptics.reject()
            }
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            playerViewModel.clearPickerResult()
        }
    }

    val onSkipNext: () -> Unit = {
        haptics.press()
        skipDirection = 1
        scope.launch { artOffsetX.animateTo(-1500f, tween(250, easing = FastOutLinearInEasing)) }
        playerViewModel.skipNext()
    }
    val onSkipPrev: () -> Unit = {
        haptics.press()
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

    // BoxWithConstraints to compute dynamic art size — maxHeight propagates from
    // the panel's heightIn(max) constraint through the Card, so the art shrinks
    // to fit rather than causing the column to scroll.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Reserve space for all non-art elements (header, track info, seekbar, time,
        // controls, spacers, action bar, bottom padding). Art takes the remainder, capped
        // at the panel width (minus horizontal padding) so it stays square.
        val reservedChrome = 360.dp
        val artSize = minOf(
            maxWidth - 40.dp,  // full width minus 2×20dp horizontal padding
            (maxHeight - reservedChrome).coerceAtLeast(60.dp),
        )

        // Gradient background
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(gradientTop, surfaceBg)))
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
                IconButton(onClick = { haptics.confirm(); onClose() }) {
                    Icon(Icons.Default.KeyboardArrowDown, "Close", tint = onAccentColor)
                }
                Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall,
                    color = onAccentColor)
                IconButton(onClick = { haptics.confirm(); onFullScreen() }) {
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
                        boundsTransform         = rememberArtBoundsTransform(),
                    )
                }
            } else Modifier
            val navArtMod = if (navSharedTransitionScope != null && navAnimatedContentScope != null) {
                with(navSharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState      = rememberSharedContentState(key = "album-art"),
                        animatedVisibilityScope = navAnimatedContentScope,
                        boundsTransform         = rememberArtBoundsTransform(),
                    )
                }
            } else Modifier
            val artSharedMod = localArtMod.then(navArtMod)
            AsyncImage(
                model              = artImageModel,
                contentDescription = "Album art",
                contentScale       = ContentScale.Crop,
                modifier           = artSharedMod
                    .size(artSize)
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
                IconButton(onClick = { haptics.toggle(!state.isLiked); playerViewModel.toggleLike() }) {
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
                var lastSeekNotch by remember { mutableIntStateOf(-1) }
                Slider(
                    value                 = if (isDragging) dragValue else state.progress,
                    onValueChange         = {
                        isDragging = true; dragValue = it
                        val notch = (it / 0.05f).toInt()
                        if (notch != lastSeekNotch) { lastSeekNotch = notch; haptics.tick() }
                    },
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
                Box(contentAlignment = Alignment.Center) {
                    IconButton(onClick = { haptics.toggle(!state.shuffleEnabled); playerViewModel.toggleShuffle() }) {
                        Icon(Icons.Default.Shuffle, stringResource(R.string.player_shuffle),
                            tint = if (state.shuffleEnabled) surfaceAccentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = (-5).dp)
                            .size(5.dp)
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
                    onClick  = { haptics.press(); playerViewModel.playPause() },
                    modifier = Modifier.size(60.dp).graphicsLayer { rotationZ = cookieRotation.value },
                    shape    = squigglyShape,
                    colors   = IconButtonDefaults.filledIconButtonColors(
                        containerColor = accentColor, contentColor = onAccentColor),
                ) {
                    if (state.isWakingUp) {
                        LoadingIndicator(
                            modifier = Modifier.size(40.dp).graphicsLayer { rotationZ = -cookieRotation.value },
                            color    = onAccentColor,
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
                Box(contentAlignment = Alignment.Center) {
                    IconButton(onClick = {
                        when (state.repeatMode) {
                            RepeatMode.OFF   -> haptics.toggle(true)
                            RepeatMode.TRACK -> haptics.toggle(false)
                            else             -> haptics.press()
                        }
                        playerViewModel.cycleRepeat()
                    }) {
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
                            .align(Alignment.BottomCenter)
                            .offset(y = (-5).dp)
                            .size(5.dp)
                            .background(
                                color = if (state.repeatMode != RepeatMode.OFF) surfaceAccentColor else Color.Transparent,
                                shape = CircleShape,
                            )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Action bar — device chip left, S-size icon-only connected button group right
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
                    onClick    = { haptics.press(); playerViewModel.loadAvailableDevices(); showDevicePicker = true },
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
                // Plain Row, not M3 ButtonGroup: overflow was disabled here, and ButtonGroup's overflow
                // MeasurePolicy crashes with an inverted Constraints when the column is tight (see the
                // matching note + folded-landscape crash fix in PlayerScreen). A Row clips instead.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilledTonalIconButton(
                        onClick  = { haptics.press(); onOpenQueue() },
                        enabled  = enabled,
                        modifier = Modifier.size(40.dp),
                        shape    = ButtonGroupDefaults.connectedLeadingButtonShape,
                        colors   = accentButtonColors,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, queueLabel, Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = {
                            haptics.press()
                            state.currentTrack?.id?.let { id ->
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                                        type = "text/plain"
                                    }, null
                                ))
                            }
                        },
                        enabled  = enabled,
                        modifier = Modifier.size(40.dp),
                        shape    = RoundedCornerShape(2.dp),
                        colors   = accentButtonColors,
                    ) {
                        Icon(Icons.Default.Share, shareLabel, Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick  = { haptics.press(); playerViewModel.loadOwnedPlaylists(); showPlaylistPicker = true },
                        enabled  = enabled,
                        modifier = Modifier.size(40.dp),
                        shape    = ButtonGroupDefaults.connectedTrailingButtonShape,
                        colors   = accentButtonColors,
                    ) {
                        Icon(Icons.Default.LibraryAdd, addToPlaylistLabel, Modifier.size(18.dp))
                    }
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
                haptics.confirm()
                playerViewModel.transferToDevice(deviceId)
            },
            onThisDevice   = {
                showDevicePicker = false
                haptics.confirm()
                playerViewModel.transferToThisDevice()
            },
            onDismiss      = { showDevicePicker = false },
            onRetry        = { playerViewModel.loadAvailableDevices() },
            onSetVolume    = playerViewModel::setVolume,
        )
    }

    if (showPlaylistPicker) {
        AddToPlaylistSheet(
            pickerState = pickerState,
            onSelect    = playerViewModel::togglePlaylistTrack,
            onCreateNew = playerViewModel::createPlaylist,
            onDismiss   = { showPlaylistPicker = false },
        )
    }
}
