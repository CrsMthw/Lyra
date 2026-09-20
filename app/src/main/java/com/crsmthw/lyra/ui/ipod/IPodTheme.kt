package com.crsmthw.lyra.ui.ipod

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R

/**
 * Liberation Sans (Red Hat, SIL Open Font License 1.1) — the Helvetica-metric face standing in
 * for the Helvetica the 6th-generation iPod Classic drew its LCD with. Licence text ships in
 * `assets/fonts/LICENSE-LiberationFonts.txt`; the About credits card names it. Apply it on the
 * iPod's own `Text`s only — `LyraTypography` stays on the platform font.
 */
val IPodFontFamily = FontFamily(
    Font(R.font.liberation_sans_regular, FontWeight.Normal),
    Font(R.font.liberation_sans_bold,    FontWeight.Bold),
)

/**
 * The iPod is its own world: it ignores `MaterialTheme.colorScheme` and Lyra's light/dark theme
 * entirely, so every colour lives here. Silver Classic body, white 320×240-style LCD with the
 * blue selection bar. Keep additions in this object rather than inline in a composable.
 */
object IPodColors {
    // ── Body (silver Classic) ─────────────────────────────────────────────
    val BodyTop        = Color(0xFFE2E4E7)
    val BodyBottom     = Color(0xFFB8BCC1)
    val BodyEdge       = Color(0xFF8E9297)
    /** The letterbox around the body when the window is wider than the body's max aspect. */
    val Surround       = Color.Black
    val SurroundText   = Color(0xFF8A8D91)
    /** Landscape-only "works best in portrait" hint, over the silver body gradient. */
    val LandscapeHintText = Color(0xFF6B6E73)

    // ── Click wheel ───────────────────────────────────────────────────────
    val WheelTop       = Color(0xFFF3F4F6)
    val WheelBottom    = Color(0xFFCFD2D6)
    val WheelEdge      = Color(0xFF9EA2A7)
    val WheelLabel     = Color(0xFF7F838A)
    val WheelPressed   = Color(0x22000000)
    val CenterTop      = Color(0xFFDADDE1)
    val CenterBottom   = Color(0xFFB4B8BE)
    val CenterEdge     = Color(0xFF8E9297)

    // ── LCD ───────────────────────────────────────────────────────────────
    val LcdBezel          = Color(0xFF0E0F11)
    val LcdBezelHighlight = Color(0xFF2A2C30)
    val LcdBackground     = Color(0xFFFFFFFF)
    val LcdStatusTop      = Color(0xFFF4F4F4)
    val LcdStatusBottom   = Color(0xFFD6D6D6)
    val LcdStatusLine     = Color(0xFF9B9B9B)
    val LcdText           = Color(0xFF000000)
    val LcdTextSecondary  = Color(0xFF6E6E6E)
    val LcdDivider        = Color(0xFFE6E6E6)
    val HighlightTop      = Color(0xFF6FA9F5)
    val HighlightBottom   = Color(0xFF1F5FD0)
    val HighlightText     = Color(0xFFFFFFFF)
    val Chevron           = Color(0xFF8F8F8F)
    val ScrollbarTrack    = Color(0xFFEDEDED)
    val ScrollbarThumbTop = Color(0xFF9FC3F7)
    val ScrollbarThumbBottom = Color(0xFF3A78D8)
    val ProgressTrack     = Color(0xFFE3E3E3)
    val ProgressTop       = Color(0xFF7FB2F4)
    val ProgressBottom    = Color(0xFF2B66CF)
    val BatteryBody       = Color(0xFF3C3C3C)
    val BatteryFill       = Color(0xFF3FA84A)
    val BatteryCharging   = Color(0xFF2B66CF)
    val BatteryBolt       = Color(0xFFFFFFFF)
    val ArtPlaceholder    = Color(0xFFBDBDBD)
    val ArtPlaceholderIcon = Color(0xFF7A7A7A)
}

/** Proportions of the Classic, expressed so the body can fill any window (see IPodRoot). */
object IPodDimens {
    /** Widest the body may be relative to its height. A narrower window fills its own width. */
    const val BodyMaxAspect = 0.62f
    val BodyCornerRadius = 28.dp
    val BodyPadding      = 14.dp
    /** The LCD is 4:3, like the Classic's 320×240 panel. */
    const val LcdAspect = 4f / 3f
    val LcdBezelWidth   = 10.dp
    val LcdCornerRadius = 6.dp
    /** Wheel diameter as a fraction of the body width. */
    const val WheelDiameterFraction = 0.78f
    /**
     * Centre button DIAMETER as a fraction of the wheel DIAMETER (a real Classic's is ~0.38).
     * Radius = wheelRadius * CenterButtonFraction — NOT halved again (the first build did, and
     * shipped a centre button half the size it should be).
     */
    const val CenterButtonFraction  = 0.38f
    /**
     * The LCD may take at most this fraction of the body HEIGHT. On a tall phone the width wins and
     * the cap is never reached; on a near-square (unfolded) window it keeps the wheel its room.
     */
    const val LcdMaxHeightFraction  = 0.46f
    /** Menu push/pop slide — the spec's 200–250 ms; finite, never a spring. */
    const val LcdSlideMillis = 220
}
