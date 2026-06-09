package com.crsmthw.lyra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import androidx.compose.ui.Modifier
import kotlin.math.abs
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.crsmthw.lyra.R
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.util.rememberArtBoundsTransform

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    currentTrack          : SpotifyTrack?,
    isPlaying             : Boolean,
    progress              : Float,
    onPlayPause           : () -> Unit,
    onSkipNext            : () -> Unit,
    onExpand              : () -> Unit,
    modifier              : Modifier = Modifier,
    isWakingUp            : Boolean = false,
    visible               : Boolean = true,
    accentColor             : Color = Color.Unspecified,
    surfaceAccentColor      : Color = Color.Unspecified,
    sharedTransitionScope      : SharedTransitionScope? = null,
    animatedVisibilityScope    : AnimatedVisibilityScope? = null,
    // Secondary scope — used when both local (mini↔panel) and nav (mini↔PlayerScreen) are needed.
    navSharedTransitionScope   : SharedTransitionScope? = null,
    navAnimatedVisibilityScope : AnimatedVisibilityScope? = null,
) {
    val haptics               = LocalHapticFeedback.current
    val resolvedAccent        = if (accentColor == Color.Unspecified) MaterialTheme.colorScheme.primary else accentColor
    val resolvedSurfaceAccent = if (surfaceAccentColor == Color.Unspecified) resolvedAccent else surfaceAccentColor
    val density    = LocalDensity.current
    val navBarPx   = WindowInsets.navigationBars.getBottom(density)
    val miniSlideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    AnimatedVisibility(
        visible  = visible && currentTrack != null,
        enter    = slideInVertically(miniSlideSpec)  { it + navBarPx },
        exit     = slideOutVertically(miniSlideSpec) { it + navBarPx },
        modifier = modifier,
    ) {
        // Use inner scope when no external scope is provided (two-pane case).
        val effectiveScope: AnimatedVisibilityScope = animatedVisibilityScope ?: this
        currentTrack ?: return@AnimatedVisibility

        val shape   = RoundedCornerShape(20.dp)
        val bgColor = MaterialTheme.colorScheme.surfaceContainerHigh

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .shadow(elevation = 12.dp, shape = shape,
                    ambientColor = resolvedAccent.copy(alpha = 0.15f))
                .clip(shape)
                .background(bgColor)
                .background(resolvedAccent.copy(alpha = 0.10f))
                .clickable(onClick = { haptics.confirm(); onExpand() }),
        ) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Album art — chains up to two shared element scopes when both are provided.
                val artModifier = if (sharedTransitionScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState      = rememberSharedContentState(key = "album-art"),
                            animatedVisibilityScope = effectiveScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        )
                    }
                } else Modifier
                val navArtModifier = if (navSharedTransitionScope != null && navAnimatedVisibilityScope != null) {
                    with(navSharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState      = rememberSharedContentState(key = "album-art"),
                            animatedVisibilityScope = navAnimatedVisibilityScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        )
                    }
                } else Modifier

                AsyncImage(
                    model              = currentTrack.thumbnailUrl,
                    contentDescription = currentTrack.album?.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = artModifier.then(navArtModifier)
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = currentTrack.name,
                        style    = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text     = currentTrack.allArtists,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Play/pause wrapped in CircularWavyProgressIndicator
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                    val progressAnim = remember { Animatable(progress) }
                    LaunchedEffect(progress, isPlaying) {
                        if (!isPlaying || abs(progress - progressAnim.value) > 0.5f) {
                            progressAnim.snapTo(progress)
                        } else {
                            progressAnim.animateTo(progress, tween(1100, easing = LinearEasing))
                        }
                    }
                    if (isWakingUp) {
                        CircularWavyProgressIndicator(
                            modifier   = Modifier.size(52.dp),
                            color      = resolvedSurfaceAccent,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        CircularWavyProgressIndicator(
                            progress   = { progressAnim.value },
                            modifier   = Modifier.size(52.dp),
                            color      = resolvedSurfaceAccent,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            amplitude  = { p -> if (isPlaying) WavyProgressIndicatorDefaults.indicatorAmplitude(p) else 0f },
                        )
                    }
                    IconButton(
                        onClick  = { haptics.press(); onPlayPause() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                            modifier           = Modifier.size(22.dp),
                        )
                    }
                }

                IconButton(
                    onClick  = { haptics.press(); onSkipNext() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.player_next),
                        modifier           = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
