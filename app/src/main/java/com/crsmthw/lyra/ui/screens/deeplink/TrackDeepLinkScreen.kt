package com.crsmthw.lyra.ui.screens.deeplink

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun TrackDeepLinkScreen(
    trackId            : String,
    playerViewModel    : PlayerViewModel,
    onNavigateToPlayer : () -> Unit,
) {
    LaunchedEffect(trackId) {
        playerViewModel.playTrack(uri = "spotify:track:$trackId")
        delay(1_500L)
        onNavigateToPlayer()
    }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text  = stringResource(R.string.deeplink_loading_track),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
