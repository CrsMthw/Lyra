package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.crsmthw.lyra.ui.ipod.nav.IPodUiState

/**
 * The iPod's LCD: bezel + status bar (title, play/pause glyph, battery) + the current stack
 * entry, which slides in from the right on a push and out to the left on a pop
 * ([IPodUiState.direction]). Lists are display-only — no touch scrolling, the highlight is
 * moved by the wheel — and an empty list reads "No <Title>" as the Classic does.
 *
 * The caller (IPodRoot) sizes it 4:3 and passes the live battery.
 *
 * STUB — the LCD lane implements this.
 */
@Composable
fun LcdScreen(
    state: IPodUiState,
    battery: BatteryState,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
