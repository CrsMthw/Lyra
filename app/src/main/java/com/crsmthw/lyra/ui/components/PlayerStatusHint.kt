package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.crsmthw.lyra.R

/** The Spotify app's package — its launcher intent is what re-registers it with Connect. */
private const val SPOTIFY_PACKAGE = "com.spotify.music"

/**
 * One quiet line under the player controls (2026-10-04, docs/PLAYER.md → Bare-uris plays stop the
 * 9.1.88 client): either the phone is not listed as a Spotify device — after a wake, or because
 * Connect dropped mid-playback (`PlayerUiState.showsOpenSpotifyHint`; [spotifyNotListed] here) —
 * with an "Open Spotify" button, since opening Spotify's UI re-registers it; or a play Spotify
 * accepted was never reported playing ([playUnconfirmed]). The text names the cause as a bug in
 * recent Spotify Android versions (Cris, 2026-10-04 23:2x). Composes nothing when neither is set.
 * Used by the full player and the pop-out card.
 */
@Composable
fun PlayerStatusHint(
    playUnconfirmed : Boolean,
    spotifyNotListed: Boolean,
    modifier        : Modifier = Modifier,
) {
    if (!playUnconfirmed && !spotifyNotListed) return
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text      = stringResource(
                if (spotifyNotListed) R.string.player_spotify_not_listed else R.string.player_play_unconfirmed,
            ),
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (spotifyNotListed) {
            TextButton(onClick = {
                context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)?.let(context::startActivity)
            }) {
                Text(stringResource(R.string.player_open_spotify))
            }
        }
    }
}
