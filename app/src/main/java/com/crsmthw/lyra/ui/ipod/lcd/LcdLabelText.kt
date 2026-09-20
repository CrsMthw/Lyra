package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.crsmthw.lyra.ui.ipod.nav.LcdLabel

/** Resolve an [LcdLabel] at draw time. */
@Composable
fun LcdLabel.resolve(): String = when (this) {
    is LcdLabel.Text -> value
    is LcdLabel.Res  -> stringResource(id)
}
