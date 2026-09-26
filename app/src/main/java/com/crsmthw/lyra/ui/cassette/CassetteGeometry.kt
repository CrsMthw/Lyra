package com.crsmthw.lyra.ui.cassette

import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

    // ── Label card ───────────────────────────────────────────────────────────
    const val LabelLeft   = 56f
    const val LabelTop    = 58f
    const val LabelRight  = 944f
    const val LabelBottom = 466f
    const val LabelRadius = 9f
    /** The label's drop shadow, offset 2.5 units under the card. */
    const val LabelShadowBottom = LabelBottom + 2.5f

    // ── Pack peek: the fuller pack seen through the clear shell BELOW the label ──────────────
    /** The band's top: just under the label's drop shadow, so the sticker's edge stays intact. */
    const val PeekTop    = LabelShadowBottom
    /** The band's bottom: where the head strip's cavity begins (the `cavity` path's top edge in
     *  `drawHeadEdge`); the trapezoid moulding above it is clear, so the arc shows through it. */
    const val PeekBottom = 500f
    /** Below the head strip's step line (y 478..480) the peek FADES to nothing at [PeekBottom]:
     *  a hard cut at 500 showed as a straight edge on the plain shell beside the trapezoid (the
     *  full pack still spans x 163..205 there), and the ad's arc dies away the same way. */
    const val PeekFadeTop = 481f
    /** The band's sides: the shell's inner moulded wall. */
    const val PeekLeft   = 18f
    const val PeekRight  = 982f

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

/** How far (units) a pack of radius `r` reaches past the label into the peek band (0 = hidden);
 *  the band is [CassetteGeometry.PeekTop]..[CassetteGeometry.PeekBottom]. A pack's edge crosses
 *  the band's top at r = 149.5, i.e. p ≈ 0.40 for the take-up and ≈ 0.60 for the supply, so at
 *  mid-song BOTH peek by ~12 units; at either end the fuller one reaches 540, past the band. */
internal fun packPeekDepth(r: Float): Float =
    (CassetteGeometry.HubY + r - CassetteGeometry.PeekTop)
        .coerceIn(0f, CassetteGeometry.PeekBottom - CassetteGeometry.PeekTop)

/**
 * A hub's angular SPEED (degrees per second, a magnitude — the direction is [advanceHubAngle]'s)
 * for the pack radius it carries: `ω = v / r` — constant linear tape speed means the small pack
 * spins fast and the full one slow.
 */
internal fun hubDegreesPerSecond(packRadius: Float, tapeSpeed: Float = CassetteGeometry.TapeSpeed): Float =
    (tapeSpeed / max(packRadius, 1f)) * (180f / PI.toFloat())

/**
 * Advances a hub angle by one frame, wrapped into [0, 360). In PLAY both hubs turn ANTICLOCKWISE
 * (natural frame, head edge at the bottom): the tape leaves the supply (left) pack and winds onto
 * the take-up (right) pack along the HEAD side, so the bottom of each pack moves toward the reel
 * that is filling — left to right — and a circle whose bottom moves right turns anticlockwise. A
 * rigid rotation keeps handedness, so the portrait screen sees anticlockwise too. The angle is fed
 * to DrawScope `rotate`, which is CLOCKWISE-positive on screen (y down), so it DECREASES over time.
 *
 * `tapeVelocity` is the SIGNED tape speed ([windTapeVelocity] during a reel wind): positive turns
 * the hubs the play way, negative (a rewind) clockwise, 0 not at all — ω = |v| / r either way.
 * `maxStepDeg` clamps one frame's turn ([CassetteWind.HubMaxStepDeg] in the frame loop). With both
 * defaults this is bit-identical to the play-only form it replaced. `dtSeconds` is capped at 0.1 s
 * so a hitch or the first frame after a resume never jumps the teeth.
 */
internal fun advanceHubAngle(
    angle: Float,
    packRadius: Float,
    dtSeconds: Float,
    tapeVelocity: Float = CassetteGeometry.TapeSpeed,
    maxStepDeg: Float = Float.MAX_VALUE,
): Float {
    val dt = dtSeconds.coerceIn(0f, 0.1f)
    val step = (hubDegreesPerSecond(packRadius, abs(tapeVelocity)) * dt).coerceAtMost(maxStepDeg)
    val next = if (tapeVelocity >= 0f) angle - step else angle + step
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

/** The eject's clearance past the stage edge, as a fraction of the shell's SHORT side. */
internal const val EjectMargin = 0.04f

/**
 * How far an ejected shell travels (any unit — the stage passes px): out through its TITLE edge
 * (natural −y), so its own SHORT side, plus the `band` of stage between that edge and the stage's
 * own edge (the letterbox — the stage clips to its bounds, so the shell must cross it too), plus
 * an [EjectMargin] of the short side. A negative band reads as 0.
 */
internal fun ejectTravel(short: Float, band: Float): Float = short * (1f + EjectMargin) + max(band, 0f)

/**
 * The stage between the shell's title edge and the stage edge it is ejected through: the
 * letterbox along the SCREEN's short dimension — the stage's height in landscape (the unfolded
 * screen's bands above and below), its width in portrait (≈ 0 on the cover screen). `fitShort` is
 * [CassetteFit.short] in the same unit as the stage size. Never negative.
 */
internal fun ejectBand(stageWidth: Float, stageHeight: Float, fitShort: Float, portrait: Boolean): Float =
    max(0f, ((if (portrait) stageWidth else stageHeight) - fitShort) / 2f)

internal const val EjectTotalMs: Int =
    CassetteTiming.EjectOutMs + CassetteTiming.EjectGapMs + CassetteTiming.InsertMs

/** The flip's rotation about the SHORT axis (the stage's `rotationY`, so the head edge stays at
 *  the bottom) at `tMs`: 0 → 180°, FastOutSlowIn. Always the same sense — every track change is
 *  the same move (see [CassetteChoreographer]). */
internal fun flipAngleAt(tMs: Float): Float {
    val f = FastOutSlowInEasing.transform((tMs / CassetteTiming.FlipMs).coerceIn(0f, 1f))
    return 180f * f
}

/** How many half-LONG-sides of the shell the flip's camera sits from it (see [flipCameraDistance]). */
internal const val FlipCameraHalfSides = 12f

/**
 * The flip's camera distance for a shell whose LONG side is `longSidePx`: the flip turns about
 * the short axis, so the half-LONG side is what swings toward the camera, and the camera sits
 * [FlipCameraHalfSides] of those away. The layer's camera distance is in the platform camera's
 * units of 72 px (the usual `12 × density` idiom was measured on the emulator: it put the camera
 * ~2 400 px from a 1 248 px shell, and the near edge ran off the cover screen by a third).
 */
internal fun flipCameraDistance(longSidePx: Float): Float = FlipCameraHalfSides * (longSidePx / 2f) / 72f

/**
 * The uniform scale the flipping face is drawn at so its NEAR end never grows past its rest size.
 * A layer scales before it rotates, so the near end (half-long-side `s·L/2`, brought `s·(L/2)·sin θ`
 * toward a camera `C·L/2` away) is magnified `C / (C − s·sin θ)`; `s = C / (C + |sin θ|)` makes
 * that product exactly 1. Without it the near end outgrows a full-bleed short side by up to
 * 1/C ≈ 9 % at 90° (the cover screen in either orientation) and the stage clips its corners —
 * measured on the emulator. 1 at rest, 12/13 edge-on.
 */
internal fun flipNearEdgeScale(angleDegrees: Float): Float {
    val s = abs(sin(angleDegrees * (PI.toFloat() / 180f)))
    return FlipCameraHalfSides / (FlipCameraHalfSides + s)
}

/** Past 90° the viewer sees the INCOMING face. */
internal fun flipShowsIncoming(angle: Float): Boolean = abs(angle) >= 90f

/** The rotation a face is drawn at for a flip angle (0..180): the incoming face is pre-rotated by
 *  180° so it lands upright (the standard card flip). */
internal fun flipFaceRotation(angle: Float, incoming: Boolean): Float =
    if (incoming) angle - 180f else angle

/**
 * One sample of the eject: shifts are along the shell's natural −y, the TITLE edge (opposite the
 * head — out to the screen's LEFT in portrait, through the TOP in landscape), in units of
 * [ejectTravel]: 0 = at rest, 1 = just clear of the stage. The outgoing shell leaves and the
 * incoming one arrives through that same edge, in both directions.
 */
internal data class EjectFrame(val outgoingShift: Float, val incomingShift: Float, val incomingScale: Float)

/** Out (EjectOutMs) → gap (EjectGapMs) → in (InsertMs), both slides FastOutSlowIn, finite. */
internal fun ejectFrameAt(tMs: Float): EjectFrame {
    val outMs = CassetteTiming.EjectOutMs.toFloat()
    val inStart = (CassetteTiming.EjectOutMs + CassetteTiming.EjectGapMs).toFloat()
    val out = FastOutSlowInEasing.transform((tMs / outMs).coerceIn(0f, 1f))
    val ins = FastOutSlowInEasing.transform(((tMs - inStart) / CassetteTiming.InsertMs).coerceIn(0f, 1f))
    return EjectFrame(
        outgoingShift = out,
        incomingShift = 1f - ins,
        incomingScale = 0.98f + 0.02f * ins,
    )
}

// ── Stage orientation ──────────────────────────────────────────────────────────────────────────

/** Portrait turns the natural drawing 90° ANTICLOCKWISE (graphicsLayer rotationZ is clockwise). */
internal fun stageRotationZ(portrait: Boolean): Float = if (portrait) -90f else 0f

/**
 * Where a direction in the shell's natural frame points on screen (y down) after the stage's
 * rotation — rotationZ θ maps (x, y) → (x cos θ − y sin θ, x sin θ + y cos θ). In portrait the head
 * edge (natural +y) lands on the RIGHT, the title edge (natural −y, the eject's way out) on the
 * LEFT, and the label's reading direction (natural +x) points UP.
 */
internal fun naturalToScreen(dx: Float, dy: Float, portrait: Boolean): Pair<Float, Float> {
    val rad = stageRotationZ(portrait) * (PI.toFloat() / 180f)
    val c = kotlin.math.cos(rad)
    val s = kotlin.math.sin(rad)
    return (dx * c - dy * s) to (dx * s + dy * c)
}

// ── Screen rotation: the settle after a turn that flips the image on the glass ────────────────────

/**
 * How far the system turns the window's content ON THE GLASS for a display rotation: degrees,
 * clockwise-positive like `rotationZ`. `surfaceRotation` is `Display.getRotation()`, a
 * `Surface.ROTATION_*` value, which is the rotation of the DRAWN GRAPHICS, opposite to the device's
 * own turn. A phone turned 90° anticlockwise draws its content turned 90° clockwise: ROTATION_90 →
 * 90, ROTATION_270 → 270 (≡ −90), ROTATION_180 → 180. Out-of-range values are clamped to 0..3.
 */
internal fun contentRotationDeg(surfaceRotation: Int): Float = surfaceRotation.coerceIn(0, 3) * 90f

/**
 * The shell's angle ON THE GLASS (the display's natural frame), degrees clockwise, in [0, 360): the
 * stage's own turn ([stageRotationZ]) plus the system's ([contentRotationDeg]). Portrait at
 * ROTATION_0 is 270 (the head edge on the glass's right), and so is landscape at ROTATION_270: turning
 * the phone CLOCKWISE out of portrait leaves the image exactly where it was on the glass, while the
 * anticlockwise turn (ROTATION_90 → 90) moves the head edge to the glass's other side.
 */
internal fun glassAngleDeg(portrait: Boolean, surfaceRotation: Int): Float =
    wrapDeg360(stageRotationZ(portrait) + contentRotationDeg(surfaceRotation))

/** `deg` wrapped into [0, 360). */
private fun wrapDeg360(deg: Float): Float = ((deg % 360f) + 360f) % 360f

/** `deg` wrapped into (−180, 180]. */
private fun wrapHalfTurn(deg: Float): Float = wrapDeg360(deg).let { if (it > 180f) it - 360f else it }

/**
 * What a display rotation does to the stage. [startDeg] = the extra turn the stage's FIRST frame in
 * the new rotation is drawn with, so the shell sits on the glass exactly where the old frame left
 * it; the settle then runs it to 0 over [CassetteTiming.OrientationTurnMs]. 0 = nothing to settle.
 * [portrait] = the orientation the new rotation gives a full-screen window.
 */
internal data class OrientationTurn(val startDeg: Float, val portrait: Boolean)

/**
 * Decides a rotation from the ROTATIONS alone. The new orientation is PREDICTED from the turn's
 * parity: a quarter turn (90° or 270°) swaps portrait and landscape and a half turn keeps it, which
 * holds for a full-screen window. So the answer is the same whether the stage composes the new
 * configuration before its new constraints or together with them.
 *
 * The image on the glass ([glassAngleDeg]) either stays where it was, and there is nothing to settle,
 * or turns by 180°; a rotation can produce nothing else, and any other change also reads as no
 * settle. A 180° change starts the stage at −180 when the content turned clockwise (+90, or a half
 * turn) and at +180 when it turned anticlockwise (−90), so the settle carries on the way the
 * system turned the content. The content turn is (new − last) × 90, wrapped into (−180, 180].
 */
internal fun orientationTurnFor(lastRotation: Int, lastPortrait: Boolean, newRotation: Int): OrientationTurn {
    val last = lastRotation.coerceIn(0, 3)
    val new = newRotation.coerceIn(0, 3)
    val portrait = if ((new - last).mod(2) == 1) !lastPortrait else lastPortrait
    val glassChange = wrapHalfTurn(glassAngleDeg(portrait, new) - glassAngleDeg(lastPortrait, last))
    if (abs(abs(glassChange) - 180f) > 0.5f) return OrientationTurn(startDeg = 0f, portrait = portrait)
    val contentTurn = wrapHalfTurn(contentRotationDeg(new) - contentRotationDeg(last))
    return OrientationTurn(startDeg = if (contentTurn < 0f) 180f else -180f, portrait = portrait)
}

/**
 * Where a settle starts when a rotation lands while an earlier settle is still running: the new
 * turn's [OrientationTurn.startDeg] plus whatever is left of the old one (`inFlightDeg`, 0 at rest).
 * The first frame in the new rotation then still shows the shell where the last frame left it on the
 * glass. A total beyond a half turn goes the short way round. An at-rest ±180 is never beyond it, so
 * the direction rule holds whenever no settle was running.
 */
internal fun orientationTurnStart(startDeg: Float, inFlightDeg: Float): Float {
    val total = startDeg + inFlightDeg
    return when {
        total > 180f  -> total - 360f
        total < -180f -> total + 360f
        else          -> total
    }
}

/**
 * The stage's rotation bookkeeping. It is fed once per composition of the stage's content and
 * assigned THERE, in plain fields (PaneStateHolder-style, like the stage's StageState, never snapshot
 * state), so the first frame of a new rotation already carries its settle. It is pure so that the
 * order in which a rotation's inputs reach the composition can be unit-tested (CassetteRotationTest).
 *
 * A rotation is judged from the display rotation alone ([orientationTurnFor]). The fit's orientation
 * only re-syncs [portrait] at compositions that bring no rotation change. The new configuration can
 * recompose the stage in the recomposer's pass while its constraints are still the OLD ones, before
 * the new size measures it. That is how a size-only change, such as unfolding with the rotation
 * unchanged, never becomes a turn. A rotation whose configuration also changes the window's smallest
 * width is not a turn of the same window (a fold or unfold that also rotated, or a split-screen
 * resize), so it settles nothing: the parity prediction would be meaningless there. This relies on
 * the configuration reaching the composition no later than the new constraints, which holds because
 * ViewRootImpl dispatches a resize's configuration before it applies the resize's frame.
 */
internal class OrientationTurnState {
    /** The last display rotation seen; −1 before the first composition. */
    var rotation: Int = -1
        private set

    /** The orientation that rotation gives the window: predicted at a rotation, re-synced from the fit otherwise. */
    var portrait: Boolean = false
        private set

    /** Where the current settle starts, in degrees added to the stage's rest turn (see [orientationTurnStart]). */
    var from: Float = 0f
        private set

    /** Bumped once per settle; the stage keys a fresh Animatable on it. */
    var generation: Int = 0
        private set

    /** The configuration's smallest width (dp) at the last composition; a rotation never changes a full-screen window's. */
    private var smallestWidthDp: Int = -1

    /**
     * One composition's inputs: the display `rotation`, the fit's `portrait` (from whichever
     * constraints this composition sees) and the configuration's `smallestWidthDp`. `inFlightDeg` =
     * what is left of a running settle, read only when a new one starts. Returns true when a new
     * settle starts, i.e. [generation] was bumped and [from] set.
     */
    fun update(rotation: Int, portrait: Boolean, smallestWidthDp: Int, inFlightDeg: () -> Float): Boolean {
        val lastRotation = this.rotation
        val lastPortrait = this.portrait
        val sameWindow = smallestWidthDp == this.smallestWidthDp
        this.smallestWidthDp = smallestWidthDp
        this.rotation = rotation
        if (lastRotation < 0 || rotation == lastRotation || !sameWindow) {
            this.portrait = portrait
            return false
        }
        val turn = orientationTurnFor(lastRotation, lastPortrait, rotation)
        this.portrait = turn.portrait
        if (turn.startDeg == 0f) return false
        from = orientationTurnStart(turn.startDeg, inFlightDeg())
        generation++
        return true
    }
}

/**
 * The largest uniform scale, never above 1, at which a `long` × `short` box turned by `angleDeg`
 * about its centre still fits a `windowW` × `windowH` window (any one unit; the stage passes dp).
 * The turned box's bounding box is `long·|cos| + short·|sin|` wide and `long·|sin| + short·|cos|`
 * tall. At a rest angle (0 in landscape, −90 in portrait) on a full-bleed window it is 1 up to float
 * rounding, and the stage does not call it at rest. Crosswise, at the midpoint of a 180° settle, it
 * is short / long ≈ 0.638, so the shell never leaves the clipped stage while it turns. A degenerate
 * (empty) box reads as 1.
 */
internal fun spinFitScale(angleDeg: Float, long: Float, short: Float, windowW: Float, windowH: Float): Float {
    val rad = angleDeg * (PI.toFloat() / 180f)
    val c = abs(kotlin.math.cos(rad))
    val s = abs(sin(rad))
    val boundsW = long * c + short * s
    val boundsH = long * s + short * c
    if (boundsW <= 0f || boundsH <= 0f) return 1f
    return min(1f, min(windowW / boundsW, windowH / boundsH))
}
