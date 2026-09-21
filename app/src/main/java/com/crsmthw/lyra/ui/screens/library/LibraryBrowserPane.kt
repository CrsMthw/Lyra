package com.crsmthw.lyra.ui.screens.library

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.components.BarContentGap
import com.crsmthw.lyra.ui.components.BarFadeHeight
import com.crsmthw.lyra.ui.components.CrampedLabelAutoSize
import com.crsmthw.lyra.ui.components.LargeBarMinPaneHeight
import com.crsmthw.lyra.ui.components.TopBarFade
import com.crsmthw.lyra.ui.components.appBarWindowInsets
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.pagerTrackingIndicator
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

// ── Library browser pane ──────────────────────────────────────────────────────

// Seam constants — the shared `BarContentGap` / `BarFadeHeight` from `DetailTopBar.kt`.
// These were private twins (`LibraryTabRowGap` / `LibraryTabFadeHeight`) until the de-dup
// (see DetailTopBar.kt's KDoc on the constants).

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
internal fun LibraryBrowserPane(
    state                 : LibraryUiState,
    /** One scroll position per filter tab, hoisted to `LibraryScreen` so they outlive this pane —
     *  see its KDoc there. Each pager page below scrolls its own. */
    listStates            : LibraryBrowserListStates,
    /** App-bar collapse state, hoisted to `LibraryScreen` for the same reason as [listStates] —
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

    // ONE page's rows. The content-type filter is not a list item — it is the pinned PrimaryTabRow
    // under the app bar (see LibraryTabRow), whose four tabs are the four pages of the pager below;
    // and the "Lyra" hero band is gone, the expanded bar's title IS the hero now.
    //
    // Takes the page's filter rather than reading `state.libraryFilter`: a neighbouring page is
    // composed while a swipe is in flight, and it must render ITS OWN type, not the settled one.
    val pageBody: LazyListScope.(LibraryFilter) -> Unit = { pageFilter ->
        when (pageFilter) {
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
        if (state.refreshError != null || state.refreshPartial) {
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

    // Height gate — the SHARED `LargeBarMinPaneHeight` (`ui/components/RootTopBar.kt`), so this
    // pane's hand-rolled bar pair and the root bar every other screen uses cannot drift apart.
    // Same measured-window idiom as the docked third pane (`LocalWindowInfo`): the window-size-class
    // height buckets top out at 900dp and have no 600dp boundary, so `isHeightAtLeastBreakpoint(600)`
    // cannot express this. The left pane is the window height less 16dp of card padding, so the
    // window read is accurate enough for a 600dp threshold.
    val paneHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val useLargeBar  = paneHeightDp >= LargeBarMinPaneHeight

    // A page's first row starts a gap below the tab row, and the `LibraryTabFadeHeight` fade at the
    // top of the content area dissolves it into that row — the same seam Search gives its results.
    val listContentPadding = remember(navBarBottomDp) {
        PaddingValues(top = BarContentGap, bottom = 100.dp + navBarBottomDp)
    }

    // The full-area states, hoisted out of the branch below because the pager's sync effect has to
    // know whether the pager is composed at all. `fullAreaError` also keeps the error non-null for
    // its own branch without relying on a smart cast.
    val fullAreaError = state.error?.takeIf { state.playlists.isEmpty() && !state.isLoadingTracks }
    val pagerVisible  = !state.isLoading && fullAreaError == null

    // The four filter tabs ARE the four pages, so the browser swipes as well as taps. At pane scope,
    // not inside the content branch: a cold start composes the loading branch first, and a pager
    // state created inside the branch would be thrown away (and `requestScrollToPage` would have
    // nothing to write to) every time a full-area state took over.
    //
    // `rememberPagerState` is `rememberSaveable`-backed, but the single-pane browser is disposed
    // whenever a detail opens (`SinglePaneLayout`'s `AnimatedContent` is not a `SaveableStateHolder`),
    // so this one is rebuilt on every pane re-entry. All that costs is an in-flight swipe offset:
    // the filter itself lives in the ViewModel, so `initialPage` comes back correct, and the settle
    // collector's first emission is then the tab the VM already holds — a no-op (`setLibraryFilter`
    // returns early on an unchanged filter).
    val pagerState = rememberPagerState(initialPage = state.libraryFilter.ordinal) {
        LibraryFilter.entries.size
    }

    // Swipe → filter. A swipe reaches the VM silently — the `press()` haptic belongs to a TAP, and
    // is fired from the gesture inside `LibraryTabRow`.
    //
    // NEITHER `settledPage` NOR `!isScrollInProgress` ALONE MEANS "SETTLED", so the test is the
    // whole snapped-and-at-rest condition. Two facts force that:
    //  - a CANCELLED programmatic scroll is never re-settled by anything in the pager package (no
    //    settle-on-cancel path; `PagerWrapperFlingBehavior` only runs for a gesture fling), so
    //    `isScrollInProgress` can go false at a FRACTIONAL offset;
    //  - `PagerState.scroll` writes `settledPageState = currentPage` when it starts over a finished
    //    scroll, so a "wrong tab" correction tap ~150 ms after the first one (tap Albums, then tap
    //    Playlists) can make `settledPage` report the INTERMEDIATE page for the whole of the return
    //    animation — which this collector then pushed into `setLibraryFilter`, losing the user's
    //    second tap and (with the tap→pager guard below firing on the re-key) parking the pager
    //    between two pages.
    // Every state the decision rests on is read INSIDE the `snapshotFlow` block so it is observed —
    // a `filter {}` over a read outside it would sample a stale value and silently drop emissions.
    // `distinctUntilChanged()` is load-bearing: `snapshotFlow` only dedupes its own block result,
    // so a 2 → null → 2 sequence would otherwise deliver 2 twice (harmless, `setLibraryFilter` is
    // idempotent, but there is no reason to wake the VM for it).
    LaunchedEffect(pagerState, viewModel) {
        snapshotFlow {
            if (pagerState.isScrollInProgress ||
                abs(pagerState.currentPageOffsetFraction) > 0.01f) null
            else pagerState.currentPage
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { viewModel.setLibraryFilter(LibraryFilter.entries[it]) }
    }
    // Tab tap → pager. While a full-area state is up the pager is not composed, and a suspending
    // `animateScrollToPage` would park on its first-layout wait and then animate in front of the
    // user the moment the content appeared — so jump the state instead in that case.
    //
    // The early return carries BOTH halves of `animateScrollToPage`'s own guard
    // (`page == currentPage && currentPageOffsetFraction == pageOffsetFraction`). The offset half is
    // the one that matters: `currentPage` is the page nearest the snap position, so it flips at the
    // halfway mark while the offset is still fractional — and a re-key that cancels an in-flight
    // programmatic scroll at that instant would otherwise return early and leave the pager parked
    // between two pages with nothing to settle it.
    LaunchedEffect(state.libraryFilter, pagerVisible) {
        val target = state.libraryFilter.ordinal
        if (pagerState.currentPage == target &&
            pagerState.currentPageOffsetFraction == 0f) return@LaunchedEffect
        if (pagerVisible) pagerState.animateScrollToPage(target)
        else              pagerState.requestScrollToPage(target)
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
        //
        // NOTHING RESETS THE LARGE BAR'S COLLAPSE. It is where the user's last drag left it, across
        // filter-tab changes, detail opens and navigation (Cris's verdict, 2026-09-14: "the bar
        // should stay collapsed or stay expanded until the user scrolls the list"). The two resets
        // this pane used to carry are gone:
        //  - the tab-change reset (at the `LibraryTabRow` call site), because re-expanding the bar
        //    moves the tab row out from under the finger that is reaching for the next tab — which
        //    is the defect, not a fix for one;
        //  - the large-branch ENTRY reset that lived here, because its premise was wrong. It claimed
        //    a collapsed bar over a list at offset 0 is a state the behaviour cannot escape, but
        //    `ExitUntilCollapsedScrollBehavior.onPostScroll` re-expands from `available.y > 0`, and a
        //    `LazyColumn` that can consume nothing still DISPATCHES the whole drag delta
        //    (`ScrollingLogic.performScroll` always calls `dispatchPreScroll`/`dispatchPostScroll`,
        //    and `CanDragCalculation` only excludes a mouse) — so a downward drag on a short or
        //    empty pane expands the bar, and an upward one collapses it. Its guard (list at the top)
        //    was also permanently true on Albums / Artists / Shows, whose content is shorter than
        //    the pane, so on those tabs it wiped a deliberate collapse on every re-entry of this
        //    pane — a nav round trip to an album and back.
        //
        // What that entry reset legitimately protected against — the large branch inheriting an
        // offset no large bar ever produced — is closed at the source instead, in the compact branch.
        val scrollBehavior = if (useLargeBar) {
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = barState)
        } else {
            // Clear the hoisted state on entry so it only ever describes the LARGE bar: this branch
            // renders a pinned bar off its own state, and `PinnedScrollBehavior` never writes
            // `heightOffset`, so without this a collapse earned in portrait would still be sitting
            // in the hoisted state when a rotation back re-entered the large branch — over a list
            // this pane may well have scrolled to the top meanwhile.
            //
            // `remember`, not a `LaunchedEffect`: it runs once per branch entry and BEFORE the bar
            // composes, so there is no frame of a shifted bar — the same "assign during
            // composition" idiom as the Library's `PaneStateHolder` (docs/MOTION.md). Keyed on
            // [barState] so a new hoisted instance re-arms it. Nothing in this branch reads the
            // hoisted state, so the write cannot invalidate the composition that performs it.
            remember(barState) {
                barState.heightOffset  = 0f
                barState.contentOffset = 0f
            }
            TopAppBarDefaults.pinnedScrollBehavior(state = rememberTopAppBarState())
        }

        // The bar's connection, with purely HORIZONTAL events filtered out — see
        // [rememberVerticalOnlyNestedScroll]. It rides the pager (below), which dispatches a tab
        // swipe's deltas and its fling velocity on the x axis, and `settleAppBar` would SNAP a
        // partially collapsed bar open or closed on the resulting `Velocity(x, 0)`.
        val barNestedScroll = rememberVerticalOnlyNestedScroll(scrollBehavior.nestedScrollConnection)

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
        // ConnectedChoiceRow it divides the width evenly and can't press-squeeze a truncated label
        // back to full width for a frame. Truncation itself is not something the row form fixes:
        // it is `paneWidth / 4` minus the label's padding versus the label, so the labels are made
        // to FIT (halved padding + a shared auto-size recipe) — see `LibraryTabRow`.
        //
        // A tap SELECTS, and does nothing else: the bar keeps its collapse and each tab keeps its
        // own scroll position (`listStates`), so the row the finger is aiming at does not move. The
        // pager's own settle collector above turns a SWIPE into the same call.
        LibraryTabRow(
            pagerState     = pagerState,
            onSelect       = viewModel::setLibraryFilter,
            containerColor = paneColor,
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            } else if (fullAreaError != null) {
                val isRateLimit   = fullAreaError.contains("429")
                val retryAfterSec = if (isRateLimit)
                    Regex("Retry-After=(\\d+)").find(fullAreaError)?.groupValues?.get(1)?.toLongOrNull()
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
                                append(stringResource(R.string.library_rate_limit_body))
                                if (retryDisplay != null) append("\n\nRetry-After: $retryDisplay")
                                append("\n\n")
                                append(stringResource(R.string.library_rate_limit_reopen))
                            } else fullAreaError,
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
                //
                // The PAGER now sits between the two, and does not disturb that ordering: its own
                // `ScrollableNestedScrollConnection` leaves `onPreScroll` at zero and its
                // `onPostScroll` runs `performRawScroll`, which takes only the x component for a
                // horizontal scrollable — so a page's vertical leftover passes through it untouched
                // and still reaches the bar before PTR. Its `onPostFling` likewise reports only the
                // x part as consumed (`doFlingAnimation` seeds `result = available` and updates just
                // its own axis), so a vertical fling's velocity arrives at the bar intact.
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
                    // One page per filter tab, so the browser swipes as well as taps. The bar's
                    // connection rides the PAGER rather than a page's list: it is the parent of all
                    // four lists and of the pager's own horizontal scrollable, so one connection
                    // serves every page (cf. Search's `dismissImeOnScroll` placement).
                    HorizontalPager(
                        state                   = pagerState,
                        modifier                = Modifier
                            .fillMaxSize()
                            .nestedScroll(barNestedScroll),
                        // Neighbours compose only while a drag is actually in flight; each page
                        // renders the filter of its OWN index, so a half-swiped page shows the type
                        // it is sliding in for.
                        beyondViewportPageCount = 0,
                    ) { page ->
                        val pageFilter    = LibraryFilter.entries[page]
                        // Fetch when the page COMPOSES — for a neighbour that is the moment the
                        // drag starts, whereas `setLibraryFilter` only hears about a swipe once it
                        // SETTLES. Without this, the first swipe onto a never-loaded Albums /
                        // Artists / Shows page rendered its empty text ("No saved albums") for the
                        // whole gesture and swapped to the spinner on release.
                        // `ensureCollectionsLoaded` is a no-op while a sweep is in flight and once
                        // every leg has completed, so it cannot race the settle path into two
                        // fetches — and a swipe the user snaps back simply loads the content early.
                        LaunchedEffect(pageFilter) {
                            if (pageFilter != LibraryFilter.PLAYLISTS) viewModel.ensureCollectionsLoaded()
                        }
                        val pageListState = listStates[pageFilter]
                        // Per page, as Search does it: each list keeps its own baseline, and a
                        // neighbour composing mid-swipe ticks nothing (the helper is gated on that
                        // list's own `isScrollInProgress`).
                        ListScrollHaptics(pageListState)
                        // A LazyColumn even when the page's content cannot fill it (the empty state
                        // is an item inside it, not a Box in its place): a lazy list that can consume
                        // nothing still dispatches the drag through nested scroll, which is what lets
                        // a downward drag on a short pane re-expand the bar and an upward one
                        // collapse it.
                        LazyColumn(
                            state          = pageListState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = listContentPadding,
                        ) { pageBody(pageFilter) }
                    }

                    // The seam under the tab row: the pane colour fading out over the first rows, so
                    // they dissolve into the row instead of sliding past its labels — the same effect
                    // Search's top scrim gives its results, minus the status-bar half (this row is
                    // solid and sits ABOVE the content in the Column, so nothing scrolls under it).
                    //
                    // Composed as the LAST child of the PTR box's content, not after the box: the
                    // indicator is composed after `content()` (`PullToRefreshBox`: `content();
                    // indicator()`), so this draws over the rows but under the indicator. A plain
                    // background Box takes no pointer input, so it cannot eat a drag on the rows
                    // beneath it.
                    TopBarFade(
                        paneColor = paneColor,
                        modifier  = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    if (showRefreshErrorDialog && (state.refreshError != null || state.refreshPartial)) {
        RefreshErrorDialog(
            error          = state.refreshError,
            isPartialSweep = state.refreshPartial,
            onDismiss      = { showRefreshErrorDialog = false },
        )
    }
}

// ── Library filter tabs (Playlists / Albums / Artists / Shows) ────────────────

/**
 * Tab height, M3's own `PrimaryNavigationTabTokens.ContainerHeight`. Applied by hand because the
 * generic [Tab] overload (see [LibraryTabRow]) does not carry it — only the `text =` slot overload's
 * `TabBaselineLayout` does. On the Tab's `modifier`, never inside the content: the row's height is
 * `max(tabMeasurable.maxIntrinsicHeight)`.
 */
private val LibraryTabHeight = 48.dp

/**
 * Horizontal padding around a tab label, halved from the 16dp M3 hard-codes.
 *
 * `TabBaselineLayout` wraps the `text =` slot in `padding(horizontal = HorizontalTextPadding)` with
 * `HorizontalTextPadding = 16.dp` (internal, not overridable), i.e. **32dp of a tab is padding**.
 * Same reasoning as `ConnectedChoiceRow`'s `SegmentContentPadding`: on a narrow pane that is most
 * of the tab, and because the label is centred the trim is invisible wherever there is room (the
 * visible gap is `(tabWidth - labelWidth) / 2`, not the padding). Reaching it means building the
 * tab with the generic `content` overload instead of the `text =` slot.
 */
private val LibraryTabLabelPadding = 8.dp

/**
 * What [LibraryTabLabelPadding] costs the indicator, and what the [LibraryTabRow] indicator adds
 * back: `2 × (16dp − 8dp)`.
 *
 * `TabRowImpl` derives each tab's `TabPosition.contentWidth` as
 * `min(tabMeasurable.maxIntrinsicWidth, tabWidth) - HorizontalTextPadding * 2` (floored at 24dp) —
 * it assumes the tab's intrinsic width includes M3's own 32dp of text padding. With 8dp a side the
 * intrinsic width is 16dp smaller, so an indicator sized off `contentWidth` alone would come out
 * 16dp narrower than the label instead of hugging it.
 *
 * It is added to each `contentWidth` the indicator interpolates between (see [LibraryTabRow]). It
 * used to be added by a `Modifier.layout` nested INSIDE `tabIndicatorOffset`, and that note claimed
 * the node "reports the child's actual width, which this node is free to make larger" — which is
 * not true: `Placeable.width` is the measured width COERCED back into the constraints it was
 * measured with, so the widened child still reported `contentWidth` and only came out right because
 * the resulting `apparentToRealOffset.x` of −8dp cancelled the difference. That trick is gone from
 * the app; the compensation now lands in the indicator's own width arithmetic, where it is plain.
 */
private val LibraryTabIndicatorCompensation = 16.dp

/**
 * The content-type filter as full-width M3 primary tabs, pinned under the app bar.
 *
 * The four tabs are the four pages of the browser's `HorizontalPager`, so the lists SWIPE as well as
 * tap. This row only reports taps: a swipe reaches the ViewModel through the pager's settle
 * collector (see `LibraryBrowserPane`), which is also why the `press()` haptic lives here — a tap is
 * a discrete choice, a swipe is its own feedback.
 *
 * Conventions copied from `SearchScreen`'s `SearchTabRow` (already settled): a **fixed**
 * `PrimaryTabRow` (divides the width evenly, unlike the left-aligned scrollable variant),
 * `maxLines = 1` + ellipsis, `divider = {}`, the expressive primary indicator hugging the label,
 * and one `press()` haptic fired from the gesture on a GENUINE change only — re-tapping the active
 * tab is silent, matching the `ConnectedChoiceRow` picker this replaces.
 *
 * **What the fixed row actually removed, and what it did not.** The picker's defects were the
 * press-squeeze (`animateWidth`) briefly un-truncating the pressed label, which reads as a glitch,
 * and the `softWrap = false` bug that rendered the longest label start-aligned and clipped. Both
 * are gone by construction here. **Truncation is not**: it is governed by `paneWidth / 4` minus the
 * label's horizontal padding versus the label, so the labels have to be made to FIT.
 *
 * Two things make them fit, mirroring the picker:
 * - the generic `Tab(selected, onClick, modifier, enabled, selectedContentColor,
 *   unselectedContentColor, interactionSource) { content }` overload, so the label gets
 *   [LibraryTabLabelPadding] instead of M3's hard-coded 16dp a side (that overload provides neither
 *   the tab's height nor its text style, so both are passed by hand — `TitleSmall` is what
 *   `PrimaryNavigationTabTokens.LabelTextFont` resolves to);
 * - `CrampedLabelAutoSize`, the SHARED 11–14sp `StepBased` recipe, so a label that still does not
 *   fit shrinks rather than ellipsises.
 *
 * The arithmetic, at the narrowest pane the app supports — a 600dp window's two-pane left pane,
 * ≈242dp: `tabWidth = 60.5dp`, label room `60.5 - 16 = 44.5dp`. "Playlists" measures ≈53dp at 14sp
 * and ≈42dp at 11sp, so it fits at ~11.5sp. With M3's 32dp the room was 28.5dp, which "Playlists"
 * cannot fit at ANY size in the range. On the unfolded Fold's ≈340dp left pane the room is 69dp and
 * every label sits at the full 14sp. Known cost, as for the ButtonGroup: on a cramped pane
 * "Playlists" renders a step or two smaller than its three neighbours.
 *
 * `unselectedContentColor` is passed explicitly: `Tab` defaults it to `selectedContentColor`,
 * which `PrimaryTabRow` sets to `primary` for the whole row, so leaving it alone renders all four
 * labels in the accent colour.
 *
 * **Which tab reads as selected is the PAGER's `currentPage`, not the ViewModel's filter** — "the
 * page that sits closest to the snapped position", so it flips at the midpoint of a swipe, which is
 * exactly when the label should take the accent colour. The VM still learns the filter at settle
 * (the collector in `LibraryBrowserPane`); this row simply stops waiting for it, as the indicator
 * does. Reading it in composition recomposes this row once per page change — four `Tab`s and a
 * `Spacer`, and the indicator's own geometry is layout-only, so nothing else is repeated.
 *
 * `selectedTabIndex` is passed for readability and is otherwise INERT: `PrimaryTabRow` uses it only
 * inside its own default `indicator` lambda, which this row replaces, and `TabRowImpl` never sees
 * it. Selection for accessibility comes from each `Tab`'s own `selected` flag — do not "restore"
 * the parameter on the assumption that the indicator depends on it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryTabRow(
    pagerState     : PagerState,
    onSelect       : (LibraryFilter) -> Unit,
    containerColor : Color,
    modifier       : Modifier = Modifier,
) {
    val haptics       = LocalHapticFeedback.current
    val selectedIndex = pagerState.currentPage
    PrimaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier         = modifier,
        containerColor   = containerColor,
        // THE INDICATOR FOLLOWS THE PAGER, it does not animate after it. `tabIndicatorLayout` is
        // M3's own hook for exactly this: the block runs on every LAYOUT pass and reads the pager's
        // live page + offset fraction THERE, so a swipe re-places and re-measures the bar per frame
        // while recomposing nothing (a state read inside a measure block invalidates layout, not
        // composition). A TAP rides the same path: `animateScrollToPage` moves the pager, the bar
        // moves with it — which is why the animated `tabIndicatorOffset` is gone rather than kept
        // alongside. Two animations over one geometry would fight, and the old one was the whole
        // defect Cris reported: driven by the ViewModel's filter, it did nothing until the swipe
        // settled and then slid across on its own.
        //
        // THE HARD RULE (docs/MOTION.md) is not in play, and no longer even nearly: there is no
        // `Animatable` and no `Transition` left in this row at all. The bar is scroll-driven
        // geometry, like an app bar's collapse.
        //
        // Geometry, reproducing what `TabRowImpl` does for the stock indicator:
        //  - `width` is the lerped CONTENT width (M3's `matchContentSize` look — the bar hugs the
        //    label and MORPHS between two labels' widths mid-swipe) plus
        //    [LibraryTabIndicatorCompensation], the 16dp the halved label padding costs;
        //  - the bar ends up CENTRED in the tab, at `left + (tabWidth - width) / 2`, but that
        //    centring is NOT added here. Reporting `placeable.width` (the lerped width) while
        //    `TabRowImpl` measured this node at `minWidth = maxWidth = tabWidth` makes
        //    `Placeable.width` coerce back up to `tabWidth` and sets
        //    `apparentToRealOffset.x = (tabWidth - width) / 2`, which `place` applies for us. That
        //    is stock M3's own mechanism — `TabIndicatorOffsetNode` also places at the bare `left`.
        //    Adding the half-slack as well would double it: right of centre at rest, and invisible
        //    mid-swipe, which is how such a bug survives a device pass.
        //  - the RTL negation is `TabIndicatorOffsetNode`'s, for the same reason: `TabRowImpl`
        //    places this node with `placeRelative`, so its own box is already mirrored.
        //
        // `width = Dp.Unspecified` on the indicator is still mandatory: `PrimaryIndicator`'s own
        // default is a 24dp stub, and only `Dp.Unspecified` makes its `requiredWidth` a pass-through
        // so the constraint below is what decides.
        // The indicator follows the pager per frame — see `pagerTrackingIndicator`'s KDoc.
        // `LibraryTabIndicatorCompensation` compensates the halved label padding (see its KDoc).
        indicator        = {
            TabRowDefaults.PrimaryIndicator(
                modifier = pagerTrackingIndicator(pagerState, extraWidth = LibraryTabIndicatorCompensation),
                width    = Dp.Unspecified,
            )
        },
        divider          = {},
    ) {
        LibraryFilter.entries.forEachIndexed { index, filter ->
            Tab(
                selected = index == selectedIndex,
                // Fired from the gesture, and only on a genuine change — re-tapping the tab that
                // is VISUALLY selected (the pager's page, not the VM's filter, which can still be
                // catching up) is intentionally silent, matching the picker this replaced.
                onClick  = {
                    if (index != selectedIndex) {
                        haptics.press()
                        onSelect(filter)
                    }
                },
                modifier               = Modifier.height(LibraryTabHeight),
                selectedContentColor   = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    stringResource(filter.labelRes),
                    style     = MaterialTheme.typography.titleSmall,
                    autoSize  = CrampedLabelAutoSize,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(horizontal = LibraryTabLabelPadding),
                )
            }
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

// ── The app bar's nested-scroll connection, minus the pager's horizontal axis ─

/**
 * Wraps a nested-scroll [connection] so that purely HORIZONTAL events never reach it.
 *
 * The app bar's connection rides the pager, which dispatches a tab swipe's deltas and its fling
 * velocity on the x axis with `y == 0f`. Two of those matter:
 * - `ExitUntilCollapsedScrollBehavior.onPostFling` calls `settleAppBar(state, available.y, …)`,
 *   which on a zero vertical velocity SNAPS a partially collapsed bar fully open or fully closed —
 *   i.e. a tab swipe would move the bar, which is exactly what this rework removes.
 * - `onPostScroll` adds `consumed.y` to `contentOffset`, which nothing horizontal should touch.
 *
 * The filter is the IDENTITY for everything the old arrangement could deliver (the connection used
 * to sit on the `LazyColumn`, which dispatches y-only deltas and a y-only fling velocity): it drops
 * an event only when every y component is zero AND some x component is not. An all-zero event still
 * goes through, so the bar's own settle behaviour on a vertical gesture is untouched.
 */
@Composable
private fun rememberVerticalOnlyNestedScroll(
    connection: NestedScrollConnection,
): NestedScrollConnection = remember(connection) {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.isHorizontalOnly()) Offset.Zero
            else connection.onPreScroll(available, source)

        override fun onPostScroll(
            consumed : Offset,
            available: Offset,
            source   : NestedScrollSource,
        ): Offset =
            if (consumed.isHorizontalOnlyWith(available)) Offset.Zero
            else connection.onPostScroll(consumed, available, source)

        override suspend fun onPreFling(available: Velocity): Velocity =
            if (available.isHorizontalOnly()) Velocity.Zero
            else connection.onPreFling(available)

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (consumed.isHorizontalOnlyWith(available)) Velocity.Zero
            else connection.onPostFling(consumed, available)
    }
}

private fun Offset.isHorizontalOnly(): Boolean = y == 0f && x != 0f

private fun Velocity.isHorizontalOnly(): Boolean = y == 0f && x != 0f

private fun Offset.isHorizontalOnlyWith(other: Offset): Boolean =
    y == 0f && other.y == 0f && (x != 0f || other.x != 0f)

private fun Velocity.isHorizontalOnlyWith(other: Velocity): Boolean =
    y == 0f && other.y == 0f && (x != 0f || other.x != 0f)
