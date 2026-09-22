package com.crsmthw.lyra.ui.screens.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.ui.components.BarContentGap
import com.crsmthw.lyra.ui.components.RootTopBar
import com.crsmthw.lyra.ui.components.rememberRootTopBarScrollBehavior
import com.crsmthw.lyra.ui.components.TopBarFade
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.horizontalSystemBarsPadding
import com.crsmthw.lyra.util.longPress
import com.crsmthw.lyra.util.toTimeString
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor
import kotlinx.coroutines.delay

/**
 * The play queue — what is playing now, and what is next.
 *
 * Chrome: the shared [RootTopBar] (a large flexible `queue_title` bar that compresses to the small
 * bar as the list scrolls and stays small until it is back at the top, or a small pinned bar on a
 * pane under 600dp tall), with back as its `navigationIcon`. It is a `Column` sibling of the list,
 * not an overlay, so the list needs no top inset — the bar owns the status-bar strip via
 * `appBarWindowInsets` — and the rows move up as the bar collapses. It replaced the 300dp hero
 * band, the `TopScrim` and the floating back/title pills.
 *
 * Used on TWO surfaces with the same code: the `Screen.Queue` route (back pops the destination) and
 * the docked player pane's in-place queue on ≥1200dp windows (back returns to the player). Both
 * differ only in [onBack], which the bar's back arrow calls.
 *
 * The bottom scrim and the visualizer wave live INSIDE the weighted Box, so they overlay the list
 * and never the bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueScreen(
    viewModel   : QueueViewModel,
    onBack      : () -> Unit,
    onOpenAlbum : (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Hoisted at screen level (`rememberTopAppBarState` is `rememberSaveable`) so the bar's collapse
    // survives navigating away to an album / artist and back.
    val barState = rememberTopAppBarState()

    // 30-second catch-all refresh while the screen is visible
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            viewModel.refresh()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        val density       = LocalDensity.current
        val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val scrimHeight    = navBarBottomDp + 48.dp
        // `BarContentGap` on top is the gap under the app bar (device pass #22) — the list's own
        // contentPadding, not an inset, so it scrolls away with the first row and nothing
        // double-pads the collapsing bar (whose measured height already owns the strip above it).
        val listContentPadding = remember(navBarBottomDp) {
            PaddingValues(top = BarContentGap, bottom = navBarBottomDp + 16.dp)
        }
        val haptics        = LocalHapticFeedback.current
        val queueListState = rememberLazyListState()
        ListScrollHaptics(queueListState)

        // Horizontal system-bar inset, applied ONCE here on the outermost content container — with
        // 3-button navigation the nav bar sits on a SIDE edge in landscape (either one, depending
        // on the rotation direction), and the app bar's own row plus the queue rows would otherwise
        // run underneath it. The bar takes `appBarWindowInsets` (the status bar's top edge) and
        // nothing else, so the horizontal term is applied exactly once, here. Everything below
        // inherits it; the scrims are decoration.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .horizontalSystemBarsPadding(),
        ) {
            val scrollBehavior = rememberRootTopBarScrollBehavior(barState)
            RootTopBar(
                title          = stringResource(R.string.queue_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { haptics.confirm(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back))
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.isLoading -> {
                        ContainedLoadingIndicator(modifier = Modifier.size(100.dp).align(Alignment.Center))
                    }
                    state.error != null && state.currentlyPlaying == null && state.queue.isEmpty() -> {
                        Column(
                            modifier            = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                modifier           = Modifier.size(56.dp),
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text  = stringResource(R.string.error_generic),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.currentlyPlaying == null && state.queue.isEmpty() -> {
                        Column(
                            modifier            = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                modifier           = Modifier.size(56.dp),
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text  = stringResource(R.string.queue_nothing_playing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            state          = queueListState,
                            // The bar's connection goes on the list's OWN modifier (`LazyList` places
                            // the caller's modifier as the parent node of its scrollable), which is
                            // what lets `ExitUntilCollapsedScrollBehavior` see the positive leftover of
                            // a downward scroll and re-expand. No top INSET: the bar is a Column
                            // sibling whose measured height shrinks as it collapses, so one here
                            // would double it. The `BarContentGap` in `listContentPadding` is not an
                            // inset — it is the gap under the bar, and it scrolls away.
                            modifier       = Modifier
                                .fillMaxSize()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = listContentPadding,
                        ) {
                            // ── Now Playing ──────────────────────────────────────────
                            state.currentlyPlaying?.let { track ->
                                item(key = "header_now_playing") {
                                    Text(
                                        text     = stringResource(R.string.queue_now_playing).uppercase(),
                                        style    = MaterialTheme.typography.labelMedium,
                                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                                    )
                                }
                                item(key = "now_playing_${track.id}") {
                                    NowPlayingCard(
                                        track       = track,
                                        // No touch-and-hold menu on a podcast episode: every row in
                                        // TrackActionsSheet (like, add to playlist, go to album, go
                                        // to artist) addresses a track-only endpoint or an object an
                                        // episode does not have. A null handler disables it.
                                        onLongClick = if (track.isEpisode) null else {
                                            { viewModel.trackActions.open(track.toTrackActionTarget()) }
                                        },
                                    )
                                }
                            }

                            // ── Divider ──────────────────────────────────────────────
                            item(key = "divider") {
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                Spacer(Modifier.height(8.dp))
                            }

                            // ── Next Up ──────────────────────────────────────────────
                            if (state.queue.isEmpty()) {
                                item(key = "empty") {
                                    Column(
                                        modifier            = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 40.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Icon(
                                            imageVector        = Icons.AutoMirrored.Filled.QueueMusic,
                                            contentDescription = null,
                                            modifier           = Modifier.size(48.dp),
                                            tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text  = stringResource(R.string.queue_empty),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                item(key = "header_next_up") {
                                    Text(
                                        text     = stringResource(R.string.queue_next_up).uppercase(),
                                        style    = MaterialTheme.typography.labelMedium,
                                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                                    )
                                }
                                items(
                                    items = state.queue,
                                    key   = { "${it.uri}_${it.id}" },
                                ) { track ->
                                    QueueTrackItem(
                                        track       = track,
                                        // See NowPlayingCard above — episodes get no actions sheet.
                                        onLongClick = if (track.isEpisode) null else {
                                            { viewModel.trackActions.open(track.toTrackActionTarget()) }
                                        },
                                    )
                                }
                            }

                        }
                    }
                }

                // The seam under the bar: the background fading out over the first rows, so they
                // dissolve into the bar instead of sliding past its title — the same strip the
                // Library browser has under its tab row. Top-anchored, because this Box's own top
                // edge already tracks the bar's collapse (the bar is a Column sibling whose
                // MEASURED height shrinks). Composed after the content so it draws over the list;
                // no pointer input, so it cannot eat a drag on the rows beneath it.
                TopBarFade(
                    paneColor = MaterialTheme.colorScheme.background,
                    modifier  = Modifier.align(Alignment.TopCenter),
                )

                // Bottom scrim — fades list content toward background so the nav bar area is clean.
                // Inside the weighted Box, so it overlays the list and never the bar.
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
                FftWaveCanvas(
                    modifier = Modifier.fillMaxWidth().height(scrimHeight).align(Alignment.BottomCenter),
                    color    = LocalVisualizerAccentColor.current,
                    alpha    = 0.20f,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingCard(track: SpotifyTrack, onLongClick: (() -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .combinedClickable(
                onClick     = {},
                onLongClick = onLongClick?.let { handler -> {
                    haptics.longPress()
                    handler()
                } },
            ),
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model              = track.artUrl,
                contentDescription = track.album?.name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = track.name,
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text     = track.primaryArtist,
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                track.album?.name?.takeIf { it.isNotBlank() }?.let { albumName ->
                    Text(
                        text     = albumName,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueTrackItem(track: SpotifyTrack, onLongClick: (() -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick     = {},
                onLongClick = onLongClick?.let { handler -> {
                    haptics.longPress()
                    handler()
                } },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model              = track.thumbnailUrl,
            contentDescription = track.album?.name,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = track.name,
                style    = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text     = track.primaryArtist,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text  = track.durationMs.toTimeString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
