package com.crsmthw.lyra.ui.cassette

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The tonal family the whole cassette is painted from, derived from ONE seed colour — the album
 * art's Vibrant accent, the system's Material You primary, or the user's custom pick. Everything
 * coloured on the shell (hubs, tape pack, label paper and grid, ink) comes from here; the shell
 * itself, the window and the metal shield are fixed smoky greys so the seed reads as the ad's
 * purple does. The background is always black (Cris, 2026-09-23). Pure Kotlin, unit-tested.
 */
@Immutable
data class CassettePalette(
    val seed          : Color,
    /** Reel hubs and the label's colour stripe. */
    val hub           : Color,
    /** Hub shading / inner ring. */
    val hubDeep       : Color,
    /** The hub's white-ish teeth. */
    val hubTeeth      : Color,
    /** The wound tape. */
    val tape          : Color,
    /** The tape pack's highlight ring. */
    val tapeSheen     : Color,
    /** Label paper — a very light tint of the seed. */
    val labelPaper    : Color,
    /** Graph-paper lines on the label. */
    val labelGrid     : Color,
    /** Label text. */
    val ink           : Color,
    /** Secondary label text (fine print, meta). */
    val inkSoft       : Color,
    /** Smoky translucent shell body. */
    val shell         : Color,
    /** Shell rim. */
    val shellEdge     : Color,
    /** Specular highlight on the shell. */
    val shellHighlight: Color,
    /** The clear window over the reels (near black — the inside of the shell). */
    val window        : Color,
    /** Screws and the head shield. */
    val metal         : Color,
    val background    : Color = Color.Black,
) {
    companion object {
        fun from(seed: Color): CassettePalette {
            val (h, sRaw, _) = seed.toHsl()
            // Clamp chroma so grey / near-black art still yields a visible tint and neon art does not blow out.
            val s = sRaw.coerceIn(0.35f, 0.80f)
            return CassettePalette(
                seed           = seed,
                hub            = Color.hsl(h, s, 0.56f),
                hubDeep        = Color.hsl(h, s, 0.40f),
                hubTeeth       = Color.hsl(h, s * 0.45f, 0.94f),
                tape           = Color.hsl(h, s * 0.85f, 0.19f),
                tapeSheen      = Color.hsl(h, s * 0.75f, 0.30f),
                labelPaper     = Color.hsl(h, (s * 0.55f).coerceIn(0.20f, 0.50f), 0.915f),
                labelGrid      = Color.hsl(h, s * 0.50f, 0.82f),
                ink            = Color.hsl(h, s * 0.40f, 0.14f),
                inkSoft        = Color.hsl(h, s * 0.30f, 0.36f),
                shell          = Color(0xFF2B292F),
                shellEdge      = Color(0xFF4B4852),
                shellHighlight = Color(0x59FFFFFF),
                window         = Color(0xFF0F0E11),
                metal          = Color(0xFFB9B9BF),
            )
        }
    }
}

/** HSL triple: hue 0..360, saturation 0..1, lightness 0..1. */
data class Hsl(val h: Float, val s: Float, val l: Float)

/** sRGB → HSL, the textbook conversion (pure Kotlin so it runs in JVM tests). */
fun Color.toHsl(): Hsl {
    val r = red; val g = green; val b = blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 1e-5f) return Hsl(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (mx) {
        r    -> 60f * (((g - b) / d) % 6f)
        g    -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return Hsl(if (h < 0f) h + 360f else h, s.coerceIn(0f, 1f), l)
}
