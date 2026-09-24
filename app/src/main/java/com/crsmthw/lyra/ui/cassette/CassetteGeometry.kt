package com.crsmthw.lyra.ui.cassette

import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The cassette's geometry and every pure piece of maths the painter and the stage use —
 * no Android, no Compose runtime, so all of it runs in JVM tests (CassetteGeometryTest).
 *
 * Space: the SVG prototype's 1000 × 638 box, natural orientation (head edge at the BOTTOM).
 * The painter draws in these units under ONE uniform scale `u = width / 1000` (the box IS the
 * shell's ratio, so the same factor serves both axes). The numbers below are the design panel's
 * winner ("polish") with the orchestrator's grafts (replica's reels) — see Cassette.kt's header.
 */
internal object CassetteGeometry {
    const val Width  = 1000f
    const val Height = 638f

    // ── Window: a true stadium (rx = half-height) ───────────────────────────
    const val WinLeft   = 186f
    const val WinTop    = 217f
    const val WinRight  = 814f
    const val WinBottom = 421f
    const val WinRadius = 102f

    // ── Reels ────────────────────────────────────────────────────────────────
    const val HubY      = 319f
    const val HubLeftX  = 290f   // supply
    const val HubRightX = 710f   // take-up
    /** 420 units = the real 42 mm hub pitch. */
    const val HubPitch  = HubRightX - HubLeftX
    const val HubRadius = 98f
    /** An empty reel: the bare hub + 4. */
    const val RMin      = 102f
    /** A full reel. Larger than the window's half-height ON PURPOSE: the packs are clipped by
     *  the window, as in the ad, so each pack edge sweeps ~120 units through the gap. */
    const val RMax      = 221f

    /** The tape run along the window's flat bottom, drawn UNDER the packs. */
    const val TapeRunY  = 410f

    // ── Label card ───────────────────────────────────────────────────────────
    const val LabelLeft   = 56f
    const val LabelTop    = 58f
    const val LabelRight  = 944f
    const val LabelBottom = 466f
    const val LabelRadius = 9f

    // ── Text boxes (units) ───────────────────────────────────────────────────
    const val CentreX         = 500f
    const val TitleBoxLeft    = 172f
    const val TitleBoxRight   = 828f
    const val TitleBaseline   = 118f
    const val ArtistBaseline  = 154f
    const val MetaBaseline    = 184f
    const val MetaMaxWidth    = 700f
    const val FinePrintBaseline  = 441f
    const val FinePrintLineHeight = 12.6f
    const val FinePrintMaxWidth   = 760f

    /** Title sizes tried largest first (units); the last one is the floor, then ellipsis. */
    val TitleSizes : List<Float> = descendingSizes(max = 46f, floor = 24f, step = 2f)
    val ArtistSizes: List<Float> = descendingSizes(max = 27f, floor = 18f, step = 1f)

    /**
     * Tape speed in units per second, tuned so the near-EMPTY hub (r = [RMin]) turns once every
     * 1.2 s. A real C-cassette runs 4.76 cm/s over pack radii of ~1.1–2.5 cm, i.e. 0.3–0.7 rev/s
     * (1.45 s per turn when empty); 1.2 s is that ×1.2 for legibility, and the full hub
     * (r = [RMax]) turns in ~2.6 s — visibly slower, as on a deck.
     */
    const val TapeSpeed = (2.0 * PI * RMin / 1.2).toFloat()
}

/** A descending list `max, max − step, …` that always ends exactly on `floor`. */
internal fun descendingSizes(max: Float, floor: Float, step: Float): List<Float> {
    require(step > 0f && max >= floor)
    val out = ArrayList<Float>()
    var s = max
    while (s > floor + 1e-3f) { out += s; s -= step }
    out += floor
    return out
}

/** The two tape packs' radii (units) at a playback position. */
internal data class PackRadii(val supply: Float, val takeUp: Float)

/**
 * `supply = Rmax − (Rmax − Rmin)·p`, `takeUp = Rmin + (Rmax − Rmin)·p` — the tape moves from the
 * LEFT reel to the RIGHT one. Their sum is constant, so the packs never touch (Rmin + Rmax < pitch).
 * A NaN / out-of-range progress is clamped (a stalled or not-yet-known position reads as 0).
 */
internal fun packRadii(progress: Float): PackRadii {
    val p = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    val span = CassetteGeometry.RMax - CassetteGeometry.RMin
    return PackRadii(
        supply = CassetteGeometry.RMax - span * p,
        takeUp = CassetteGeometry.RMin + span * p,
    )
}

/**
 * A hub's angular SPEED (degrees per second, a magnitude — the direction is [advanceHubAngle]'s)
 * for the pack radius it carries: `ω = v / r` — constant linear tape speed means the small pack
 * spins fast and the full one slow.
 */
internal fun hubDegreesPerSecond(packRadius: Float, tapeSpeed: Float = CassetteGeometry.TapeSpeed): Float =
    (tapeSpeed / max(packRadius, 1f)) * (180f / PI.toFloat())

/**
 * Advances a hub angle by one frame, wrapped into [0, 360). BOTH hubs turn ANTICLOCKWISE (natural
 * frame, head edge at the bottom): the tape leaves the supply (left) pack and winds onto the
 * take-up (right) pack along the HEAD side, so the bottom of each pack moves toward the reel that
 * is filling — left to right — and a circle whose bottom moves right turns anticlockwise. A rigid
 * rotation keeps handedness, so the portrait screen sees anticlockwise too. The angle is fed to
 * DrawScope `rotate`, which is CLOCKWISE-positive on screen (y down), so it DECREASES over time.
 * `dtSeconds` is capped at 0.1 s so a hitch or the first frame after a resume never jumps the teeth.
 */
internal fun advanceHubAngle(angle: Float, packRadius: Float, dtSeconds: Float): Float {
    val dt = dtSeconds.coerceIn(0f, 0.1f)
    val next = angle - hubDegreesPerSecond(packRadius) * dt
    return ((next % 360f) + 360f) % 360f
}

/** A chosen label font size, and whether even the floor overflowed (→ ellipsis). */
internal data class FittedSize(val size: Float, val ellipsize: Boolean)

/**
 * The largest size in `sizes` (descending) whose measured width fits `maxWidth`; at the floor,
 * the floor + ellipsis. `widthAt` measures the text at a size — the painter passes a real
 * `TextMeasurer`, the tests a formula.
 */
internal fun chooseFontSize(sizes: List<Float>, maxWidth: Float, widthAt: (Float) -> Float): FittedSize {
    require(sizes.isNotEmpty())
    for (s in sizes) if (widthAt(s) <= maxWidth) return FittedSize(s, ellipsize = false)
    return FittedSize(sizes.last(), ellipsize = true)
}

/**
 * The sizes worth trying once the text has been measured at the LARGEST size: a width with
 * em-proportional tracking scales ~linearly with the size, so everything above the estimated fit
 * (+3 % for hinting) is skipped, and [chooseFontSize] usually verifies one or two sizes instead of
 * walking the whole list. Always keeps the floor, so the ellipsis case is still reached.
 */
internal fun candidateSizes(sizes: List<Float>, widthAtFirst: Float, maxWidth: Float): List<Float> {
    if (widthAtFirst <= maxWidth || widthAtFirst <= 0f) return sizes.take(1)
    val estimate = sizes.first() * maxWidth / widthAtFirst * 1.03f
    val kept = sizes.drop(1).filter { it <= estimate }
    return kept.ifEmpty { listOf(sizes.last()) }
}

/**
 * The fine print is always set as two balanced lines (as the ad prints it): it is laid out at 60 %
 * of its one-line width — so a balanced line breaker splits it near the middle — never wider than
 * the label allows, never narrower than `minWidth`.
 */
internal fun finePrintWidth(singleLineWidth: Float, maxWidth: Float, minWidth: Float = 0f): Float =
    min(maxWidth, max(minWidth, singleLineWidth * 0.6f))

/** "ALBUM · YEAR", or the half that exists, or null when both are missing/blank. */
internal fun cassetteMetaLine(album: String?, year: String?, join: (String, String) -> String): String? {
    val a = album?.trim()?.takeIf { it.isNotEmpty() }
    val y = year?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        a != null && y != null -> join(a, y)
        a != null              -> a
        else                   -> y
    }
}

/** The fine print: Spotify's copyright line (when known) + the ad's boilerplate, in capitals. */
internal fun cassetteFinePrint(copyright: String?, boilerplate: String): String {
    val c = copyright?.trim()?.takeIf { it.isNotEmpty() }
    return (if (c != null) "$c $boilerplate" else boilerplate).uppercase()
}

// ── Choreography timelines (the stage samples these from one linear Animatable in ms) ──────────

/** How far an ejected shell travels: its own long edge + 10 %, in units of the long edge. */
internal const val EjectTravel = 1.10f

internal const val EjectTotalMs: Int =
    CassetteTiming.EjectOutMs + CassetteTiming.EjectGapMs + CassetteTiming.InsertMs

/** The flip's rotation about the long axis at `tMs`: 0 → ±180°, FastOutSlowIn. A backward
 *  change (previous song) turns the other way. */
internal fun flipAngleAt(tMs: Float, forward: Boolean): Float {
    val f = FastOutSlowInEasing.transform((tMs / CassetteTiming.FlipMs).coerceIn(0f, 1f))
    return (if (forward) 180f else -180f) * f
}

/** Past 90° the viewer sees the INCOMING face. */
internal fun flipShowsIncoming(angle: Float): Boolean = abs(angle) >= 90f

/** The rotation a face is drawn at for a flip angle: the incoming face is pre-rotated by 180° so
 *  it lands upright (the standard card flip). */
internal fun flipFaceRotation(angle: Float, incoming: Boolean): Float =
    if (!incoming) angle else angle - (if (angle >= 0f) 180f else -180f)

/** One sample of the eject: shifts are along the shell's natural +x, in units of its long edge. */
internal data class EjectFrame(val outgoingShift: Float, val incomingShift: Float, val incomingScale: Float)

/** Out (EjectOutMs) → gap (EjectGapMs) → in (InsertMs), both slides FastOutSlowIn, finite. */
internal fun ejectFrameAt(tMs: Float): EjectFrame {
    val outMs = CassetteTiming.EjectOutMs.toFloat()
    val inStart = (CassetteTiming.EjectOutMs + CassetteTiming.EjectGapMs).toFloat()
    val out = FastOutSlowInEasing.transform((tMs / outMs).coerceIn(0f, 1f))
    val ins = FastOutSlowInEasing.transform(((tMs - inStart) / CassetteTiming.InsertMs).coerceIn(0f, 1f))
    return EjectFrame(
        outgoingShift = EjectTravel * out,
        incomingShift = EjectTravel * (1f - ins),
        incomingScale = 0.98f + 0.02f * ins,
    )
}

// ── Stage orientation ──────────────────────────────────────────────────────────────────────────

/** Portrait turns the natural drawing 90° ANTICLOCKWISE (graphicsLayer rotationZ is clockwise). */
internal fun stageRotationZ(portrait: Boolean): Float = if (portrait) -90f else 0f

/**
 * Where a direction in the shell's natural frame points on screen (y down) after the stage's
 * rotation — rotationZ θ maps (x, y) → (x cos θ − y sin θ, x sin θ + y cos θ). In portrait the head
 * edge (natural +y) lands on the RIGHT and the label's reading direction (natural +x) points UP.
 */
internal fun naturalToScreen(dx: Float, dy: Float, portrait: Boolean): Pair<Float, Float> {
    val rad = stageRotationZ(portrait) * (PI.toFloat() / 180f)
    val c = kotlin.math.cos(rad)
    val s = kotlin.math.sin(rad)
    return (dx * c - dy * s) to (dx * s + dy * c)
}
