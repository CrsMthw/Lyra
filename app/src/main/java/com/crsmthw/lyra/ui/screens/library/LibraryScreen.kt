package com.crsmthw.lyra.ui.screens.library

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.reject
import com.crsmthw.lyra.util.threshold

/** Pairs the search FAB with the Search screen's bar for the container transform — must match the
 *  identical key in `SearchScreen`. */
internal const val SEARCH_BAR_SHARED_KEY = "search-bar"

/** Shared-element key for the single-pane Library container transform (tapped card art → detail
 *  hero). Namespaced separately from the nav-level `"album-art"` morph; `id` is the playlist id or
 *  `"liked"`. The matching source card and the hero use the same key so only that pair morphs. */
internal fun libArtKey(id: String?): String = "lib-art-${id ?: "liked"}"

/**
 * The browser's four scroll positions — one per filter tab, because the tabs are the pages of a
 * `HorizontalPager` and each page scrolls its own list.
 *
 * Held together so the pane takes one parameter instead of four; [get] is the only way the pane
 * picks one, so a page can never be handed the wrong list.
 */
internal class LibraryBrowserListStates(
    val playlists : LazyListState,
    val albums    : LazyListState,
    val artists   : LazyListState,
    val shows     : LazyListState,
) {
    operator fun get(filter: LibraryFilter): LazyListState = when (filter) {
        LibraryFilter.PLAYLISTS -> playlists
        LibraryFilter.ALBUMS    -> albums
        LibraryFilter.ARTISTS   -> artists
        LibraryFilter.SHOWS     -> shows
    }
}

/**
 * Creates the four browser list states. Called at SCREEN scope so they outlive the pane that
 * scrolls them — see the call site's comment for why that hoist is load-bearing.
 */
@Composable
internal fun rememberLibraryBrowserListStates(): LibraryBrowserListStates {
    val playlists = rememberLazyListState()
    val albums    = rememberLazyListState()
    val artists   = rememberLazyListState()
    val shows     = rememberLazyListState()
    return remember(playlists, albums, artists, shows) {
        LibraryBrowserListStates(playlists, albums, artists, shows)
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(
    viewModel             : LibraryViewModel,
    playerViewModel       : PlayerViewModel,
    /** Opens the player for a tapped track: the pop-out panel on a wide screen, a push to
     *  `PlayerScreen` on a narrow one. Supplied by `LyraNavGraph` from the app-wide
     *  `PlayerPanelHost`, which now hosts the mini player around the whole NavHost. */
    onOpenPlayer          : () -> Unit,
    onOpenSearch          : () -> Unit,
    onOpenSettings        : () -> Unit,
    onOpenAlbum           : (String) -> Unit = {},
    onOpenArtist          : (String) -> Unit = {},
    onOpenShow            : (String) -> Unit = {},
    onOpenStats           : () -> Unit = {},
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state        by viewModel.uiState.collectAsStateWithLifecycle()
    val isWideScreen  = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(600)
    val haptics       = LocalHapticFeedback.current
    val onOpenSearchHaptic = { haptics.confirm(); onOpenSearch() }

    // The browser's scroll positions — ONE PER FILTER TAB, hoisted to the SCREEN so they outlive the
    // pane that scrolls them. `rememberLazyListState` is `rememberSaveable(saver = LazyListState.Saver)`,
    // but inside `LibraryBrowserPane` that bought nothing: the single-pane browser lives in
    // `SinglePaneLayout`'s `AnimatedContent`, which simply disposes the pane when a playlist opens (it
    // is not a `SaveableStateHolder`), so backing out always landed at the top. Held here they survive
    // both the detail↔browser pane swap AND navigating away — the Library's own `NavBackStackEntry`
    // saves this screen's `rememberSaveable` values, so Album/Artist/Search/Stats/Settings and back
    // restore them too. A `LazyListState` restores its index/offset at construction, so a position is
    // on the FIRST frame, with no scroll animation. Both layouts get the same holder (only one is
    // composed at a time), so the positions also carry across a fold/unfold.
    //
    // Four states, not one, since the filter tabs became the pages of a `HorizontalPager`: each page
    // scrolls its own list, and switching tabs now returns to where that tab was left instead of
    // resetting it (Cris's verdict, 2026-09-14 — the shared-state reset moved the tab row under the
    // finger of someone mid-tap).
    val browserListStates = rememberLibraryBrowserListStates()

    // The browser app bar's collapse state, hoisted for the same reason as `browserListStates`: the
    // single-pane browser pane is disposed by `SinglePaneLayout`'s `AnimatedContent` whenever a
    // playlist detail opens, so a pane-local state would snap the bar back to fully expanded over a
    // list that restored itself half-way down. `rememberTopAppBarState` is `rememberSaveable`, so
    // held here it also survives navigating away and a fold/unfold (both layouts get the same
    // instance and only one is composed at a time).
    //
    // Safe with respect to the predictive-back seek: this is an ordinary composition-local
    // `mutableFloatStateOf` holder — it never enters `LibraryUiState`, so it cannot perturb
    // `detailKey`, which is the `SeekableTransitionState`'s target (docs/MOTION.md → Predictive
    // back). The COMPACT bar deliberately does not use it, and clears it on entry so this state
    // only ever describes the large bar — see `LibraryBrowserPane`. Nothing else resets it: the bar
    // stays where the user's last drag left it, across tab changes, detail opens and navigation.
    val browserBarState = rememberTopAppBarState()

    if (isWideScreen) {
        TwoPaneLayout(
            state                 = state,
            browserListStates     = browserListStates,
            browserBarState       = browserBarState,
            viewModel             = viewModel,
            playerViewModel       = playerViewModel,
            onOpenSearch          = onOpenSearchHaptic,
            onOpenSettings        = onOpenSettings,
            onRequestPlayer       = onOpenPlayer,
            onOpenAlbum           = onOpenAlbum,
            onOpenArtist          = onOpenArtist,
            onOpenShow            = onOpenShow,
            onOpenStats           = onOpenStats,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope  = animatedContentScope,
        )
    } else {
        SinglePaneLayout(
            state                 = state,
            browserListStates     = browserListStates,
            browserBarState       = browserBarState,
            viewModel             = viewModel,
            playerViewModel       = playerViewModel,
            onOpenSearch          = onOpenSearchHaptic,
            onOpenSettings        = onOpenSettings,
            onRequestPlayer       = onOpenPlayer,
            onOpenAlbum           = onOpenAlbum,
            onOpenArtist          = onOpenArtist,
            onOpenShow            = onOpenShow,
            onOpenStats           = onOpenStats,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope  = animatedContentScope,
        )
    }

    TrackActionsHost(
        controller           = viewModel.trackActions,
        onGoToAlbum          = onOpenAlbum,
        onGoToArtist         = onOpenArtist,
        onRemoveFromPlaylist = viewModel::removeTrackFromCurrentPlaylist,
        // Door 1 into multi-select: the long-pressed track becomes the first check. Only the
        // Library supplies this, so the row is absent from the menu on every other screen.
        onSelect             = { target -> viewModel.enterSelectionMode(target.uri) },
    )

    // Outcome of a multi-select removal, reported ONCE at screen level rather than from inside the
    // detail pane — that pane is rendered by both layouts (and, mid pane-swap, as both a live and a
    // frozen copy), and only one of them may buzz.
    val removeResult = state.removeResult
    LaunchedEffect(removeResult) {
        when (removeResult) {
            is RemoveSelectionResult.Success -> { haptics.confirm(); viewModel.clearRemoveResult() }
            is RemoveSelectionResult.Failure -> haptics.reject()   // the dialog below holds it
            null                             -> Unit
        }
    }
    if (removeResult is RemoveSelectionResult.Failure) {
        AlertDialog(
            onDismissRequest = viewModel::clearRemoveResult,
            icon    = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title   = { Text(stringResource(R.string.library_selection_remove_failed)) },
            text    = { Text(removeResult.message ?: stringResource(R.string.error_generic)) },
            confirmButton = {
                TextButton(onClick = viewModel::clearRemoveResult) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

/** Fires a one-shot threshold haptic each time a pull-to-refresh drag crosses the trigger point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullThresholdHaptics(state: PullToRefreshState) {
    val haptics = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction }.collect { fraction ->
            when {
                fraction >= 1f && !armed -> { armed = true; haptics.threshold() }
                fraction < 1f && armed   -> armed = false
            }
        }
    }
}

// ── Refresh-error dialog ──────────────────────────────────────────────────────

@Composable
internal fun RefreshErrorDialog(error: String, onDismiss: () -> Unit) {
    val isRateLimit   = error.contains("429")
    val retryAfterSec = if (isRateLimit)
        Regex("Retry-After=(\\d+)").find(error)?.groupValues?.get(1)?.toLongOrNull() else null
    val retryDisplay  = when {
        retryAfterSec == null -> null
        retryAfterSec >= 3600 -> "${retryAfterSec / 3600}h ${(retryAfterSec % 3600) / 60}m"
        retryAfterSec >= 60   -> "${retryAfterSec / 60}m ${retryAfterSec % 60}s"
        else                  -> "${retryAfterSec}s"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (isRateLimit) "Rate Limited" else "Refresh Error") },
        text  = {
            Text(if (isRateLimit) buildString {
                append("Spotify is rate limiting requests.")
                if (retryDisplay != null) append("\n\nRetry-After: $retryDisplay")
                append("\n\nCached data is shown. Wait, then retry.")
            } else error)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
