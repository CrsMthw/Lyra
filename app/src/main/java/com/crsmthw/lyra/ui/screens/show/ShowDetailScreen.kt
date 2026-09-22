package com.crsmthw.lyra.ui.screens.show

import android.content.Intent
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
import androidx.compose.material.icons.filled.Check
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
import com.crsmthw.lyra.ui.components.DetailTopBar
import com.crsmthw.lyra.ui.components.DetailTopBarFade
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.rememberHeroTitleHandoff
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
 * How many episode uris a single Play may hand `me/player/play`.
 *
 * 750, the same cap `PlayerViewModel.playFromLikedSongs` uses. A smaller number would not reduce
 * the real risk: multiple EPISODE uris in `uris` are undocumented (the Web API reference describes
 * `uris` as track uris, and a show is not a valid `context_uri`), so if Spotify refuses the shape it
 * refuses it at two entries as readily as at seven hundred — which is what `PlayerViewModel`'s
 * one-shot degrade to a single uri is there to catch.
 */
private const val EPISODE_QUEUE_LIMIT = 750

/**
 * How close to the end of an episode a resume point may be and still be honoured — 10 seconds.
 *
 * Spotify sets `fully_played` only at the very end, so a resume point can sit inside the last
 * moments of an episode without it. Resuming there would play a few seconds and stop, and the
 * useful answer for an episode that is effectively finished is the beginning. The same bound
 * discards a cached page's resume point that has drifted past the episode's duration.
 */
private const val RESUME_TAIL_GUARD_MS = 10_000L

/**
 * Where this episode should start when Lyra has to place the playhead itself, or null to start
 * wherever the API decides (the beginning, for the App Remote).
 *
 * **Live**, as of `user-read-playback-position` joining `SpotifyAuthManager.SCOPES` (2026-09-15) —
 * it is what fills `resume_point`. It still reads null on a session authorized before that scope
 * existed, until the user reconnects (see [SpotifyEpisode.resumePoint]), and the screen says so in
 * one line while that is the case. Only the **App Remote fallback** consumes it: its `play(uri)`
 * always starts at 0:00 and consults no server point. The Web API path still needs nothing —
 * `me/player/play` with no `position_ms` resumes an episode from Spotify's own authoritative
 * position, which stays the truth a cached page's resume point can be stale against.
 *
 * Rejected: a fully-played episode, an absent or zero position, and a position inside the last
 * [RESUME_TAIL_GUARD_MS] of a known duration. [EpisodeRow]'s "N left" + progress-bar branch reads
 * this SAME property, so the row and the playhead can never disagree about what is in progress.
 */
private val SpotifyEpisode.startPositionMs: Long?
    get() {
        val point = resumePoint ?: return null
        if (point.fullyPlayed == true) return null
        val position = point.resumePositionMs?.takeIf { it > 0L } ?: return null
        val duration = durationMs
        if (duration != null && position > duration - RESUME_TAIL_GUARD_MS) return null
        return position
    }

/**
 * Podcast show detail — structurally the album screen (`AlbumDetailScreen`): single-pane is a
 * [DetailArtHero] over the episode list, two-pane puts the hero in the left panel and the
 * episodes on the right, both edge-to-edge under a transparent status bar with a [DetailTopBar]
 * laid over the hero.
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShowDetailScreen(
    viewModel             : ShowDetailViewModel,
    playerViewModel       : PlayerViewModel,
    onBack                : () -> Unit,
    onNavigateToPlayer    : () -> Unit,
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

    // The shared `DetailTopBar` (docs/APP_BARS_OPTIONS.md → D1) replaces the floating back / title /
    // action pills and the single-pane TopScrim. One back icon for every bar on the screen
    // (single-pane, the two-pane LEFT pane, loading/error) so the gesture, the debounce path
    // (`onBack` → `safeNavigateUp`) and the haptic can't drift.
    val backNavIcon: @Composable () -> Unit = {
        IconButton(onClick = { haptics.confirm(); onBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.nav_back))
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        // Still NO Scaffold topBar: each configuration lays its own bar over its own scrolling
        // content (the bar must overlap the hero, and in two-pane it belongs to the left pane only),
        // so a Scaffold bar would both reserve height and span both cards.
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

                // Queue continuity: starting an episode with its uri ALONE leaves Spotify's queue
                // empty behind it, so playback stops after that one episode. Send the tapped
                // episode followed by every episode AFTER it in the loaded list instead — the feed
                // is newest-first, so "after" is the older episodes, which is the order the Spotify
                // show page plays in. Only the pages loaded so far can be queued; paging further
                // down the list before tapping queues more.
                //
                // Returns null when there is nothing to continue with, so a one-episode show keeps
                // the proven single-uri call shape (and with it the App Remote restore path's
                // `needsRestore == false`) rather than a one-element list that behaves the same but
                // arms extra machinery. Capped at EPISODE_QUEUE_LIMIT, matching
                // PlayerViewModel.playFromLikedSongs.
                fun queueFrom(episode: SpotifyEpisode?): List<String>? {
                    if (episode == null) return null
                    // Locate by id in the SAME list that is sliced — the rendered rows are a
                    // filtered copy, so an index taken from them would not line up here.
                    val start = episodes.indexOfFirst { it.id != null && it.id == episode.id }
                    val tail  = if (start >= 0) episodes.drop(start) else listOf(episode)
                    return tail.mapNotNull { it.uri?.takeIf(String::isNotBlank) }
                        .take(EPISODE_QUEUE_LIMIT)
                        .takeIf { it.size > 1 }
                }

                // Play = the newest episode, and the whole loaded feed behind it. The endpoint
                // returns newest first, so that is simply the head of the list; no button at all
                // when the feed is empty.
                val newest = episodes.firstOrNull { !it.uri.isNullOrBlank() }
                val onPlayNewest: (() -> Unit)? = newest?.uri?.let { uri ->
                    {
                        haptics.press()
                        playerViewModel.playTrack(
                            uri             = uri,
                            uris            = queueFrom(newest),
                            startPositionMs = newest.startPositionMs,
                        )
                        onNavigateToPlayer()
                    }
                }
                val onPlayEpisode = { episode: SpotifyEpisode ->
                    episode.uri?.takeIf { it.isNotBlank() }?.let { uri ->
                        playerViewModel.playTrack(
                            uri             = uri,
                            uris            = queueFrom(episode),
                            // Only the App Remote fallback uses this; the Web API resumes an
                            // episode from Spotify's own position without being told.
                            startPositionMs = episode.startPositionMs,
                        )
                        onNavigateToPlayer()
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

                // The old TopActionPill's contents verbatim (follow heart + share), now the bar's
                // `actions` slot — one definition for both panes. `@Composable RowScope.() -> Unit`
                // is exactly the shape `DetailTopBar` wants.
                val showActions: @Composable RowScope.() -> Unit = {
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
                            // Left pane — the detail hero panel, with the solid DetailTopBar over it
                            // (was: a top scrim plus back and action pills).
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
                                    // The seam under the bar — composed after the pane's content and
                                    // before the bar, so it draws over the hero and under the bar.
                                    DetailTopBarFade(
                                        paneColor = MaterialTheme.colorScheme.surface,
                                        modifier  = Modifier.align(Alignment.TopCenter),
                                    )
                                    // No title in this bar, and so no hand-off: the hero's own name
                                    // sits right under it and barely scrolls in a pane this short,
                                    // exactly as this pane carried no title pill. `paneColor` is the
                                    // CARD's colour, not the screen background.
                                    DetailTopBar(
                                        paneColor      = MaterialTheme.colorScheme.surface,
                                        navigationIcon = backNavIcon,
                                        actions        = showActions,
                                        modifier       = Modifier.align(Alignment.TopCenter),
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
                                        episodeItems(episodes, show, state.isLoadingMore, state.resumeScopeMissing, onPlayEpisode)
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
                        // The bar title takes over from the hero title over the ~35dp that title
                        // needs to slide under the bar (M3's own `TopTitleAlphaEasing` hand-off) —
                        // not over the whole hero's scroll, which read as a slow crossfade.
                        val heroTitle = rememberHeroTitleHandoff()
                        LazyColumn(
                            state          = episodesListState,
                            modifier       = Modifier.fillMaxSize(),
                            // No top inset: `DetailArtHero` bakes `statusBarsPadding()` + the bar's
                            // own collapsed height + 8dp + `BarContentGap` onto its art tile. Adding
                            // one here doubles.
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) {
                            item(key = "header") {
                                Column {
                                    DetailArtHero(
                                        title        = showTitle,
                                        subtitle     = subtitle,
                                        onPlay       = onPlayNewest,
                                        onShuffle    = null,
                                        titleHandoff = heroTitle,
                                        artContent   = showArt,
                                    )
                                    ShowDescription(show)
                                }
                            }
                            episodeItems(episodes, show, state.isLoadingMore, state.resumeScopeMissing, onPlayEpisode)
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

                        // The seam under the bar: the page colour fading out over the first rows,
                        // so the hero and the episodes dissolve into the bar instead of sliding past
                        // its title — the same strip the Library browser has under its tab row.
                        // Composed after the list and before the bar, so it draws over the content
                        // and under the bar, and it takes no pointer input.
                        DetailTopBarFade(
                            paneColor = background,
                            modifier  = Modifier.align(Alignment.TopCenter),
                        )

                        // The bar, composed LAST so it draws (and hit-tests) over the list. Solid
                        // `background` at rest and scrolled — at rest only the hero's 8dp +
                        // `BarContentGap` of page background sits between its bottom edge and the
                        // art, so it reads as the page until the art arrives (it replaces the old
                        // TopScrim as well as the pills).
                        DetailTopBar(
                            paneColor      = background,
                            navigationIcon = backNavIcon,
                            actions        = showActions,
                            heroTitle      = heroTitle,
                            title          = { titleModifier ->
                                Text(
                                    text     = showTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = titleModifier,
                                )
                            },
                            modifier       = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }

            // Screen-level back bar, for the loading and error states ONLY — once the show loads,
            // the bar that carries the title and actions lives inside the layout that owns the
            // scrolling content (single-pane over the list, two-pane over the LEFT card).
            //
            // The condition is the exact NEGATION of the `when`'s content arm, not just
            // `show == null`: a future reload path that set `isLoading` over a loaded show would
            // otherwise swap the content (bar included) for the spinner and leave back unreachable.
            //
            // It carries its own `horizontalSystemBarsPadding()` because it is a SIBLING of those
            // layouts, not a descendant — still one horizontal application per subtree, not a second
            // on the same element. In the wide case it also takes the Row's own 8dp inset so the
            // back arrow doesn't jump when the content lands and the left-pane bar takes over.
            if (state.isLoading || state.error != null || state.show == null) {
                DetailTopBar(
                    // Nothing is loaded, so there is no card behind this one — the page background
                    // is what it sits on in both configurations.
                    paneColor      = background,
                    navigationIcon = backNavIcon,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .horizontalSystemBarsPadding()
                        .padding(horizontal = if (isWideScreen) 8.dp else 0.dp,
                                 vertical   = if (isWideScreen) 8.dp else 0.dp),
                )
            }
        }
    }
}

/**
 * The episode rows plus the list's empty / loading-more states, shared by both layouts so the two
 * can't drift. Keyed on the episode id (ids are de-duped in the ViewModel before they get here).
 *
 * Id-less episodes are filtered out ONCE and the filtered list drives both the empty-state gate and
 * the rows: an episode with no id cannot be keyed, and gating on the raw list while rendering the
 * filtered one would paint a blank list with no empty state for a page that happened to be all
 * id-less. Same rule as the Search screen's Shows tab.
 *
 * [resumeScopeMissing] adds the one-line reconnect hint above the first row — only with rows to
 * explain, which is why it is tested after the empty-state return.
 */
private fun LazyListScope.episodeItems(
    episodes           : List<SpotifyEpisode>,
    show               : SpotifyShow,
    isLoadingMore      : Boolean,
    resumeScopeMissing : Boolean,
    onPlay             : (SpotifyEpisode) -> Unit,
) {
    val keyed = episodes.filter { !it.id.isNullOrBlank() }
    if (keyed.isEmpty()) {
        item(key = "episodes_empty") { EpisodesEmptyState() }
        return
    }
    if (resumeScopeMissing) {
        item(key = "episodes_resume_hint") { ResumeScopeHint() }
    }
    items(keyed, key = { "episode-${it.id}" }) { episode ->
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
 * The subtitle has three shapes, driven by `resume_point` (see [SpotifyEpisode.resumePoint]):
 *  - **finished** (`fully_played`) → "<date> · Played" with a check, and NO duration — the runtime
 *    of an episode you have finished is the one number that tells you nothing;
 *  - **in progress** → "<date> · <remaining> left" plus a thin determinate progress bar under the
 *    line. The branch is [SpotifyEpisode.startPositionMs], the SAME property the play lambdas hand
 *    `playTrack`, so a row can never advertise progress the playhead would then ignore (it rejects
 *    a point inside the last [RESUME_TAIL_GUARD_MS], among others). The bar needs a known duration
 *    too: a NaN fraction is a crash in `ProgressBarRangeInfo`, not a cosmetic bug;
 *  - **untouched, or no grant** → "<date> · <duration>", exactly as before.
 *
 * No `onLongClick`: the song touch-and-hold menu is track-specific — see the screen KDoc.
 */
@Composable
private fun EpisodeRow(
    episode : SpotifyEpisode,
    show    : SpotifyShow,
    onClick : () -> Unit,
) {
    val haptics     = LocalHapticFeedback.current
    val artUrl      = episode.thumbnailUrl.takeIf { it.isNotBlank() }
                      ?: show.thumbnailUrl.takeIf { it.isNotBlank() }
    val durationMs  = episode.durationMs?.takeIf { it > 0L }
    val fullyPlayed = episode.resumePoint?.fullyPlayed == true
    val resumeFrom  = episode.startPositionMs
    // Only with both ends known: an unknown duration has no fraction and no remainder, so such an
    // episode falls back to the plain shapes rather than painting a bar of nothing.
    val progress    = if (resumeFrom != null && durationMs != null)
                          (resumeFrom.toFloat() / durationMs).coerceIn(0f, 1f) else null
    val tail = when {
        fullyPlayed      -> stringResource(R.string.show_episode_played)
        progress != null -> stringResource(
            R.string.show_episode_time_left,
            // toDurationString() floors to whole minutes and would read "0m left" for the last
            // seconds, so a sub-minute remainder rounds up to one minute.
            (durationMs!! - resumeFrom!!).coerceAtLeast(60_000L).toDurationString(),
        )
        else             -> durationMs?.toDurationString()
    }
    val subtitle = listOfNotNull(formatReleaseDate(episode.releaseDate), tail)
        .joinToString(" · ").takeIf { it.isNotBlank() }

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
            // The supporting slot is already indented past the leading art, so the bar spans the
            // text column — the row width minus the thumbnail — without any padding of its own.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (fullyPlayed) {
                        // Decorative: the word "Played" beside it is what carries the meaning.
                        Icon(
                            imageVector        = Icons.Default.Check,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier           = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text     = text,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null) {
                    // Determinate, and deliberately NOT the wavy indicator: this is a position in
                    // an episode, not a wait. Default M3 height.
                    LinearProgressIndicator(
                        progress   = { progress },
                        modifier   = Modifier.fillMaxWidth().padding(top = 6.dp),
                        color      = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
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

/**
 * Why the rows show no progress: this session's token was issued before Lyra asked for
 * `user-read-playback-position`, and a grant only widens on a fresh authorization. Deliberately a
 * plain line and not a card with a button — reconnecting is a Settings action the user takes when
 * they feel like it, and nothing else on this screen is degraded. It disappears by itself once a
 * token carrying the scope is stored.
 */
@Composable
private fun ResumeScopeHint() {
    Text(
        text     = stringResource(R.string.show_resume_scope_hint),
        style    = MaterialTheme.typography.bodySmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
