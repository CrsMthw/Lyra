package com.crsmthw.lyra.util.visualizer

/**
 * Which visualizer surfaces are shown while the visualizer is enabled.
 *
 * - [CIRCLE] — only the circular pulse behind the album art in the full player screen.
 * - [BOTTOM] — only the horizontal wave along the bottom of most screens
 *   (friendlier for users with the Android navigation bar).
 * - [BOTH]   — both surfaces (default; previous behavior).
 *
 * Capture (RECORD_AUDIO) is gated solely on the master visualizer toggle, never on
 * this style — both CIRCLE-only and BOTTOM-only still need the capture running.
 */
enum class VisualizerStyle {
    CIRCLE, BOTTOM, BOTH;

    val showCircle: Boolean get() = this == CIRCLE || this == BOTH
    val showBottom: Boolean get() = this == BOTTOM || this == BOTH
}
