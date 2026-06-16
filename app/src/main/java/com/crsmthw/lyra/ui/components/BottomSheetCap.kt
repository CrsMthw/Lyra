package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Gap to leave above a fully-expanded modal bottom sheet — the **status-bar height**, so the sheet
 * sits right below the status bar (like Spotify) rather than edge-to-edge.
 *
 * Cap a sheet's scrollable content to the enclosing `BoxWithConstraints.maxHeight - sheetTopGap()`
 * so the sheet's measured height stays STRICTLY below the screen. The M3 sheet puts its Expanded
 * anchor at `(fullHeight - contentSize)`; when the content fills the screen exactly that anchor is at
 * offset 0, where the spring fling overshoots and bounces / wedges touch on the first pull-up — a
 * known M3 bug (https://issuetracker.google.com/issues/285847707). Any positive gap is bounce-safe;
 * the status-bar height just sets where the fully-expanded sheet stops. Falls back to 24.dp if the
 * inset reads 0 (e.g. an immersive/edge case) so the gap is never zero.
 */
@Composable
fun sheetTopGap(): Dp = with(LocalDensity.current) {
    WindowInsets.statusBars.getTop(this).toDp().coerceAtLeast(24.dp)
}

/**
 * [ModalBottomSheet] with the app-wide configuration shared by every sheet: **Hidden ↔ Expanded
 * only — no partial-expanded detent**. So the sheet expands fully on open (content-sized when short,
 * near-full + internal scroll when long), and bottom actions are never hidden behind a half-height
 * detent (which also snaps to a screen-relative position when it settles). Pair it with a content
 * cap of `maxHeight - sheetTopGap()` on the single scrollable child to keep the sheet below the
 * screen height — see [sheetTopGap].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CappedModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        SheetValue.Hidden,
        setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState       = sheetState,
        content          = content,
    )
}
