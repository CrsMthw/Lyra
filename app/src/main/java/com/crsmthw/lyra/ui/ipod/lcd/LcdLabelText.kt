package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.crsmthw.lyra.ui.ipod.nav.LcdLabel

/** Resolve an [LcdLabel] at draw time. */
@Composable
fun LcdLabel.resolve(): String = when (this) {
    is LcdLabel.Text    -> value
    is LcdLabel.Res     -> stringResource(id)
    is LcdLabel.ResArgs -> stringResource(id, *args.toTypedArray())
    is LcdLabel.Plural  -> pluralStringResource(id, quantity, quantity)
    is LcdLabel.Joined  -> {
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            val s = part.resolve()
            if (s.isNotBlank()) out += s
        }
        out.joinToString(separator)
    }
}
