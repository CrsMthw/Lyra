package com.crsmthw.lyra.ui.screens.library

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.components.appBarWindowInsets
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import java.io.File

// ── Library browser pane ──────────────────────────────────────────────────────

/**
 * Pane height at or above which the browser gets the **large flexible** app bar. Below it (the
 * folded outer screen in landscape, a ≈380dp-tall pane) a 152dp expanded bar plus a 48dp tab row
 * would eat over half the pane before a single card, so that case gets the small pinned bar.
 * 600dp is the M3 medium-height boundary and clears portrait on both screens as well as an
 * unfolded / tablet landscape pane (≈800dp+).
 */
private val LargeBarMinPaneHeight = 600.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
internal fun LibraryBrowserPane(
    state                 : LibraryUiState,
    /** Hoisted to `LibraryScreen` so the scroll position outlives this pane — see its KDoc there. */
    listState             : LazyListState,
    /** App-bar collapse state, hoisted to `LibraryScreen` for the same reason as [listState] —
     *  see its KDoc there. Only the LARGE bar branch below uses it. */
    barState              : TopAppBarState,
    viewModel             : LibraryViewModel,
    onOpenSettings        : () -> Unit,
    modifier              : Modifier = Modifier,
    onOpenAlbum           : (String) -> Unit = {},
    onOpenArtist          : (String) -> Unit = {},
    onOpenShow            : (String) -> Unit = {},
    onOpenStats           : () -> Unit = {},
    onPlayTopTrack        : (Int) -> Unit = {},
    /** The pane's own container colour, which the app bar and the tab row both paint (defaults to
     *  `background`; the two-pane left card passes `surface`). */
    containerColor        : Color = Color.Unspecified,
    sharedScope           : SharedTransitionScope? = null,   // non-null only in single pane (container transform)
    animScope             : AnimatedContentScope? = null,
) {
    var showRefreshErrorDialog by remember { mutableStateOf(false) }
    val haptics        = LocalHapticFeedback.current
    val paneColor      = if (containerColor == Color.Unspecified)
                             MaterialTheme.colorScheme.background else containerColor
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

    // Shared list items. The content-type filter is no longer one of them — it is the pinned
    // PrimaryTabRow under the app bar (see LibraryTabRow), and the "Lyra" hero band is gone: the
    // expanded bar's title IS the hero now.
    val listBody: LazyListScope.() -> Unit = {
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
            LibraryFilter.SHOWS -> {
                if (state.followedShows.isEmpty()) {
                    item(key = "shows_empty") {
                        CollectionEmptyState(
                            isLoading = state.isLoadingCollections,
                            text      = stringResource(R.string.library_no_shows),
                        )
                    }
                } else {
                    // A show with no id can't be opened or keyed, and `me/shows` is null-tolerant
                    // by design — drop those rather than crash on a duplicate blank LazyColumn key.
                    items(
                        items = state.followedShows.filter { !it.id.isNullOrBlank() },
                        key   = { "show-${it.id}" },
                    ) { show ->
                        ShowListCard(
                            show    = show,
                            onClick = { haptics.confirm(); show.id?.let(onOpenShow) },
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

    // The bar's `actions` slot verbatim — the old floating TopActionPill's contents, including the
    // conditional refresh-error warning. `@Composable RowScope.() -> Unit` is exactly the shape
    // `TopAppBar`/`LargeFlexibleTopAppBar` want, so it hands straight over.
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

    // Quiet library summary under the big title. Always emitted (never a null subtitle) so the
    // expanded height can be pinned: `LargeFlexibleTopAppBar`'s default expandedHeight is 152dp
    // WITH a subtitle and 120dp without, so letting the slot appear when the counts land would
    // resize the bar under the user. Pinning the 152dp form and rendering an empty line until the
    // library is read costs only a small shift of the title's baseline, behind the cold-start
    // loading indicator.
    //
    // Both halves come from the SERVER's own `total`, never from a rendered list's length
    // (CLAUDE.md → "Counts come from the server, deltas are interim"). `state.playlists` is one
    // unpaged page of `me/playlists` (limit 50, null slots filtered out), so its `.size` reads 50
    // forever for a user with 120 playlists — `state.playlistCount` is the endpoint's `total`.
    // While that is still null (cache-warm, network-cold) the playlist half is SUPPRESSED rather
    // than guessed, and the liked half paints alone.
    val playlistsLabel = state.playlistCount?.let {
        pluralStringResource(R.plurals.library_bar_playlists, it, it)
    }
    val likedLabel     = state.likedSongCount.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.library_bar_liked, it, it)
    }
    val barSubtitle    = listOfNotNull(playlistsLabel, likedLabel).joinToString(" · ")

    val barTitle  = stringResource(R.string.app_name)
    // containerColor == scrolledContainerColor on purpose. M3's default `scrolledContainerColor`
    // is `surfaceContainer`, which the AMOLED overlay does NOT flatten (it only forces background /
    // surface / surfaceVariant), so on a pure-black theme the bar would light up as a grey band the
    // moment the list moved. The pane colour in both slots means the bar simply IS the pane.
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor         = paneColor,
        scrolledContainerColor = paneColor,
    )

    // Height gate, same measured-window idiom as the docked third pane (`LocalWindowInfo`): the
    // window-size-class height buckets top out at 900dp and have no 600dp boundary, so
    // `isHeightAtLeastBreakpoint(600)` cannot express this. The left pane is the window height
    // less 16dp of card padding, so the window read is accurate enough for a 600dp threshold.
    val paneHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val useLargeBar  = paneHeightDp >= LargeBarMinPaneHeight

    val listContentPadding = remember(navBarBottomDp) {
        PaddingValues(bottom = 100.dp + navBarBottomDp)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // The large bar COMPRESSES to the small bar as the list scrolls and stays small until the
        // list is back at the top (M3: "Medium and large flexible app bars … should remain small
        // until the page is scrolled back to the top") — that is exactly what
        // `exitUntilCollapsedScrollBehavior` does: it collapses in `onPreScroll` on any up-scroll
        // and re-expands only from the leftover of a down-scroll, i.e. once the list has hit 0.
        //
        // The compact bar keeps its OWN `rememberTopAppBarState()` rather than the hoisted one.
        // `PinnedScrollBehavior` never writes `heightOffset`, and `SingleRowTopAppBar` still passes
        // `scrolledOffset = { heightOffset }` — so a collapsed value inherited from the large bar
        // (e.g. rotating portrait → folded landscape) would draw the small bar shifted up and
        // clipped. Separate `if` branches are separate composition groups, so each keeps its own.
        val scrollBehavior = if (useLargeBar) {
            // ENTRY RESET — a collapsed bar over a list that is AT THE TOP is a state the behaviour
            // cannot produce itself, and cannot get out of either: `ExitUntilCollapsedScrollBehavior`
            // re-expands only from the leftover of a downward scroll, which a list already at offset
            // 0 never produces. It can be ARRIVED at, though, because `rememberTopAppBarState` is
            // `rememberSaveable` and the compact branch below keeps its own state: collapse the bar
            // in portrait, rotate to the short pane (a pinned bar, which never writes `heightOffset`),
            // rotate back, and the large branch re-enters with the stale collapsed offset — over a
            // list the compact pane may well have scrolled back to the top.
            //
            // So clear it whenever this branch (re)enters composition with the list at the top.
            // `remember`, not a `LaunchedEffect`: this runs BEFORE the bar composes, so there is no
            // frame of a shifted bar, and it is the same "assign during composition" idiom as the
            // Library's `PaneStateHolder` (docs/MOTION.md). It is keyed on [barState] so a new
            // hoisted instance re-arms it.
            //
            // It therefore also runs on every ordinary re-entry of this pane (the single-pane
            // browser is disposed whenever a detail opens). That is SAFE ONLY because of the guard,
            // which is exactly the impossible-state test — with the list restored part-way down, the
            // guard is false and the restored collapse the hoisting exists to preserve is untouched.
            // Do not narrow the guard to a rotation.
            remember(barState) {
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    barState.heightOffset  = 0f
                    barState.contentOffset = 0f
                }
            }
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = barState)
        } else {
            TopAppBarDefaults.pinnedScrollBehavior(state = rememberTopAppBarState())
        }

        if (useLargeBar) {
            LargeFlexibleTopAppBar(
                title          = { Text(barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle       = {
                    Text(barSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions        = actionsBar,
                expandedHeight = TopAppBarDefaults.LargeFlexibleAppBarWithSubtitleExpandedHeight,
                windowInsets   = appBarWindowInsets,
                colors         = barColors,
                scrollBehavior = scrollBehavior,
            )
        } else {
            TopAppBar(
                title          = { Text(barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions        = actionsBar,
                windowInsets   = appBarWindowInsets,
                colors         = barColors,
                scrollBehavior = scrollBehavior,
            )
        }

        // Pinned directly under the bar — it does not scroll away, and unlike the connected
        // ConnectedChoiceRow it divides the width evenly with ellipsised single-line labels, so a
        // narrow pane (the ≈340dp unfolded left pane) can't produce "Playl…" + a press-squeeze that
        // briefly un-truncates it.
        LibraryTabRow(
            selected       = state.libraryFilter,
            onSelect       = { filter ->
                // Reset the BAR AND THE LIST TOGETHER, at the call site, before the filter changes.
                //
                // The bar state and the list state are shared by all four tabs, and
                // `ExitUntilCollapsedScrollBehavior` has no "content is at the top, re-expand" path
                // (it re-expands only from the leftover of a downward scroll). So scrolling
                // Playlists until the bar collapsed and then tapping a tab whose content cannot
                // scroll — Shows with nothing followed, a two-item Albums — left the big title and
                // the subtitle collapsed over an empty pane with no gesture available to bring them
                // back. Resetting only the bar would be just as unreachable a state in reverse: a
                // fully expanded bar over a list parked mid-scroll.
                //
                // NOT a `LaunchedEffect(state.libraryFilter)`: this pane is recomposed on every
                // detail open/close and nav-back, so an effect keyed on the filter would wipe the
                // restored scroll position the hoisting exists to preserve, and would fire in the
                // middle of a predictive-back seek. `LibraryTabRow` already calls `onSelect` only on
                // a genuine change, so no extra guard is needed here.
                //
                // UX CHANGE: the scroll position no longer carries across filter tabs. It did before
                // (incidentally — the tabs share one `LazyListState`), but that was never designed:
                // the position of the Playlists list means nothing in Albums.
                barState.heightOffset  = 0f
                barState.contentOffset = 0f
                listState.requestScrollToItem(0)
                viewModel.setLibraryFilter(filter)
            },
            containerColor = paneColor,
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                val ptrState = rememberPullToRefreshState()
                PullThresholdHaptics(ptrState)
                // PULL-TO-REFRESH OUTSIDE, THE APP BAR'S CONNECTION INSIDE — verified against the
                // alpha27 sources, and the opposite of the obvious arrangement. Nested pre-scroll
                // dispatches outermost-first but POST-scroll innermost-first, and
                // `PullToRefreshModifierNode.onPostScroll` → `consumeAvailableOffset` returns the
                // WHOLE of `available.y` while `distancePulled == 0`. With the bar's connection on
                // an ancestor of the PTR box it would therefore never see a positive
                // `available.y`, and `ExitUntilCollapsedScrollBehavior` re-expands ONLY from that
                // leftover — so the collapsed bar could never come back. Inside, the bar consumes
                // the leftover first (expanding), and PTR only starts pulling once the bar is
                // fully out, which is also the right gesture order for the user.
                //
                // The up-scroll path is unaffected: PTR's `onPreScroll` consumes nothing while
                // `distancePulled == 0` (`coerceAtLeast(0f)` on a negative delta), and while a pull
                // IS in progress it correctly shrinks the pull before the bar collapses.
                PullToRefreshBox(
                    isRefreshing = state.isLibraryRefreshing,
                    onRefresh    = viewModel::refreshLibrary,
                    state        = ptrState,
                    modifier     = Modifier.fillMaxSize(),
                    indicator    = {
                        // No `statusBarsPadding()` any more: this Box starts below the app bar and
                        // the tab row, so the indicator is already clear of the status bar.
                        if (state.isLibraryRefreshing) {
                            ContainedLoadingIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 12.dp),
                            )
                        } else {
                            PullToRefreshDefaults.Indicator(
                                state        = ptrState,
                                isRefreshing = false,
                                modifier     = Modifier.align(Alignment.TopCenter),
                            )
                        }
                    },
                ) {
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = listContentPadding,
                    ) { listBody() }
                }
            }
        }
    }

    if (showRefreshErrorDialog && state.refreshError != null) {
        RefreshErrorDialog(error = state.refreshError, onDismiss = { showRefreshErrorDialog = false })
    }
}

// ── Library filter tabs (Playlists / Albums / Artists / Shows) ────────────────

/**
 * The content-type filter as full-width M3 primary tabs, pinned under the app bar — the
 * `ConnectedChoiceRow` picker this replaces truncated all four labels on a narrow pane and its
 * press-squeeze briefly un-truncated the pressed one.
 *
 * Conventions copied from `SearchScreen`'s `SearchTabRow` (already settled): a **fixed**
 * `PrimaryTabRow` (divides the width evenly, unlike the left-aligned scrollable variant),
 * `maxLines = 1` + ellipsis, `divider = {}`, the default expressive primary indicator, and one
 * `press()` haptic fired from the gesture on a GENUINE change only — re-tapping the active tab is
 * silent, matching the picker.
 *
 * `unselectedContentColor` is passed explicitly: `Tab` defaults it to `selectedContentColor`,
 * which `PrimaryTabRow` sets to `primary` for the whole row, so leaving it alone renders all four
 * labels in the accent colour.
 */
@Composable
private fun LibraryTabRow(
    selected       : LibraryFilter,
    onSelect       : (LibraryFilter) -> Unit,
    containerColor : Color,
    modifier       : Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        modifier         = modifier,
        containerColor   = containerColor,
        divider          = {},
    ) {
        LibraryFilter.entries.forEach { filter ->
            Tab(
                selected = filter == selected,
                onClick  = {
                    if (filter != selected) {
                        haptics.press()
                        onSelect(filter)
                    }
                },
                text     = {
                    Text(
                        stringResource(filter.labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                selectedContentColor   = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tab label for each filter. SHOWS stays LAST — `rememberSaveable` indices are ordinals. */
private val LibraryFilter.labelRes: Int
    get() = when (this) {
        LibraryFilter.PLAYLISTS -> R.string.library_filter_playlists
        LibraryFilter.ALBUMS    -> R.string.library_filter_albums
        LibraryFilter.ARTISTS   -> R.string.library_filter_artists
        LibraryFilter.SHOWS     -> R.string.library_filter_shows
    }

/** Loading / empty placeholder for the Albums, Artists and Shows filters. */
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
