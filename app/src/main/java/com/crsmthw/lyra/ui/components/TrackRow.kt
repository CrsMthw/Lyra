package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.longPress
import com.crsmthw.lyra.util.tick
import com.crsmthw.lyra.util.toTimeString

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track       : SpotifyTrack,
    onClick     : () -> Unit,
    modifier    : Modifier = Modifier,
    isPlaying   : Boolean   = false,
    onLongClick : (() -> Unit)? = null,
    onMoreClick : (() -> Unit)? = null,
    // Multi-select: null = not in selection mode (no overlay, ordinary click haptics); true / false
    // = in selection mode, checked / unchecked. Used by the Library playlist detail.
    selected    : Boolean? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                // In selection mode a tap is a check, not playback — so it ticks like a notch
                // instead of confirming, and a long-press only toggles (there is no menu to reveal).
                onClick     = {
                    if (selected != null) haptics.tick() else haptics.confirm()
                    onClick()
                },
                onLongClick = onLongClick?.let { handler -> {
                    if (selected != null) haptics.tick() else haptics.longPress()
                    handler()
                } },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art — in selection mode it doubles as the checkbox (a tinted scrim + check when
        // checked, a small hollow badge when not), so the row's layout never shifts as the mode
        // turns on and off. Both overlays sit inside the art's own clip, keeping its rounding.
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))) {
            AsyncImage(
                model             = track.thumbnailUrl,
                contentDescription= track.album?.name,
                contentScale      = ContentScale.Crop,
                modifier          = Modifier.fillMaxSize(),
            )
            when (selected) {
                true  -> Box(
                    modifier         = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Default.Check,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onPrimary,
                        modifier           = Modifier.size(26.dp),
                    )
                }
                false -> Icon(
                    imageVector        = Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.surface,
                    modifier           = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(16.dp),
                )
                null  -> Unit
            }
        }

        Spacer(Modifier.width(12.dp))

        // Title + artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = track.name,
                style    = MaterialTheme.typography.bodyMedium,
                color    = if (isPlaying) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text     = track.allArtists,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Duration
        Text(
            text  = track.durationMs.toTimeString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
        }
    }
}
