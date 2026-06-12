package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import java.io.File

@Composable
fun PlaylistCard(
    playlist    : SpotifyPlaylist,
    mosaicFile  : File?,
    onClick     : () -> Unit,
    modifier    : Modifier = Modifier,
) {
    val imageShape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier
            .width(140.dp)
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp),
    ) {
        val imageModel = mosaicFile ?: playlist.thumbnailUrl.takeIf { it.isNotBlank() }
        if (imageModel != null) {
            AsyncImage(
                model              = imageModel,
                contentDescription = playlist.name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(imageShape),
            )
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(imageShape),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text     = playlist.name,
            style    = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color    = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text  = "${playlist.trackCount} tracks",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
