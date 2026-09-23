package com.crsmthw.lyra.ui.cassette

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/*
 * PLACEHOLDER — the painter lane replaces this whole file. The two signatures below are the
 * contract; keep them.
 */

/**
 * The cassette in its NATURAL orientation: long edge horizontal, head edge (the exposed tape,
 * the capstan holes, the shield) at the BOTTOM, label upright. Fills `modifier`'s bounds, which
 * the CALLER has sized at [CASSETTE_ASPECT] (see [cassetteFit]); nothing here letterboxes.
 *
 * `progress` is a lambda read inside the draw phase so the per-second tick never recomposes the
 * label. `spinning` drives the hubs (a frame clock while true, frozen while false — paused music).
 */
@Composable
fun Cassette(
    palette : CassettePalette,
    label   : CassetteLabel,
    side    : CassetteSide,
    progress: () -> Float,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        drawRoundRect(color = palette.shell, cornerRadius = CornerRadius(size.minDimension * 0.06f))
        drawRoundRect(
            color = palette.labelPaper,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.06f, size.height * 0.08f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.88f, size.height * 0.55f),
            cornerRadius = CornerRadius(size.minDimension * 0.03f),
        )
        val r = size.height * 0.12f
        drawCircle(palette.hub, r, androidx.compose.ui.geometry.Offset(size.width * 0.33f, size.height * 0.42f))
        drawCircle(palette.hub, r, androidx.compose.ui.geometry.Offset(size.width * 0.67f, size.height * 0.42f))
    }
}

/**
 * Orientation + fit + choreography over [Cassette]. Fills `modifier`'s bounds (the whole window,
 * insets ignored — the caller is immersive), paints [CassettePalette.background] behind, sizes the
 * shell with [cassetteFit] (no margin, never stretched), turns it 90° anticlockwise in portrait,
 * and shows a change of `trackKey` as a FLIP or an EJECT per [CassetteChoreographer] (`forward`
 * is the direction of THAT change, sampled when the key changes). The outgoing face keeps the
 * label it had; the incoming face gets the new one.
 */
@Composable
fun CassetteStage(
    palette : CassettePalette,
    label   : CassetteLabel,
    trackKey: String?,
    forward : Boolean,
    progress: () -> Float,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawRect(palette.background) }
        Cassette(palette, label, CassetteSide.A, progress, spinning, Modifier.fillMaxSize())
    }
}
