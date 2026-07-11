package com.crsmthw.lyra.util

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/**
 * Bouncy [BoundsTransform] for shared-element / container-transform morphs, driven by the app's
 * expressive [androidx.compose.material3.MotionScheme] (wired in `LyraTheme`). The spatial spec
 * carries the tasteful overshoot that gives the M3 Expressive "settle" — use this for anything
 * that morphs bounds (e.g. album art flying into a hero area), never for colour/alpha.
 *
 * The `motionScheme` getter is `@Composable`, so the spec is read in composition and captured in a
 * remembered [BoundsTransform]; the spec is never read inside the (non-composable) lambda.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun rememberArtBoundsTransform(): BoundsTransform {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    return remember(spec) { BoundsTransform { _, _ -> spec } }
}

/**
 * A [Shape] that press-morphs between two [RoundedPolygon]s (the M3 Expressive press squish —
 * e.g. `DetailArtHero`'s 6-sided-cookie Play flattening toward a square while held, springing
 * back on release).
 *
 * Why hand-rolled: `IconButtonDefaults.shapes(shape, pressedShape)` *compiles* with any [Shape],
 * but the icon-button implementation only animates between [androidx.compose.foundation.shape.CornerBasedShape]s
 * — with `MaterialShapes` polygons (cookies/clovers) it hard-snaps. This helper does the real
 * polygon morph via `androidx.graphics.shapes.Morph`, driven by the pressed state of the button's
 * hoisted [InteractionSource]. Pass the returned shape as the button's `shape` and the same
 * `interactionSource` to the button.
 *
 * Shape morphing is spatial, so progress springs through `motionScheme.fastSpatialSpec()`
 * (see docs/MOTION.md); progress is clamped inside the outline so spring overshoot can never
 * extrapolate the morph outside its defined [0, 1] range.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberPressMorphShape(
    shape             : RoundedPolygon,
    pressedShape      : RoundedPolygon,
    interactionSource : InteractionSource,
): Shape {
    val pressed by interactionSource.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val progress by animateFloatAsState(
        targetValue   = if (pressed) 1f else 0f,
        animationSpec = spec,
        label         = "pressMorphProgress",
    )
    val morph = remember(shape, pressedShape) { Morph(shape.normalized(), pressedShape.normalized()) }
    return MorphShape(morph, progress)
}

/** Clips to [morph] at [progress] (0 = start shape, 1 = end shape); polygons must be normalized. */
private class MorphShape(
    private val morph   : Morph,
    private val progress: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress.coerceIn(0f, 1f)).asComposePath()
        val matrix = Matrix().apply { scale(size.width, size.height, 1f) }
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
