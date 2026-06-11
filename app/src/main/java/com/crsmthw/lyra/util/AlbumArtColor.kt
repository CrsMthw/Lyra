package com.crsmthw.lyra.util

import android.content.Context
import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SPOTIFY_GREEN = 0xFF1DB954.toInt()
private const val MIN_AVG_CHROMA = 10L

/**
 * Colours extracted from a piece of album art. All values are opaque ARGB ints; Compose callers
 * wrap with `Color(...)`, the widget uses them directly.
 *
 * This is the single source of truth for Lyra's album-art colour recipe — previously copy-pasted
 * across the player, pop-out panel, mini-player, nav graph, and widget.
 */
data class AlbumArtColors(
    /**
     * Chroma-weighted average of the art's outer border ring (see [perimeterColor]). Use for any
     * background that should "merge" with the cover — the gradient behind the player, the mini-player
     * wash, the widget background. Follows the colours the art is *bordered* by, so the cover appears
     * to dissolve into the screen instead of sitting on a contrasting accent.
     */
    val edge: Int,
    /** Palette Vibrant (→ dominant → Spotify green). Punchy accent for highlights/buttons. */
    val accent: Int,
    /**
     * Theme-aware light-/dark-vibrant chain — an accent chosen to keep contrast against the current
     * surface. Use for foreground tints (sliders, like/shuffle/repeat, the visualizer wave, the
     * mini-player progress ring) that must stay legible, NOT for backgrounds.
     */
    val surfaceAccent: Int,
    /** Palette dominant (most populous colour). Kept for callers that specifically want it. */
    val dominant: Int,
)

/**
 * Compute [AlbumArtColors] from an already-decoded **ARGB_8888** bitmap. MUST be a software bitmap —
 * Palette and [perimeterColor] both read pixels, which throws on HARDWARE bitmaps (the reason every
 * caller does `.copy(ARGB_8888, false)`). Call off the main thread.
 *
 * @param isDark whether the active theme is dark — only affects [AlbumArtColors.surfaceAccent]
 *               (light accent on dark, dark accent on light).
 */
fun Bitmap.albumArtColors(isDark: Boolean): AlbumArtColors {
    val palette  = Palette.from(this).generate()
    val fallback = palette.getDominantColor(SPOTIFY_GREEN)
    val surface  = if (isDark) {
        palette.getLightVibrantColor(palette.getVibrantColor(palette.getLightMutedColor(fallback)))
    } else {
        palette.getDarkVibrantColor(palette.getVibrantColor(palette.getDarkMutedColor(fallback)))
    }
    return AlbumArtColors(
        edge          = perimeterColor(),
        accent        = palette.getVibrantColor(fallback),
        surfaceAccent = surface,
        dominant      = palette.getDominantColor(fallback),
    )
}

/**
 * Load album art via Coil and compute [AlbumArtColors]. Returns null on a blank URL or load
 * failure. Switches to [Dispatchers.IO] internally, so it's safe to call straight from a
 * `LaunchedEffect`. Callers that already hold the decoded bitmap (e.g. the widget, which also needs
 * it for the art file) should call [albumArtColors] directly to avoid a second decode.
 */
suspend fun loadAlbumArtColors(context: Context, url: String?, isDark: Boolean): AlbumArtColors? {
    val safeUrl = url?.takeIf { it.isNotBlank() } ?: return null
    return withContext(Dispatchers.IO) {
        try {
            val loader = SingletonImageLoader.get(context)
            val result = loader.execute(ImageRequest.Builder(context).data(safeUrl).build())
            if (result is SuccessResult) {
                result.image.toBitmap()
                    .copy(Bitmap.Config.ARGB_8888, false)
                    .albumArtColors(isDark)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Representative colour of the album-art's outer border ring — the band of pixels that visually
 * touches the player background. Feeding a background from this makes the art appear to "dissolve"
 * into the screen (the way Spotify's now-playing background does), instead of pulling a single
 * saturated accent from the centre of the cover (Palette's Vibrant swatch), which is often a
 * minority colour that doesn't represent the art's edges.
 *
 * Border pixels are averaged **weighted by chroma** (saturation): a colourful minority on the
 * edge — e.g. green smoke amid dark hair — counts far more than near-grey/black pixels, so the
 * result follows the hue that's actually present instead of being dragged to mud/black by a
 * desaturated majority. This is safe (won't re-introduce a wrong accent like the old Vibrant pick)
 * because we only weight *within the border ring*; the misleading saturated colours that broke
 * whole-image sampling sit in the centre, not the edges. When the border is essentially grey
 * (average chroma below [MIN_AVG_CHROMA]) the weighting has nothing to latch onto, so it falls back
 * to a plain average.
 *
 * MUST be called on an ARGB_8888 (software) bitmap — [Bitmap.getPixel] throws on HARDWARE bitmaps.
 * Cheap: strided sampling of only the border band. Returns an opaque ARGB int.
 *
 * @param insetFraction thickness of the sampled border ring, as a fraction of the shorter side.
 * @param step          pixel stride; higher = fewer samples = faster.
 */
fun Bitmap.perimeterColor(insetFraction: Float = 0.06f, step: Int = 4): Int {
    val w = width
    val h = height
    if (w < 4 || h < 4) return getPixel(0, 0)
    val s = step.coerceAtLeast(1)
    val band = (minOf(w, h) * insetFraction).toInt().coerceIn(1, minOf(w, h) / 2)

    // Plain average (fallback for near-grey borders).
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0L
    var chromaSum = 0L
    // Chroma-weighted average (primary): weight = saturation, so colourful pixels dominate.
    var wr = 0.0
    var wg = 0.0
    var wb = 0.0
    var wTot = 0.0

    fun sample(x: Int, y: Int) {
        val p = getPixel(x, y)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        rSum += r
        gSum += g
        bSum += b
        count++
        val chroma = maxOf(r, g, b) - minOf(r, g, b)   // 0..255
        chromaSum += chroma
        val weight = chroma.toDouble()
        wr += weight * r
        wg += weight * g
        wb += weight * b
        wTot += weight
    }

    // Top + bottom bands (full width, including corners).
    var y = 0
    while (y < band) {
        var x = 0
        while (x < w) {
            sample(x, y)
            sample(x, h - 1 - y)
            x += s
        }
        y += s
    }
    // Left + right bands (rows between the top/bottom bands, so corners aren't double-counted).
    var yy = band
    while (yy < h - band) {
        var x = 0
        while (x < band) {
            sample(x, yy)
            sample(w - 1 - x, yy)
            x += s
        }
        yy += s
    }

    if (count == 0L) return getPixel(0, 0)

    return if (chromaSum / count >= MIN_AVG_CHROMA && wTot > 0.0) {
        (0xFF shl 24) or
            ((wr / wTot).toInt() shl 16) or
            ((wg / wTot).toInt() shl 8) or
            (wb / wTot).toInt()
    } else {
        (0xFF shl 24) or
            ((rSum / count).toInt() shl 16) or
            ((gSum / count).toInt() shl 8) or
            (bSum / count).toInt()
    }
}
