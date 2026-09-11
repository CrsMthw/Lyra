@file:Suppress("ConfigurationScreenWidthHeight")

package com.crsmthw.lyra.ui.screens.library

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.components.ConnectedChoiceRow
import com.crsmthw.lyra.ui.components.HeroBandHeight
import com.crsmthw.lyra.ui.components.rememberHeroScrollProgress
import com.crsmthw.lyra.ui.components.TitlePill
import com.crsmthw.lyra.ui.components.TopActionPill
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import java.io.File

// ── Library browser pane ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
internal fun LibraryBrowserPane(
    state                 : LibraryUiState,
    viewModel             : LibraryViewModel,
    onOpenSettings        : () -> Unit,
    isLandscape           : Boolean,
    modifier              : Modifier = Modifier,
    onOpenAlbum           : (String) -> Unit = {},
    onOpenArtist          : (String) -> Unit = {},
    onOpenStats           : () -> Unit = {},
    onPlayTopTrack        : (Int) -> Unit = {},
    containerColor        : Color = Color.Unspecified,   // top-scrim target; defaults to background
    sharedScope           : SharedTransitionScope? = null,   // non-null only in single pane (container transform)
    animScope             : AnimatedContentScope? = null,
) {
    var showRefreshErrorDialog by remember { mutableStateOf(false) }
    val haptics        = LocalHapticFeedback.current
    val scrimColor     = if (containerColor == Color.Unspecified)
                             MaterialTheme.colorScheme.background else containerColor
    val listState      = rememberLazyListState()
    ListScrollHaptics(listState)
    val density        = LocalDensity.current
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val context        = LocalContext.current
    val mosaicDir = remember { File(context.filesDir, "mosaics") }

    val likedSongsSelected = state.currentPlaylist == null &&
        (state.isLoadingTracks || state.currentTracks.isNotEmpty())
    val selectedPlaylistId = state.currentPlaylist?.id

    val userId      = state.user?.id
    val myPlaylists = if (userId != null) state.playlists.filter { it.owner?.id == userId } else state.playlists
    val following   = if (userId != null) state.playlists.filter { it.owner?.id != userId } else emptyList()

    // Shared list items — identical in both portrait and landscape LazyColumns
    val listBody: LazyListScope.() -> Unit = {
        // Content-type filter (Playlists / Albums / Artists) — connected M3 ButtonGroup.
        item(key = "filter") {
            LibraryFilterRow(
                selected = state.libraryFilter,
                onSelect = viewModel::setLibraryFilter,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        when (state.libraryFilter) {
            LibraryFilter.ALBUMS -> {
                if (state.savedAlbums.isEmpty()) {
                    item(key = "albums_empty") {
                        CollectionEmptyState(
                            isLoading = state.isLoadingCollections,
                            text      = stringResource(R.string.library_no_albums),
                        )
                    }
                } else {
                    items(state.savedAlbums, key = { "album-${it.id}" }) { album ->
                        AlbumListCard(
                            album   = album,
                            onClick = { haptics.confirm(); onOpenAlbum(album.id) },
                        )
                    }
                }
            }
            LibraryFilter.ARTISTS -> {
                if (state.followedArtists.isEmpty()) {
                    item(key = "artists_empty") {
                        CollectionEmptyState(
                            isLoading = state.isLoadingCollections,
                            text      = stringResource(R.string.library_no_artists),
                        )
                    }
                } else {
                    items(state.followedArtists, key = { "artist-${it.id}" }) { artist ->
                        ArtistListCard(
                            artist  = artist,
                            onClick = { haptics.confirm(); onOpenArtist(artist.id) },
                        )
                    }
                }
            }
            LibraryFilter.PLAYLISTS -> {
        item(key = "liked") {
            // Container-transform source: same inline `sharedBounds` form as the search-FAB morph.
            val likedArt = if (sharedScope != null && animScope != null)
                with(sharedScope) {
                    Modifier.sharedBounds(
                        sharedContentState      = rememberSharedContentState(key = libArtKey(null)),
                        animatedVisibilityScope = animScope,
                        boundsTransform         = rememberArtBoundsTransform(),
                    )
                } else Modifier
            LikedSongsCard(
                count             = state.likedSongCount,
                isSelected        = likedSongsSelected,
                onOpen            = { haptics.confirm(); viewModel.selectLikedSongs() },
                onPlay            = { viewModel.playPlaylist("spotify:user:${state.user?.id}:collection") },
                artSharedModifier = likedArt,
            )
        }
        // ── "For you" band — discovery from the user's own listening. Opt-in via Settings
        //    (default off): cached band data may still be in state, so gate on the toggle too.
        if (state.forYouEnabled && state.jumpBackIn.isNotEmpty()) {
            item(key = "jump_header") {
                Text(stringResource(R.string.library_jump_back_in), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            }
            item(key = "jump_row") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.jumpBackIn, key = { it.uri }) { jump ->
                        JumpBackInCard(
                            item       = jump,
                            mosaicFile = if (jump.type == "playlist" && jump.id in state.playlistsWithMosaics)
                                File(mosaicDir, "${jump.id}.png") else null,
                            onClick    = {
                                haptics.confirm()
                                when (jump.type) {
                                    "liked"    -> viewModel.selectLikedSongs()
                                    "playlist" -> state.playlists
                                        .firstOrNull { it.id == jump.id }
                                        ?.let(viewModel::selectPlaylist)
                                    "album"    -> onOpenAlbum(jump.id)
                                    "artist"   -> onOpenArtist(jump.id)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (state.forYouEnabled && state.topTracks.isNotEmpty()) {
            item(key = "onrepeat_header") {
                Text(stringResource(R.string.library_on_repeat), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            }
            item(key = "onrepeat_row") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(state.topTracks, key = { _, t -> t.id }) { idx, track ->
                        TopTrackCard(
                            track       = track,
                            onClick     = { haptics.confirm(); onPlayTopTrack(idx) },
                            onLongClick = { viewModel.trackActions.open(track.toTrackActionTarget()) },
                        )
                    }
                }
            }
        }
        if (myPlaylists.isNotEmpty()) {
            item(key = "mine_header") {
                Text(stringResource(R.string.library_my_playlists), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            }
            items(myPlaylists, key = { it.id }) { playlist ->
                val playlistArt = if (sharedScope != null && animScope != null)
                    with(sharedScope) {
                        Modifier.sharedBounds(
                            sharedContentState      = rememberSharedContentState(key = libArtKey(playlist.id)),
                            animatedVisibilityScope = animScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        )
                    } else Modifier
                PlaylistListCard(
                    playlist          = playlist,
                    mosaicFile        = if (playlist.id in state.playlistsWithMosaics)
                        File(mosaicDir, "${playlist.id}.png") else null,
                    isSelected        = playlist.id == selectedPlaylistId,
                    isMine            = true,
                    onClick           = { haptics.confirm(); viewModel.selectPlaylist(playlist) },
                    onPlay            = { viewModel.playPlaylist(playlist.uri) },
                    artSharedModifier = playlistArt,
                )
            }
        }
        if (following.isNotEmpty()) {
            item(key = "following_header") {
                Text(stringResource(R.string.library_following), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            }
            items(following, key = { it.id }) { playlist ->
                val playlistArt = if (sharedScope != null && animScope != null)
                    with(sharedScope) {
                        Modifier.sharedBounds(
                            sharedContentState      = rememberSharedContentState(key = libArtKey(playlist.id)),
                            animatedVisibilityScope = animScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        )
                    } else Modifier
                PlaylistListCard(
                    playlist          = playlist,
                    mosaicFile        = if (playlist.id in state.playlistsWithMosaics)
                        File(mosaicDir, "${playlist.id}.png") else null,
                    isSelected        = playlist.id == selectedPlaylistId,
                    onClick           = { haptics.confirm(); viewModel.selectPlaylist(playlist) },
                    onPlay            = { viewModel.playPlaylist(playlist.uri) },
                    artSharedModifier = playlistArt,
                )
            }
        }
            }   // end PLAYLISTS branch
        }   // end filter when
    }

    val actionsBar: @Composable RowScope.() -> Unit = {
        if (state.refreshError != null) {
            IconButton(onClick = { showRefreshErrorDialog = true }) {
                Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.cd_refresh_error),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = { haptics.press(); onOpenStats() }) {
            Icon(Icons.Default.Insights, contentDescription = stringResource(R.string.stats_title))
        }
        IconButton(onClick = { haptics.press(); onOpenSettings() }) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
        }
    }

    // The spacious "Lyra" hero needs vertical room. Show it whenever the pane is tall enough —
    // portrait on any device, and landscape on a tablet (≈800dp tall). Only a SHORT landscape pane
    // (a phone, ≈360–410dp tall) drops the hero for the compact title-pill layout.
    val compactNoHero = isLandscape && LocalConfiguration.current.screenHeightDp < 500

    if (compactNoHero) {
        // Compact (short landscape, e.g. phone): floating controls over scrollable content, no hero
        // — vertical space is tight, so the title pill is always shown rather than fading in.
        val statusBarTopDp     = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val listTopPadding     = statusBarTopDp + 64.dp
        val listContentPadding = remember(listTopPadding, navBarBottomDp) { PaddingValues(top = listTopPadding, bottom = 100.dp + navBarBottomDp) }

        Box(modifier = modifier.fillMaxSize()) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            } else if (state.error != null && state.playlists.isEmpty() && !state.isLoadingTracks) {
                val isRateLimit   = state.error.contains("429")
                val retryAfterSec = if (isRateLimit)
                    Regex("Retry-After=(\\d+)").find(state.error)?.groupValues?.get(1)?.toLongOrNull()
                else null
                val retryDisplay  = when {
                    retryAfterSec == null -> null
                    retryAfterSec >= 3600 -> "${retryAfterSec / 3600}h ${(retryAfterSec % 3600) / 60}m"
                    retryAfterSec >= 60   -> "${retryAfterSec / 60}m ${retryAfterSec % 60}s"
                    else                  -> "${retryAfterSec}s"
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)) {
                        Text(
                            text = if (isRateLimit) buildString {
                                append("Spotify is rate limiting requests.")
                                if (retryDisplay != null) append("\n\nRetry-After: $retryDisplay")
                                append("\n\nWait, then reopen.")
                            } else state.error,
                            color     = if (isRateLimit) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error,
                            style     = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::loadLibrary) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            } else {
                val landscapePtrState = rememberPullToRefreshState()
                PullThresholdHaptics(landscapePtrState)
                PullToRefreshBox(
                    isRefreshing = state.isLibraryRefreshing,
                    onRefresh    = viewModel::refreshLibrary,
                    state        = landscapePtrState,
                    modifier     = Modifier.fillMaxSize(),
                    indicator    = {
                        if (state.isLibraryRefreshing) {
                            ContainedLoadingIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 64.dp + 12.dp),
                            )
                        } else {
                            PullToRefreshDefaults.Indicator(
                                state        = landscapePtrState,
                                isRefreshing = false,
                                modifier     = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 64.dp),
                            )
                        }
                    },
                ) {
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding,
                    ) { listBody() }
                }
            }

            // Floating controls (matching portrait) — no solid bar. Landscape has no scroll-away
            // hero (vertical space is tight), so the title pill is always shown rather than fading in.
            TopScrim(color = scrimColor, modifier = Modifier.align(Alignment.TopCenter))
            TitlePill(
                text     = "Lyra",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp),
            )
            TopActionPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp),
                content  = actionsBar,
            )
        }
    } else {
        // Hero layout (portrait on any device, or a landscape pane tall enough — e.g. a tablet):
        // no solid top app bar (OneUI 8.5 style). The "Lyra" hero is the first list item
        // and scrolls away; a top scrim fades content under the status bar, a floating action pill
        // carries the settings/error actions, and a small title pill fades in once the hero is gone.
        val titlePillAlpha = rememberHeroScrollProgress(listState)
        val listContentPadding = remember(navBarBottomDp) {
            PaddingValues(bottom = 100.dp + navBarBottomDp)
        }

        Box(modifier = modifier.fillMaxSize()) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            } else if (state.error != null && state.playlists.isEmpty() && !state.isLoadingTracks) {
                val isRateLimit   = state.error.contains("429")
                val retryAfterSec = if (isRateLimit)
                    Regex("Retry-After=(\\d+)").find(state.error)?.groupValues?.get(1)?.toLongOrNull()
                else null
                val retryDisplay  = when {
                    retryAfterSec == null -> null
                    retryAfterSec >= 3600 -> "${retryAfterSec / 3600}h ${(retryAfterSec % 3600) / 60}m"
                    retryAfterSec >= 60   -> "${retryAfterSec / 60}m ${retryAfterSec % 60}s"
                    else                  -> "${retryAfterSec}s"
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)) {
                        Text(
                            text = if (isRateLimit) buildString {
                                append("Spotify is rate limiting requests.")
                                if (retryDisplay != null) append("\n\nRetry-After: $retryDisplay")
                                append("\n\nWait, then reopen.")
                            } else state.error,
                            color     = if (isRateLimit) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error,
                            style     = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::loadLibrary) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            } else {
                val portraitPtrState = rememberPullToRefreshState()
                PullThresholdHaptics(portraitPtrState)
                PullToRefreshBox(
                    isRefreshing = state.isLibraryRefreshing,
                    onRefresh    = viewModel::refreshLibrary,
                    state        = portraitPtrState,
                    modifier     = Modifier.fillMaxSize(),
                    indicator    = {
                        if (state.isLibraryRefreshing) {
                            ContainedLoadingIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 12.dp),
                            )
                        } else {
                            PullToRefreshDefaults.Indicator(
                                state        = portraitPtrState,
                                isRefreshing = false,
                                modifier     = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding(),
                            )
                        }
                    },
                ) {
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding,
                    ) {
                        item(key = "hero") {
                            // Spacious hero (OneUI Phone-app style): the title sits centred in a
                            // tall band with room above (where the action pill floats) and below.
                            // Boundary-only haptics mean its height adds no extra ticks.
                            Box(
                                modifier         = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .height(HeroBandHeight),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    text     = "Lyra",
                                    style    = MaterialTheme.typography.displayMedium,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                )
                            }
                        }
                        listBody()
                    }
                }
            }

            // Top scrim — fades content under the status bar (vertical mirror of the bottom scrim).
            TopScrim(
                color    = scrimColor,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // Small title pill — fades in once the hero title has scrolled away.
            TitlePill(
                text     = "Lyra",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp)
                    .graphicsLayer { alpha = titlePillAlpha.value },
            )

            // Floating action pill — settings (+ refresh-error warning when present).
            TopActionPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp),
                content  = actionsBar,
            )
        }
    }

    if (showRefreshErrorDialog && state.refreshError != null) {
        RefreshErrorDialog(error = state.refreshError, onDismiss = { showRefreshErrorDialog = false })
    }
}

// ── Library filter (Playlists / Albums / Artists) ─────────────────────────────

/** Connected single-choice picker for the Library content type — see [ConnectedChoiceRow]. */
@Composable
private fun LibraryFilterRow(
    selected : LibraryFilter,
    onSelect : (LibraryFilter) -> Unit,
    modifier : Modifier = Modifier,
) {
    ConnectedChoiceRow(
        options  = listOf(
            LibraryFilter.PLAYLISTS to stringResource(R.string.library_filter_playlists),
            LibraryFilter.ALBUMS    to stringResource(R.string.library_filter_albums),
            LibraryFilter.ARTISTS   to stringResource(R.string.library_filter_artists),
        ),
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
    )
}

/** Loading / empty placeholder for the Albums and Artists filters. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollectionEmptyState(isLoading: Boolean, text: String) {
    Box(
        modifier         = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            ContainedLoadingIndicator()
        } else {
            Text(text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
