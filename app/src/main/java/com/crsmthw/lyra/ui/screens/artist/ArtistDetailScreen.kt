package com.crsmthw.lyra.ui.screens.artist

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtistFull
import com.crsmthw.lyra.ui.components.PlayerPanelHost
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistDetailScreen(
    viewModel             : ArtistDetailViewModel,
    playerViewModel       : PlayerViewModel,
    onBack                : () -> Unit,
    onOpenAlbum           : (albumId: String) -> Unit,
    onOpenPlayer          : () -> Unit = {},
    onOpenQueue           : () -> Unit = {},
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state         by viewModel.uiState.collectAsStateWithLifecycle()
    val context        = LocalContext.current
    val haptics        = LocalHapticFeedback.current
    val density        = LocalDensity.current
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val scrimHeight    = 140.dp
    val background     = MaterialTheme.colorScheme.background
    val isWideScreen   = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)

    PlayerPanelHost(
        playerViewModel          = playerViewModel,
        onOpenPlayer             = onOpenPlayer,
        onOpenQueue              = onOpenQueue,
        navSharedTransitionScope = sharedTransitionScope,
        navAnimatedContentScope  = animatedContentScope,
    ) { _ ->
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
                        text     = state.artist?.name ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { haptics.confirm(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.press()
                            state.artist?.id?.let { id ->
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/artist/$id")
                                        type = "text/plain"
                                    }, null
                                ))
                            }
                        },
                        enabled = state.artist != null,
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
            state.artist != null -> {
                val artist = state.artist!!

                val labelAlbums       = stringResource(R.string.artist_albums)
                val labelSingles      = stringResource(R.string.artist_singles)
                val labelCompilations = stringResource(R.string.artist_compilations)
                val groupedAlbums = remember(state.albums) {
                    val map = state.albums.groupBy {
                        it.albumType?.lowercase()?.takeIf { t -> t.isNotBlank() } ?: "album"
                    }
                    listOfNotNull(
                        map["album"]?.let       { labelAlbums       to it },
                        map["single"]?.let      { labelSingles      to it },
                        map["compilation"]?.let { labelCompilations to it },
                    )
                }

                if (isWideScreen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Left pane — artist photo + info
                            Card(
                                modifier  = Modifier.weight(0.42f).fillMaxHeight(),
                                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                    val photoSize = maxWidth.coerceAtMost(maxHeight * 0.5f)
                                    val compact   = maxHeight < 500.dp
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(bottom = navBarBottomDp),
                                    ) {
                                        ArtistHeader(
                                            artist        = artist,
                                            modifier      = Modifier.size(photoSize).clip(RoundedCornerShape(12.dp)),
                                            compact       = compact,
                                            showDivider   = false,
                                        )
                                    }
                                }
                            }

                            // Right pane — discography
                            Card(
                                modifier  = Modifier.weight(0.58f).fillMaxHeight(),
                                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val albumsListState = rememberLazyListState()
                                    ListScrollHaptics(albumsListState)
                                    LazyColumn(
                                        state          = albumsListState,
                                        modifier       = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                                    ) {
                                        artistContent(
                                            state         = state,
                                            groupedAlbums = groupedAlbums,
                                            onOpenAlbum   = onOpenAlbum,
                                            onLoadMore    = viewModel::loadMoreAlbums,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(scrimHeight)
                                            .align(Alignment.BottomCenter)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
                                    )
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
                        val albumsListState = rememberLazyListState()
                        ListScrollHaptics(albumsListState)
                        LazyColumn(
                            state          = albumsListState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) {
                            item(key = "header") {
                                ArtistHeader(artist = artist, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                            }
                            artistContent(
                                state         = state,
                                groupedAlbums = groupedAlbums,
                                onOpenAlbum   = onOpenAlbum,
                                onLoadMore    = viewModel::loadMoreAlbums,
                            )
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
                    }
                }
            }
        }
    }
    } // PlayerPanelHost
}

private fun LazyListScope.artistContent(
    state         : ArtistDetailUiState,
    groupedAlbums : List<Pair<String, List<SpotifyAlbum>>>,
    onOpenAlbum   : (String) -> Unit,
    onLoadMore    : () -> Unit,
) {
    groupedAlbums.forEach { (label, albums) ->
        item(key = "header_$label") {
            SectionHeader(label)
        }
        items(albums, key = { "album_${it.id}" }) { album ->
            ArtistAlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
        }
    }

    if (state.albumsNext != null || state.isLoadingMore) {
        item(key = "load_more") {
            Box(
                modifier         = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isLoadingMore) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    OutlinedButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.artist_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(
    artist        : SpotifyArtistFull,
    modifier      : Modifier = Modifier,
    compact       : Boolean  = false,
    showDivider   : Boolean  = true,
) {
    Column {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (artist.imageUrl.isNotBlank()) {
                AsyncImage(
                    model              = artist.imageUrl,
                    contentDescription = artist.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = modifier,
                )
            } else {
                Surface(
                    modifier = modifier,
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    shape    = RoundedCornerShape(12.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null,
                            modifier = Modifier.fillMaxSize(0.4f),
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text  = artist.name,
                style = if (compact) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.titleLarge,
            )
            val genres = artist.genres?.take(3)?.joinToString(" · ")
            if (!genres.isNullOrBlank()) {
                Text(
                    text     = genres,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val followers = artist.formattedFollowers
            if (followers.isNotBlank()) {
                Text(
                    text  = followers,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showDivider) HorizontalDivider()
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ArtistAlbumRow(
    album  : SpotifyAlbum,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            val url = album.images?.firstOrNull()?.url
            if (url != null) {
                AsyncImage(
                    model              = url,
                    contentDescription = album.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)),
                )
            } else {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape    = RoundedCornerShape(4.dp),
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        headlineContent   = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text  = album.releaseYear,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
