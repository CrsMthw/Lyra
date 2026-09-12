package com.crsmthw.lyra.ui.screens.show

import android.content.Intent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import com.crsmthw.lyra.data.remote.model.SpotifyEpisode
import com.crsmthw.lyra.data.remote.model.SpotifyShow
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
import com.crsmthw.lyra.util.horizontalSystemBarsPadding
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.toDurationString
import com.crsmthw.lyra.util.toggle
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Podcast show detail — structurally the album screen (`AlbumDetailScreen`): single-pane is a
 * [DetailArtHero] over the episode list, two-pane puts the hero in the left panel and the
 * episodes on the right, both edge-to-edge under a transparent status bar with floating pills.
 *
 * Three deliberate differences from the album screen:
 *  - **No shuffle button.** A podcast is a chronological feed; shuffling it is meaningless. The
 *    hero's Play plays the NEWEST episode (the endpoint returns newest first).
 *  - **No song touch-and-hold menu on the rows.** `TrackActionsController` is track-specific —
 *    its add-to-playlist, go-to-album and go-to-artist rows all address track-only endpoints, so
 *    a long-press on an episode would open a sheet where every action is wrong.
 *  - **The subtitle is the episode count, never the publisher** — Feb-2026 deprecated
 *    `show.publisher` and the device spike confirmed it is absent from live responses.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
fun ShowDetailScreen(
    viewModel             : ShowDetailViewModel,
    playerViewModel       : PlayerViewModel,
    onBack                : () -> Unit,
    onNavigateToPlayer    : () -> Unit,
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
    val isWideScreen   = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(600)
    val fallbackTitle  = stringResource(R.string.show_fallback_title)

    PlayerPanelHost(
        playerViewModel          = playerViewModel,
        onOpenPlayer             = onNavigateToPlayer,
        onOpenQueue              = onOpenQueue,
        navSharedTransitionScope = sharedTransitionScope,
        navAnimatedContentScope  = animatedContentScope,
    ) { onRequestPlayer ->
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        // No top app bar in either configuration — both layouts float their own back / share
        // pills over the hero, and a leftover bar would cover the two-pane ones.
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
            state.show != null -> {
                val show      = state.show!!
                val episodes  = state.episodes
                val showTitle = show.name?.takeIf { it.isNotBlank() } ?: fallbackTitle

                // Play = the newest episode. The endpoint returns newest first, so that is simply
                // the head of the list; no button at all when the feed is empty.
                val newest = episodes.firstOrNull { !it.uri.isNullOrBlank() }
                val onPlayNewest: (() -> Unit)? = newest?.uri?.let { uri ->
                    {
                        haptics.press()
                        playerViewModel.playTrack(uri = uri)
                        onRequestPlayer()
                    }
                }
                val onPlayEpisode = { episode: SpotifyEpisode ->
                    episode.uri?.let { uri ->
                        playerViewModel.playTrack(uri = uri)
                        onRequestPlayer()
                    }
                    Unit
                }

                val subtitle = show.totalEpisodes?.let {
                    pluralStringResource(R.plurals.show_episode_count, it, it)
                }

                // Shared show art (single-pane hero + two-pane left panel).
                val showArt: @Composable BoxScope.() -> Unit = {
                    val artUrl = show.artUrl.takeIf { it.isNotBlank() }
                    if (artUrl != null) {
                        AsyncImage(
                            model              = artUrl,
                            contentDescription = show.name,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Podcasts, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp))
                        }
                    }
                }

                val actionPill: @Composable RowScope.() -> Unit = {
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
                                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/show/${show.id}")
                                type = "text/plain"
                            }, null
                        ))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.player_share))
                    }
                }

                if (isWideScreen) {
                    // Edge-to-edge under a transparent status bar (like single-pane). The hero's
                    // own statusBarsPadding() + the pane TopScrims carry the top inset; no parent
                    // statusBarsPadding, which would leave an opaque band where the bar sits.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalSystemBarsPadding(),
                    ) {
                        Row(
                            modifier              = Modifier.fillMaxSize().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Left pane — the detail hero panel, with floating back + action pills.
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
                                            title      = showTitle,
                                            subtitle   = subtitle,
                                            onPlay     = onPlayNewest,
                                            onShuffle  = null,   // a feed has no meaningful shuffle
                                            artContent = showArt,
                                        )
                                        ShowDescription(show)
                                    }
                                    TopScrim(color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.align(Alignment.TopCenter))
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
                                    TopActionPill(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .statusBarsPadding()
                                            .padding(end = 12.dp, top = 8.dp),
                                        content  = actionPill,
                                    )
                                }
                            }

                            // Right pane — the episode list.
                            Card(
                                modifier  = Modifier.weight(0.58f).fillMaxHeight(),
                                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val episodesListState = rememberLazyListState()
                                    EpisodeLoadMoreTrigger(episodesListState, state, viewModel)
                                    ListScrollHaptics(episodesListState)
                                    LazyColumn(
                                        state          = episodesListState,
                                        modifier       = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(top = statusBarTopDp, bottom = 100.dp + navBarBottomDp),
                                    ) {
                                        episodeItems(episodes, show, state.isLoadingMore, onPlayEpisode)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(scrimHeight)
                                            .align(Alignment.BottomCenter)
                                            .background(Brush.verticalGradient(
                                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
                                    )
                                    TopScrim(color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.align(Alignment.TopCenter))
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
                            .horizontalSystemBarsPadding(),
                    ) {
                        val episodesListState = rememberLazyListState()
                        EpisodeLoadMoreTrigger(episodesListState, state, viewModel)
                        ListScrollHaptics(episodesListState)
                        val titlePillAlpha = rememberHeroScrollProgress(episodesListState)
                        LazyColumn(
                            state          = episodesListState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) {
                            item(key = "header") {
                                Column {
                                    DetailArtHero(
                                        title      = showTitle,
                                        subtitle   = subtitle,
                                        onPlay     = onPlayNewest,
                                        onShuffle  = null,
                                        artContent = showArt,
                                    )
                                    ShowDescription(show)
                                }
                            }
                            episodeItems(episodes, show, state.isLoadingMore, onPlayEpisode)
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

                        // Top scrim — fades the show art under the status bar.
                        TopScrim(color = background, modifier = Modifier.align(Alignment.TopCenter))

                        // Show-name title pill — fades in as the art scrolls away, sitting just
                        // right of the screen-level back pill.
                        TitlePill(
                            text     = showTitle,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(start = 16.dp + TopPillHeight + 8.dp, top = 8.dp)
                                .widthIn(max = 220.dp)
                                .graphicsLayer { alpha = titlePillAlpha.value },
                        )

                        TopActionPill(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(end = 16.dp, top = 8.dp),
                            content  = actionPill,
                        )
                    }
                }
            }
        }

            // Screen-level back pill. Single-pane: always (loading/error/content). Two-pane: only
            // while loading/erroring — once the show loads, the left-pane hero carries its own
            // back, and this one must land exactly where that pill will so it doesn't jump.
            if (!isWideScreen || state.show == null) {
                TopActionPill(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .horizontalSystemBarsPadding()
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

/**
 * The episode rows plus the list's empty / loading-more states, shared by both layouts so the two
 * can't drift. Keyed on the episode id (ids are de-duped in the ViewModel before they get here).
 */
private fun LazyListScope.episodeItems(
    episodes      : List<SpotifyEpisode>,
    show          : SpotifyShow,
    isLoadingMore : Boolean,
    onPlay        : (SpotifyEpisode) -> Unit,
) {
    if (episodes.isEmpty()) {
        item(key = "episodes_empty") { EpisodesEmptyState() }
        return
    }
    items(episodes.filter { !it.id.isNullOrBlank() }, key = { "episode-${it.id}" }) { episode ->
        EpisodeRow(episode = episode, show = show, onClick = { onPlay(episode) })
    }
    if (isLoadingMore) {
        item(key = "episodes_loading_more") { EpisodesLoadingMore() }
    }
}

/**
 * Reached-bottom pagination trigger — the Library's house pattern (`LibraryTrackListPane`), with
 * the paging flags as effect keys: the last page leaves `reachedBottom` latched true, so without
 * them the effect would never re-run once `isLoadingMore` cleared.
 */
@Composable
private fun EpisodeLoadMoreTrigger(
    listState : LazyListState,
    state     : ShowDetailUiState,
    viewModel : ShowDetailViewModel,
) {
    val reachedBottom by remember(listState) {
        derivedStateOf {
            val info        = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(reachedBottom, state.canLoadMore, state.isLoadingMore) {
        if (reachedBottom && state.canLoadMore && !state.isLoadingMore) viewModel.loadMoreEpisodes()
    }
}

/**
 * One episode row. `ListItem`'s trailing `content` lambda form (NOT the deprecated
 * `headlineContent`), matching `AlbumTrackRow`.
 *
 * No `onLongClick`: the song touch-and-hold menu is track-specific — see the screen KDoc.
 */
@Composable
private fun EpisodeRow(
    episode : SpotifyEpisode,
    show    : SpotifyShow,
    onClick : () -> Unit,
) {
    val haptics  = LocalHapticFeedback.current
    val artUrl   = episode.thumbnailUrl.takeIf { it.isNotBlank() }
                   ?: show.thumbnailUrl.takeIf { it.isNotBlank() }
    val subtitle = listOfNotNull(
        formatReleaseDate(episode.releaseDate),
        episode.durationMs?.takeIf { it > 0L }?.toDurationString(),
    ).joinToString(" · ").takeIf { it.isNotBlank() }

    ListItem(
        leadingContent = {
            if (artUrl != null) {
                AsyncImage(
                    model              = artUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Podcasts, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        supportingContent = subtitle?.let { text -> {
            Text(
                text     = text,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } },
        modifier = Modifier.clickable { haptics.confirm(); onClick() },
        content  = {
            Text(
                text     = episode.name.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** The show blurb under the hero — three lines, tap to expand. Hidden when there is no blurb. */
@Composable
private fun ShowDescription(show: SpotifyShow) {
    val text = show.description?.takeIf { it.isNotBlank() } ?: return
    var expanded by rememberSaveable(show.id) { mutableStateOf(false) }
    Text(
        text     = text,
        style    = MaterialTheme.typography.bodyMedium,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EpisodesEmptyState() {
    Box(
        modifier         = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = stringResource(R.string.show_no_episodes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EpisodesLoadingMore() {
    Box(
        modifier         = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) { ContainedLoadingIndicator() }
}

/**
 * `release_date` as a locale-formatted date. Spotify's `release_date_precision` can be year,
 * month or day, so anything shorter than a full ISO date is shown verbatim rather than guessed
 * into a wrong day. An unparseable value also falls back to itself — a date line is never worth
 * an exception.
 */
private fun formatReleaseDate(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    if (value.length < 10) return value
    return runCatching {
        LocalDate.parse(value.take(10))
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrDefault(value)
}
