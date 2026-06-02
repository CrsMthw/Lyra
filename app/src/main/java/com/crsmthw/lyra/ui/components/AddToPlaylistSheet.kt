package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.ui.screens.player.PlaylistPickerState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddToPlaylistSheet(
    pickerState: PlaylistPickerState,
    onSelect   : (SpotifyPlaylist) -> Unit,
    onDismiss  : () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text     = stringResource(R.string.player_add_to_playlist_title),
            style    = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        when {
            pickerState.isLoading -> {
                Box(
                    modifier         = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator()
                }
            }
            pickerState.playlists.isEmpty() -> {
                Box(
                    modifier         = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = stringResource(R.string.player_add_to_playlist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn {
                    items(pickerState.playlists, key = { it.id }) { playlist ->
                        val isChecked = playlist.id in pickerState.containingPlaylistIds
                        ListItem(
                            leadingContent  = { PlaylistThumbnail(playlist.thumbnailUrl) },
                            headlineContent = {
                                Text(
                                    text     = playlist.name,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Checkbox(
                                    checked         = isChecked,
                                    onCheckedChange = null,
                                )
                            },
                            modifier = Modifier.clickable { onSelect(playlist) },
                        )
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistThumbnail(url: String) {
    val shape = RoundedCornerShape(6.dp)
    if (url.isNotBlank()) {
        AsyncImage(
            model              = url,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.size(44.dp).clip(shape),
        )
    } else {
        Box(
            modifier         = Modifier
                .size(44.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.MusicNote,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier           = Modifier.size(22.dp),
            )
        }
    }
}

