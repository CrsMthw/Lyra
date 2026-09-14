package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable

/**
 * The ONLY window insets a Lyra top app bar may consume — the status bar's top edge.
 *
 * Pass this as every `TopAppBar` / `LargeFlexibleTopAppBar` / `MediumFlexibleTopAppBar`
 * `windowInsets` argument. **Never take the default.** `TopAppBarDefaults.windowInsets` is
 * `systemBarsForVisualComponents.only(Horizontal + Top)`, and every Lyra screen already applies
 * `Modifier.horizontalSystemBarsPadding()` exactly once on its outermost content container
 * (CLAUDE.md → Inset Rules), so the default's horizontal term would be applied a second time
 * inside the bar — an app bar indented off both edges in landscape with 3-button nav.
 *
 * The top term stays with the bar rather than an ancestor `statusBarsPadding()` on purpose: the bar
 * paints its container **under** the status icons and pads only its content row, which is what keeps
 * the screens edge-to-edge under a transparent status bar.
 */
val appBarWindowInsets: WindowInsets
    @Composable get() = WindowInsets.statusBars.only(WindowInsetsSides.Top)
