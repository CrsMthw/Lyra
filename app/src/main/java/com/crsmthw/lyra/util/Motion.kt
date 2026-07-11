package com.crsmthw.lyra.util

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect

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
