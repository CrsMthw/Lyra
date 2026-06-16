package com.crsmthw.lyra.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.screens.player.AddToPlaylistResult
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.reject
import com.crsmthw.lyra.util.toggle

/**
 * Single rendering point for the song touch-and-hold menu. Drop one of these into a screen,
 * bound to that screen's [TrackActionsController]; rows trigger it via `controller.open(target)`.
 *
 * Renders nothing until a target is open. Shows the actions sheet first, then swaps to the shared
 * [AddToPlaylistSheet] when the user picks "Add to playlist". Remove-from-playlist and navigation
 * are screen-supplied because only the screen owns that context; an action whose data is absent on
 * the target (no album/artist id, not removable / no callback) is simply not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsHost(
    controller          : TrackActionsController,
    onGoToAlbum         : (String) -> Unit,
    onGoToArtist        : (String) -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val state       by controller.state.collectAsStateWithLifecycle()
    val pickerState by controller.pickerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val target  = state.target ?: return

    // Mirror PlayerScreen: surface add/remove outcomes as a toast, then clear.
    val addResult = pickerState.addResult
    val addResultMsg = when (addResult) {
        is AddToPlaylistResult.Added          -> stringResource(R.string.player_add_to_playlist_success, addResult.playlistName)
        is AddToPlaylistResult.Removed        -> stringResource(R.string.player_remove_from_playlist_success, addResult.playlistName)
        is AddToPlaylistResult.NeedsReconnect -> stringResource(R.string.player_add_to_playlist_403)
        is AddToPlaylistResult.Error          -> addResult.message ?: stringResource(R.string.error_generic)
        null                                  -> null
    }
    LaunchedEffect(addResult) {
        addResultMsg?.let {
            when (addResult) {
                is AddToPlaylistResult.Added, is AddToPlaylistResult.Removed -> haptics.confirm()
                else -> haptics.reject()
            }
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            controller.clearPickerResult()
        }
    }

    if (state.showPlaylistPicker) {
        AddToPlaylistSheet(
            pickerState = pickerState,
            onSelect    = controller::togglePlaylistTrack,
            onCreateNew = controller::createPlaylist,
            onDismiss   = controller::dismissPlaylistPicker,
        )
        return
    }

    CappedModalBottomSheet(onDismissRequest = controller::dismiss) {
      BoxWithConstraints {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight - sheetTopGap())
                .verticalScroll(rememberScrollState()),
        ) {
        // Header — track art + title/artist
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model              = target.artUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text     = target.name,
                    style    = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text     = target.subtitle,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        ActionItem(
            icon = Icons.Default.LibraryAdd,
            text = stringResource(R.string.player_add_to_playlist),
            onClick = controller::openPlaylistPicker,
        )

        val liked = state.isLiked
        ActionItem(
            icon    = if (liked == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            text    = stringResource(if (liked == true) R.string.track_action_unlike else R.string.track_action_like),
            enabled = liked != null,          // wait for isTrackSaved to resolve before allowing a toggle
            onClick = { haptics.toggle(liked != true); controller.toggleLike() }, // optimistic; label flips in place
        )

        if (target.removable != null && onRemoveFromPlaylist != null) {
            ActionItem(
                icon = Icons.Default.PlaylistRemove,
                text = stringResource(R.string.track_action_remove_from_playlist, target.removable.name),
                onClick = { haptics.confirm(); onRemoveFromPlaylist() },
            )
        }

        target.albumId?.let { albumId ->
            ActionItem(
                icon = Icons.Default.Album,
                text = stringResource(R.string.track_action_go_to_album),
                onClick = { controller.dismiss(); onGoToAlbum(albumId) },
            )
        }

        target.artistId?.let { artistId ->
            ActionItem(
                icon = Icons.Default.Person,
                text = stringResource(R.string.track_action_go_to_artist),
                onClick = { controller.dismiss(); onGoToArtist(artistId) },
            )
        }

        Spacer(Modifier.navigationBarsPadding())
        }
      }
    }
}

@Composable
private fun ActionItem(
    icon    : ImageVector,
    text    : String,
    onClick : () -> Unit,
    enabled : Boolean = true,
) {
    ListItem(
        modifier        = Modifier.clickable(enabled = enabled, onClick = onClick),
        leadingContent  = {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            )
        },
        headlineContent = {
            Text(
                text  = text,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        },
    )
}
