package com.crsmthw.lyra.ui.ipod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.crsmthw.lyra.di.AppContainer

/**
 * The whole iPod mode. Composed by MainActivity INSTEAD of LyraNavGraph while `ipodEnabled` is
 * true (the flag is read before the first frame, so neither UI flashes). Owns:
 *  - the immersive window state (system bars hidden, swipe-to-reveal), the portrait request,
 *    the display-cutout mode, forced LTR — all restored on dispose;
 *  - the body layout: fills a portrait window; on a wider window a body of at most
 *    IPodDimens.BodyMaxAspect is centred on IPodColors.Surround with the portrait hint under it;
 *  - the LCD (4:3, top) and the ClickWheel (bottom), wiring wheel events into IPodViewModel and
 *    IPodEffects out to the Activity-scoped PlayerViewModel;
 *  - the exit confirmation (BackHandler → dialog → setIpodEnabled(false)), the session-expired
 *    collector, and ClickSounds' lifetime.
 *
 * STUB — the shell lane implements this.
 */
@Composable
fun IPodRoot(container: AppContainer, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(IPodColors.Surround))
}
