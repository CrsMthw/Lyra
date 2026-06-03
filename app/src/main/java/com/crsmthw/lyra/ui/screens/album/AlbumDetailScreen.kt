package com.crsmthw.lyra.ui.screens.album

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.AlbumTrack
import com.crsmthw.lyra.data.remote.model.SpotifyAlbumFull
import com.crsmthw.lyra.ui.components.PlayerPanelHost
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.toDurationString
import com.crsmthw.lyra.util.toTimeString

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
    val density        = LocalDensity.current
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
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
        topBar = {
            TopAppBar(
                modifier     = Modifier
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        text     = state.album?.name ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            state.album?.id?.let { id ->
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/album/$id")
                                        type = "text/plain"
                                    }, null
                                ))
                            }
                        },
                        enabled = state.album != null,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.player_share))
                    }
                },
            )
        },
    ) { paddingValues ->
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
                        playerViewModel.playTrack(uri = tracks[0].uri, contextUri = albumUri, index = 0)
                        onRequestPlayer()
                    }
                }
                val onPlayTrack = { track: AlbumTrack, idx: Int ->
                    playerViewModel.playTrack(uri = track.uri, contextUri = albumUri, index = idx)
                    onRequestPlayer()
                }

                if (isWideScreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(8.dp)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Left pane — art + info + play button
                        Card(
                            modifier  = Modifier.weight(0.42f).fillMaxHeight(),
                            shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                            colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val artSize = maxWidth.coerceAtMost(maxHeight * 0.5f)
                                val compact = maxHeight < 500.dp
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = navBarBottomDp),
                                ) {
                                    AlbumHeader(
                                        album         = album,
                                        tracks        = tracks,
                                        modifier      = Modifier
                                            .size(artSize)
                                            .clip(RoundedCornerShape(8.dp)),
                                        compact       = compact,
                                        showDivider   = false,
                                        onPlayAll     = onPlayAll,
                                        onOpenArtist  = onOpenArtist,
                                    )
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
                                LazyColumn(
                                    modifier       = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                                ) {
                                    itemsIndexed(tracks, key = { idx, t -> "track_${t.id}_$idx" }) { idx, track ->
                                        AlbumTrackRow(track = track, onClick = { onPlayTrack(track, idx) })
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
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                    ) {
                        LazyColumn(
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) {
                            item(key = "header") {
                                AlbumHeader(album = album, tracks = tracks, onPlayAll = onPlayAll, onOpenArtist = onOpenArtist)
                            }
                            itemsIndexed(tracks, key = { idx, t -> "track_${t.id}_$idx" }) { idx, track ->
                                AlbumTrackRow(track = track, onClick = { onPlayTrack(track, idx) })
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
                    }
                }
            }
        }
    }
    } // PlayerPanelHost
}

@Composable
private fun AlbumHeader(
    album        : SpotifyAlbumFull,
    tracks       : List<AlbumTrack>,
    modifier     : Modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    compact      : Boolean  = false,
    showDivider  : Boolean  = true,
    onPlayAll    : () -> Unit,
    onOpenArtist : ((artistId: String) -> Unit)? = null,
) {
    Column {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model              = album.artUrl,
                contentDescription = album.name,
                contentScale       = ContentScale.Crop,
                modifier           = modifier,
            )
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text  = album.name,
                style = if (compact) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.titleLarge,
            )
            if (!album.artists.isNullOrEmpty()) {
                val firstArtistId = album.artists.firstOrNull()?.id
                Text(
                    text     = album.artists.joinToString(", ") { it.name },
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onOpenArtist != null && firstArtistId != null)
                        Modifier.clickable { onOpenArtist(firstArtistId) }
                    else Modifier,
                )
            }
            Spacer(Modifier.height(4.dp))
            val totalMs   = tracks.sumOf { it.durationMs }
            val metaParts = listOfNotNull(
                album.releaseYear.takeIf { it.isNotBlank() },
                album.albumTypeDisplay.takeIf { it.isNotBlank() },
                if (tracks.isNotEmpty()) pluralStringResource(R.plurals.album_tracks_count, tracks.size, tracks.size) else null,
                if (totalMs > 0L) totalMs.toDurationString() else null,
            )
            Text(
                text  = metaParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onPlayAll, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.album_play))
            }
        }
        if (showDivider) HorizontalDivider()
    }
}

@Composable
private fun AlbumTrackRow(
    track  : AlbumTrack,
    onClick: () -> Unit,
) {
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
        modifier = Modifier.clickable(onClick = onClick),
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
