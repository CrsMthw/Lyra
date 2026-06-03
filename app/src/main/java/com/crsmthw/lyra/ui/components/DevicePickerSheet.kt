package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyDevice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DevicePickerSheet(
    isLoading     : Boolean,
    devices       : List<SpotifyDevice>,
    error         : String?,
    onSelectDevice: (deviceId: String) -> Unit,
    onThisDevice  : () -> Unit,
    onDismiss     : () -> Unit,
    onRetry       : () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text     = stringResource(R.string.player_connect_device),
            style    = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        when {
            isLoading -> {
                Box(
                    modifier         = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator()
                }
            }
            error != null -> {
                Column(
                    modifier            = Modifier.fillMaxWidth().height(200.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text  = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.player_device_picker_retry))
                    }
                }
            }
            else -> {
                LazyColumn {
                    item(key = "this_device_card") {
                        ElevatedCard(
                            onClick  = onThisDevice,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier          = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint               = MaterialTheme.colorScheme.primary,
                                    modifier           = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text  = stringResource(R.string.player_this_device),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text  = stringResource(R.string.player_this_device_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (devices.isEmpty()) {
                        item(key = "empty") {
                            Box(
                                modifier         = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text  = stringResource(R.string.player_device_picker_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(devices, key = { it.id ?: it.name }) { device ->
                            DeviceRow(
                                name     = device.name,
                                icon     = deviceTypeIcon(device.type),
                                isActive = device.isActive,
                                enabled  = !device.isRestricted,
                                onClick  = { device.id?.let { onSelectDevice(it) } },
                            )
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    name    : String,
    icon    : ImageVector,
    isActive: Boolean,
    enabled : Boolean,
    onClick : () -> Unit,
) {
    ListItem(
        modifier        = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        leadingContent  = {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = if (isActive) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text  = name,
                color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            )
        },
        trailingContent = {
            RadioButton(
                selected = isActive,
                onClick  = null,
                colors   = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    )
}

private fun deviceTypeIcon(type: String): ImageVector = when (type.lowercase()) {
    "computer"               -> Icons.Default.Computer
    "smartphone"             -> Icons.Default.PhoneAndroid
    "speaker"                -> Icons.Default.Speaker
    "tv"                     -> Icons.Default.Tv
    "castaudio", "castvideo" -> Icons.Default.Cast
    "automobile"             -> Icons.Default.DirectionsCar
    else                     -> Icons.Default.Devices
}
