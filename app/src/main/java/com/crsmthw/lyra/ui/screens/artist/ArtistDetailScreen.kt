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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.ui.components.DetailArtHero
import com.crsmthw.lyra.ui.components.PlayerPanelHost
import com.crsmthw.lyra.ui.components.TitlePill
import com.crsmthw.lyra.ui.components.TopActionPill
import com.crsmthw.lyra.ui.components.TopPillHeight
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.rememberHeroScrollProgress
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.toggle
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
    val statusBarTopDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
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

                // Shared artist photo (single-pane hero + two-pane left panel) — image, else a fallback.
                val artistArt: @Composable BoxScope.() -> Unit = {
                    if (artist.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model              = artist.imageUrl,
                            contentDescription = artist.name,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Person, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxSize(0.4f))
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
                            // Left pane — the detail hero panel (photo + name), with floating back
                            // + share pills and a top scrim (no solid bar, no play/shuffle).
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
                                            title      = artist.name,
                                            subtitle   = artist.formattedFollowers.takeIf { it.isNotBlank() },
                                            artContent = artistArt,
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
                                            onClick = { haptics.toggle(state.isFollowed != true); viewModel.toggleFollowed() },
                                            enabled = state.isFollowed != null,
                                        ) {
                                            Icon(
                                                imageVector        = if (state.isFollowed == true) Icons.Default.Favorite
                                                                                 else Icons.Default.FavoriteBorder,
                                                contentDescription = stringResource(
                                                    if (state.isFollowed == true) R.string.cd_unfollow else R.string.cd_follow),
                                            )
                                        }
                                        IconButton(onClick = {
                                            haptics.press()
                                            context.startActivity(Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/artist/${artist.id}")
                                                    type = "text/plain"
                                                }, null
                                            ))
                                        }) {
                                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.player_share))
                                        }
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
                                        contentPadding = PaddingValues(top = statusBarTopDp, bottom = 100.dp + navBarBottomDp),
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
                                    // Top scrim — fades the discography under the transparent status bar.
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
                        val albumsListState = rememberLazyListState()
                        ListScrollHaptics(albumsListState)
                        val titlePillAlpha = rememberHeroScrollProgress(albumsListState)
                        LazyColumn(
                            state          = albumsListState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) {
                            item(key = "header") {
                                DetailArtHero(
                                    title      = artist.name,
                                    subtitle   = artist.formattedFollowers.takeIf { it.isNotBlank() },
                                    artContent = artistArt,
                                )
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

                        // Top scrim — fades the artist art under the status bar.
                        TopScrim(color = background, modifier = Modifier.align(Alignment.TopCenter))

                        // Artist-name title pill — fades in as the art scrolls away, sitting just
                        // right of the screen-level back pill.
                        TitlePill(
                            text     = artist.name,
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
                                onClick = { haptics.toggle(state.isFollowed != true); viewModel.toggleFollowed() },
                                enabled = state.isFollowed != null,
                            ) {
                                Icon(
                                    imageVector        = if (state.isFollowed == true) Icons.Default.Favorite
                                                         else Icons.Default.FavoriteBorder,
                                    contentDescription = stringResource(
                                        if (state.isFollowed == true) R.string.cd_unfollow else R.string.cd_follow),
                                )
                            }
                            IconButton(onClick = {
                                haptics.press()
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/artist/${artist.id}")
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
            // while loading/erroring — once the artist loads, the left-pane hero carries its own back.
            // In two-pane it must land exactly where that left-pane pill will (inside the Row's 8dp
            // inset + the pill's own 12/8dp) so it doesn't jump when the content loads in.
            if (!isWideScreen || state.artist == null) {
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
