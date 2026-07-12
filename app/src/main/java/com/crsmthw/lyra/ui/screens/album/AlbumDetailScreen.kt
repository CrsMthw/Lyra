package com.crsmthw.lyra.ui.screens.album

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.AlbumTrack
import com.crsmthw.lyra.data.remote.model.SpotifyAlbumFull
import com.crsmthw.lyra.ui.components.DetailArtHero
import com.crsmthw.lyra.ui.components.PlayerPanelHost
import com.crsmthw.lyra.ui.components.TitlePill
import com.crsmthw.lyra.ui.components.TopActionPill
import com.crsmthw.lyra.ui.components.TopPillHeight
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.components.rememberHeroScrollProgress
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.longPress
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.toggle
import com.crsmthw.lyra.util.toDurationString
import com.crsmthw.lyra.util.toTimeString
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailScreen(
    viewModel             : AlbumDetailViewModel,
    playerViewModel       : PlayerViewModel,
    onBack                : () -> Unit,
    onNavigateToPlayer    : () -> Unit,
    onOpenQueue           : () -> Unit = {},
    onOpenArtist          : ((artistId: String) -> Unit)? = null,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state         by viewModel.uiState.collectAsStateWithLifecycle()
    val context        = LocalContext.current
    val haptics        = LocalHapticFeedback.current
    val density        = LocalDensity.current
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val statusBarTopDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val scrimHeight    = 140.dp
    val background     = MaterialTheme.colorScheme.background
    val isWideScreen   = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)

    PlayerPanelHost(
        playerViewModel          = playerViewModel,
        onOpenPlayer             = onNavigateToPlayer,
        onOpenQueue              = onOpenQueue,
        navSharedTransitionScope = sharedTransitionScope,
        navAnimatedContentScope  = animatedContentScope,
    ) { onRequestPlayer ->
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        // No top app bar in either configuration — both single- and two-pane float their own back /
        // share pills over the hero (a leftover bar here would cover those pills in two-pane).
    ) { paddingValues ->
        Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                Box(
                    modifier         = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) { ContainedLoadingIndicator(modifier = Modifier.size(100.dp)) }
            }
            state.error != null -> {
                Box(
                    modifier         = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) { Text(state.error!!, color = MaterialTheme.colorScheme.error) }
            }
            state.album != null -> {
                val album    = state.album!!
                val tracks   = album.tracks?.items?.filter { it.isPlayable != false } ?: emptyList()
                val albumUri = "spotify:album:${album.id}"

                val onPlayAll: () -> Unit = {
                    if (tracks.isNotEmpty()) {
                        haptics.press()
                        playerViewModel.playTrack(uri = tracks[0].uri, contextUri = albumUri, index = 0)
                        onRequestPlayer()
                    }
                }
                val onPlayTrack = { track: AlbumTrack, idx: Int ->
                    playerViewModel.playTrack(uri = track.uri, contextUri = albumUri, index = idx)
                    onRequestPlayer()
                }
                val onTrackLongPress = { track: AlbumTrack ->
                    viewModel.trackActions.open(track.toTrackActionTarget(album))
                }

                // Metadata line under the artist — year · type · N songs (plural-safe) · playtime.
                // Same `tracks` (isPlayable-filtered) the two-pane list uses, so count/time match.
                val totalMs   = tracks.sumOf { it.durationMs }
                val albumMeta = listOfNotNull(
                    album.releaseYear.takeIf { it.isNotBlank() },
                    album.albumTypeDisplay.takeIf { it.isNotBlank() },
                    if (tracks.isNotEmpty())
                        pluralStringResource(R.plurals.album_tracks_count, tracks.size, tracks.size)
                    else null,
                    if (totalMs > 0L) totalMs.toDurationString() else null,
                ).joinToString(" · ").takeIf { it.isNotBlank() }

                // Shared album art (single-pane hero + two-pane left panel) — cover, else a fallback.
                val albumArt: @Composable BoxScope.() -> Unit = {
                    if (!album.artUrl.isNullOrBlank()) {
                        AsyncImage(
                            model              = album.artUrl,
                            contentDescription = album.name,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.MusicNote, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp))
                        }
                    }
                }

                if (isWideScreen) {
                    // Edge-to-edge under a transparent status bar (like single-pane). The hero's
                    // own `statusBarsPadding()` + the pane TopScrims handle the top inset; no parent
                    // statusBarsPadding (which would leave an opaque band where the bar sits).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Left pane — the detail hero panel (art + name/meta + play/shuffle),
                            // with floating back + share pills and a top scrim (no solid bar).
                            Card(
                                modifier  = Modifier.weight(0.42f).fillMaxHeight(),
                                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(bottom = navBarBottomDp),
                                    ) {
                                        DetailArtHero(
                                            title      = album.name,
                                            subtitle   = album.artists?.joinToString(", ") { it.name },
                                            meta       = albumMeta,
                                            onPlay     = onPlayAll,
                                            onShuffle  = {
                                                haptics.press()
                                                playerViewModel.shuffleContext(albumUri)
                                                onRequestPlayer()
                                            },
                                            artContent = albumArt,
                                        )
                                    }
                                    // Top scrim (drawn under the pills) — fades the hero toward the card.
                                    TopScrim(color = MaterialTheme.colorScheme.surface, modifier = Modifier.align(Alignment.TopCenter))
                                    // Back pill (top-left).
                                    TopActionPill(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .statusBarsPadding()
                                            .padding(start = 12.dp, top = 8.dp),
                                    ) {
                                        IconButton(onClick = { haptics.confirm(); onBack() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = stringResource(R.string.nav_back))
                                        }
                                    }
                                    // Share pill (top-right).
                                    TopActionPill(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .statusBarsPadding()
                                            .padding(end = 12.dp, top = 8.dp),
                                    ) {
                                        IconButton(
                                            onClick = { haptics.toggle(state.isSaved != true); viewModel.toggleSaved() },
                                            enabled = state.isSaved != null,
                                        ) {
                                            Icon(
                                                imageVector        = if (state.isSaved == true) Icons.Default.Favorite
                                                                                 else Icons.Default.FavoriteBorder,
                                                contentDescription = stringResource(
                                                    if (state.isSaved == true) R.string.cd_unfollow else R.string.cd_follow),
                                            )
                                        }
                                        IconButton(onClick = {
                                            haptics.press()
                                            context.startActivity(Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/album/${album.id}")
                                                    type = "text/plain"
                                                }, null
                                            ))
                                        }) {
                                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.player_share))
                                        }
                                    }
                                }
                            }

                            // Right pane — track list
                            Card(
                                modifier  = Modifier.weight(0.58f).fillMaxHeight(),
                                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val tracksListState = rememberLazyListState()
                                    ListScrollHaptics(tracksListState)
                                    LazyColumn(
                                        state          = tracksListState,
                                        modifier       = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(top = statusBarTopDp, bottom = 100.dp + navBarBottomDp),
                                    ) {
                                        itemsIndexed(tracks, key = { idx, t -> "track_${t.id}_$idx" }) { idx, track ->
                                            AlbumTrackRow(track = track, onClick = { onPlayTrack(track, idx) }, onLongClick = { onTrackLongPress(track) })
                                        }
                                        if (!album.label.isNullOrBlank() || album.copyrights?.isNotEmpty() == true) {
                                            item(key = "footer") { AlbumFooter(album = album) }
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(scrimHeight)
                                            .align(Alignment.BottomCenter)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
                                    )
                                    // Top scrim — fades tracks under the transparent status bar.
                                    TopScrim(color = MaterialTheme.colorScheme.surface, modifier = Modifier.align(Alignment.TopCenter))
                                }
                            }
                        }
                        FftWaveCanvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(scrimHeight)
                                .align(Alignment.BottomCenter),
                            color    = LocalVisualizerAccentColor.current,
                            alpha    = 0.20f,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                    ) {
                        val tracksListState = rememberLazyListState()
                        ListScrollHaptics(tracksListState)
                        val titlePillAlpha = rememberHeroScrollProgress(tracksListState)
                        LazyColumn(
                            state          = tracksListState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) {
                            item(key = "header") {
                                DetailArtHero(
                                    title      = album.name,
                                    subtitle   = album.artists?.joinToString(", ") { it.name },
                                    meta       = albumMeta,
                                    onPlay     = onPlayAll,
                                    onShuffle  = {
                                        haptics.press()
                                        playerViewModel.shuffleContext(albumUri)
                                        onRequestPlayer()
                                    },
                                    artContent = albumArt,
                                )
                            }
                            itemsIndexed(tracks, key = { idx, t -> "track_${t.id}_$idx" }) { idx, track ->
                                AlbumTrackRow(track = track, onClick = { onPlayTrack(track, idx) }, onLongClick = { onTrackLongPress(track) })
                            }
                            if (!album.label.isNullOrBlank() || album.copyrights?.isNotEmpty() == true) {
                                item(key = "footer") { AlbumFooter(album = album) }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(scrimHeight)
                                .align(Alignment.BottomCenter)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, background)))
                        )
                        FftWaveCanvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(scrimHeight)
                                .align(Alignment.BottomCenter),
                            color    = LocalVisualizerAccentColor.current,
                            alpha    = 0.20f,
                        )

                        // Top scrim — fades the album art under the status bar.
                        TopScrim(color = background, modifier = Modifier.align(Alignment.TopCenter))

                        // Album-name title pill — fades in as the art scrolls away, sitting just
                        // right of the screen-level back pill.
                        TitlePill(
                            text     = album.name,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(start = 16.dp + TopPillHeight + 8.dp, top = 8.dp)
                                .widthIn(max = 220.dp)
                                .graphicsLayer { alpha = titlePillAlpha.value },
                        )

                        // Share pill (top-right).
                        TopActionPill(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(end = 16.dp, top = 8.dp),
                        ) {
                            IconButton(
                                onClick = { haptics.toggle(state.isSaved != true); viewModel.toggleSaved() },
                                enabled = state.isSaved != null,
                            ) {
                                Icon(
                                    imageVector        = if (state.isSaved == true) Icons.Default.Favorite
                                                         else Icons.Default.FavoriteBorder,
                                    contentDescription = stringResource(
                                        if (state.isSaved == true) R.string.cd_unfollow else R.string.cd_follow),
                                )
                            }
                            IconButton(onClick = {
                                haptics.press()
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/album/${album.id}")
                                        type = "text/plain"
                                    }, null
                                ))
                            }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.player_share))
                            }
                        }
                    }
                }
            }
        }

            // Screen-level back pill. Single-pane: always (loading/error/content). Two-pane: only
            // while loading/erroring — once the album loads, the left-pane hero carries its own back.
            // In two-pane it must land exactly where that left-pane pill will (inside the Row's 8dp
            // inset + the pill's own 12/8dp) so it doesn't jump when the content loads in.
            if (!isWideScreen || state.album == null) {
                TopActionPill(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                        .padding(
                            start = if (isWideScreen) 8.dp + 12.dp else 16.dp,
                            top   = if (isWideScreen) 8.dp + 8.dp  else 8.dp,
                        ),
                ) {
                    IconButton(onClick = { haptics.confirm(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back))
                    }
                }
            }
        }
    }
    } // PlayerPanelHost

    TrackActionsHost(
        controller   = viewModel.trackActions,
        onGoToAlbum  = {},   // album rows never expose this — we're already on the album
        onGoToArtist = { id -> onOpenArtist?.invoke(id) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    track      : AlbumTrack,
    onClick    : () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    ListItem(
        leadingContent = {
            Text(
                text     = "${track.trackNumber}",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
        },
        headlineContent = {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text     = track.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.explicit) ExplicitBadge()
            }
        },
        supportingContent = {
            Text(
                text     = track.allArtists,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Text(
                text  = track.durationMs.toTimeString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.combinedClickable(
            onClick     = { haptics.confirm(); onClick() },
            onLongClick = onLongClick?.let { handler -> {
                haptics.longPress()
                handler()
            } },
        ),
    )
}

@Composable
private fun ExplicitBadge() {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        shape = RoundedCornerShape(2.dp),
    ) {
        Text(
            text     = "E",
            style    = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun AlbumFooter(album: SpotifyAlbumFull) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        album.copyrights?.forEach { copyright ->
            Text(
                text  = copyright.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!album.label.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = album.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
