package com.crsmthw.lyra.ui.screens.search

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.local.RecentSearch
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.components.TrackRow
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.rememberArtBoundsTransform
import kotlinx.coroutines.flow.first
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
       ExperimentalSharedTransitionApi::class)
@Composable
fun SearchScreen(
    viewModel             : SearchViewModel,
    onBack                : () -> Unit,
    onOpenPlayer          : () -> Unit,
    onAlbumClick          : (albumId: String) -> Unit,
    onArtistClick         : (artistId: String) -> Unit,
    onTrackClick          : (uri: String, allUris: List<String>) -> Unit,
    sharedTransitionScope : SharedTransitionScope? = null,
    animatedContentScope  : AnimatedContentScope? = null,
) {
    val state          by viewModel.uiState.collectAsStateWithLifecycle()
    val recents        by viewModel.recentSearches.collectAsStateWithLifecycle()
    val keyboard        = LocalSoftwareKeyboardController.current
    val focusRequester  = remember { FocusRequester() }
    val haptics         = LocalHapticFeedback.current

    // Container transform: the floating bar shares bounds with the Library search FAB (same
    // SEARCH_BAR_SHARED_KEY) so tapping the FAB expands it into this bar. Null scopes (two-pane /
    // previews) fall back to no morph.
    val searchBarSharedModifier: Modifier =
        if (sharedTransitionScope != null && animatedContentScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState      = rememberSharedContentState(key = SEARCH_BAR_SHARED_KEY),
                    animatedVisibilityScope = animatedContentScope,
                    boundsTransform         = rememberArtBoundsTransform(),
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
    // Top content inset clears the floating bar: status bar + top margin + bar height + a gap.
    val topInset       = statusBarTopDp + 8.dp + SearchBarHeight + 12.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrolling content rides above the keyboard; the floating bar + top scrim do not.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
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
                    val results   = state.results!!
                    val tracks    = results.tracks?.items    ?: emptyList()
                    val albums    = results.albums?.items    ?: emptyList()
                    val artists   = results.artists?.items   ?: emptyList()
                    val playlists = results.playlists?.items ?: emptyList()

                    val hasAny = tracks.isNotEmpty() || albums.isNotEmpty() ||
                                 artists.isNotEmpty() || playlists.isNotEmpty()

                    if (!hasAny) {
                        Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                            Text("No results for \"${state.query}\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val resultsListState = rememberLazyListState()
                        ListScrollHaptics(resultsListState)
                        LazyColumn(
                            state          = resultsListState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = topInset, bottom = navBarBottomDp + 16.dp),
                        ) {

                            // ── Artists — horizontal stories row ──────────────
                            if (artists.isNotEmpty()) {
                                item(key = "section_artists") {
                                    SectionHeader("Artists")
                                }
                                item(key = "artists_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        items(artists, key = { "artist_${it.id}" }) { artist ->
                                            ArtistChip(
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

                            // ── Tracks ────────────────────────────────────────
                            if (tracks.isNotEmpty()) {
                                item(key = "section_tracks") {
                                    SectionHeader("Tracks")
                                }
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

                            // ── Albums ────────────────────────────────────────
                            if (albums.isNotEmpty()) {
                                item(key = "section_albums") {
                                    SectionHeader("Albums")
                                }
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

                            // ── Playlists ─────────────────────────────────────
                            if (playlists.isNotEmpty()) {
                                item(key = "section_playlists") {
                                    SectionHeader("Playlists")
                                }
                                items(playlists, key = { "playlist_${it.id}" }) { playlist ->
                                    PlaylistRow(
                                        playlist = playlist,
                                        onClick  = {
                                            viewModel.addRecentSearch(playlist.toRecentSearch())
                                            onOpenPlayer()
                                        },
                                    )
                                }
                            }

                            item(key = "footer_space") { Spacer(Modifier.height(16.dp)) }
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
        if (state.query.isBlank() && recents.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = topInset),
            ) {
                SectionHeader(stringResource(R.string.search_recent))
                recents.forEach { recent ->
                    RecentSearchRow(
                        recent  = recent,
                        onClick = {
                            haptics.confirm()
                            viewModel.addRecentSearch(recent)   // re-tapping moves it to the front
                            when (recent.type) {
                                "track"    -> onTrackClick(recent.uri, listOf(recent.uri))
                                "album"    -> onAlbumClick(recent.id)
                                "artist"   -> onArtistClick(recent.id)
                                "playlist" -> onOpenPlayer()
                            }
                        },
                    )
                }
            }
        }

        // Top scrim — fades content under the status bar (covers the bar; no statusBarsPadding).
        TopScrim(color = background, modifier = Modifier.align(Alignment.TopCenter))

        // Floating M3 search bar. The back arrow is its own leading icon, so there is no separate
        // floating back pill — one element, which also keeps the FAB→bar morph clean.
        SearchInputBar(
            query          = state.query,
            onQueryChange  = viewModel::onQueryChange,
            onBack         = { keyboard?.hide(); haptics.confirm(); onBack() },
            onClear        = viewModel::clearQuery,
            onSearch       = { keyboard?.hide() },
            focusRequester = focusRequester,
            modifier       = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .then(searchBarSharedModifier),
        )
    }

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

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** One row of the "Recent" list — mirrors [AlbumRow]'s look; artist art is a circle. */
@Composable
private fun RecentSearchRow(recent: RecentSearch, onClick: () -> Unit) {
    val artShape = if (recent.type == "artist") CircleShape else RoundedCornerShape(4.dp)
    ListItem(
        headlineContent   = { Text(recent.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ArtistChip(artist: SpotifyArtist, onClick: () -> Unit) {
    Column(
        modifier            = Modifier
            .width(88.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val imageUrl = artist.images?.firstOrNull()?.url
        if (imageUrl != null) {
            AsyncImage(
                model              = imageUrl,
                contentDescription = artist.name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.size(64.dp).clip(CircleShape),
            )
        } else {
            Surface(
                modifier = Modifier.size(64.dp),
                shape    = CircleShape,
                color    = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text      = artist.name,
            style     = MaterialTheme.typography.labelSmall,
            maxLines  = 2,
            overflow  = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AlbumRow(album: SpotifyAlbum, onClick: () -> Unit) {
    val imageUrl    = album.images?.firstOrNull()?.url
    val artistNames = album.artists?.joinToString(", ") { it.name } ?: ""

    ListItem(
        headlineContent  = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent= {
            Text(
                artistNames.ifBlank { "Album" },
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
    )
}

@Composable
private fun PlaylistRow(playlist: SpotifyPlaylist, onClick: () -> Unit) {
    ListItem(
        headlineContent  = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent= {
            Text(
                playlist.owner?.displayName ?: "Playlist",
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            val imageUrl = playlist.thumbnailUrl.ifBlank { null }
            if (imageUrl != null) {
                AsyncImage(
                    model              = imageUrl,
                    contentDescription = playlist.name,
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
    )
}

/** Height of the floating search bar — matches the M3 search input-field height. */
private val SearchBarHeight = 56.dp

/**
 * Floating Material 3 search bar. A `SearchBarDefaults.InputField` (transparent container) inside a
 * stadium [Surface] tinted to match the screen's other floating pills (`surfaceContainerHigh` + a
 * small shadow). The back arrow is the field's own leading icon — no separate back pill — so the
 * whole control is a single bounding box (which the FAB→bar container transform will share).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInputBar(
    query         : String,
    onQueryChange : (String) -> Unit,
    onBack        : () -> Unit,
    onClear       : () -> Unit,
    onSearch      : () -> Unit,
    focusRequester: FocusRequester,
    modifier      : Modifier = Modifier,
) {
    Surface(
        modifier        = modifier.fillMaxWidth().height(SearchBarHeight),
        shape           = CircleShape,
        color           = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        SearchBarDefaults.InputField(
            query            = query,
            onQueryChange    = onQueryChange,
            onSearch         = { onSearch() },
            expanded         = false,
            onExpandedChange = {},
            modifier         = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder      = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon      = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back))
                }
            },
            trailingIcon     = if (query.isNotBlank()) {
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
