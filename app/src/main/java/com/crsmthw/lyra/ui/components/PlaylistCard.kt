package com.crsmthw.lyra.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import java.io.File

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlaylistCard(
    playlist    : SpotifyPlaylist,
    mosaicFile  : File?,
    onClick     : () -> Unit,
    modifier    : Modifier = Modifier,
    sharedScope : SharedTransitionScope? = null,   // non-null only in single-pane library (container transform)
    animScope   : AnimatedContentScope? = null,
) {
    val imageShape = RoundedCornerShape(8.dp)
    val artMod = if (sharedScope != null && animScope != null) {
        with(sharedScope) {
            Modifier.sharedElement(
                sharedContentState      = rememberSharedContentState(key = "lib-art-${playlist.id}"),
                animatedVisibilityScope = animScope,
                boundsTransform         = rememberArtBoundsTransform(),
            )
        }
    } else Modifier

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
                    .then(artMod)
                    .clip(imageShape),
            )
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .then(artMod)
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
