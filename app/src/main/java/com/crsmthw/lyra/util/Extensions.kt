package com.crsmthw.lyra.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Clickable with built-in debounce – prevents double-tap actions. */
@Composable
fun Modifier.debouncedClickable(
    debounceMs       : Long    = 400L,
    enabled          : Boolean = true,
    onClick          : () -> Unit,
): Modifier {
    var lastClick by remember { mutableLongStateOf(0L) }
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        enabled           = enabled,
        interactionSource = interactionSource,
        indication        = null,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastClick >= debounceMs) {
            lastClick = now
            onClick()
        }
    }
}

/** Format milliseconds as m:ss */
fun Long.toTimeString(): String {
    val totalSeconds = this / 1000
    val minutes      = totalSeconds / 60
    val seconds      = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Format milliseconds as Xh Ym or Ym Zs */
fun Long.toDurationString(): String {
    val totalSeconds = this / 1000
    val hours        = totalSeconds / 3600
    val minutes      = (totalSeconds % 3600) / 60
    return when {
        hours > 0  -> "${hours}h ${minutes}m"
        else       -> "${minutes}m"
    }
}
