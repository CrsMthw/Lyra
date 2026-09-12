package com.crsmthw.lyra.ui.screens.search

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import com.crsmthw.lyra.ui.components.ConnectedChoiceRow
import com.crsmthw.lyra.ui.components.PlayerPanelHost
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
    onTrackClick          : (uri: String, allUris: List<String>) -> Unit,
    onOpenQueue           : () -> Unit = {},
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state          by viewModel.uiState.collectAsStateWithLifecycle()
    val recents        by viewModel.recentSearches.collectAsStateWithLifecycle()
    val keyboard        = LocalSoftwareKeyboardController.current
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
    // left off — only the active tab's list is composed at a time. `rememberLazyListState` is
    // already `rememberSaveable`-backed, and three distinct call sites get three distinct keys.
    val tracksListState  = rememberLazyListState()
    val albumsListState  = rememberLazyListState()
    val artistsListState = rememberLazyListState()
    // A new search starts at the top of every tab. The three states are hoisted so each tab keeps
    // its scroll position across tab switches — which would also carry the previous query's position
    // into the next one (new results landing mid-list, and an immediate page-2 fetch from the
    // reached-bottom trigger). Keyed on the SEARCHED query, not the live field text.
    LaunchedEffect(state.resultsQuery) {
        tracksListState.scrollToItem(0)
        albumsListState.scrollToItem(0)
        artistsListState.scrollToItem(0)
    }

    val tracksLabel  = stringResource(R.string.search_tab_tracks)
    val albumsLabel  = stringResource(R.string.search_tab_albums)
    val artistsLabel = stringResource(R.string.search_tab_artists)
    val tabOptions = remember(tracksLabel, albumsLabel, artistsLabel) {
        listOf(
            SearchTab.TRACKS  to tracksLabel,
            SearchTab.ALBUMS  to albumsLabel,
            SearchTab.ARTISTS to artistsLabel,
        )
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
    // tab chooser is absent — so handing it the results' inset would shove it down by a row height
    // with nothing above it.
    val barInset       = statusBarTopDp + SearchBarBlockHeight
    val resultsInset   = barInset + SearchTabRowHeight + SearchTabRowGap

    // The mini player / pop-out panel wrap the whole screen, as on Library/Album/Artist. The
    // "search-bar" container transform is unaffected: it is built against the NAV shared-transition
    // scope, which is passed straight through the host's own SharedTransitionLayout — exactly how
    // the Library FAB end of the same morph already coexists with this host.
    PlayerPanelHost(
        playerViewModel          = playerViewModel,
        onOpenPlayer             = onOpenPlayer,
        onOpenQueue              = onOpenQueue,
        // A single full-width results list at every width, and the field auto-focuses — so the bar
        // stays full-width and rides above the keyboard instead of hiding behind it.
        miniPlayerFullWidth      = true,
        miniPlayerAvoidsIme      = true,
        navSharedTransitionScope = sharedTransitionScope,
        navAnimatedContentScope  = animatedContentScope,
    ) { _ ->
    // THE screen's single horizontal inset. In landscape with 3-button navigation the nav bar sits
    // on the left or right edge, and the search field, the Recent rows' X buttons and the result
    // rows all ran underneath it — only the inner results Box carried a narrower nav-bars-only
    // inset, and the bar and recents had none at all. Applied once here, every descendant clears
    // it, and because `windowInsetsPadding` CONSUMES what it applies, the `navigationBarsPadding()`
    // calls further down resolve to the remaining BOTTOM inset only, so there is no double padding
    // (CLAUDE.md → Inset Rules). `displayCutout` joins the union for a landscape notch on the same
    // edge. The scrims are inset along with everything else, which is invisible: nothing is drawn
    // in that strip any more either. The mini player is NOT affected — `PlayerPanelHost` renders it
    // outside this Box and handles its own insets.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
            ),
    ) {
        // Scrolling content rides above the keyboard; the floating bar + tab chooser do not.
        Box(modifier = Modifier.fillMaxSize().imePadding()) {
            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator(modifier = Modifier.size(90.dp))
                    }
                }
                state.error != null -> {
                    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                state.results != null -> {
                    val results     = state.results!!
                    val paging      = state.pagingFor(state.tab)
                    val emptyText   = stringResource(R.string.search_no_results, state.query)
                    val listPadding = remember(resultsInset, navBarBottomDp) {
                        // 100dp of mini-player clearance on top of the nav bar, so the last row
                        // scrolls clear of the floating bar (UI_PATTERNS.md → "LazyColumn bottom
                        // padding must include nav bar height").
                        PaddingValues(top = resultsInset, bottom = 100.dp + navBarBottomDp)
                    }

                    // One vertical list per type. A plain swap, not an `AnimatedContent`: nothing
                    // here needs a content-swap transition, and any that does must ride the single
                    // finite `screenTransitionSpec()` (docs/MOTION.md → THE HARD RULE).
                    when (state.tab) {
                        SearchTab.TRACKS -> {
                            val tracks = results.tracks?.items ?: emptyList()
                            SearchResultsList(
                                tab            = SearchTab.TRACKS,
                                listState      = tracksListState,
                                itemCount      = tracks.size,
                                paging         = paging,
                                isLoading      = state.isLoading,
                                emptyText      = emptyText,
                                contentPadding = listPadding,
                                onLoadMore     = viewModel::loadMore,
                                onRetry        = viewModel::retryLoadMore,
                            ) {
                                items(tracks, key = { "track_${it.id}" }) { track ->
                                    TrackRow(
                                        track   = track,
                                        onClick = {
                                            viewModel.addRecentSearch(track.toRecentSearch())
                                            val idx = tracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
                                            onTrackClick(track.uri, tracks.drop(idx).map { it.uri })
                                        },
                                        onLongClick = { viewModel.trackActions.open(track.toTrackActionTarget()) },
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
                                isLoading      = state.isLoading,
                                emptyText      = emptyText,
                                contentPadding = listPadding,
                                onLoadMore     = viewModel::loadMore,
                                onRetry        = viewModel::retryLoadMore,
                            ) {
                                items(albums, key = { "album_${it.id}" }) { album ->
                                    AlbumRow(
                                        album   = album,
                                        onClick = {
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
                                            viewModel.addRecentSearch(artist.toRecentSearch())
                                            onArtistClick(artist.id)
                                        },
                                    )
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
                            viewModel.addRecentSearch(recent)   // re-tapping moves it to the front
                            when (recent.type) {
                                "track"    -> onTrackClick(recent.uri, listOf(recent.uri))
                                "album"    -> onAlbumClick(recent.id)
                                "artist"   -> onArtistClick(recent.id)
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

        // Top scrim — fades content under the status bar (covers the bar; no statusBarsPadding).
        // Not the shared `TopScrim`, because this one has to grow: while the chooser is showing it
        // fades all the way past the BOTTOM of it. The chooser's ButtonGroup is width-capped at
        // 420dp and centred, so on an unfolded pane there is ~120dp of empty space either side of
        // it, in a horizontal band that result rows scroll straight through. Extending the same
        // gradient down past the chooser keeps the scroll-under look (no hard band edge) while
        // pushing what shows through it most of the way to the background colour. Same brush and
        // same 24dp tail as `TopScrim` otherwise, so the blank-query state is pixel-identical.
        val topScrimHeight =
            if (queryBlank) statusBarTopDp + 24.dp
            else            statusBarTopDp + SearchBarBlockHeight + SearchTabRowHeight + 24.dp
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topScrimHeight)
                .background(Brush.verticalGradient(listOf(background, Color.Transparent)))
        )

        // Floating M3 search bar. The back arrow is its own leading icon, so there is no separate
        // floating back pill — one element, which also keeps the FAB→bar morph clean.
        SearchInputBar(
            queryState     = queryState,
            onBack         = { keyboard?.hide(); haptics.confirm(); onBack() },
            onClear        = { queryState.clearText(); viewModel.clearQuery() },
            onSearch       = { keyboard?.hide() },
            focusRequester = focusRequester,
            modifier       = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .then(searchBarSharedModifier),
        )

        // Result-type chooser, FIXED in the same floating layer as the bar so it stays put while a
        // list scrolls underneath it. Results used to be one LazyColumn of stacked sections, so
        // every appended page of tracks pushed Albums further out of reach; each type is now its
        // own list. Only shown once something is typed — the Recent list owns the blank state. The
        // `press` haptic comes from ConnectedChoiceRow, and only on a genuine change of selection.
        if (!queryBlank) {
            ConnectedChoiceRow(
                options  = tabOptions,
                selected = state.tab,
                onSelect = viewModel::selectTab,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = SearchBarBlockHeight),
            )
        }
    }
    } // PlayerPanelHost

    TrackActionsHost(
        controller   = viewModel.trackActions,
        onGoToAlbum  = onAlbumClick,
        onGoToArtist = onArtistClick,
    )

    // Auto-focus the field. If we arrived via the FAB→bar shared-element morph, wait for it to
    // settle before popping the keyboard so the layout shift doesn't stutter the transition.
    LaunchedEffect(Unit) {
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

/** `ToggleButtonDefaults.MinHeight` — what one row of [ConnectedChoiceRow] segments measures. */
private val SearchTabRowHeight = 40.dp

/** Breathing room between the tab chooser and the first result row. */
private val SearchTabRowGap = 12.dp

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
 */
@Composable
private fun SearchResultsList(
    tab           : SearchTab,
    listState     : LazyListState,
    itemCount     : Int,
    paging        : TabPaging,
    isLoading     : Boolean,
    emptyText     : String,
    contentPadding: PaddingValues,
    onLoadMore    : (SearchTab) -> Unit,
    onRetry       : (SearchTab) -> Unit,
    rows          : LazyListScope.() -> Unit,
) {
    if (itemCount == 0) {
        // Per-tab: the other two are unaffected, and the query may well have results in them.
        Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        val haptics = LocalHapticFeedback.current
        ListScrollHaptics(listState)

        val reachedBottom by remember(listState) {
            derivedStateOf {
                val info        = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
            }
        }

        // The paging flags are KEYS, not just reads: a page that adds fewer rows than the threshold
        // leaves `reachedBottom` latched true, so without them the effect would never re-run and the
        // tab would stop paging until the user scrolled away and back. With them the sequence is
        // loop-free — fire → isLoadingMore=true (restart, no-op) → page lands → isLoadingMore=false
        // (restart, fires again only while canLoadMore is still true).
        LaunchedEffect(reachedBottom, tab, paging.canLoadMore, paging.isLoadingMore) {
            if (reachedBottom && paging.canLoadMore && !isLoading && !paging.isLoadingMore) {
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
 * One row of the "Recent" list — mirrors [AlbumRow]'s look; artist art is a circle. The remove X
 * goes in `trailingContent`, not inside the row's own clickable area: the [IconButton] consumes
 * the tap there, so removing an entry can't also navigate to it.
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
                            if (recent.type == "artist") Icons.Default.Person else Icons.Default.MusicNote,
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
