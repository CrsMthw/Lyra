package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R

/**
 * The shared OneUI-style detail hero used by the playlist/Liked, album, and artist detail screens
 * (single-pane hero, and the two-pane left panel): the art clipped to a 4-sided cookie shape
 * (bordered, centred), then a row with the [title] + [subtitle] (+ optional [meta] line) on the
 * left and optional shuffle/play cookie buttons on the right. Pass `onShuffle`/`onPlay` = null to
 * omit that button (the artist screen has neither). [meta] is a second, smaller line under the
 * subtitle (the album screen's `year · type · N songs · playtime`). [artContent] renders the art
 * inside the cookie tile (a `fillMaxSize` `AsyncImage`, or a fallback). The title crossfades into
 * the floating title pill as the hero scrolls away (it's the list's item 0).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailArtHero(
    title      : String,
    modifier   : Modifier = Modifier,
    subtitle   : String? = null,
    meta       : String? = null,
    onPlay     : (() -> Unit)? = null,
    onShuffle  : (() -> Unit)? = null,
    artSize    : Dp = 220.dp,
    artContent : @Composable BoxScope.() -> Unit,
) {
    val shuffleLabel = stringResource(R.string.player_shuffle)
    val playLabel    = stringResource(R.string.player_play)
    Column(modifier = modifier.fillMaxWidth()) {
        // Centered cookie art tile.
        Box(
            modifier         = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier         = Modifier
                    .size(artSize)
                    .border(2.dp, MaterialTheme.colorScheme.outline, MaterialShapes.Cookie4Sided.toShape())
                    .clip(MaterialShapes.Cookie4Sided.toShape()),
                contentAlignment = Alignment.Center,
                content          = artContent,
            )
        }
        Spacer(Modifier.height(20.dp))
        // Name + subtitle (left) with shuffle + play to the right.
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = title,
                    style    = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text     = subtitle,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (!meta.isNullOrBlank()) {
                    Text(
                        text     = meta,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (onShuffle != null || onPlay != null) {
                Spacer(Modifier.width(12.dp))
                onShuffle?.let { shuffle ->
                    FilledTonalIconButton(
                        onClick  = shuffle,
                        shape    = MaterialShapes.Clover4Leaf.toShape(),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = shuffleLabel, modifier = Modifier.size(22.dp))
                    }
                }
                if (onShuffle != null && onPlay != null) Spacer(Modifier.width(8.dp))
                onPlay?.let { play ->
                    FilledIconButton(
                        onClick  = play,
                        shape    = MaterialShapes.Cookie6Sided.toShape(),
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = playLabel, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
