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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R

/**
 * The shared detail hero used by the playlist/Liked, album, artist and show detail screens
 * (single-pane hero, and the two-pane left panel): the art clipped to an M3 square shape
 * (bordered, centred), then a row with the [title] + [subtitle] (+ optional [meta] line) on the
 * left and optional shuffle/play cookie buttons on the right. Pass `onShuffle`/`onPlay` = null to
 * omit that button (the artist screen has neither). [meta] is a second, smaller line under the
 * subtitle (the album screen's `year · type · N songs · playtime`). [artContent] renders the art
 * inside the square tile (a `fillMaxSize` `AsyncImage`, or a fallback).
 *
 * The hero is the list's item 0 and scrolls under the screen's [DetailTopBar]; pass that bar's
 * [HeroTitleHandoff] as [titleHandoff] and the bar's own title takes over as this [title] goes
 * under it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DetailArtHero(
    title       : String,
    modifier    : Modifier = Modifier,
    subtitle    : String? = null,
    meta        : String? = null,
    onPlay      : (() -> Unit)? = null,
    onShuffle   : (() -> Unit)? = null,
    artSize     : Dp = 220.dp,
    // Carries the single-pane Library container-transform `sharedBounds` onto the art tile; defaults
    // to a no-op so the album / artist / two-pane heroes stay static.
    artModifier : Modifier = Modifier,
    // Feeds the title's position to the screen's DetailTopBar, which fades its own title in as this
    // one passes under it. Null (the two-pane hero panes, which carry no bar title) reports nothing.
    titleHandoff: HeroTitleHandoff? = null,
    artContent  : @Composable BoxScope.() -> Unit,
) {
    val shuffleLabel = stringResource(R.string.player_shuffle)
    val playLabel    = stringResource(R.string.player_play)
    if (titleHandoff != null) {
        // A lazy list disposes item 0 once it is off screen, and a fling can dispose it before a
        // final position lands — without this the bar title would be stranded part-faded.
        DisposableEffect(titleHandoff) {
            onDispose { titleHandoff.onHeroTitleGone() }
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        // Centered square art tile. The top padding clears the DetailTopBar laid over this hero:
        // the status bar plus the bar's OWN collapsed height plus a small gap, so it is expressed in
        // terms of the thing that is actually above it. Do NOT also add a list contentPadding top
        // inset — that doubles it (docs/UI_PATTERNS.md → Hero clearance). It is a constant, not a
        // fraction of the width, so a narrower window only shrinks the art around it: the art can
        // never ride up under the bar.
        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = TopAppBarDefaults.TopAppBarExpandedHeight + 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val artTileShape = MaterialShapes.Square.toShape()
            Box(
                modifier         = Modifier
                    .then(artModifier)
                    .size(artSize)
                    .border(2.dp, MaterialTheme.colorScheme.outline, artTileShape)
                    .clip(artTileShape),
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
                    // Root coordinates, reported from layout and read by the bar in its draw phase.
                    modifier = if (titleHandoff == null) Modifier else Modifier.onGloballyPositioned {
                        val top = it.positionInRoot().y
                        titleHandoff.onHeroTitleBounds(top, top + it.size.height)
                    },
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
