package com.crsmthw.lyra.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.local.JumpBackInItem
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.util.longPress
import java.io.File

// ── Liked Songs card ──────────────────────────────────────────────────────────

@Composable
internal fun LikedSongsCard(
    count             : Int,
    onOpen            : () -> Unit,
    onPlay            : () -> Unit,
    modifier          : Modifier = Modifier,
    isSelected        : Boolean = false,
    artSharedModifier : Modifier = Modifier,   // container-transform source — applied to the art tile
) {
    Card(
        onClick   = onOpen,
        modifier  = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .then(artSharedModifier)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6A11CB), Color(0xFF2575FC)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(28.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.liked_songs), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(pluralStringResource(R.plurals.library_track_count, count, count), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SmallFloatingActionButton(
                onClick         = onPlay,
                containerColor  = MaterialTheme.colorScheme.primaryContainer,
                contentColor    = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation       = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                modifier        = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.player_play),
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Playlist list card ────────────────────────────────────────────────────────

@Composable
internal fun PlaylistListCard(
    playlist          : SpotifyPlaylist,
    mosaicFile        : File?,
    modifier          : Modifier = Modifier,
    isSelected        : Boolean = false,
    isMine            : Boolean = false,   // own playlist → count only (owner name is the user, redundant)
    onClick           : () -> Unit,
    onPlay            : () -> Unit,
    artSharedModifier : Modifier = Modifier,   // container-transform source — applied to the art tile
) {
    Card(
        onClick   = onClick,
        modifier  = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbShape = RoundedCornerShape(12.dp)
            // Spotify cover first; the local mosaic is only a fallback when Spotify has no art yet.
            val thumbModel = playlist.thumbnailUrl.takeIf { it.isNotBlank() } ?: mosaicFile
            if (thumbModel != null) {
                AsyncImage(
                    model              = thumbModel,
                    contentDescription = playlist.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(56.dp).then(artSharedModifier).clip(thumbShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .then(artSharedModifier)
                        .clip(thumbShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val countText =
                    pluralStringResource(R.plurals.library_track_count, playlist.trackCount, playlist.trackCount)
                val subtitle = if (isMine) {
                    // Own playlist — the owner is the user, so show just the count.
                    countText
                } else {
                    // Following — show whose it is and the count together when both are known.
                    listOfNotNull(playlist.owner?.displayName, countText.takeIf { playlist.trackCount > 0 })
                        .joinToString(" · ")
                        .ifBlank { stringResource(R.string.library_playlist_fallback) }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.width(8.dp))

            SmallFloatingActionButton(
                onClick        = onPlay,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation      = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                modifier       = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.player_play),
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * One followed podcast row (Shows filter) — mirrors [AlbumListCard]: square art, show name, and
 * an "N episodes" subtitle.
 *
 * The subtitle is the episode count and NOT the publisher: February 2026 deprecated
 * `show.publisher` and the device spike confirmed it is absent from live responses, so a
 * publisher subtitle would render blank for every show.
 */
@Composable
internal fun ShowListCard(
    show    : com.crsmthw.lyra.data.remote.model.SpotifyShow,
    onClick : () -> Unit,
) {
    Card(
        onClick   = onClick,
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artUrl = show.artUrl.takeIf { it.isNotBlank() }
            if (artUrl != null) {
                AsyncImage(
                    model              = artUrl,
                    contentDescription = show.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Podcasts, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(show.name.orEmpty(), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val episodes = show.totalEpisodes
                if (episodes != null) {
                    Text(
                        text     = pluralStringResource(R.plurals.show_episode_count, episodes, episodes),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** One saved album row (Albums filter) — mirrors PlaylistListCard's layout. */
@Composable
internal fun AlbumListCard(
    album   : com.crsmthw.lyra.data.remote.model.SpotifyAlbum,
    onClick : () -> Unit,
) {
    Card(
        onClick   = onClick,
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artUrl = album.images?.firstOrNull()?.url
            if (artUrl != null) {
                AsyncImage(
                    model              = artUrl,
                    contentDescription = album.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Album, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(album.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = listOfNotNull(
                    album.artists?.joinToString(" · ") { it.name }?.takeIf { it.isNotBlank() },
                    album.releaseYear.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One followed artist row (Artists filter) — circular art, name only. */
@Composable
internal fun ArtistListCard(
    artist  : com.crsmthw.lyra.data.remote.model.SpotifyArtist,
    onClick : () -> Unit,
) {
    Card(
        onClick   = onClick,
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artUrl = artist.images?.firstOrNull()?.url
            if (artUrl != null) {
                AsyncImage(
                    model              = artUrl,
                    contentDescription = artist.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(56.dp).clip(CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(artist.name, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
    }
}

// ── "For you" band cards ──────────────────────────────────────────────────────

/** One "Jump back in" tile: a recently-played context (playlist / Liked / album / artist). */
@Composable
internal fun JumpBackInCard(
    item       : JumpBackInItem,
    mosaicFile : File?,
    onClick    : () -> Unit,
) {
    val imageShape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp),
    ) {
        if (item.type == "liked") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(imageShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF6A11CB), Color(0xFF2575FC)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(32.dp))
            }
        } else {
            val imageModel = mosaicFile ?: item.artUrl
            if (imageModel != null) {
                AsyncImage(
                    model              = imageModel,
                    contentDescription = item.title,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxWidth().aspectRatio(1f).clip(imageShape),
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(imageShape),
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // minLines 2 + an always-present subtitle line keep EVERY card the same height — mixed
        // 1-/2-line titles made the LazyRow re-measure as items scrolled in, visibly shifting the
        // section below.
        Text(
            // "liked" persists a marker, not a name, so the label stays localized.
            text     = if (item.type == "liked") stringResource(R.string.liked_songs) else item.title,
            style    = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color    = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text     = item.subtitle.orEmpty(),
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One "On repeat" tile: a short-term top track. Tap plays from here; hold opens the song menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TopTrackCard(
    track       : com.crsmthw.lyra.data.remote.model.SpotifyTrack,
    onClick     : () -> Unit,
    onLongClick : () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .width(110.dp)
            .combinedClickable(
                onClick     = onClick,
                onLongClick = { haptics.longPress(); onLongClick() },
            )
            .padding(bottom = 4.dp),
    ) {
        if (track.artUrl.isNotBlank()) {
            AsyncImage(
                model              = track.artUrl,
                contentDescription = track.name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                color    = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // minLines 2 keeps every card the same height (see JumpBackInCard).
        Text(
            text     = track.name,
            style    = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color    = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text     = track.allArtists,
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
