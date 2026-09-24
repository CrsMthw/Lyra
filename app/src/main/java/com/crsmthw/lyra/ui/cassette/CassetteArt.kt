package com.crsmthw.lyra.ui.cassette

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The cassette's artwork as DrawScope functions in the 1000 × 638 UNIT space (the caller applies
 * one `scale(u)`). A 1:1 port of the design panel's winning SVG ("polish", group by group), with
 * the orchestrator's grafts: replica's reels and stadium window, engineer's head-access recess,
 * replica's larger pinch rollers / guide rollers, no faux ribs in the window. Every colour comes
 * from the [CassettePalette]; white-with-alpha is `shellHighlight` re-alpha'd and shadow is the
 * palette's black `background` re-alpha'd — the SVG used exactly those two plus palette hexes.
 *
 * Only the two hubs and the tape packs change per frame ([drawReels]); every
 * other function here is recorded ONCE into a GraphicsLayer per (size, palette, label, side).
 */

private val G = CassetteGeometry

private fun CassettePalette.hi(a: Float) = shellHighlight.copy(alpha = a)
private fun CassettePalette.shade(a: Float) = background.copy(alpha = a)

private fun stroke(width: Float) = Stroke(width = width)

private fun DrawScope.rr(l: Float, t: Float, w: Float, h: Float, r: Float, color: Color, style: DrawStyle = Fill) =
    drawRoundRect(color, Offset(l, t), Size(w, h), CornerRadius(r), style = style)

private fun DrawScope.rr(l: Float, t: Float, w: Float, h: Float, r: Float, brush: Brush, style: DrawStyle = Fill) =
    drawRoundRect(brush, Offset(l, t), Size(w, h), CornerRadius(r), style = style)

private fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float) =
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = width)

private fun DrawScope.ring(cx: Float, cy: Float, r: Float, color: Color, width: Float) =
    drawCircle(color, r, Offset(cx, cy), style = stroke(width))

private fun DrawScope.disc(cx: Float, cy: Float, r: Float, color: Color) = drawCircle(color, r, Offset(cx, cy))

private fun polygon(vararg xy: Float): Path = Path().apply {
    moveTo(xy[0], xy[1])
    var i = 2
    while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
    close()
}

/**
 * Paths and brushes built once per cache (unit space). The hub's flange slots and teeth are
 * built about the ORIGIN so a hub is drawn as translate(centre) + rotate(angle).
 */
internal class CassetteArtKit(p: CassettePalette) {
    /** The window stadium — also the packs' clip inside the window. */
    val window: Path = Path().apply {
        addRoundRect(RoundRect(G.WinLeft, G.WinTop, G.WinRight, G.WinBottom, CornerRadius(G.WinRadius)))
    }

    /** The label's graph paper: 20-unit pitch, verticals from x 70, horizontals from y 70. */
    val grid: Path = Path().apply {
        var x = G.LabelLeft + 14f
        while (x < G.LabelRight - 4f) { moveTo(x, G.LabelTop + 1f); lineTo(x, G.LabelBottom - 1f); x += 20f }
        var y = G.LabelTop + 12f
        while (y < G.LabelBottom - 4f) { moveTo(G.LabelLeft + 1f, y); lineTo(G.LabelRight - 1f, y); y += 20f }
    }

    /** Six flange slots (annular sectors r 74..88, ±11° around 30° + k·60°) — they rotate. */
    val hubSlots: Path = Path().apply {
        for (k in 0 until 6) {
            val a0 = 30f + k * 60f - 11f
            arcTo(Rect(-88f, -88f, 88f, 88f), a0, 22f, forceMoveTo = true)
            arcTo(Rect(-74f, -74f, 74f, 74f), a0 + 22f, -22f, forceMoveTo = false)
            close()
        }
    }

    /** Six drive teeth pointing at the centre (r 32..47, 14 wide, at −90° + k·60°) — they rotate. */
    val hubTeeth: Path = Path().apply {
        for (k in 0 until 6) {
            val a = Math.toRadians((-90.0 + k * 60.0)).toFloat()
            val ux = cos(a); val uy = sin(a); val vx = -uy; val vy = ux
            val r0 = 32f; val r1 = 47f; val hw = 7f
            addPath(polygon(
                ux * r0 + vx * hw, uy * r0 + vy * hw,
                ux * r1 + vx * hw, uy * r1 + vy * hw,
                ux * r1 - vx * hw, uy * r1 - vy * hw,
                ux * r0 - vx * hw, uy * r0 - vy * hw,
            ))
        }
    }

    /** The hubs' FIXED lighting (does not rotate): a soft key light from the upper left. */
    val hubLightLeft: Brush = hubLight(p, G.HubLeftX)
    val hubLightRight: Brush = hubLight(p, G.HubRightX)

    private fun hubLight(p: CassettePalette, cx: Float) = Brush.radialGradient(
        0f to p.hi(0.22f), 0.6f to p.hi(0.04f), 1f to p.shade(0.22f),
        center = Offset(cx - 35f, G.HubY - 38f), radius = G.HubRadius + 48f,
    )
}

// ── shell ────────────────────────────────────────────────────────────────────────────────────

/** `<g id="shell">`: smoky translucent body, lighter clear rim, moulded inner wall, top channel. */
internal fun DrawScope.drawShellBase(p: CassettePalette) {
    val body = Brush.verticalGradient(
        0f to p.shellEdge.copy(alpha = 0.55f), 0.5f to p.shell.copy(alpha = 0.80f), 1f to p.shell.copy(alpha = 0.95f),
        startY = 0f, endY = G.Height,
    )
    rr(1f, 1f, 998f, 636f, 26f, body)
    rr(1f, 1f, 998f, 636f, 26f, p.shellEdge, stroke(2f))
    rr(9f, 9f, 982f, 620f, 18f, p.shellEdge.copy(alpha = 0.55f), stroke(14f))
    rr(9f, 9f, 982f, 620f, 18f, Brush.linearGradient(
        0f to p.hi(0.13f), 0.5f to p.hi(0.03f), 1f to p.hi(0.08f), start = Offset.Zero, end = Offset(G.Width, G.Height),
    ), stroke(14f))
    rr(2f, 2f, 996f, 634f, 25f, p.hi(0.30f), stroke(1f))
    rr(5f, 5f, 990f, 628f, 22f, Brush.linearGradient(
        0f to p.hi(0.35f), 0.45f to p.hi(0.10f), 1f to p.hi(0.22f), start = Offset.Zero, end = Offset(G.Width, G.Height),
    ), stroke(1.5f))
    // inner moulded wall: the see-through middle is darker than the rim
    rr(18f, 18f, 964f, 602f, 14f, Brush.linearGradient(
        0f to p.shade(0.22f), 0.55f to p.shade(0.50f), 1f to p.shade(0.34f), start = Offset(0f, 18f), end = Offset(982f, 620f),
    ))
    rr(18f, 18f, 964f, 602f, 14f, Brush.verticalGradient(0f to p.hi(0.35f), 1f to p.hi(0.16f), startY = 18f, endY = 620f), stroke(1.4f))
    rr(23f, 23f, 954f, 592f, 10f, p.hi(0.08f), stroke(1f))
    rr(20.5f, 20.5f, 959f, 597f, 12f, p.shade(0.45f), stroke(1f))
    // top channel + write-protect recesses
    line(60f, 46f, 940f, 46f, p.hi(0.10f), 1f)
    line(60f, 48f, 940f, 48f, p.shade(0.35f), 1f)
    for (x0 in floatArrayOf(126f, 818f)) {
        rr(x0, 24f, 56f, 16f, 3f, p.shade(0.35f))
        rr(x0, 24f, 56f, 16f, 3f, p.hi(0.18f), stroke(1f))
    }
    for (x0 in floatArrayOf(196f, 748f)) rr(x0, 26f, 56f, 12f, 2f, p.hi(0.10f), stroke(1f))
    disc(500f, 33f, 7f, p.shade(0.35f)); ring(500f, 33f, 7f, p.hi(0.14f), 1f)
    // side channels: the inner walls seen through the clear sides
    for (x in floatArrayOf(30f, 44f, 956f, 970f)) line(x, 64f, x, 470f, p.hi(0.16f), 1f)
    for (x in floatArrayOf(31f, 45f, 957f, 971f)) line(x, 64f, x, 470f, p.shade(0.35f), 1f)
    for (y in floatArrayOf(180f, 300f)) for (x0 in floatArrayOf(24f, 950f)) {
        rr(x0, y, 26f, 6f, 3f, p.shade(0.3f))
        rr(x0, y, 26f, 6f, 3f, p.hi(0.10f), stroke(1f))
    }
}

// ── label ────────────────────────────────────────────────────────────────────────────────────

/** `<g id="label">`: paper sticker with a hairline shadow, graph grid, and the window's cut edge. */
internal fun DrawScope.drawLabelCard(p: CassettePalette, kit: CassetteArtKit) {
    val w = G.LabelRight - G.LabelLeft
    val h = G.LabelBottom - G.LabelTop
    rr(G.LabelLeft, G.LabelTop + 2.5f, w, h, G.LabelRadius, p.shade(0.55f))
    rr(G.LabelLeft, G.LabelTop, w, h, G.LabelRadius, p.labelPaper)
    drawPath(kit.grid, p.labelGrid, style = stroke(1f))
    rr(G.LabelLeft, G.LabelTop, w, h, G.LabelRadius,
        Brush.verticalGradient(0f to p.hi(0f), 1f to p.inkSoft.copy(alpha = 0.07f), startY = G.LabelTop, endY = G.LabelBottom))
    rr(G.LabelLeft, G.LabelTop, w, h, G.LabelRadius, p.inkSoft.copy(alpha = 0.35f), stroke(1f))
    // the paper's cut edge round the window
    rr(G.WinLeft - 1.5f, G.WinTop - 1.5f, G.WinRight - G.WinLeft + 3f, G.WinBottom - G.WinTop + 3f,
        G.WinRadius + 1.5f, p.inkSoft.copy(alpha = 0.55f))
}

/** `<g id="window">`: the dark cavity + the small centre pin, UNDER the packs (they cover it at
 *  the very start and end of a song, as a real pin would be hidden). */
internal fun DrawScope.drawWindowBack(p: CassettePalette, kit: CassetteArtKit) {
    drawPath(kit.window, p.window)
    disc(500f, 312f, 6f, p.metal)
    disc(498f, 310f, 2.2f, p.hubTeeth)
}

// ── reels (per frame) ────────────────────────────────────────────────────────────────────────

/**
 * `<g id="reelLeft|reelRight">`: the packs clipped by the window; the hubs (which fit inside it)
 * drawn over them, rotated about their centres. NOTHING is drawn between the packs but the centre
 * pin (Cris: the ad's diagonal strand was deleted 2026-09-23, the run along the window's flat
 * bottom 2026-09-24): a real tape leaves each pack toward the head edge — out to the rollers,
 * across the pressure pad and back — which the pinch rollers and the head recess imply.
 */
internal fun DrawScope.drawReels(
    p: CassettePalette, kit: CassetteArtKit, radii: PackRadii, supplyDeg: Float, takeUpDeg: Float,
) {
    clipPath(kit.window) {
        drawPack(p, G.HubLeftX, radii.supply)
        drawPack(p, G.HubRightX, radii.takeUp)
    }
    drawHub(p, kit, G.HubLeftX, supplyDeg, kit.hubLightLeft)
    drawHub(p, kit, G.HubRightX, takeUpDeg, kit.hubLightRight)
}

/** Flat tape + hub-ANCHORED winding rings (inner layers stay put as the pack shrinks or grows —
 *  only the outermost ring appears or goes) + a clearly visible 4-unit sheen at the moving edge. */
private fun DrawScope.drawPack(p: CassettePalette, cx: Float, r: Float) {
    val c = Offset(cx, G.HubY)
    drawCircle(p.tape, r, c)
    val groove = p.tapeSheen.copy(alpha = 0.45f)
    var g = G.RMin + 12f
    while (g < r - 8f) { drawCircle(groove, g, c, style = stroke(1f)); g += 14f }
    drawCircle(p.tapeSheen, r - 2f, c, style = stroke(4f))
}

private fun DrawScope.drawHub(p: CassettePalette, kit: CassetteArtKit, cx: Float, degrees: Float, light: Brush) {
    val c = Offset(cx, G.HubY)
    drawCircle(p.hub, G.HubRadius, c)
    drawCircle(p.hubDeep, G.HubRadius - 1.5f, c, style = stroke(3f))
    drawCircle(p.hubDeep, 62f, c, style = stroke(9f))
    drawCircle(p.hubTeeth, 52f, c)                 // the bold tooth ring
    drawCircle(p.window, 46f, c)                   // the big dark centre
    translate(cx, G.HubY) {
        rotate(degrees, pivot = Offset.Zero) {
            drawPath(kit.hubSlots, p.hubDeep)
            drawPath(kit.hubTeeth, p.hubTeeth)
        }
    }
    // fixed lighting — does NOT rotate
    drawCircle(light, G.HubRadius, c)
    drawCircle(p.shade(0.5f), 46f, c, style = stroke(3f))
    drawCircle(p.shade(0.45f), G.HubRadius + 1.5f, c, style = stroke(2f))
}

// ── window glass, head edge, label graphics, screws, gloss (top layer) ───────────────────────

/** `<g id="windowGlass">`: glass tint, a depth shadow under the top edge, one specular band. */
internal fun DrawScope.drawWindowGlass(p: CassettePalette, kit: CassetteArtKit) {
    val w = G.WinRight - G.WinLeft
    val h = G.WinBottom - G.WinTop
    clipPath(kit.window) {
        drawRect(Brush.verticalGradient(0f to p.hi(0.07f), 0.5f to p.hi(0.015f), 1f to p.hi(0.04f),
            startY = G.WinTop, endY = G.WinBottom), Offset(G.WinLeft, G.WinTop), Size(w, h))
        drawRect(Brush.verticalGradient(0f to p.shade(0.75f), 1f to p.shade(0f), startY = G.WinTop, endY = G.WinTop + 34f),
            Offset(G.WinLeft, G.WinTop), Size(w, 34f))
        // The gradient runs exactly ACROSS the band (61.4 units, perpendicular to its edges) so
        // both edges fade to 0 — the SVG's longer axis left a hard line on the band's right edge.
        drawPath(polygon(560f, G.WinTop, 640f, G.WinTop, 470f, G.WinBottom, 390f, G.WinBottom),
            Brush.linearGradient(0f to p.hi(0f), 0.5f to p.hi(0.10f), 1f to p.hi(0f),
                start = Offset(560f, G.WinTop), end = Offset(607.2f, G.WinTop + 39.3f)))
        rr(G.WinLeft + 0.5f, G.WinTop + 0.5f, w - 1f, h - 1f, G.WinRadius - 0.5f, p.hi(0.16f), stroke(1f))
    }
}

/** `<g id="headEdge">`: polish's moulded step and translucent recess, engineer's shield /
 *  pressure-pad recess, replica's capstan + pinch-roller + guide-roller sizes. */
internal fun DrawScope.drawHeadEdge(p: CassettePalette) {
    line(20f, 478f, 980f, 478f, p.shade(0.5f), 1.5f)
    line(20f, 480f, 980f, 480f, p.hi(0.12f), 1f)
    for (x0 in floatArrayOf(40f, 850f)) {
        rr(x0, 494f, 110f, 12f, 6f, p.shade(0.3f))
        rr(x0, 494f, 110f, 12f, 6f, p.hi(0.28f), stroke(1f))
        line(x0 + 6f, 509.5f, x0 + 104f, 509.5f, p.hi(0.10f), 1f)
    }
    // the raised clear trapezoid moulding, its inner cavity, the lit top step
    val trap = Path().apply {
        moveTo(168f, 637f); lineTo(206f, 496f); quadraticTo(209f, 486f, 219f, 486f)
        lineTo(781f, 486f); quadraticTo(791f, 486f, 794f, 496f); lineTo(832f, 637f); close()
    }
    drawPath(trap, p.shellEdge.copy(alpha = 0.32f))
    drawPath(trap, p.hi(0.35f), style = stroke(1.6f))
    val cavity = Path().apply {
        moveTo(188f, 637f); lineTo(222f, 506f); quadraticTo(224f, 500f, 231f, 500f)
        lineTo(769f, 500f); quadraticTo(776f, 500f, 778f, 506f); lineTo(812f, 637f); close()
    }
    drawPath(cavity, p.window.copy(alpha = 0.55f))
    drawPath(cavity, p.hi(0.22f), style = stroke(1f))
    drawPath(Path().apply {
        moveTo(206f, 496f); quadraticTo(209f, 486f, 219f, 486f); lineTo(781f, 486f)
        quadraticTo(791f, 486f, 794f, 496f); lineTo(797f, 508f); lineTo(203f, 508f); close()
    }, Brush.verticalGradient(0f to p.hi(0.14f), 1f to p.hi(0f), startY = 486f, endY = 530f))
    // the back half of the clear shell showing through, 6 units lower
    drawPath(Path().apply {
        moveTo(160f, 637f); lineTo(198f, 500f); quadraticTo(201f, 492f, 211f, 492f)
        lineTo(789f, 492f); quadraticTo(799f, 492f, 802f, 500f); lineTo(840f, 637f)
    }, p.hi(0.06f), style = stroke(1.2f))
    for ((cx, cy, r) in listOf(Triple(122f, 563f, 46f), Triple(878f, 563f, 46f), Triple(367f, 579f, 28f),
        Triple(633f, 579f, 28f), Triple(272f, 596f, 27f), Triple(728f, 596f, 27f))) ring(cx, cy, r, p.hi(0.06f), 1f)
    // shelf + V ribs from the centre screw boss
    line(200f, 566f, 800f, 566f, p.hi(0.18f), 1.2f)
    line(200f, 569f, 800f, 569f, p.shade(0.4f), 1f)
    line(488f, 538f, 318f, 566f, p.hi(0.22f), 1.4f)
    line(512f, 538f, 682f, 566f, p.hi(0.22f), 1.4f)
    line(500f, 486f, 500f, 512f, p.hi(0.2f), 1.2f)
    disc(500f, 530f, 19f, p.shellEdge.copy(alpha = 0.35f)); ring(500f, 530f, 19f, p.hi(0.25f), 1.2f)
    // head-access opening: metal shield, pressure-pad spring + felt, the tape passing in front
    rr(418f, 552f, 164f, 85f, 3f, p.window)
    line(418f, 552f, 418f, 636f, p.hi(0.25f), 1.2f)
    line(582f, 552f, 582f, 636f, p.hi(0.25f), 1.2f)
    rr(450f, 564f, 100f, 10f, 2f, Brush.linearGradient(0f to p.metal, 1f to p.shellEdge,
        start = Offset(450f, 564f), end = Offset(550f, 574f)))
    rr(450.5f, 564.5f, 99f, 9f, 2f, p.hi(0.35f), stroke(1f))
    drawPath(Path().apply { moveTo(462f, 574f); lineTo(480f, 604f); lineTo(520f, 604f); lineTo(538f, 574f) },
        p.metal, style = stroke(2f))
    rr(478f, 604f, 44f, 12f, 2f, p.inkSoft)
    drawRect(p.tape, Offset(418.6f, 621f), Size(162.8f, 6f))
    line(418.6f, 621.6f, 581.4f, 621.6f, p.tapeSheen, 1f)
    // capstan holes
    for (cx in floatArrayOf(367f, 633f)) {
        disc(cx, 573f, 28f, p.shellEdge.copy(alpha = 0.3f)); ring(cx, 573f, 28f, p.hi(0.26f), 1.2f)
        disc(cx, 573f, 21f, p.shade(0.9f)); ring(cx, 573f, 21f, p.metal.copy(alpha = 0.9f), 1.5f)
    }
    // pinch-roller openings with the purple rollers seen through them
    for (cx in floatArrayOf(272f, 728f)) {
        disc(cx, 590f, 27f, p.window); ring(cx, 590f, 27f, p.hi(0.30f), 1.4f)
        disc(cx, 590f, 20f, p.hub)
        drawCircle(Brush.radialGradient(0f to p.hi(0.25f), 1f to p.shade(0.25f),
            center = Offset(cx - 7f, 583f), radius = 28f), 20f, Offset(cx, 590f), alpha = 0.6f)
        ring(cx, 590f, 12f, p.hubDeep, 2f)
        disc(cx, 590f, 5f, p.window)
    }
    // small bosses / locating holes
    for ((cx, cy, r) in listOf(Triple(204f, 606f, 11f), Triple(796f, 606f, 11f), Triple(270f, 528f, 9f),
        Triple(730f, 528f, 9f), Triple(56f, 530f, 12f), Triple(944f, 530f, 12f))) {
        disc(cx, cy, r, p.shade(0.35f)); ring(cx, cy, r, p.hi(0.20f), 1f)
    }
    // corner guide rollers
    for (cx in floatArrayOf(122f, 878f)) {
        disc(cx, 557f, 46f, p.shade(0.35f)); ring(cx, 557f, 46f, p.hi(0.16f), 1f)
        disc(cx, 557f, 40f, p.hub)
        drawCircle(Brush.radialGradient(0f to p.hi(0.25f), 1f to p.shade(0.25f),
            center = Offset(cx - 14f, 541f), radius = 58f), 40f, Offset(cx, 557f))
        ring(cx, 557f, 26f, p.hubDeep, 3f)
        disc(cx, 557f, 13f, p.metal)
        disc(cx, 557f, 6f, p.window)
    }
    line(30f, 634f, 970f, 634f, p.hi(0.10f), 1f)
}

/** The label's non-text marks: the STEREO badge box and the Lyra logo (an ink sun over three
 *  stripes whose widths are chords of the same circle). */
internal fun DrawScope.drawLabelMarks(p: CassettePalette) {
    rr(84f, 82f, 72f, 20f, 1.5f, p.inkSoft, stroke(1.2f))
    val cx = LogoX; val cy = 296f; val r = 18f
    drawPath(Path().apply {
        arcTo(Rect(cx - r, cy - r, cx + r, cy + r), 180f, 180f, forceMoveTo = true); close()
    }, p.ink)
    for ((y0, hh) in listOf(3f to 3.5f, 8.5f to 3f, 13.5f to 2.5f)) {
        val half = sqrt(r * r - (y0 + hh) * (y0 + hh))
        drawRect(p.ink, Offset(cx - half, cy + y0), Size(2f * half, hh))
    }
}

internal const val LogoX = 115f

/** `<g id="screws">`: four corner screws + the head screw, flattened to absolute coordinates. */
internal fun DrawScope.drawScrews(p: CassettePalette) {
    for ((cx, cy, r) in listOf(Triple(34f, 34f, 14f), Triple(966f, 34f, 14f), Triple(34f, 604f, 14f),
        Triple(966f, 604f, 14f), Triple(500f, 530f, 12f))) {
        disc(cx, cy, r + 4f, p.shade(0.4f)); ring(cx, cy, r + 4f, p.hi(0.22f), 1f)
        drawCircle(Brush.radialGradient(0f to p.metal, 1f to p.window, center = Offset(cx - 4f, cy - 5f), radius = r + 4f),
            r, Offset(cx, cy))
        val k = r * 0.55f
        line(cx - k, cy, cx + k, cy, p.window, r * 0.2f)
        line(cx, cy - k, cx, cy + k, p.window, r * 0.2f)
        ring(cx, cy, r - 0.5f, p.hi(0.25f), 1f)
    }
}

/** `<g id="shellGloss">`, drawn last and on PLASTIC ONLY (the label is a paper sticker): one
 *  diagonal band split around the label, and a second one bottom right. */
internal fun DrawScope.drawShellGloss(p: CassettePalette) {
    val spec = Brush.linearGradient(0f to p.hi(0f), 0.5f to p.hi(0.20f), 1f to p.hi(0f),
        start = Offset(200f, 4f), end = Offset(330.3f, 39.2f))
    drawPath(polygon(200f, 4f, 340f, 4f, 325.4f, 58f, 185.4f, 58f), spec)
    drawPath(polygon(75.3f, 466f, 215.3f, 466f, 170f, 634f, 30f, 634f), spec)
    drawPath(polygon(880f, 470f, 960f, 470f, 900f, 634f, 820f, 634f),
        Brush.linearGradient(0f to p.hi(0f), 0.5f to p.hi(0.14f), 1f to p.hi(0f),
            start = Offset(760f, 480f), end = Offset(860f, 560f)))
}
