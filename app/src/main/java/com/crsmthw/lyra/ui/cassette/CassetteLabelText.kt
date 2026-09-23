package com.crsmthw.lyra.ui.cassette

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.crsmthw.lyra.R
import kotlin.math.ceil

/*
 * The label's words: measured ONCE per (label, side, strings, pixel size) in composition and
 * drawn by the cached top layer. Colours are applied at DRAW time (`drawText(color = …)`), so a
 * palette slide never re-measures.
 *
 * Sizes are in the 1000-unit design space; they are measured at `units × u` PIXELS with a
 * Density(1, 1) (1 sp = 1 px), so the label is artwork — the user's font scale does not resize
 * it, exactly as it does not resize a photo of a cassette.
 */

/** The label's small print is the bundled Liberation Sans (Arial-metric, Helvetica-like) so the
 *  cassette reads the same on every OEM's system sans. */
private val CassetteSans: FontFamily = FontFamily(
    Font(R.font.liberation_sans_regular, FontWeight.Normal),
    Font(R.font.liberation_sans_bold, FontWeight.Bold),
)

/** Every resolved string the label prints (resolved with `stringResource` in composition). */
@Immutable
internal data class CassetteArtStrings(
    val stereo       : String,
    val brand        : String,
    val sideWord     : String,
    val sideLetter   : String,
    val boilerplate  : String,
    val metaSeparator: String,
)

internal class CassetteTextLayouts(
    val title     : TextLayoutResult,
    val artist    : TextLayoutResult,
    val meta      : TextLayoutResult?,
    val stereo    : TextLayoutResult,
    val brand     : TextLayoutResult,
    val sideWord  : TextLayoutResult,
    val sideLetter: TextLayoutResult,
    val finePrint : TextLayoutResult,
)

private val PixelDensity = Density(1f, 1f)
private val Balanced = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

/** Measures every label line at `u` px per unit. Only ever called from a `remember`. */
internal fun measureCassetteText(
    measurer: TextMeasurer, label: CassetteLabel, strings: CassetteArtStrings, u: Float,
): CassetteTextLayouts {
    val g = CassetteGeometry

    fun style(family: FontFamily, units: Float, spacingEm: Float, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = family, fontSize = (units * u).sp, letterSpacing = spacingEm.em, fontWeight = weight)

    /** One line; `maxWidthUnits` null = unconstrained (for measuring); else ellipsised to it. */
    fun line(text: String, style: TextStyle, maxWidthUnits: Float? = null): TextLayoutResult =
        measurer.measure(
            text            = text,
            style           = style,
            overflow        = if (maxWidthUnits != null) TextOverflow.Ellipsis else TextOverflow.Clip,
            softWrap        = maxWidthUnits != null,
            maxLines        = 1,
            constraints     = if (maxWidthUnits != null) Constraints(maxWidth = (maxWidthUnits * u).toInt().coerceAtLeast(1)) else Constraints(),
            layoutDirection = LayoutDirection.Ltr,
            density         = PixelDensity,
        )

    /** The auto-shrink: the largest size whose one-line width fits, the floor + ellipsis past it. */
    fun fitted(text: String, sizes: List<Float>, spacingEm: Float, boxUnits: Float): TextLayoutResult {
        val fit = chooseFontSize(sizes, boxUnits * u) { s -> line(text, style(CassetteSerif, s, spacingEm)).size.width.toFloat() }
        val st = style(CassetteSerif, fit.size, spacingEm)
        return if (fit.ellipsize) line(text, st, boxUnits) else line(text, st)
    }

    val box = g.TitleBoxRight - g.TitleBoxLeft
    val title = fitted(label.title.uppercase(), g.TitleSizes, TitleSpacingEm, box)
    val artist = fitted(label.artist, g.ArtistSizes, ArtistSpacingEm, box)
    val metaText = cassetteMetaLine(label.album, label.year) { a, y -> a + strings.metaSeparator + y }
    val meta = metaText?.let { line(it, style(CassetteSans, 17f, 0.0235f), g.MetaMaxWidth) }

    val fineText = cassetteFinePrint(label.copyright, strings.boilerplate)
    val fineStyle = style(CassetteSans, FinePrintSize, 0.032f).copy(
        textAlign = TextAlign.Center, lineHeight = (g.FinePrintLineHeight * u).sp, lineBreak = Balanced,
    )
    val oneLine = line(fineText, fineStyle).size.width.toFloat()
    val fineWidth = ceil(finePrintWidth(oneLine, g.FinePrintMaxWidth * u, minWidth = 200f * u)).toInt().coerceAtLeast(1)
    val finePrint = measurer.measure(
        text = fineText, style = fineStyle, overflow = TextOverflow.Ellipsis, softWrap = true, maxLines = 2,
        constraints = Constraints.fixedWidth(fineWidth), layoutDirection = LayoutDirection.Ltr, density = PixelDensity,
    )

    return CassetteTextLayouts(
        title      = title,
        artist     = artist,
        meta       = meta,
        stereo     = line(strings.stereo, style(CassetteSans, 10.5f, 0.23f)),
        brand      = line(strings.brand, style(CassetteSans, 15f, 0.033f, FontWeight.Bold)),
        sideWord   = line(strings.sideWord, style(CassetteSans, 13f, 0.123f)),
        sideLetter = line(strings.sideLetter, style(CassetteSans, 28f, 0f, FontWeight.Bold)),
        finePrint  = finePrint,
    )
}

/** The fine print's size: ≥ 9 units so it survives the 0.75 dp/unit of the cover screen. */
internal const val FinePrintSize = 9.4f
/** Title tracking: the SVG's 4 units at 46 (0.087 em), kept proportional as it shrinks. */
internal const val TitleSpacingEm = 0.087f
internal const val ArtistSpacingEm = 0.055f

/** Draws every label line centred on its x at its baseline (units → px with `u`). */
internal fun DrawScope.drawLabelText(t: CassetteTextLayouts, p: CassettePalette, u: Float) {
    fun centred(layout: TextLayoutResult, cx: Float, baseline: Float, color: Color) =
        drawText(layout, color = color, topLeft = Offset(cx * u - layout.size.width / 2f, baseline * u - layout.firstBaseline))
    val g = CassetteGeometry
    centred(t.stereo, 120f, 96f, p.inkSoft)
    centred(t.title, g.CentreX, g.TitleBaseline, p.ink)
    centred(t.artist, g.CentreX, g.ArtistBaseline, p.ink)
    t.meta?.let { centred(it, g.CentreX, g.MetaBaseline, p.inkSoft) }
    centred(t.brand, LogoX, 336f, p.ink)
    centred(t.sideWord, 886f, 298f, p.inkSoft)
    centred(t.sideLetter, 886f, 332f, p.ink)
    centred(t.finePrint, g.CentreX, g.FinePrintBaseline, p.inkSoft)
}
