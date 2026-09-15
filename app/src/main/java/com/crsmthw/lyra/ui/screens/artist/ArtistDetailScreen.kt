package com.crsmthw.lyra.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.ui.components.DetailArtHero
import com.crsmthw.lyra.ui.components.DetailTopBar
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.rememberHeroTitleHandoff
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.util.ListScrollHaptics
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.horizontalSystemBarsPadding
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.toggle
import com.crsmthw.lyra.util.visualizer.FftWaveCanvas
import com.crsmthw.lyra.util.visualizer.LocalVisualizerAccentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistDetailScreen(
    viewModel             : ArtistDetailViewModel,
    playerViewModel       : PlayerViewModel,
    onBack                : () -> Unit,
    onOpenAlbum           : (albumId: String) -> Unit,
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

    // The shared `DetailTopBar` (docs/APP_BARS_OPTIONS.md → D1) replaces the floating back / title /
    // share pills and the single-pane TopScrim. One back icon for every bar on the screen
    // (single-pane, the two-pane LEFT pane, loading/error) so the gesture, the debounce path
    // (`onBack` → `safeNavigateUp`) and the haptic can't drift.
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
            state.artist != null -> {
                val artist = state.artist!!

                val labelAlbums       = stringResource(R.string.artist_albums)
                val labelSingles      = stringResource(R.string.artist_singles)
                val labelCompilations = stringResource(R.string.artist_compilations)
                val groupedAlbums = remember(state.albums) {
                    val map = state.albums.groupBy {
                        it.albumType?.lowercase()?.takeIf { t -> t.isNotBlank() } ?: "album"
                    }
                    listOfNotNull(
                        map["album"]?.let       { labelAlbums       to it },
                        map["single"]?.let      { labelSingles      to it },
                        map["compilation"]?.let { labelCompilations to it },
                    )
                }

                // Shared artist photo (single-pane hero + two-pane left panel) — image, else a fallback.
                val artistArt: @Composable BoxScope.() -> Unit = {
                    if (artist.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model              = artist.imageUrl,
                            contentDescription = artist.name,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Person, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxSize(0.4f))
                        }
                    }
                }

                // The old TopActionPill's contents verbatim (follow heart + share), now the bar's
                // `actions` slot — one definition for both panes, which is also the last of the two
                // copies the pills needed. `@Composable RowScope.() -> Unit` is exactly the shape
                // `DetailTopBar` wants.
                val artistActions: @Composable RowScope.() -> Unit = {
                    IconButton(
                        onClick = { haptics.toggle(state.isFollowed != true); viewModel.toggleFollowed() },
                        enabled = state.isFollowed != null,
                    ) {
                        Icon(
                            imageVector        = if (state.isFollowed == true) Icons.Default.Favorite
                                                 else Icons.Default.FavoriteBorder,
                            contentDescription = stringResource(
                                if (state.isFollowed == true) R.string.cd_unfollow else R.string.cd_follow),
                        )
                    }
                    IconButton(onClick = {
                        haptics.press()
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/artist/${artist.id}")
                                type = "text/plain"
                            }, null
                        ))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.player_share))
                    }
                }

                if (isWideScreen) {
                    // Edge-to-edge under a transparent status bar (like single-pane). The hero's
                    // own `statusBarsPadding()` + the LEFT pane's bar / the right pane's TopScrim
                    // carry the top inset; no parent statusBarsPadding (which would leave an opaque
                    // band where the bar sits).
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
                            // Left pane — the detail hero panel (photo + name), with the solid
                            // DetailTopBar over it (was: a top scrim plus back and share pills).
                            // No play/shuffle on an artist hero.
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
                                            title      = artist.name,
                                            subtitle   = artist.formattedFollowers.takeIf { it.isNotBlank() },
                                            artContent = artistArt,
                                        )
                                    }
                                    // No title in this bar, and so no hand-off: the hero's own name
                                    // sits right under it and barely scrolls in a pane this short,
                                    // exactly as this pane carried no title pill. `paneColor` is the
                                    // CARD's colour, not the screen background.
                                    DetailTopBar(
                                        paneColor      = MaterialTheme.colorScheme.surface,
                                        navigationIcon = backNavIcon,
                                        actions        = artistActions,
                                        modifier       = Modifier.align(Alignment.TopCenter),
                                    )
                                }
                            }

                            // Right pane — discography
                            Card(
                                modifier  = Modifier.weight(0.58f).fillMaxHeight(),
                                shape     = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val albumsListState = rememberLazyListState()
                                    ListScrollHaptics(albumsListState)
                                    LazyColumn(
                                        state          = albumsListState,
                                        modifier       = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(top = statusBarTopDp, bottom = 100.dp + navBarBottomDp),
                                    ) {
                                        artistContent(
                                            state         = state,
                                            groupedAlbums = groupedAlbums,
                                            onOpenAlbum   = onOpenAlbum,
                                            onLoadMore    = viewModel::loadMoreAlbums,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(scrimHeight)
                                            .align(Alignment.BottomCenter)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
                                    )
                                    // Top scrim — fades the discography under the transparent status bar.
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
                        val albumsListState = rememberLazyListState()
                        ListScrollHaptics(albumsListState)
                        // The bar title takes over from the hero title over the ~35dp that title
                        // needs to slide under the bar (M3's own `TopTitleAlphaEasing` hand-off) —
                        // not over the whole hero's scroll, which read as a slow crossfade.
                        val heroTitle = rememberHeroTitleHandoff()
                        LazyColumn(
                            state          = albumsListState,
                            modifier       = Modifier.fillMaxSize(),
                            // No top inset: `DetailArtHero` bakes `statusBarsPadding()` + the bar's
                            // own collapsed height + 8dp onto its art tile. Adding one here doubles.
                            contentPadding = PaddingValues(bottom = 100.dp + navBarBottomDp),
                        ) {
                            item(key = "header") {
                                DetailArtHero(
                                    title        = artist.name,
                                    subtitle     = artist.formattedFollowers.takeIf { it.isNotBlank() },
                                    titleHandoff = heroTitle,
                                    artContent   = artistArt,
                                )
                            }
                            artistContent(
                                state         = state,
                                groupedAlbums = groupedAlbums,
                                onOpenAlbum   = onOpenAlbum,
                                onLoadMore    = viewModel::loadMoreAlbums,
                            )
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

                        // The bar, composed LAST so it draws (and hit-tests) over the list. Solid
                        // `background` at rest and scrolled — at rest only 8dp of page background
                        // sits between its bottom edge and the art, so it reads as the page until
                        // the art arrives (it replaces the old TopScrim as well as the pills).
                        DetailTopBar(
                            paneColor      = background,
                            navigationIcon = backNavIcon,
                            actions        = artistActions,
                            heroTitle      = heroTitle,
                            title          = { titleModifier ->
                                Text(
                                    text     = artist.name,
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

            // Screen-level back bar, for the loading and error states ONLY — once the artist loads,
            // the bar that carries the title and actions lives inside the layout that owns the
            // scrolling content (single-pane over the list, two-pane over the LEFT card).
            //
            // The condition is the exact NEGATION of the `when`'s content arm, not just
            // `artist == null`: a future reload path that set `isLoading` over a loaded artist
            // would otherwise swap the content (bar included) for the spinner and leave back
            // unreachable.
            //
            // It carries its own `horizontalSystemBarsPadding()` because it is a SIBLING of those
            // layouts, not a descendant — still one horizontal application per subtree, not a second
            // on the same element. In the wide case it also takes the Row's own 8dp inset so the
            // back arrow doesn't jump when the content lands and the left-pane bar takes over.
            if (state.isLoading || state.error != null || state.artist == null) {
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
}

private fun LazyListScope.artistContent(
    state         : ArtistDetailUiState,
    groupedAlbums : List<Pair<String, List<SpotifyAlbum>>>,
    onOpenAlbum   : (String) -> Unit,
    onLoadMore    : () -> Unit,
) {
    groupedAlbums.forEach { (label, albums) ->
        item(key = "header_$label") {
            SectionHeader(label)
        }
        items(albums, key = { "album_${it.id}" }) { album ->
            ArtistAlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
        }
    }

    if (state.albumsNext != null || state.isLoadingMore) {
        item(key = "load_more") {
            Box(
                modifier         = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isLoadingMore) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    OutlinedButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.artist_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ArtistAlbumRow(
    album  : SpotifyAlbum,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            val url = album.images?.firstOrNull()?.url
            if (url != null) {
                AsyncImage(
                    model              = url,
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
        supportingContent = {
            Text(
                text  = album.releaseYear,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
        content  = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}
