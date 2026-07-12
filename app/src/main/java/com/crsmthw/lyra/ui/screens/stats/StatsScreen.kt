package com.crsmthw.lyra.ui.screens.stats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.ui.components.ConnectedChoiceRow
import com.crsmthw.lyra.ui.components.HeroBandHeight
import com.crsmthw.lyra.ui.components.TitlePill
import com.crsmthw.lyra.ui.components.TopActionPill
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.components.rememberHeroScrollProgress
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.longPress
import com.crsmthw.lyra.util.press

/**
 * "Wrapped-lite" listening stats: top artists (circle row) + top tracks (ranked list) from
 * `/me/top/{type}`, with a connected time-range picker (4 weeks / 6 months / all time).
 * Same OneUI floating-controls chrome as Queue: hero title + TopScrim + back/title pills.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatsScreen(
    viewModel       : StatsViewModel,
    playerViewModel : PlayerViewModel,
    onBack          : () -> Unit,
    onOpenAlbum     : (String) -> Unit = {},
    onOpenArtist    : (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        val density        = LocalDensity.current
        val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val scrimHeight    = navBarBottomDp + 48.dp
        val listBottomPad  = remember(navBarBottomDp) { PaddingValues(bottom = navBarBottomDp + 16.dp) }
        val listState      = rememberLazyListState()
        ListScrollHaptics(listState)
        val titlePillAlpha = rememberHeroScrollProgress(listState)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LazyColumn(
                state          = listState,
                modifier       = Modifier.fillMaxSize(),
                contentPadding = listBottomPad,
            ) {
                item(key = "hero") {
                    Box(
                        modifier         = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(HeroBandHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            text     = stringResource(R.string.stats_title),
                            style    = MaterialTheme.typography.displayMedium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
                item(key = "range_picker") {
                    val options = listOf(
                        StatsTimeRange.SHORT  to stringResource(R.string.stats_range_short),
                        StatsTimeRange.MEDIUM to stringResource(R.string.stats_range_medium),
                        StatsTimeRange.LONG   to stringResource(R.string.stats_range_long),
                    )
                    ConnectedChoiceRow(
                        options  = options,
                        selected = state.range,
                        onSelect = viewModel::setRange,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    )
                }

                when {
                    state.showLoading -> item(key = "loading") {
                        Box(
                            modifier         = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) { ContainedLoadingIndicator() }
                    }
                    state.error != null && state.range !in state.data -> item(key = "error") {
                        Column(
                            modifier            = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text      = state.error ?: stringResource(R.string.error_generic),
                                style     = MaterialTheme.typography.bodyMedium,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier  = Modifier.padding(horizontal = 32.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { haptics.press(); viewModel.retry() }) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                    state.current.topArtists.isEmpty() && state.current.topTracks.isEmpty() -> item(key = "empty") {
                        Column(
                            modifier            = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Insights,
                                contentDescription = null,
                                modifier           = Modifier.size(48.dp),
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text  = stringResource(R.string.stats_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        if (state.current.topArtists.isNotEmpty()) {
                            item(key = "artists_header") {
                                Text(
                                    text     = stringResource(R.string.stats_top_artists).uppercase(),
                                    style    = MaterialTheme.typography.labelMedium,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                                )
                            }
                            item(key = "artists_row_${state.range}") {
                                LazyRow(
                                    contentPadding        = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    itemsIndexed(state.current.topArtists, key = { _, a -> a.id }) { idx, artist ->
                                        TopArtistTile(
                                            artist  = artist,
                                            rank    = idx + 1,
                                            onClick = { haptics.confirm(); onOpenArtist(artist.id) },
                                        )
                                    }
                                }
                            }
                        }
                        if (state.current.topTracks.isNotEmpty()) {
                            item(key = "tracks_header") {
                                Text(
                                    text     = stringResource(R.string.stats_top_tracks).uppercase(),
                                    style    = MaterialTheme.typography.labelMedium,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                                )
                            }
                            itemsIndexed(state.current.topTracks, key = { _, t -> "${state.range}_${t.id}" }) { idx, track ->
                                TopTrackRow(
                                    track       = track,
                                    rank        = idx + 1,
                                    onClick     = {
                                        haptics.confirm()
                                        // Proven play path (state refresh + wake/404 fallback).
                                        playerViewModel.playTrack(
                                            uri  = track.uri,
                                            uris = state.current.topTracks.drop(idx).map { it.uri },
                                        )
                                    },
                                    onLongClick = { viewModel.trackActions.open(track.toTrackActionTarget()) },
                                )
                            }
                        }
                    }
                }
            }

            // Bottom scrim — fades list content toward background so the nav bar area is clean.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scrimHeight)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        )
                    )
            )

            // Top scrim — fades content under the status bar.
            TopScrim(
                color    = MaterialTheme.colorScheme.background,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // Floating back pill + title pill (fades in once the hero scrolls away).
            Row(
                modifier              = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TopActionPill {
                    IconButton(onClick = { haptics.confirm(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back))
                    }
                }
                TitlePill(
                    text     = stringResource(R.string.stats_title),
                    modifier = Modifier.graphicsLayer { alpha = titlePillAlpha.value },
                )
            }
        }
    }

    TrackActionsHost(
        controller   = viewModel.trackActions,
        onGoToAlbum  = onOpenAlbum,
        onGoToArtist = onOpenArtist,
    )
}

/** One top artist: rank-badged circular art with the name below. */
@Composable
private fun TopArtistTile(
    artist  : SpotifyArtist,
    rank    : Int,
    onClick : () -> Unit,
) {
    Column(
        modifier            = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            val artUrl = artist.images?.firstOrNull()?.url
            if (artUrl != null) {
                AsyncImage(
                    model              = artUrl,
                    contentDescription = artist.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(72.dp).clip(CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Rank badge — bottom-start of the circle.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = "$rank",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text      = artist.name,
            style     = MaterialTheme.typography.labelMedium,
            maxLines  = 1,
            overflow  = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** One ranked top track row. Tap plays from here; hold opens the song menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopTrackRow(
    track       : SpotifyTrack,
    rank        : Int,
    onClick     : () -> Unit,
    onLongClick : () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick     = onClick,
                onLongClick = { haptics.longPress(); onLongClick() },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = "$rank",
            style    = MaterialTheme.typography.titleMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        if (track.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model              = track.thumbnailUrl,
                contentDescription = track.name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = track.name,
                style    = MaterialTheme.typography.bodyLarge,
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
    }
}
