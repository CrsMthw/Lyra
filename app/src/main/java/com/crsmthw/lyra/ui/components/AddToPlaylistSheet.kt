package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.ui.screens.player.PlaylistPickerState
import com.crsmthw.lyra.util.toggle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddToPlaylistSheet(
    pickerState : PlaylistPickerState,
    onSelect    : (SpotifyPlaylist) -> Unit,
    onCreateNew : (name: String, description: String, isPublic: Boolean) -> Unit,
    onDismiss   : () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName          by remember { mutableStateOf("") }
    var newDescription   by remember { mutableStateOf("") }
    var newIsPublic      by remember { mutableStateOf(false) }
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(pickerState.addResult) {
        if (showCreateDialog && pickerState.addResult != null) {
            showCreateDialog = false
        }
    }

    CappedModalBottomSheet(onDismissRequest = onDismiss) {
      BoxWithConstraints {
        Column(modifier = Modifier.heightIn(max = maxHeight - sheetTopGap())) {
            PlaylistList(
                pickerState = pickerState,
                onSelect    = onSelect,
                onCreateNew = {
                    newName          = ""
                    newDescription   = ""
                    newIsPublic      = false
                    showCreateDialog = true
                },
            )
        }
      }
    }

    if (showCreateDialog) {
        BasicAlertDialog(
            onDismissRequest = { if (!pickerState.isCreatingPlaylist) showCreateDialog = false },
            properties       = DialogProperties(decorFitsSystemWindows = false),
        ) {
            Surface(
                shape          = AlertDialogDefaults.shape,
                color          = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                modifier       = Modifier.fillMaxWidth().imePadding(),
            ) {
                CreateForm(
                    name                = newName,
                    onNameChange        = { newName = it },
                    description         = newDescription,
                    onDescriptionChange = { newDescription = it },
                    isPublic            = newIsPublic,
                    onIsPublicChange    = { newIsPublic = it },
                    isCreating          = pickerState.isCreatingPlaylist,
                    error               = pickerState.createPlaylistError,
                    nameFocusRequester  = nameFocusRequester,
                    onBack              = { showCreateDialog = false },
                    onCreate            = { onCreateNew(newName.trim(), newDescription.trim(), newIsPublic) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.PlaylistList(
    pickerState : PlaylistPickerState,
    onSelect    : (SpotifyPlaylist) -> Unit,
    onCreateNew : () -> Unit,
) {
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
        else -> {
            // weight(fill = false): scrolls within the capped column when long, wraps when short.
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                item {
                    ListItem(
                        leadingContent  = {
                            Box(
                                modifier         = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.Add,
                                    contentDescription = null,
                                    tint               = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier           = Modifier.size(22.dp),
                                )
                            }
                        },
                        headlineContent = {
                            Text(stringResource(R.string.create_playlist_title))
                        },
                        modifier = Modifier.clickable(onClick = onCreateNew),
                    )
                }
                if (pickerState.playlists.isEmpty()) {
                    item {
                        Box(
                            modifier         = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text  = stringResource(R.string.player_add_to_playlist_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
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
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun CreateForm(
    name               : String,
    onNameChange       : (String) -> Unit,
    description        : String,
    onDescriptionChange: (String) -> Unit,
    isPublic           : Boolean,
    onIsPublicChange   : (Boolean) -> Unit,
    isCreating         : Boolean,
    error              : String?,
    nameFocusRequester : FocusRequester,
    onBack             : () -> Unit,
    onCreate           : () -> Unit,
) {
    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack, enabled = !isCreating) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
            Text(
                text  = stringResource(R.string.create_playlist_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value         = name,
            onValueChange = onNameChange,
            label         = { Text(stringResource(R.string.create_playlist_name_label)) },
            placeholder   = { Text(stringResource(R.string.create_playlist_name_placeholder)) },
            singleLine    = true,
            enabled       = !isCreating,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction      = ImeAction.Next,
            ),
            modifier      = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value         = description,
            onValueChange = onDescriptionChange,
            label         = { Text(stringResource(R.string.create_playlist_description_label)) },
            placeholder   = { Text(stringResource(R.string.create_playlist_description_placeholder)) },
            singleLine    = true,
            enabled       = !isCreating,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank() && !isCreating) onCreate() }),
            modifier      = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.fillMaxWidth(),
        ) {
            Text(
                text     = stringResource(R.string.create_playlist_public_label),
                style    = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            val haptics = LocalHapticFeedback.current
            Switch(
                checked         = isPublic,
                onCheckedChange = { enabled -> haptics.toggle(enabled); onIsPublicChange(enabled) },
                enabled         = !isCreating,
            )
        }

        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = stringResource(R.string.create_playlist_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick  = onCreate,
            enabled  = name.isNotBlank() && !isCreating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isCreating) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color       = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.create_playlist_button))
            }
        }

        Spacer(Modifier.height(24.dp))
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
