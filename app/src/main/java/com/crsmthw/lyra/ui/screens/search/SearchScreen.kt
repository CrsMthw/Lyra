package com.crsmthw.lyra.ui.screens.search

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
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.ui.components.TrackActionsHost
import com.crsmthw.lyra.ui.components.TrackRow
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(
    viewModel     : SearchViewModel,
    onBack        : () -> Unit,
    onOpenPlayer  : () -> Unit,
    onAlbumClick  : (albumId: String) -> Unit,
    onArtistClick : (artistId: String) -> Unit,
    onTrackClick  : (uri: String, allUris: List<String>) -> Unit,
) {
    val state          by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard        = LocalSoftwareKeyboardController.current
    val focusRequester  = remember { FocusRequester() }
    val haptics         = LocalHapticFeedback.current

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                SearchBar(
                    query          = state.query,
                    onQueryChange  = viewModel::onQueryChange,
                    onBack         = { haptics.confirm(); onBack() },
                    onClear        = viewModel::clearQuery,
                    focusRequester = focusRequester,
                    keyboard       = keyboard,
                )
            }
        },
    ) { paddingValues ->
        val density        = LocalDensity.current
        val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val scrimHeight    = navBarBottomDp + 48.dp
        val background     = MaterialTheme.colorScheme.background

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                            contentPadding = PaddingValues(bottom = navBarBottomDp + 16.dp),
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
                                                onClick = { onArtistClick(artist.id) },
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
                                        onClick = { onAlbumClick(album.id) },
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
                                        onClick  = onOpenPlayer,
                                    )
                                }
                            }

                            item(key = "footer_space") { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
                state.query.isBlank() -> {
                    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Search, contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.search_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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
        }
    }

    TrackActionsHost(
        controller   = viewModel.trackActions,
        onGoToAlbum  = onAlbumClick,
        onGoToArtist = onArtistClick,
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
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
                playlist.owner.displayName ?: "Playlist",
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

@Composable
private fun SearchBar(
    query         : String,
    onQueryChange : (String) -> Unit,
    onBack        : () -> Unit,
    onClear       : () -> Unit,
    focusRequester: FocusRequester,
    keyboard      : androidx.compose.ui.platform.SoftwareKeyboardController?,
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { keyboard?.hide(); onBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        OutlinedTextField(
            value         = query,
            onValueChange = onQueryChange,
            placeholder   = { Text(stringResource(R.string.search_placeholder)) },
            singleLine    = true,
            trailingIcon  = if (query.isNotBlank()) {
                { IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Clear") } }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch  = { keyboard?.hide() }),
            modifier        = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )
    }
}
