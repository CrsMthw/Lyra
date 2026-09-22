package com.crsmthw.lyra.ui.screens.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
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
import com.crsmthw.lyra.data.remote.model.AlbumTrack
import com.crsmthw.lyra.data.remote.model.SpotifyAlbumFull
import com.crsmthw.lyra.ui.components.DetailArtHero
import com.crsmthw.lyra.ui.components.DetailTopBar
import com.crsmthw.lyra.ui.components.DetailTopBarFade
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.components.rememberHeroTitleHandoff
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.horizontalSystemBarsPadding
import com.crsmthw.lyra.util.longPress
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.toggle
import com.crsmthw.lyra.util.toDurationString
import com.crsmthw.lyra.util.toTimeString
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumDetailScreen(
    viewModel             : AlbumDetailViewModel,
    playerViewModel       : PlayerViewModel,
    onBack                : () -> Unit,
    onNavigateToPlayer    : () -> Unit,
    onOpenArtist          : ((artistId: String) -> Unit)? = null,
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

    // App bars (docs/APP_BARS_OPTIONS.md → D1): the floating back / title / share pills and the
    // single-pane TopScrim are replaced by the shared `DetailTopBar` laid over the hero — SOLID, in
    // the pane's own colour, with no scroll behaviour and no colour change (see its KDoc for the
    // grey flash the trial's transparent container produced). One back icon for every bar on the
    // screen (single-pane, the two-pane LEFT pane, and the loading/error state) so the gesture, the
    // debounce path (`onBack` → `safeNavigateUp`) and the haptic can't drift.
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
            state.album != null -> {
                val album    = state.album!!
                val tracks   = album.tracks?.items?.filter { it.isPlayable != false } ?: emptyList()
                val albumUri = "spotify:album:${album.id}"

                val onPlayAll: () -> Unit = {
                    if (tracks.isNotEmpty()) {
                        haptics.press()
                        playerViewModel.playTrack(uri = tracks[0].uri, contextUri = albumUri, index = 0)
                        onNavigateToPlayer()
                    }
                }
                val onPlayTrack = { track: AlbumTrack, idx: Int ->
                    playerViewModel.playTrack(uri = track.uri, contextUri = albumUri, index = idx)
                    onNavigateToPlayer()
                }
                val onTrackLongPress = { track: AlbumTrack ->
                    viewModel.trackActions.open(track.toTrackActionTarget(album))
                }

                // Metadata line under the artist — year · type · N songs (plural-safe) · playtime.
                // Same `tracks` (isPlayable-filtered) the two-pane list uses, so count/time match.
                val totalMs   = tracks.sumOf { it.durationMs }
                val albumMeta = listOfNotNull(
                    album.releaseYear.takeIf { it.isNotBlank() },
                    album.albumTypeDisplay.takeIf { it.isNotBlank() },
                    if (tracks.isNotEmpty())
                        pluralStringResource(R.plurals.album_tracks_count, tracks.size, tracks.size)
                    else null,
                    if (totalMs > 0L) totalMs.toDurationString() else null,
                ).joinToString(" · ").takeIf { it.isNotBlank() }

                // Shared album art (single-pane hero + two-pane left panel) — cover, else a fallback.
                val albumArt: @Composable BoxScope.() -> Unit = {
                    if (!album.artUrl.isNullOrBlank()) {
                        AsyncImage(
                            model              = album.artUrl,
                            contentDescription = album.name,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
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

                // The old TopActionPill's contents verbatim (save/follow heart + share), now the
                // bar's `actions` slot — one definition for both panes, which is also the last of
                // the two copies the pills needed. `@Composable RowScope.() -> Unit` is exactly the
                // shape `TopAppBar` wants.
                val albumActions: @Composable RowScope.() -> Unit = {
                    IconButton(
                        onClick = { haptics.toggle(state.isSaved != true); viewModel.toggleSaved() },
                        enabled = state.isSaved != null,
                    ) {
                        Icon(
                            imageVector        = if (state.isSaved == true) Icons.Default.Favorite
                                                 else Icons.Default.FavoriteBorder,
                            contentDescription = stringResource(
                                if (state.isSaved == true) R.string.cd_unfollow else R.string.cd_follow),
                        )
                    }
                    IconButton(onClick = {
                        haptics.press()
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/album/${album.id}")
                                type = "text/plain"
                            }, null
                        ))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.player_share))
                    }
                }

                if (isWideScreen) {
                    // Edge-to-edge under a transparent status bar (like single-pane), so NO parent
                    // statusBarsPadding (it would leave an opaque band where the status bar sits).
                    // Each pane self-pads: the LEFT pane's app bar takes the status-bar inset as its
                    // own `windowInsets` over a hero that already bakes `statusBarsPadding()`; the
                    // RIGHT pane keeps its top contentPadding inset + TopScrim.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalSystemBarsPadding(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Left pane — the detail hero panel (art + name/meta + play/shuffle),
                            // with the transparent pinned app bar over it (was: a top scrim plus a
                            // back pill and a share pill).
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
                                            title      = album.name,
                                            subtitle   = album.artists?.joinToString(", ") { it.name },
                                            meta       = albumMeta,
                                            onPlay     = onPlayAll,
                                            onShuffle  = {
                                                haptics.press()
                                                playerViewModel.shuffleContext(albumUri)
                                                onNavigateToPlayer()
                                            },
                                            artContent = albumArt,
                                        )
                                    }
                                    // The seam under the bar — composed after the pane's content and
                                    // before the bar, so it draws over the hero and under the bar.
                                    DetailTopBarFade(
                                        paneColor = MaterialTheme.colorScheme.surface,
                                        modifier  = Modifier.align(Alignment.TopCenter),
                                    )
                                    // No title in this bar, and so no hand-off: the hero's own name
                                    // sits right under it and barely scrolls in a pane this short,
                                    // exactly as the two-pane left pane carried no title pill.
                                    // `paneColor` is the CARD's colour here, not the screen
                                    // background — the bar IS whatever pane it sits on.
                                    DetailTopBar(
                                        paneColor      = MaterialTheme.colorScheme.surface,
                                        navigationIcon = backNavIcon,
                                        actions        = albumActions,
                                        modifier       = Modifier.align(Alignment.TopCenter),
                                    )
                                }
                            }

                            // Right pane — track list
                            Card(
                                modifier  = Modifier.weight(0.58f).fillMaxHeight(),
                                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val tracksListState = rememberLazyListState()
                                    ListScrollHaptics(tracksListState)
                                    LazyColumn(
                                        state          = tracksListState,
                                        modifier       = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(top = statusBarTopDp, bottom = 100.dp + navBarBottomDp),
                                    ) {
                                        itemsIndexed(tracks, key = { idx, t -> "track_${t.id}_$idx" }) { idx, track ->
                                            AlbumTrackRow(track = track, onClick = { onPlayTrack(track, idx) }, onLongClick = { onTrackLongPress(track) })
                                        }
                                        if (!album.label.isNullOrBlank() || album.copyrights?.isNotEmpty() == true) {
                                            item(key = "footer") { AlbumFooter(album = album) }
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(scrimHeight)
                                            .align(Alignment.BottomCenter)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
                                    )
                                    // Top scrim — fades tracks under the transparent status bar.
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
                            .horizontalSystemBarsPadding(),
                    ) {
                        val tracksListState = rememberLazyListState()
                        ListScrollHaptics(tracksListState)
                        // The bar title takes over from the hero title over the ~35dp that title
                        // needs to slide under the bar — M3's own `TopTitleAlphaEasing` hand-off,
                        // driven by the two composables' measured positions. NOT the whole hero's
                        // scroll progress (`rememberHeroScrollProgress`), which the trial used: a
                        // ~400dp ramp reads as a slow crossfade, which Cris rejected on device.
                        val heroTitle = rememberHeroTitleHandoff()
                        LazyColumn(
                            state          = tracksListState,
                            modifier       = Modifier.fillMaxSize(),
                            // NO extra top inset. `DetailArtHero` already bakes
                            // `statusBarsPadding()` + the bar's own collapsed height + 8dp +
                            // `BarContentGap` onto its art tile — adding a top inset here would
                            // double it, and forking the component to remove its padding would break
                            // the two-pane pane and the Library hero that share it.
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) {
                            item(key = "header") {
                                DetailArtHero(
                                    title        = album.name,
                                    subtitle     = album.artists?.joinToString(", ") { it.name },
                                    meta         = albumMeta,
                                    onPlay       = onPlayAll,
                                    onShuffle    = {
                                        haptics.press()
                                        playerViewModel.shuffleContext(albumUri)
                                        onNavigateToPlayer()
                                    },
                                    titleHandoff = heroTitle,
                                    artContent   = albumArt,
                                )
                            }
                            itemsIndexed(tracks, key = { idx, t -> "track_${t.id}_$idx" }) { idx, track ->
                                AlbumTrackRow(track = track, onClick = { onPlayTrack(track, idx) }, onLongClick = { onTrackLongPress(track) })
                            }
                            if (!album.label.isNullOrBlank() || album.copyrights?.isNotEmpty() == true) {
                                item(key = "footer") { AlbumFooter(album = album) }
                            }
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

                        // The seam under the bar: the page colour fading out over the first rows, so
                        // the hero art and the tracks dissolve into the bar instead of sliding past
                        // its title — the same strip the Library browser has under its tab row.
                        // Composed after the list and before the bar, so it draws over the content
                        // and under the bar, and it takes no pointer input.
                        DetailTopBarFade(
                            paneColor = background,
                            modifier  = Modifier.align(Alignment.TopCenter),
                        )

                        // The bar, composed LAST so it draws (and hit-tests) over the list. Its
                        // height never changes (no scroll behaviour), so overlaying is correct, and
                        // it is solid `background` at rest and scrolled — at rest there is only the
                        // hero's 8dp + `BarContentGap` of page background between its bottom edge and
                        // the art, so it reads as the page until the art arrives (and the fade strip
                        // above covers that gap). Default `onSurface` icon/title colours are
                        // right here: unlike PlayerScreen there is no accent gradient behind the
                        // bar, just the page background.
                        DetailTopBar(
                            paneColor      = background,
                            navigationIcon = backNavIcon,
                            actions        = albumActions,
                            heroTitle      = heroTitle,
                            title          = { titleModifier ->
                                Text(
                                    text     = album.name,
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

            // Screen-level back bar, for the loading and error states ONLY — once the album loads,
            // the bar that carries the title and actions lives inside the layout that owns the
            // scrolling content (single-pane over the list, two-pane over the LEFT card), because
            // that is what gives it a scroll behaviour to colour itself from.
            //
            // The condition is the exact NEGATION of the `when`'s content arm, not just
            // `album == null`: today `loadAlbum()` only runs from `init` so `isLoading` implies a
            // null album, but a future reload path that set `isLoading` over a loaded album would
            // swap the content (bar included) for the spinner and leave back unreachable.
            //
            // It carries its own `horizontalSystemBarsPadding()` because it is a SIBLING of those
            // layouts, not a descendant — so this is still one horizontal application per subtree,
            // not a second one on the same element. In the wide case it also takes the Row's own
            // 8dp inset so the back arrow doesn't jump when the content lands and the left-pane
            // bar takes over.
            if (state.isLoading || state.error != null || state.album == null) {
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

    TrackActionsHost(
        controller   = viewModel.trackActions,
        onGoToAlbum  = {},   // album rows never expose this — we're already on the album
        onGoToArtist = { id -> onOpenArtist?.invoke(id) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    track      : AlbumTrack,
    onClick    : () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    ListItem(
        leadingContent = {
            Text(
                text     = "${track.trackNumber}",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
        },
        supportingContent = {
            Text(
                text     = track.allArtists,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Text(
                text  = track.durationMs.toTimeString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.combinedClickable(
            onClick     = { haptics.confirm(); onClick() },
            onLongClick = onLongClick?.let { handler -> {
                haptics.longPress()
                handler()
            } },
        ),
        content = {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text     = track.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.explicit) ExplicitBadge()
            }
        },
    )
}

@Composable
private fun ExplicitBadge() {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        shape = RoundedCornerShape(2.dp),
    ) {
        Text(
            text     = "E",
            style    = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun AlbumFooter(album: SpotifyAlbumFull) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        album.copyrights?.forEach { copyright ->
            Text(
                text  = copyright.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!album.label.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = album.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
