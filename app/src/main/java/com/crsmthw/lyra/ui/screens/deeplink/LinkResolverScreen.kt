package com.crsmthw.lyra.ui.screens.deeplink

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * The single landing pad for every incoming Spotify link. It shows a spinner while
 * [LinkResolverViewModel] normalises the URL — instant for a plain `open.spotify.com` URL or a
 * `spotify:` URI, a network round trip for a `spotify.link` short link — then hands off to the
 * destination that link names and pops itself off the back stack, so back always lands on the
 * Library rather than on a dead loading screen.
 *
 * Tracks and episodes have no detail screen of their own: both are started with
 * `PlayerViewModel.playTrack(spotify:<type>:<id>)` (the Web API's `me/player/play` takes an
 * episode uri exactly like a track uri) and then the player is opened. The short settle delay is
 * the same one the old track-only deep link used — it gives the play call time to land so the
 * player doesn't open on the PREVIOUS track and visibly swap.
 *
 * A playlist has no hosted destination either: `onOpenPlaylist` hands the id back to the Library,
 * which opens it in place when it is one of the user's own.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinkResolverScreen(
    rawUrl          : String,
    viewModel       : LinkResolverViewModel,
    playerViewModel : PlayerViewModel,
    onOpenAlbum     : (String) -> Unit,
    onOpenArtist    : (String) -> Unit,
    onOpenShow      : (String) -> Unit,
    onOpenPlaylist  : (String) -> Unit,
    onOpenPlayer    : () -> Unit,
    onUnsupported   : () -> Unit,
) {
    val context     = LocalContext.current
    val resolution  by viewModel.state.collectAsStateWithLifecycle()
    var isStartingPlayback by remember { mutableStateOf(false) }

    LaunchedEffect(rawUrl) { viewModel.resolve(rawUrl) }

    LaunchedEffect(resolution) {
        when (val outcome = resolution) {
            null -> Unit                       // still resolving
            is LinkResolution.Unsupported -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.deeplink_unsupported),
                    Toast.LENGTH_LONG,
                ).show()
                onUnsupported()
            }
            is LinkResolution.Resolved -> when (outcome.link.type) {
                SpotifyLinkType.ALBUM    -> onOpenAlbum(outcome.link.id)
                SpotifyLinkType.ARTIST   -> onOpenArtist(outcome.link.id)
                SpotifyLinkType.SHOW     -> onOpenShow(outcome.link.id)
                SpotifyLinkType.PLAYLIST -> onOpenPlaylist(outcome.link.id)
                SpotifyLinkType.TRACK, SpotifyLinkType.EPISODE -> {
                    isStartingPlayback = true
                    playerViewModel.playTrack(uri = outcome.link.uri)
                    delay(PLAYBACK_SETTLE_MS)
                    onOpenPlayer()
                }
            }
        }
    }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ContainedLoadingIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text  = stringResource(
                    if (isStartingPlayback) R.string.deeplink_loading_track
                    else                    R.string.deeplink_resolving
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PLAYBACK_SETTLE_MS = 1_500L
