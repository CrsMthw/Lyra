package com.crsmthw.lyra.ui.screens.library

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.components.DetailArtHero
import com.crsmthw.lyra.ui.components.DetailTopBar
import com.crsmthw.lyra.ui.components.DetailTopBarFade
import com.crsmthw.lyra.ui.components.HeroTitleHandoff
import com.crsmthw.lyra.ui.components.RemovablePlaylist
import com.crsmthw.lyra.ui.components.rememberHeroTitleHandoff
import com.crsmthw.lyra.ui.components.TrackRow
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.screenTransitionSpec
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// ── Track-list pane ───────────────────────────────────────────────────────────
// The playlist / Liked Songs detail: the single-pane detail pane AND the two-pane right pane are
// the same composable (the two-pane one is this minus the back icon).

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun RightPaneContent(
    state           : LibraryUiState,
    viewModel       : LibraryViewModel,
    playerViewModel : PlayerViewModel,
    mosaicDir       : File,
    onTrackClick    : () -> Unit,
    onRefresh       : () -> Unit,
    onBack          : (() -> Unit)? = null,            // non-null → the bar shows back (single-pane)
    containerColor  : Color = Color.Unspecified,       // the pane's own colour; defaults to background
    sharedScope     : SharedTransitionScope? = null,   // container-transform target (single pane)
    animScope       : AnimatedContentScope? = null,
) {
    // Seeded from the StateFlow's current value rather than a literal, for the same reason as
    // `hasCurrentTrack` in LibrarySinglePaneLayout: a literal initial value is re-applied every
    // time this pane re-enters composition, so the now-playing row highlight would drop for one
    // frame on each pane swap / screen re-entry. Cosmetic here — but the same shape as the bug
    // that made the search bar→FAB morph stutter, so keep both seeded.
    val currentTrackId by remember {
        playerViewModel.uiState.map { it.currentTrack?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(playerViewModel.uiState.value.currentTrack?.id)
    val isPlayingState by remember {
        playerViewModel.uiState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(playerViewModel.uiState.value.isPlaying)
    val playlist     = state.currentPlaylist
    val isLikedSongs = playlist == null
    // Owned playlists only (never Liked Songs / followed) get the delete action — and the same
    // ownership test gates multi-select, so Liked Songs and followed playlists show no Select
    // affordance at all. ANDed with the mode flag so a mode left over from an ownership change
    // (or a pane rendering an older state during a swap) can't paint a selection UI.
    val canDelete    = playlist != null && playlist.owner?.id == state.user?.id
    val inSelection  = canDelete && state.selectionMode
    val nSelected    = state.selectedUris.size
    val haptics      = LocalHapticFeedback.current
    var showOverflowMenu  by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val mosaicFile   = playlist?.let { p ->
        if (p.id in state.playlistsWithMosaics) File(mosaicDir, "${p.id}.png") else null
    }
    // Spotify's own cover wins; the locally-generated mosaic is only a fallback for when Spotify
    // hasn't provided art yet (e.g. a just-created playlist). Once Spotify fills it in, a library
    // refresh picks up the real URL and it replaces the stale mosaic.
    val artUrl       = playlist?.thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: mosaicFile?.absolutePath
    val likedSongsStr = stringResource(R.string.liked_songs)
    val playlistName  = playlist?.name ?: likedSongsStr
    val trackCount   = when {
        isLikedSongs && state.likedSongsTotal > 0 -> state.likedSongsTotal
        isLikedSongs                              -> state.likedSongCount
        state.playlistTracksTotal > 0             -> state.playlistTracksTotal   // authoritative total, not the loaded count
        state.currentTracks.isNotEmpty()          -> state.currentTracks.size
        else                                      -> playlist.trackCount         // metadata fallback — avoids layout shift
    }
    val playUri      = playlist?.uri ?: "spotify:user:${state.user?.id}:collection"

    // Delay the loading spinner so cache hits (< ~250ms) never flash it.
    val latestState = rememberUpdatedState(state)
    var showLoadingIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentPlaylist?.id) {
        showLoadingIndicator = false
        delay(250)
        val s = latestState.value
        if (s.isLoadingTracks && s.currentTracks.isEmpty()) showLoadingIndicator = true
    }
    LaunchedEffect(state.isLoadingTracks) {
        if (!state.isLoadingTracks) showLoadingIndicator = false
    }

    val density          = LocalDensity.current
    val navBarBottomDp   = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val listState        = rememberLazyListState()
    // The bar title takes over from the hero title over the ~35dp that title needs to slide under
    // the bar (M3's own `TopTitleAlphaEasing` hand-off, see `HeroTitleHandoff`) — not over the whole
    // hero's scroll, which read as a slow crossfade on device.
    val heroTitle        = rememberHeroTitleHandoff()
    val pullToRefreshState   = rememberPullToRefreshState()
    PullThresholdHaptics(pullToRefreshState)
    // The colour of the pane this content sits in: the screen background single-pane, the Card's
    // surface in a two-pane card. It is what the app bar paints itself (see `DetailTopBar`).
    val paneColor        = if (containerColor == Color.Unspecified)
                               MaterialTheme.colorScheme.background else containerColor

    val canLoadMore          = if (isLikedSongs)
                                   state.likedSongsTotal > 0 && state.likedSongsOffset < state.likedSongsTotal
                               else
                                   state.playlistTracksTotal > 0 && state.playlistTracksOffset < state.playlistTracksTotal

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh    = onRefresh,
            state        = pullToRefreshState,
            // Refreshing replaces the list wholesale back to page 0, which would drop an
            // in-progress selection's rows out from under it — so the gesture is out of the mode.
            // Belt to the VM's braces: refreshCurrentTracks clears the selection anyway, for the
            // case where the mode is entered from the song menu while a refresh is already in
            // flight (nothing gates that on isRefreshing).
            enabled      = !inSelection,
            modifier     = Modifier.fillMaxSize(),
            // Both indicators clear the app bar laid over this list: the PTR box still spans the
            // whole pane (the bar is an overlay, not a Column sibling), so an indicator that only
            // took `statusBarsPadding()` would animate in BEHIND a bar that is now solid.
            indicator    = {
                if (state.isRefreshing) {
                    ContainedLoadingIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = TopAppBarDefaults.TopAppBarExpandedHeight + 12.dp),
                    )
                } else {
                    PullToRefreshDefaults.Indicator(
                        state        = pullToRefreshState,
                        isRefreshing = false,
                        modifier     = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = TopAppBarDefaults.TopAppBarExpandedHeight),
                    )
                }
            },
        ) {
        TrackList(
            tracks         = state.currentTracks,
            currentTrackId = currentTrackId,
            isPlaying      = isPlayingState,
            isLoadingMore  = state.isLoadingMoreTracks,
            canLoadMore    = canLoadMore,
            onLoadMore     = if (isLikedSongs) viewModel::loadMoreLikedSongs else viewModel::loadMorePlaylistTracks,
            // In selection mode a tap is a check and playback is suspended; a long-press just
            // toggles too, since the menu it would open is where the mode came from.
            onTrackClick   = { track ->
                if (inSelection) {
                    viewModel.toggleTrackSelection(track.uri)
                } else if (playlist != null) {
                    val idx = state.currentTracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
                    playerViewModel.playTrack(track.uri, contextUri = playlist.uri, index = idx)
                    onTrackClick()
                } else {
                    playerViewModel.playFromLikedSongs(track.uri)
                    onTrackClick()
                }
            },
            onTrackLongClick = { track ->
                if (inSelection) {
                    viewModel.toggleTrackSelection(track.uri)
                } else if (!track.isEpisode) {
                    // A playlist CAN hold a podcast episode, and every row in TrackActionsSheet
                    // (like, add to playlist, go to album, go to artist) addresses a track-only
                    // endpoint or an object an episode does not have — so the sheet stays shut
                    // for one. The row below never even calls this for an episode; this is the
                    // belt to that braces. Multi-select removal above is untouched: that is a
                    // by-uri playlist-items DELETE, which removes an episode perfectly well.
                    val removable = playlist?.takeIf { it.owner?.id == state.user?.id }
                        ?.let { RemovablePlaylist(it.id, it.name) }
                    viewModel.trackActions.open(track.toTrackActionTarget(removable))
                }
            },
            selectedUris   = if (inSelection) state.selectedUris else null,
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
            listState      = listState,
            headerContent  = {
                // The same square-art hero in both single- and two-pane (the two-pane right pane is
                // the single-pane track list minus the back icon); its name hands off to the bar's.
                TrackListHero(
                    artUrl       = artUrl,
                    isLikedSongs = isLikedSongs,
                    name         = playlistName,
                    trackCount   = trackCount,
                    onPlay       = { haptics.press(); viewModel.playPlaylist(playUri) },
                    onShuffle    = { haptics.press(); viewModel.shufflePlaylist(playUri) },
                    selecting    = inSelection,
                    playlistId   = playlist?.id,
                    sharedScope  = sharedScope,
                    animScope    = animScope,
                    titleHandoff = heroTitle,
                )
            },
            emptyContent = when {
                showLoadingIndicator && state.currentTracks.isEmpty() -> { {
                    Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                } }
                state.error != null && state.currentTracks.isEmpty() -> { {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier            = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
                    ) {
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        if (!isLikedSongs && playUri.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.playPlaylist(playUri) }) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.player_play))
                            }
                        }
                    }
                } }
                else -> null
            },
        )

        // The seam under the bar: the pane colour fading out over the first rows, so the hero art
        // and the tracks dissolve into the bar instead of sliding past its title — the same strip
        // the Library browser pane has under its tab row (Cris's device pass, 2026-09-16 #12).
        //
        // Composed as the LAST child of the PTR box's CONTENT, not after the box: `PullToRefreshBox`
        // emits `content(); indicator()`, so the strip draws over the rows and the PTR indicator
        // still comes out OVER it — which is what we want, since the indicator's own
        // `padding(top = TopAppBarExpandedHeight)` places it at exactly this strip's top edge. A
        // plain background Box takes no pointer input, so it cannot eat a drag on the rows beneath.
        DetailTopBarFade(
            paneColor = paneColor,
            modifier  = Modifier.align(Alignment.TopCenter),
        )
        } // PullToRefreshBox

        // The shared `DetailTopBar`, laid over the list and composed LAST so it draws (and
        // hit-tests) above the rows — it replaces the floating back / title / overflow pills AND
        // the TopScrim they sat in (the bar is solid in the pane's own colour, so there is nothing
        // left to fade content into). Single-pane carries the back icon; the two-pane RIGHT pane
        // sits beside the browser list and never had one, which is all `onBack == null` now means.
        //
        // Selection mode swaps the bar's CONTENTS for the standard M3 contextual bar —
        // [✕] "N selected" [remove] — through the app-wide finite `screenTransitionSpec()`. Never a
        // spring: this can run while the pane swap or a nav transition is in flight (THE HARD RULE,
        // docs/MOTION.md).
        //
        // THE TRANSITION IS HOISTED so each branch can ask whether it is the one currently SHOWN.
        // `Crossfade` APPENDS the entering state to `currentlyVisible` and renders the list in
        // order, so the entering bar is the LAST child of its Box: it draws last and therefore
        // hit-tests FIRST, from its first frame at alpha 0 (alpha does not affect hit testing), and
        // its only wrapper is a `graphicsLayer` — nothing gates pointer input. Both branches put a
        // control in the same navigationIcon slot (✕ while selecting, back arrow otherwise), and in
        // SINGLE-PANE `onBack` is `clearSelection()`, so a second tap on ✕ inside the 300 ms fade
        // landed on the invisible back arrow and collapsed the whole playlist detail back to the
        // browser. Same for the actions slot: ⌫ sits where ⋮ was, and the song-menu door into
        // selection mode pre-checks one uri, so ⌫ is already live on the entering bar's first frame
        // and a single-row removal skips the confirm dialog.
        //
        // During the fade `currentState` is the OUTGOING branch, so `live` makes the invisible
        // entering bar inert and leaves the visible one usable; once settled both agree and
        // everything is live (and on first composition `currentState == targetState`, so nothing is
        // dead on entry). A disabled `IconButton` still installs a pointer node and SWALLOWS the
        // tap rather than passing it down — which is what we want here: the ✕'s own action is
        // already in flight, so the swallowed second tap loses nothing.
        //
        // A mid-fade REVERSAL is deliberately NOT covered, and needs no defending. `updateTarget`
        // assigns `currentState =` the OLD `targetState` when the target flips back, i.e. the
        // branch `Crossfade` appended — so for the rest of the unwind the topmost branch is the
        // live one and the branch being reverted TO only goes live at the settle. Getting there
        // takes two deliberate actions inside 300 ms, neither of which can come from this bar (its
        // own ✕ / ⌫ are the controls the fade has just deadened): a row long-press or the ⋮ menu's
        // Select row on the way in, the selection `BackHandler` on the way out. And it is no worse
        // than the pre-fix state, where the topmost branch was live for every fade.
        val barTransition = updateTransition(inSelection, label = "detail_bar")
        barTransition.Crossfade(
            animationSpec = screenTransitionSpec(),
            modifier      = Modifier.align(Alignment.TopCenter),
        ) { selecting ->
            val live = selecting == barTransition.currentState
            if (selecting) {
                DetailTopBar(
                    paneColor      = paneColor,
                    navigationIcon = {
                        IconButton(
                            onClick = { haptics.press(); viewModel.exitSelectionMode() },
                            enabled = live,
                        ) {
                            Icon(Icons.Default.Close,
                                contentDescription = stringResource(R.string.library_selection_cancel))
                        }
                    },
                    actions        = {
                        // press() only — the confirm/reject buzz reports what the API actually did
                        // and is fired once from LibraryScreen when `removeResult` lands.
                        IconButton(
                            onClick = {
                                haptics.press()
                                // A batch is confirmed first; a single checked row is one deliberate
                                // tap and goes straight through, like the song menu's remove row.
                                if (nSelected >= 2) showRemoveConfirm = true
                                else viewModel.removeSelectedTracks()
                            },
                            enabled = live && nSelected > 0 && !state.isRemovingSelection,
                        ) {
                            Icon(Icons.Default.Delete,
                                contentDescription = stringResource(R.string.library_selection_remove))
                        }
                    },
                    // No hand-off: the count is the whole point of a contextual bar, so it is
                    // visible from the first frame of the mode regardless of the scroll position.
                    title          = {
                        Text(
                            text     = pluralStringResource(
                                R.plurals.library_selected_count, nSelected, nSelected,
                            ),
                            maxLines = 1,
                        )
                    },
                )
            } else {
                DetailTopBar(
                    paneColor      = paneColor,
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(
                                onClick = { haptics.confirm(); onBack() },
                                enabled = live,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back))
                            }
                        }
                    },
                    actions        = {
                        if (canDelete) {
                            // The menu anchors to this Box, which is the ⋮ button's own slot in the
                            // actions row.
                            Box {
                                IconButton(
                                    onClick = { haptics.press(); showOverflowMenu = true },
                                    enabled = live,
                                ) {
                                    Icon(Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.more_options))
                                }
                                DropdownMenu(
                                    expanded         = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false },
                                ) {
                                    // Second door into selection mode — discoverable without a
                                    // long-press, and present for exactly the playlists the song
                                    // menu's "Select" row is (owned ones, since this whole action is
                                    // gated on ownership).
                                    DropdownMenuItem(
                                        text        = { Text(stringResource(R.string.library_select_songs)) },
                                        leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                                        onClick     = {
                                            haptics.press(); showOverflowMenu = false
                                            viewModel.enterSelectionMode()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text        = { Text(stringResource(R.string.delete_playlist)) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        onClick     = {
                                            haptics.press(); showOverflowMenu = false
                                            showDeleteConfirm = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                    heroTitle      = heroTitle,
                    title          = { titleModifier ->
                        Text(
                            text     = playlistName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = titleModifier,
                        )
                    },
                )
            }
        }
        if (showRemoveConfirm && playlist != null) {
            AlertDialog(
                onDismissRequest = { showRemoveConfirm = false },
                title   = { Text(stringResource(R.string.library_selection_confirm_title)) },
                text    = {
                    Text(pluralStringResource(
                        R.plurals.library_selection_confirm_message, nSelected, nSelected, playlist.name,
                    ))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRemoveConfirm = false
                        haptics.press()   // the confirm/reject buzz fires when removeResult lands
                        viewModel.removeSelectedTracks()
                    }) {
                        Text(
                            text  = stringResource(R.string.library_selection_confirm_button),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { haptics.press(); showRemoveConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
        if (showDeleteConfirm && playlist != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title   = { Text(stringResource(R.string.delete_playlist_confirm_title)) },
                text    = { Text(stringResource(R.string.delete_playlist_confirm_message, playlist.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        haptics.confirm()
                        viewModel.deletePlaylist(playlist)   // closes the detail view on success
                    }) {
                        Text(
                            text  = stringResource(R.string.delete_playlist_confirm_button),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { haptics.press(); showDeleteConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

// ── Track-list hero ───────────────────────────────────────────────────────────
// The shared DetailArtHero with the playlist/Liked art (real cover, else the Liked gradient or a
// music-note fallback) and an "N tracks" subtitle.

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackListHero(
    artUrl      : String?,
    isLikedSongs: Boolean,
    name        : String,
    trackCount  : Int,
    onPlay      : () -> Unit,
    onShuffle   : () -> Unit,
    selecting   : Boolean = false,
    playlistId  : String? = null,
    sharedScope : SharedTransitionScope? = null,
    animScope   : AnimatedContentScope? = null,
    titleHandoff: HeroTitleHandoff? = null,
) {
    // Container-transform TARGET (single pane): the hero art tile shares bounds with the tapped
    // browser card (`lib-art-<id>`, or `lib-art-liked`) and flies + cross-fades into the hero. Card
    // and hero are the same M3 square shape, so it's a real M3 container transform (no shape morph).
    val artModifier = if (sharedScope != null && animScope != null)
        with(sharedScope) {
            Modifier.sharedBounds(
                sharedContentState      = rememberSharedContentState(key = libArtKey(playlistId)),
                animatedVisibilityScope = animScope,
                boundsTransform         = rememberArtBoundsTransform(),
            )
        } else Modifier

    DetailArtHero(
        title        = name,
        subtitle     = if (trackCount > 0) pluralStringResource(R.plurals.library_track_count, trackCount, trackCount) else null,
        // Play / Shuffle are suspended in selection mode — a tap in the list is a check, so
        // starting playback from the hero mid-selection would be a mixed message. Passing null
        // omits the cookie buttons entirely (the shared hero's own contract).
        onPlay       = if (selecting) null else onPlay,
        onShuffle    = if (selecting) null else onShuffle,
        artModifier  = artModifier,
        titleHandoff = titleHandoff,
    ) {
        if (!artUrl.isNullOrBlank()) {
            AsyncImage(
                model              = artUrl,
                contentDescription = name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else if (isLikedSongs) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF6A11CB), Color(0xFF2575FC)))
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Favorite, null, tint = Color.White.copy(0.5f),
                    modifier = Modifier.size(84.dp))
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp))
            }
        }
    }
}

// ── Paginated track list ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackList(
    tracks         : List<com.crsmthw.lyra.data.remote.model.SpotifyTrack>,
    currentTrackId : String?,
    isPlaying      : Boolean,
    isLoadingMore  : Boolean,
    canLoadMore    : Boolean,
    onLoadMore     : () -> Unit,
    onTrackClick   : (com.crsmthw.lyra.data.remote.model.SpotifyTrack) -> Unit,
    modifier       : Modifier = Modifier,
    onTrackLongClick: ((com.crsmthw.lyra.data.remote.model.SpotifyTrack) -> Unit)? = null,
    // null = not in selection mode; otherwise the checked uris (drives each row's check overlay).
    selectedUris   : Set<String>? = null,
    contentPadding : PaddingValues = PaddingValues(bottom = 100.dp),
    listState      : androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    headerContent  : (@Composable () -> Unit)? = null,
    emptyContent   : (@Composable () -> Unit)? = null,
) {

    ListScrollHaptics(listState)

    val reachedBottom by remember {
        derivedStateOf {
            val info        = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - 5
        }
    }

    LaunchedEffect(reachedBottom) {
        if (reachedBottom && canLoadMore && !isLoadingMore) onLoadMore()
    }

    // A row's key must NOT depend on how many unrelated rows precede it. With the index baked in,
    // a multi-select removal above the viewport changed the first-visible row's key, LazyList's
    // findIndexByKey missed (an exact map lookup — no partial matching on the id half) and kept the
    // raw index, so the list jumped forward by the number of rows removed above it and every
    // visible row's remembered state was thrown away. Keying on the uri plus a per-uri occurrence
    // ordinal is stable instead: remove-by-uri drops EVERY occurrence of the uri it names, so when
    // a uri goes all of its rows go together and no surviving uri's ordinal shifts. The ordinal is
    // mandatory, not tidy — a bare uri would trip SaveableStateHolder's duplicate-key `require`
    // whenever both copies of a twice-added track are composed at once. Precomputed once per list
    // instance (never inside the key lambda, which the nearest-range map re-invokes per item), and
    // carried alongside each row so the keys can't desync from the content.
    val keyedTracks = remember(tracks) {
        val seen = HashMap<String, Int>(tracks.size)
        tracks.map { t ->
            val n = seen.getOrDefault(t.uri, 0)
            seen[t.uri] = n + 1
            "${t.uri}#$n" to t
        }
    }

    LazyColumn(
        state          = listState,
        modifier       = modifier,
        contentPadding = contentPadding,
    ) {
        headerContent?.let { header ->
            item(key = "playlist_header") { header() }
        }
        if (tracks.isEmpty() && emptyContent != null) {
            item(key = "empty_state") { emptyContent() }
        }
        items(keyedTracks, key = { it.first }) { (_, track) ->
            TrackRow(
                track       = track,
                isPlaying   = currentTrackId == track.id && isPlaying,
                onClick     = { onTrackClick(track) },
                // A podcast episode gets NO long-press outside selection mode. The handler
                // ignores one anyway (every row of TrackActionsSheet is track-only), but a
                // present handler still fires TrackRow's long-press haptic — a buzz for a
                // gesture that does nothing. `selectedUris != null` IS selection mode, where a
                // long-press is just another way to check the row and works fine for an episode.
                onLongClick = onTrackLongClick
                    ?.takeIf { selectedUris != null || !track.isEpisode }
                    ?.let { handler -> { handler(track) } },
                selected    = selectedUris?.contains(track.uri),
            )
        }
        if (isLoadingMore) {
            item(key = "loading_more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
