package com.crsmthw.lyra.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Share
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
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.reject
import com.crsmthw.lyra.util.toggle

/**
 * Single rendering point for the song touch-and-hold menu. Drop one of these into a screen,
 * bound to that screen's [TrackActionsController]; rows trigger it via `controller.open(target)`.
 *
 * Renders nothing until a target is open. Shows the actions sheet first, then swaps to the shared
 * [AddToPlaylistSheet] when the user picks "Add to playlist". Remove-from-playlist, multi-select
 * and navigation are screen-supplied because only the screen owns that context; an action whose
 * data is absent on the target (no album/artist id, not removable / no callback) is simply not shown.
 *
 * [onSelect] is the touch-and-hold front door to multi-select removal. It is offered on the same
 * condition as "Remove from <playlist>" — the row lives in an OWNED playlist — and only the Library
 * supplies it, so the row is absent on every other screen. The sheet animates itself away first and
 * hands the target over, so the caller can enter selection mode with that track pre-checked.
 *
 * **Every action that closes the sheet goes through [LocalSheetDismissal]**, whose `then` runs once
 * the sheet is down: the sheet slides away, *then* the screen navigates / removes / shares. Calling
 * `controller.dismiss()` from a row instead would null the target in the same frame, dropping the
 * sheet's window out of composition with no hide animation at all. The three rows that do NOT
 * close the sheet (Add to playlist — a sheet swap — plus Add to queue and the like toggle, which
 * act in place) are instead gated on `!dismissal.isHiding`, because a row acting mid-hide bypasses
 * that handle's latch as well as its animation.
 *
 * Residual worth knowing: a user drag that cancels a programmatic hide and springs the sheet back
 * to Expanded re-enables every row, but `LaunchedEffect(queueResult)` does not re-fire, so after a
 * successful "Add to queue" the sheet simply stays open with that row still inert. Benign, and far
 * better than the alternative — before the cancellation fix the whole sheet went inert.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsHost(
    controller          : TrackActionsController,
    onGoToAlbum         : (String) -> Unit,
    onGoToArtist        : (String) -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onSelect            : ((TrackActionTarget) -> Unit)? = null,
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

    // Add-to-queue outcome: toast + haptic, then close the sheet (mirrors the add flow above). The
    // effect that consumes it sits INSIDE the sheet content below, where [LocalSheetDismissal] is
    // provided, so the close animates instead of the sheet blinking out from under the toast.
    val queueResult = state.queueResult
    val queueResultMsg = when (queueResult) {
        true  -> stringResource(R.string.track_action_queued)
        false -> stringResource(R.string.track_action_queue_failed)
        null  -> null
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
      // Closing the sheet from a row goes through this, never straight to controller.dismiss():
      // nulling the target would drop the ModalBottomSheet out of composition with no hide
      // animation, leaving the destination to slide in under a sheet that then pops out of
      // existence. `then` runs after the sheet is down and BEFORE onDismissRequest — see
      // [SheetDismissal.dismiss].
      val dismissal = LocalSheetDismissal.current

      LaunchedEffect(queueResult) {
          queueResultMsg?.let {
              if (queueResult == true) haptics.confirm() else haptics.reject()
              Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
              dismissal.dismiss()
          }
      }

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

        // The three rows below act WITHOUT going through `dismissal`, so they bypass its one-hide
        // latch as well as its animation — hence `!dismissal.isHiding` on each. The rows stay on
        // screen and tappable for the whole hide, so a tap there acts on a sheet already on its way
        // out. Rows that DO go through `dismissal.dismiss` need no gate: the latch makes a second
        // tap a no-op.
        //
        // Add to playlist is deliberately NOT routed through `dismissal`: it is a sheet SWAP, not a
        // dismissal. The picker still needs `state.target`, which onDismissRequest
        // (controller::dismiss) clears, so the two sheets keep exchanging places in one frame — the
        // incoming picker's own entrance covers the swap, and the scrim never drops. That swap is
        // also the row that actually broke: tapping it mid-hide disposed THIS sheet and cancelled
        // the hide job, whose completion handler then cleared the picker state before the picker
        // had rendered (fixed on both ends — see [SheetDismissal]).
        ActionItem(
            icon    = Icons.Default.LibraryAdd,
            text    = stringResource(R.string.player_add_to_playlist),
            enabled = !dismissal.isHiding,
            onClick = controller::openPlaylistPicker,
        )

        ActionItem(
            icon    = Icons.AutoMirrored.Filled.QueueMusic,
            text    = stringResource(R.string.track_action_add_to_queue),
            // Also inert once the result has landed: the row now stays on screen for the length of
            // the hide animation, and a second tap there would queue the track twice.
            enabled = !state.isQueueing && queueResult == null && !dismissal.isHiding,
            onClick = controller::addToQueue,
        )

        val liked = state.isLiked
        ActionItem(
            icon    = if (liked == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            text    = stringResource(if (liked == true) R.string.track_action_unlike else R.string.track_action_like),
            // `liked != null` waits for isTrackSaved to resolve before allowing a toggle.
            enabled = liked != null && !dismissal.isHiding,
            onClick = { haptics.toggle(liked != true); controller.toggleLike() }, // optimistic; label flips in place
        )

        if (target.removable != null && onSelect != null) {
            ActionItem(
                icon    = Icons.Default.Checklist,
                text    = stringResource(R.string.track_action_select),
                onClick = { haptics.press(); dismissal.dismiss { onSelect(target) } },
            )
        }

        if (target.removable != null && onRemoveFromPlaylist != null) {
            ActionItem(
                icon = Icons.Default.PlaylistRemove,
                text = stringResource(R.string.track_action_remove_from_playlist, target.removable.name),
                // The screen's remover reads the controller's target synchronously, so it MUST run
                // before onDismissRequest clears it — that is exactly `then`'s slot.
                onClick = { haptics.confirm(); dismissal.dismiss { onRemoveFromPlaylist() } },
            )
        }

        target.albumId?.let { albumId ->
            ActionItem(
                icon = Icons.Default.Album,
                text = stringResource(R.string.track_action_go_to_album),
                // Navigate only once the sheet is down, so the detail screen pushes over a clean
                // playlist instead of loading in behind an open sheet.
                onClick = { dismissal.dismiss { onGoToAlbum(albumId) } },
            )
        }

        target.artistId?.let { artistId ->
            ActionItem(
                icon = Icons.Default.Person,
                text = stringResource(R.string.track_action_go_to_artist),
                onClick = { dismissal.dismiss { onGoToArtist(artistId) } },
            )
        }

        // Hidden, not inert, for an item with no open.spotify.com page (a local file) — this menu
        // already shows "Go to album" / "Go to artist" only when they lead somewhere.
        target.shareUrl?.let { url ->
            ActionItem(
                icon = Icons.Default.Share,
                text = stringResource(R.string.track_action_share),
                onClick = {
                    dismissal.dismiss {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, url)
                                type = "text/plain"
                            }, null,
                        ))
                    }
                },
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
        content         = {
            Text(
                text  = text,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        },
    )
}
