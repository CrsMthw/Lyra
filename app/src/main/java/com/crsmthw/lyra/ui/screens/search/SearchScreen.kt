package com.crsmthw.lyra.ui.screens.search

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.local.RecentSearch
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyShow
import com.crsmthw.lyra.ui.components.TrackActionTarget
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.components.TrackRow
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import com.crsmthw.lyra.util.rememberSearchBarMorphClip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
fun SearchScreen(
    viewModel             : SearchViewModel,
    playerViewModel       : PlayerViewModel,
    onBack                : () -> Unit,
    onOpenPlayer          : () -> Unit,
    onAlbumClick          : (albumId: String) -> Unit,
    onArtistClick         : (artistId: String) -> Unit,
    onShowClick           : (showId: String) -> Unit,
    onTrackClick          : (uri: String, allUris: List<String>) -> Unit,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state          by viewModel.uiState.collectAsStateWithLifecycle()
    val recents        by viewModel.recentSearches.collectAsStateWithLifecycle()
    val keyboard        = LocalSoftwareKeyboardController.current
    val focusManager    = LocalFocusManager.current
    val focusRequester  = remember { FocusRequester() }
    val haptics         = LocalHapticFeedback.current

    // The input field owns its own text (M3's TextFieldState form). The ViewModel stays the source
    // of truth for the *searched* query, fed from here so its 400 ms debounce is untouched; it is
    // seeded from the VM so returning to a still-live Search entry keeps what was typed.
    val queryState      = rememberTextFieldState(initialText = state.query)
    LaunchedEffect(queryState, viewModel) {
        snapshotFlow { queryState.text.toString() }.collect { viewModel.onQueryChange(it) }
    }
    // Collapsed to a Boolean on purpose. `TextFieldState.text` reads one snapshot state carrying
    // text PLUS selection PLUS composing region, so reading it directly in the content lambda below
    // subscribed that whole scope — the results LazyColumn included — to cursor drags and IME
    // composing updates. Derived, only a genuine blank↔non-blank flip invalidates it, and
    // derivedStateOf adds no frame of lag (dependents are notified with the recomputed value).
    val queryBlank by remember(queryState) { derivedStateOf { queryState.text.isBlank() } }

    // One scroll position per tab, kept at screen scope so switching away and back lands where you
    // left off — the pager composes only the settled page plus, mid-swipe, its neighbour.
    // `rememberLazyListState` is already `rememberSaveable`-backed, and three distinct call sites
    // get three distinct keys.
    val tracksListState  = rememberLazyListState()
    val albumsListState  = rememberLazyListState()
    val artistsListState = rememberLazyListState()
    val showsListState   = rememberLazyListState()
    // A new search starts at the top of every tab. The states are hoisted so each tab keeps its
    // scroll position across tab switches — which would also carry the previous query's position
    // into the next one, i.e. new results landing mid-list. Keyed on the SEARCHED query, not the
    // live field text. (The other half of that inheritance — an immediate page-2 fetch — is closed
    // by the stale-layout guard inside `SearchResultsList`, not here.)
    //
    // A CHANGE of that query, not merely an entry. `LaunchedEffect(state.resultsQuery)` runs again
    // every time the screen is composed, and popping back from Album/Artist/Show detail composes
    // Search afresh against the SAME ViewModel — same `resultsQuery`, four `LazyListState`s just
    // restored to where the user left them — so an unguarded reset threw all four away and dumped
    // them at the top of the list they had just come back from (device pass 2026-09-13). The guard
    // is `rememberSaveable`, never a plain `remember`: it has to survive the same save/restore that
    // brings the scroll offsets back, or a re-entry would read it fresh and reset anyway. `null` is
    // the sentinel rather than "" so "no reset has run in this instance" stays distinguishable from
    // "the reset ran for a cleared field". A query typed after returning is a genuine change and
    // still resets all four.
    //
    // `requestScrollToItem`, NOT the suspending `scrollToItem`, for the same reason the pager effect
    // below uses `requestScrollToPage`: `LazyListState.scroll` waits for that list's FIRST layout
    // before doing anything, and only the settled page is composed (`beyondViewportPageCount = 0`),
    // so a tab that has never been laid out in this composition blocks forever — and blocks every
    // call after it in this one coroutine. A genuine new query is exactly that case: the three tabs
    // the user is not on have not been measured for these results, so the suspending form would
    // park on the first of them and leave the rest holding the previous query's offset. The request
    // form writes the position synchronously and schedules the remeasure, composed or not.
    var lastResetQuery by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.resultsQuery) {
        if (state.resultsQuery == lastResetQuery) return@LaunchedEffect
        lastResetQuery = state.resultsQuery
        tracksListState.requestScrollToItem(0)
        albumsListState.requestScrollToItem(0)
        artistsListState.requestScrollToItem(0)
        showsListState.requestScrollToItem(0)
    }

    // One page per tab, so the lists can be SWIPED between as well as tapped. The pager is
    // composed only in the results branch below, which is exactly `pagerVisible`.
    val pagerState   = rememberPagerState(initialPage = state.tab.ordinal) { SearchTab.entries.size }
    val pagerVisible = !state.isLoading && state.error == null && state.results != null

    // Swipe → tab. `settledPage` changes only once a drag or a programmatic scroll comes to rest:
    // while one runs it holds the page the scroll STARTED from, which is the value this collector
    // has already seen, so a tab tap's own `animateScrollToPage` can never write the old tab back
    // mid-flight. `selectTab` no-ops on an unchanged tab, so the settle after a tap is silent too.
    LaunchedEffect(pagerState, viewModel) {
        snapshotFlow { pagerState.settledPage }
            .collect { viewModel.selectTab(SearchTab.entries[it]) }
    }
    // Tab tap → pager, plus the reset to Tracks that clearing the field performs. The pager is not
    // composed while there are no results (or while a new query is loading), and a suspending
    // `animateScrollToPage` would park on the pager's first-layout wait and then animate that reset
    // in front of the user the moment the next query's results appeared — so jump the state instead.
    LaunchedEffect(state.tab, pagerVisible) {
        val target = state.tab.ordinal
        if (pagerState.currentPage == target) return@LaunchedEffect
        if (pagerVisible) pagerState.animateScrollToPage(target)
        else              pagerState.requestScrollToPage(target)
    }

    // Container transform: the floating bar shares bounds with the Library search FAB (same
    // SEARCH_BAR_SHARED_KEY) so tapping the FAB expands it into this bar. Null scopes (two-pane /
    // previews) fall back to no morph. clipInOverlayDuringTransition is the OUTLINE morph
    // (stadium ↔ SoftBurst) — the identical clip both Library FAB call sites pass, so the exiting
    // and entering halves are clipped to the same path on every frame. See util/SearchBarMorph.kt.
    val searchBarSharedModifier: Modifier =
        if (sharedTransitionScope != null && animatedContentScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState      = rememberSharedContentState(key = SEARCH_BAR_SHARED_KEY),
                    animatedVisibilityScope = animatedContentScope,
                    boundsTransform         = rememberArtBoundsTransform(),
                    clipInOverlayDuringTransition = rememberSearchBarMorphClip(),
                )
            }
        } else Modifier

    // Edge-to-edge under a transparent status bar (Lyra's floating-controls pattern) — no opaque
    // top app bar. The results pane self-pads under a floating M3 search bar + a top scrim.
    val density        = LocalDensity.current
    val statusBarTopDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val scrimHeight    = navBarBottomDp + 48.dp
    val background     = MaterialTheme.colorScheme.background
    // Two different top insets, because the two things that live under the bar are mutually
    // exclusive: the Recent list only exists while the query is BLANK, which is exactly when the
    // tab row is absent — so handing it the results' inset would shove it down by a row height
    // with nothing above it.
    val barInset       = statusBarTopDp + SearchBarBlockHeight
    val tabRowBottom   = barInset + SearchTabRowHeight
    val resultsInset   = tabRowBottom + SearchTabRowGap
    // Where the full-area states (spinner, error) must start so they centre in the VISIBLE area
    // rather than in the whole screen: half of a `ContainedLoadingIndicator` used to sit behind the
    // bar and the type chooser (device pass 2026-09-12, checklist 1). The per-tab "No results"
    // message takes the same inset from its list's own `contentPadding`.
    val fullAreaInset  = if (queryBlank) barInset else resultsInset

    // Long-press on a result opens the song menu — but not until the keyboard is gone. The menu is a
    // ModalBottomSheet in its own dialog window, and when that window appears in the same frame the
    // IME starts hiding, the sheet's enter animation stalls behind the IME hide animation (device
    // pass 2026-09-12: "the bottom sheet slide out lags for a second"). Search is the only screen in
    // the app with a keyboard up over a track list, so this stays local to it.
    var pendingTrackAction by remember { mutableStateOf<TrackActionTarget?>(null) }
    val imeInsets = WindowInsets.ime
    LaunchedEffect(pendingTrackAction) {
        val target = pendingTrackAction ?: return@LaunchedEffect
        // Reading the inset through a snapshotFlow rather than the composable `isImeVisible` keeps
        // the IME's per-frame inset changes out of this screen's content lambda. An already-hidden
        // keyboard resolves on the first emission, so a long-press with no keyboard up adds no
        // delay; the timeout covers a device that never reports the inset reaching zero.
        withTimeoutOrNull(ImeHideTimeoutMs) {
            snapshotFlow { imeInsets.getBottom(density) }.first { it == 0 }
        }
        viewModel.trackActions.open(target)
        pendingTrackAction = null
    }

    // THE screen's single horizontal inset. In landscape with 3-button navigation the nav bar sits
    // on the left or right edge, and the search field, the Recent rows' X buttons and the result
    // rows all ran underneath it — only the inner results Box carried a narrower nav-bars-only
    // inset, and the bar and recents had none at all. Applied once here, every descendant clears
    // it, and because `windowInsetsPadding` CONSUMES what it applies, the `navigationBarsPadding()`
    // calls further down resolve to the remaining BOTTOM inset only, so there is no double padding
    // (CLAUDE.md → Inset Rules). `displayCutout` joins the union for a landscape notch on the same
    // edge. The scrims are inset along with everything else, which is invisible: nothing is drawn
    // in that strip any more either. The mini player is NOT affected — the app-wide
    // `PlayerPanelHost` in `LyraNavGraph` renders it outside this screen and insets it itself.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
            ),
    ) {
        // Scrolling content rides above the keyboard; the floating bar + tab row do not.
        Box(modifier = Modifier.fillMaxSize().imePadding()) {
            when {
                state.isLoading -> {
                    Box(
                        modifier         = Modifier.fillMaxSize()
                            .padding(top = fullAreaInset).navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContainedLoadingIndicator(modifier = Modifier.size(90.dp))
                    }
                }
                state.error != null -> {
                    Box(
                        modifier         = Modifier.fillMaxSize()
                            .padding(top = fullAreaInset).navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                state.results != null -> {
                    val results     = state.results!!
                    val emptyText   = stringResource(R.string.search_no_results, state.query)
                    val listPadding = remember(resultsInset, navBarBottomDp) {
                        // 100dp of mini-player clearance on top of the nav bar, so the last row
                        // scrolls clear of the floating bar (UI_PATTERNS.md → "LazyColumn bottom
                        // padding must include nav bar height").
                        PaddingValues(top = resultsInset, bottom = 100.dp + navBarBottomDp)
                    }

                    // One vertical list per type, one page each, so the tabs can be swiped as well
                    // as tapped. Not an `AnimatedContent`: the pager's own snap IS the swap, it is
                    // gesture-driven rather than a content-swap transition, so the finite
                    // `screenTransitionSpec()` rule (docs/MOTION.md → THE HARD RULE) is not in play.
                    HorizontalPager(
                        state                   = pagerState,
                        modifier                = Modifier.fillMaxSize(),
                        // Neighbours compose only while a drag is actually in flight; the `isActive`
                        // gate below is what stops one of them paging itself as it slides into view.
                        beyondViewportPageCount = 0,
                    ) { page ->
                        val pageTab  = SearchTab.entries[page]
                        // The paging trigger belongs to the tab the user has SETTLED on. Without
                        // this a 10px drag toward a short tab (fewer rows than the threshold) would
                        // compose it, satisfy `reachedBottom` immediately and fetch its page 2 for a
                        // tab the user never arrived at — then spring back, leaving a spinner and a
                        // mutated list behind on an off-screen tab.
                        val isActive = pagerState.settledPage == page
                        val paging   = state.pagingFor(pageTab)

                        when (pageTab) {
                            SearchTab.TRACKS -> {
                                val tracks = results.tracks?.items ?: emptyList()
                                SearchResultsList(
                                    tab            = SearchTab.TRACKS,
                                    listState      = tracksListState,
                                    itemCount      = tracks.size,
                                    paging         = paging,
                                    isActive       = isActive,
                                    isLoading      = state.isLoading,
                                    emptyText      = emptyText,
                                    contentPadding = listPadding,
                                    onLoadMore     = viewModel::loadMore,
                                    onRetry        = viewModel::retryLoadMore,
                                ) {
                                    items(tracks, key = { "track_${it.id}" }) { track ->
                                        TrackRow(
                                            track   = track,
                                            // Drop the keyboard from the gesture, exactly as the
                                            // album/artist/show rows do. A track tap pushes the
                                            // full player, and the mini player's bottom inset
                                            // unions the IME — dismissing it here is what lets the
                                            // bar ride the keyboard down on the way out instead of
                                            // the IME retracting behind the pushed screen.
                                            onClick = {
                                                keyboard?.hide()
                                                viewModel.addRecentSearch(track.toRecentSearch())
                                                val idx = tracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
                                                onTrackClick(track.uri, tracks.drop(idx).map { it.uri })
                                            },
                                            // Drop the keyboard HERE, from the gesture, then hand
                                            // the target to the effect above — it opens the sheet
                                            // once the IME inset is actually back to zero. TrackRow
                                            // still fires its own long-press haptic on the gesture.
                                            onLongClick = {
                                                focusManager.clearFocus(force = true)
                                                keyboard?.hide()
                                                pendingTrackAction = track.toTrackActionTarget()
                                            },
                                        )
                                    }
                                }
                            }

                            SearchTab.ALBUMS -> {
                                val albums = results.albums?.items ?: emptyList()
                                SearchResultsList(
                                    tab            = SearchTab.ALBUMS,
                                    listState      = albumsListState,
                                    itemCount      = albums.size,
                                    paging         = paging,
                                    isActive       = isActive,
                                    isLoading      = state.isLoading,
                                    emptyText      = emptyText,
                                    contentPadding = listPadding,
                                    onLoadMore     = viewModel::loadMore,
                                    onRetry        = viewModel::retryLoadMore,
                                ) {
                                    items(albums, key = { "album_${it.id}" }) { album ->
                                        AlbumRow(
                                            album   = album,
                                            // Drop the keyboard from the gesture, as the back arrow
                                            // does. Nothing dismissed the IME on the way OUT to a
                                            // detail screen, so it was still animating down (or
                                            // still up) behind the pushed screen, and the state on
                                            // return depended on that race. Same on the track,
                                            // artist and show rows and on the Recent list below.
                                            onClick = {
                                                keyboard?.hide()
                                                viewModel.addRecentSearch(album.toRecentSearch())
                                                onAlbumClick(album.id)
                                            },
                                        )
                                    }
                                }
                            }

                            SearchTab.ARTISTS -> {
                                val artists = results.artists?.items ?: emptyList()
                                SearchResultsList(
                                    tab            = SearchTab.ARTISTS,
                                    listState      = artistsListState,
                                    itemCount      = artists.size,
                                    paging         = paging,
                                    isActive       = isActive,
                                    isLoading      = state.isLoading,
                                    emptyText      = emptyText,
                                    contentPadding = listPadding,
                                    onLoadMore     = viewModel::loadMore,
                                    onRetry        = viewModel::retryLoadMore,
                                ) {
                                    items(artists, key = { "artist_${it.id}" }) { artist ->
                                        ArtistRow(
                                            artist  = artist,
                                            onClick = {
                                                keyboard?.hide()
                                                viewModel.addRecentSearch(artist.toRecentSearch())
                                                onArtistClick(artist.id)
                                            },
                                        )
                                    }
                                }
                            }

                            SearchTab.SHOWS -> {
                                // Id-less shows are dropped once, up front: every podcast field is
                                // nullable (Gson bypasses the constructor), and a show with no id
                                // can neither be opened nor keyed. Deriving the list here keeps
                                // `itemCount` in step with what is actually rendered — pass the
                                // unfiltered size and an all-id-less page would show a blank list
                                // with no empty state.
                                val shows = results.shows?.items?.filter { !it.id.isNullOrBlank() }
                                            ?: emptyList()
                                SearchResultsList(
                                    tab            = SearchTab.SHOWS,
                                    listState      = showsListState,
                                    itemCount      = shows.size,
                                    paging         = paging,
                                    isActive       = isActive,
                                    isLoading      = state.isLoading,
                                    emptyText      = emptyText,
                                    contentPadding = listPadding,
                                    onLoadMore     = viewModel::loadMore,
                                    onRetry        = viewModel::retryLoadMore,
                                ) {
                                    items(shows, key = { "show_${it.id}" }) { show ->
                                        ShowRow(
                                            show    = show,
                                            onClick = {
                                                keyboard?.hide()
                                                viewModel.addRecentSearch(show.toRecentSearch())
                                                show.id?.let(onShowClick)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Blank query → nothing; just the floating bar over an empty background.
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

        // Recent searches — only while the query is blank. Lives in the outer (non-ime-padded) Box,
        // top-anchored, so the keyboard never lifts it; it vanishes the moment anything is typed.
        // Gated on the *field's* text, not the ViewModel's: the VM is a debounce-coupled frame or
        // two behind now, which would flash this list over the results on the first keystroke.
        if (queryBlank && recents.isNotEmpty()) {
            // A full cap-10 list plus its header and Clear all overruns the shorter geometries
            // (folded outer screen, any landscape), and Clear all sits at the END — so the column
            // scrolls. The bottom inset is max(IME, nav bar) and is applied OUTSIDE the scroll, so
            // the viewport ends above the keyboard rather than behind it: the screen auto-focuses,
            // so the keyboard is up by default and a viewport that ran under it would park Clear all
            // out of reach at full scroll. Top-anchoring (the reason this lives in the outer,
            // non-imePadding Box) is untouched — only the viewport's bottom edge moves.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = barInset)
                    .windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars)
                            .only(WindowInsetsSides.Bottom)
                    )
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionHeader(stringResource(R.string.search_recent))
                recents.forEach { recent ->
                    RecentSearchRow(
                        recent   = recent,
                        onClick  = {
                            haptics.confirm()
                            // The keyboard is up by default over this list (blank field ⇒
                            // auto-focus), so hiding it from the gesture matters most here.
                            keyboard?.hide()
                            viewModel.addRecentSearch(recent)   // re-tapping moves it to the front
                            when (recent.type) {
                                "track"    -> onTrackClick(recent.uri, listOf(recent.uri))
                                "album"    -> onAlbumClick(recent.id)
                                "artist"   -> onArtistClick(recent.id)
                                "show"     -> onShowClick(recent.id)
                                "playlist" -> onOpenPlayer()
                            }
                        },
                        onRemove = {
                            haptics.press()
                            viewModel.removeRecentSearch(recent.id)
                        },
                    )
                }
                TextButton(
                    onClick  = {
                        haptics.press()
                        viewModel.clearRecentSearches()
                    },
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.search_recent_clear_all))
                }
                // Mini-player clearance, INSIDE the scroll: "Clear all" is the last thing in the
                // list, and the floating bar would otherwise cover it once something is playing.
                Spacer(Modifier.height(100.dp))
            }
        }

        // Top scrim — fades content out under the floating controls (covers the status bar; no
        // statusBarsPadding). Not the shared `TopScrim`, because this one has to reach the tab row:
        // with full-width tabs, rows scroll under BOTH the bar and the tabs, and a plain
        // top-to-transparent gradient was already spent by the time it got there — rows slid
        // visibly past the tab labels and the fade read as aimed at the status bar instead (device
        // pass 2026-09-12, checklist 10). So while the tabs are up the gradient stays fully opaque
        // down to the tab row's BOTTOM edge and only fades out over a `TopScrimTail` below it, and
        // rows dissolve into the tabs. A blank query has no tab row and keeps `TopScrim`'s exact
        // two-stop brush and height, so that state is pixel-identical.
        val (topScrimHeight, topScrimBrush) =
            remember(queryBlank, statusBarTopDp, tabRowBottom, background) {
                if (queryBlank) {
                    (statusBarTopDp + TopScrimTail) to
                        Brush.verticalGradient(listOf(background, Color.Transparent))
                } else {
                    val height = tabRowBottom + TopScrimTail
                    height to Brush.verticalGradient(
                        0f                       to background,
                        (tabRowBottom / height)  to background,
                        1f                       to Color.Transparent,
                    )
                }
            }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topScrimHeight)
                .background(topScrimBrush)
        )

        // Floating M3 search bar. The back arrow is its own leading icon, so there is no separate
        // floating back pill — one element, which also keeps the FAB→bar morph clean.
        SearchInputBar(
            queryState     = queryState,
            onBack         = { keyboard?.hide(); haptics.confirm(); onBack() },
            // Focus explicitly: clearing is the start of typing the next query, and since the
            // auto-focus effect above no longer fires on a re-entry over results, the field may
            // well not be focused when the ✕ is tapped. Already-focused (the ordinary case, mid
            // typing) makes the request a no-op.
            onClear        = {
                queryState.clearText()
                viewModel.clearQuery()
                focusRequester.requestFocus()
            },
            onSearch       = { keyboard?.hide() },
            focusRequester = focusRequester,
            modifier       = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .then(searchBarSharedModifier),
        )

        // Result-type tabs, FIXED in the same floating layer as the bar so they stay put while a
        // list scrolls underneath them. Results used to be one LazyColumn of stacked sections, so
        // every appended page of tracks pushed Albums further out of reach; each type is now its
        // own list, and its own pager page. Only shown once something is typed — the Recent list
        // owns the blank state.
        if (!queryBlank) {
            SearchTabRow(
                selected = state.tab,
                onSelect = viewModel::selectTab,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = SearchBarBlockHeight),
            )
        }
    }

    TrackActionsHost(
        controller   = viewModel.trackActions,
        onGoToAlbum  = onAlbumClick,
        onGoToArtist = onArtistClick,
    )

    // Auto-focus the field — on the FIRST entry of this screen instance (the arrival from the
    // Library FAB, where the whole point is to start typing), or on any entry where the field is
    // blank and there is nothing to look at behind the keyboard.
    //
    // Not on a re-entry over results: `LaunchedEffect(Unit)` runs again every time the screen is
    // composed, and popping back from Album/Artist/Show detail composes Search afresh, so the
    // unguarded version threw the keyboard back up over the restored results the user had just
    // returned to (device pass 2026-09-13 — and it is the state the podcast crash was hit from).
    // The flag is `rememberSaveable` for the same reason the scroll-reset guard above is: it has to
    // survive the save/restore a pop performs, or every re-entry would read it fresh and focus.
    //
    // If we arrived via the FAB→bar shared-element morph, wait for it to settle before popping the
    // keyboard so the layout shift doesn't stutter the transition. The wait is unconditional:
    // `first { it }` returns on the spot when the transition has already settled, and on a blank
    // re-entry it also keeps the focus request out of the pop slide.
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The field's own text, not `state.query`: the field is the thing being focused, and this
        // reads it once from a coroutine, so the screen scope gains no subscription to it.
        if (autoFocused && queryState.text.isNotBlank()) return@LaunchedEffect
        autoFocused = true
        animatedContentScope?.transition?.let { t ->
            snapshotFlow { t.currentState == t.targetState }.first { it }
        }
        focusRequester.requestFocus()
    }
}

/** Pairs the Library search FAB with the Search screen's bar for the container transform. */
private const val SEARCH_BAR_SHARED_KEY = "search-bar"

/** How close to the end of a results list a scroll gets before the next page is requested. */
private const val LOAD_MORE_THRESHOLD = 5

/** Height of the floating search bar — matches the M3 search input-field height. */
private val SearchBarHeight = 56.dp

/** Top margin + bar + gap: where the next floating element starts, measured below the status bar. */
private val SearchBarBlockHeight: Dp = 8.dp + SearchBarHeight + 12.dp

/**
 * What a [PrimaryTabRow] of text-only [Tab]s measures — `PrimaryNavigationTabTokens.ContainerHeight`
 * read off the Material3 1.5.0-alpha27 sources (the token is internal). Re-check on a BOM bump.
 */
private val SearchTabRowHeight = 48.dp

/** Breathing room between the tab row and the first result row. */
private val SearchTabRowGap = 12.dp

/** How far past its opaque end the top scrim fades out — the shared `TopScrim`'s own tail. */
private val TopScrimTail = 24.dp

/**
 * Upper bound on how long a long-press waits for the keyboard to finish hiding before opening the
 * song menu anyway. The IME hide animation is well under this; the timeout only exists so a device
 * that never reports the inset back at zero still gets its sheet.
 */
private const val ImeHideTimeoutMs = 300L

/** The tab label for each result type. */
@get:StringRes
private val SearchTab.labelRes: Int
    get() = when (this) {
        SearchTab.TRACKS  -> R.string.search_tab_tracks
        SearchTab.ALBUMS  -> R.string.search_tab_albums
        SearchTab.ARTISTS -> R.string.search_tab_artists
        SearchTab.SHOWS   -> R.string.search_tab_shows
    }

/**
 * The result-type tabs — a Material 3 [PrimaryTabRow], full width, floating over the results.
 *
 * Two deliberate departures from the defaults:
 * - **`containerColor = Color.Transparent`**, so the screen's own growing top scrim (which fades the
 *   scrolling rows out into this row's bottom edge) shows through instead of a flat surface band.
 * - **no `divider`**. The default `HorizontalDivider` would draw a hard line exactly where the scrim
 *   turns transparent, reinstating the band edge the scrim exists to avoid.
 *
 * `unselectedContentColor` is passed explicitly because M3's own default for it is
 * `selectedContentColor` — which `PrimaryTabRow` sets to `primary` for the whole row, so leaving it
 * alone renders the unselected tabs in the accent colour as well. The value here is the
 * `InactiveLabelTextColor` token (`onSurfaceVariant`) that default is presumably meant to resolve to.
 */
@Composable
private fun SearchTabRow(
    selected: SearchTab,
    onSelect: (SearchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        modifier         = modifier,
        containerColor   = Color.Transparent,
        divider          = {},
    ) {
        SearchTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                // Fired from the gesture, and only on a genuine change — re-tapping the active tab
                // is intentionally silent, matching the picker this replaced.
                onClick  = {
                    if (tab != selected) {
                        haptics.press()
                        onSelect(tab)
                    }
                },
                text     = {
                    Text(
                        stringResource(tab.labelRes),
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

/**
 * One tab's results list — the rows are the caller's, everything around them is shared: the
 * per-tab empty state, scroll haptics, the paging trigger and the paging footer.
 *
 * The trigger is the Library's house pattern (`LibraryTrackListPane.TrackList`): a `derivedStateOf`
 * Boolean plus a `LaunchedEffect` keyed on it (and on [tab], so switching lists re-evaluates for
 * the new one). Every tab appends its rows at the bottom of its OWN list now, so an append always
 * grows `totalItemsCount` past the threshold and un-latches the trigger — the old "artists render
 * as two fixed items, so appending them never grew the count and the trigger stayed satisfied"
 * failure mode (docs/UI_PATTERNS.md → Search pagination) is closed by construction.
 *
 * [isActive] is the pager's doing: a neighbouring page composes while a swipe is in flight, so
 * without it a short tab would page itself the moment it slid into view, for a tab the user may
 * never settle on. It is both a key and part of the condition — a page that becomes the settled one
 * must get its chance to fire.
 */
@Composable
private fun SearchResultsList(
    tab           : SearchTab,
    listState     : LazyListState,
    itemCount     : Int,
    paging        : TabPaging,
    isActive      : Boolean,
    isLoading     : Boolean,
    emptyText     : String,
    contentPadding: PaddingValues,
    onLoadMore    : (SearchTab) -> Unit,
    onRetry       : (SearchTab) -> Unit,
    rows          : LazyListScope.() -> Unit,
) {
    if (itemCount == 0) {
        // Per-tab: the other two are unaffected, and the query may well have results in them. The
        // top inset is the list's own, so the message centres in the area BELOW the tabs rather
        // than behind them.
        Box(
            modifier         = Modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding())
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        val haptics = LocalHapticFeedback.current
        ListScrollHaptics(listState)

        // Everything this `LazyColumn` declares below: the caller's rows, the optional paging
        // footer, the trailing spacer. A measure of THESE contents reports exactly this many items.
        val declaredItems = itemCount +
                            (if (paging.isLoadingMore || paging.pagingFailed) 1 else 0) +
                            1
        val reachedBottom by remember(listState, declaredItems) {
            derivedStateOf {
                val info = listState.layoutInfo
                // Stale-layout guard. `layoutInfo` is the LAST measure and is never reset when the
                // list leaves composition — a new query removes the pager while `isLoading`, so on
                // the frame the new results compose it still describes the PREVIOUS query's rows,
                // parked wherever the user left them (i.e. "at the bottom"). The per-query reset
                // writes the new scroll position synchronously, but the measure that applies it
                // runs in the traversal AFTER this effect, so without this every new query fired a
                // page-2 fetch on its first frame. Comparing against the declared count makes the
                // guard independent of effect ordering: it lets the trigger through only once a
                // measure of the CURRENT contents exists, and it self-clears at that measure.
                if (info.totalItemsCount != declaredItems) return@derivedStateOf false
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
            }
        }

        // The paging flags are KEYS, not just reads: a page that adds fewer rows than the threshold
        // leaves `reachedBottom` latched true, so without them the effect would never re-run and the
        // tab would stop paging until the user scrolled away and back. With them the sequence is
        // loop-free — fire → isLoadingMore=true (restart, no-op) → page lands → isLoadingMore=false
        // (restart, fires again only while canLoadMore is still true).
        LaunchedEffect(reachedBottom, tab, isActive, paging.canLoadMore, paging.isLoadingMore) {
            if (isActive && reachedBottom && paging.canLoadMore && !isLoading && !paging.isLoadingMore) {
                onLoadMore(tab)
            }
        }

        LazyColumn(
            state          = listState,
            modifier       = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            rows()

            // Paging footer — spinner XOR retry, never both, so the one `load_more` key is safe and
            // the item count doesn't churn across the loading→failed flip. The spinner is a small
            // inline one, per MATERIAL3.md's loading conventions (ContainedLoadingIndicator is for
            // full-area states). The retry row is the only way back from a failed page: this tab's
            // `canLoadMore` stays false while it is shown, so the trigger above cannot refire on
            // its own — and it re-arms the tab it belongs to, not "whichever tab is showing now".
            if (paging.isLoadingMore || paging.pagingFailed) {
                item(key = "load_more") {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (paging.isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            TextButton(
                                onClick = {
                                    haptics.press()
                                    onRetry(tab)
                                },
                            ) {
                                Text(stringResource(R.string.search_load_more_retry))
                            }
                        }
                    }
                }
            }

            item(key = "footer_space") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One row of the "Recent" list — mirrors [AlbumRow]'s look; artist art is a circle, everything
 * else (tracks, albums, shows) a rounded square. The remove X goes in `trailingContent`, not inside
 * the row's own clickable area: the [IconButton] consumes the tap there, so removing an entry can't
 * also navigate to it.
 */
@Composable
private fun RecentSearchRow(recent: RecentSearch, onClick: () -> Unit, onRemove: () -> Unit) {
    val artShape = if (recent.type == "artist") CircleShape else RoundedCornerShape(4.dp)
    ListItem(
        supportingContent = {
            Text(
                recent.subtitle,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            if (recent.imageUrl != null) {
                AsyncImage(
                    model              = recent.imageUrl,
                    contentDescription = recent.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(52.dp).clip(artShape),
                )
            } else {
                Surface(modifier = Modifier.size(52.dp), shape = artShape,
                    color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when (recent.type) {
                                "artist" -> Icons.Default.Person
                                "show"   -> Icons.Default.Podcasts
                                else     -> Icons.Default.MusicNote
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close,
                    contentDescription = stringResource(R.string.search_recent_remove))
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
        content  = { Text(recent.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * An artist result, as a full-width row. Artists used to be a horizontal `LazyRow` of circular
 * chips pinned above the tracks; with a tab of their own they page like everything else, so they
 * read as ordinary rows — circular art (the one thing kept from the chips), the name, and an
 * "Artist" subtitle, matching what [RecentSearchRow] renders for an artist entry.
 */
@Composable
private fun ArtistRow(artist: SpotifyArtist, onClick: () -> Unit) {
    val imageUrl = artist.images?.firstOrNull()?.url
    ListItem(
        supportingContent = {
            Text(
                stringResource(R.string.search_type_artist),
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            if (imageUrl != null) {
                AsyncImage(
                    model              = imageUrl,
                    contentDescription = artist.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(52.dp).clip(CircleShape),
                )
            } else {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape    = CircleShape,
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
        content  = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun AlbumRow(album: SpotifyAlbum, onClick: () -> Unit) {
    val imageUrl    = album.images?.firstOrNull()?.url
    val artistNames = album.artists?.joinToString(", ") { it.name } ?: ""
    val fallback    = stringResource(R.string.search_type_album)

    ListItem(
        supportingContent= {
            Text(
                artistNames.ifBlank { fallback },
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            if (imageUrl != null) {
                AsyncImage(
                    model              = imageUrl,
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
        modifier = Modifier.clickable(onClick = onClick),
        content  = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * A podcast show result — [AlbumRow]'s twin: square art, the show name, and an "N episodes"
 * subtitle. Deliberately NOT the Library's `ShowListCard`, which is a `Card` in the Library's own
 * browser style; Search renders every type as a plain `ListItem` row.
 *
 * The subtitle is the episode count and never the publisher — February 2026 deprecated
 * `show.publisher` and the device spike confirmed it is absent from live responses. A show that
 * omits `total_episodes` falls back to a plain "Podcast", the same shape [AlbumRow] uses when a
 * result has no artists.
 *
 * Only ever handed shows with a non-blank id (filtered at the call site), so the tap can navigate.
 */
@Composable
private fun ShowRow(show: SpotifyShow, onClick: () -> Unit) {
    val imageUrl = show.images?.firstOrNull()?.url
    val fallback = stringResource(R.string.search_type_show)
    val subtitle = show.totalEpisodes
        ?.let { pluralStringResource(R.plurals.show_episode_count, it, it) }
        ?: fallback

    ListItem(
        supportingContent = {
            Text(
                subtitle,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            if (imageUrl != null) {
                AsyncImage(
                    model              = imageUrl,
                    contentDescription = show.name,
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
                        Icon(Icons.Default.Podcasts, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
        content  = { Text(show.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * Floating Material 3 search bar. A `SearchBarDefaults.InputField` (transparent container) inside a
 * stadium [Surface] tinted to match the screen's other floating pills (`surfaceContainerHigh` + a
 * small shadow). The back arrow is the field's own leading icon — no separate back pill — so the
 * whole control is a single bounding box (which the FAB→bar container transform will share).
 *
 * The only non-deprecated `InputField` overload takes a [TextFieldState] **and** a [SearchBarState]
 * (both the `query`/`onQueryChange` and the `expanded`/`onExpandedChange` forms are deprecated in
 * Material3 1.5.0-alpha27). Lyra never expands into a full-screen search bar — there is no
 * `ExpandedFullScreenSearchBar` anywhere — so the required state is created **already Expanded**
 * and then left alone. That is deliberate, not cosmetic: starting it Collapsed makes the field
 * (a) run `animateToExpanded()` on focus, whose `Animatable` is read during composition and so
 * recomposes the field every frame for the length of a slow spatial spring, (b) keep a
 * `snapshotFlow { text }` collector alive for the whole screen just to trigger that same expansion
 * on the first keystroke, and (c) arm its clear-focus-on-collapse effect — all for an expansion
 * nothing renders. Expanded short-circuits all three. Nothing in the field's *appearance* depends
 * on the value (only key handling, the a11y state description, and that focus effect do).
 */
@Composable
private fun SearchInputBar(
    queryState    : TextFieldState,
    onBack        : () -> Unit,
    onClear       : () -> Unit,
    onSearch      : () -> Unit,
    focusRequester: FocusRequester,
    modifier      : Modifier = Modifier,
) {
    val searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)

    Surface(
        modifier        = modifier.fillMaxWidth().height(SearchBarHeight),
        shape           = CircleShape,
        color           = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        SearchBarDefaults.InputField(
            textFieldState = queryState,
            searchBarState = searchBarState,
            onSearch       = { onSearch() },
            modifier       = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder    = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon    = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back))
                }
            },
            trailingIcon   = if (queryState.text.isNotBlank()) {
                {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close,
                            contentDescription = stringResource(R.string.search_clear))
                    }
                }
            } else null,
        )
    }
}
