package com.crsmthw.lyra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crsmthw.lyra.data.remote.model.SpotifyTrack

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
    visible               : Boolean = true,
    accentColor             : Color = Color.Unspecified,
    surfaceAccentColor      : Color = Color.Unspecified,
    sharedTransitionScope   : SharedTransitionScope? = null,
    animatedVisibilityScope : AnimatedVisibilityScope? = null,
) {
    val resolvedAccent        = if (accentColor == Color.Unspecified) MaterialTheme.colorScheme.primary else accentColor
    val resolvedSurfaceAccent = if (surfaceAccentColor == Color.Unspecified) resolvedAccent else surfaceAccentColor
    val density    = LocalDensity.current
    val navBarPx   = WindowInsets.navigationBars.getBottom(density)
    AnimatedVisibility(
        visible  = visible && currentTrack != null,
        enter    = slideInVertically  { it + navBarPx },
        exit     = slideOutVertically { it + navBarPx },
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
                .clickable(onClick = onExpand),
        ) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Album art — shared element when transition params are provided
                val artModifier = if (sharedTransitionScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState      = rememberSharedContentState(key = "album-art"),
                            animatedVisibilityScope = effectiveScope,
                        )
                    }
                } else Modifier

                AsyncImage(
                    model              = currentTrack.thumbnailUrl,
                    contentDescription = currentTrack.album?.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = artModifier
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
                        text     = currentTrack.primaryArtist,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Play/pause wrapped in CircularWavyProgressIndicator
                val waveAmplitude by animateFloatAsState(
                    targetValue   = if (isPlaying) 1f else 0f,
                    animationSpec = tween(400),
                    label         = "waveAmplitude",
                )
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                    CircularWavyProgressIndicator(
                        progress  = { progress },
                        modifier  = Modifier.size(52.dp),
                        color     = resolvedSurfaceAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        amplitude = { p -> WavyProgressIndicatorDefaults.indicatorAmplitude(p) * waveAmplitude },
                    )
                    IconButton(
                        onClick  = onPlayPause,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier           = Modifier.size(22.dp),
                        )
                    }
                }

                IconButton(
                    onClick  = onSkipNext,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier           = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
