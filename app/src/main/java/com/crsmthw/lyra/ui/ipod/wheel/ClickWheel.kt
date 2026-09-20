package com.crsmthw.lyra.ui.ipod.wheel

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The click wheel: drawn in Compose (ring, four labels, centre button) and driven by a rotary
 * pointer gesture around the ring's centre. A drag around the ring emits [WheelEvent.Scroll]
 * per detent (clockwise = positive), with detents shrinking as angular velocity rises; a tap on
 * the ring emits the button under it; a tap in the centre emits SELECT. The wheel fires its own
 * feedback from the gesture lambdas: `scrollTick()` + [ClickSounds.tick] per detent, `press()`
 * for MENU/PREVIOUS/NEXT/PLAY_PAUSE, `confirm()` + [ClickSounds.select] for SELECT.
 *
 * The composable is square; its size is decided by the caller (IPodRoot).
 *
 * STUB — the wheel lane implements this.
 */
@Composable
fun ClickWheel(
    onEvent: (WheelEvent) -> Unit,
    sounds: ClickSounds?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier)
}
