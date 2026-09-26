package com.crsmthw.lyra.ui.cassette

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crsmthw.lyra.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min

/*
 * THE CASSETTE PAINTER (2026-09-23). Ported from the design panel's winning SVG
 * (scratchpad design/polish/cassette.svg, "polish") with the orchestrator's grafts
 * (specs/GRAFTS.md); design record docs/CASSETTE.md.
 *
 * ── Geometry (1000 × 638 units, natural orientation, head edge at the BOTTOM) ──────────────────
 *   one uniform scale u = width / 1000 serves both axes (the box IS the shell's ratio)
 *   shell            0,0 – 1000,638, rx 26; inner moulded wall 18,18 – 982,620, rx 14
 *   label card       x 56..944, y 58..466, rx 9 (73 % of the face; grid pitch 20 from 70,70)
 *   window           a stadium x 186..814, y 217..421, rx 102 (= half-height) — the ONE clip
 *   hub centres      (290, 319) supply / (710, 319) take-up — pitch 420 = the real 42 mm
 *   hub radius       98 (flange slots r 74..88, tooth ring r 46..52, teeth r 32..47, dark centre r 46)
 *   tape pack        Rmin = 102 (bare hub + 4), Rmax = 221 — packs CLIPPED by the window as in the ad;
 *                    supply = Rmax − (Rmax − Rmin)·p, take-up = Rmin + (Rmax − Rmin)·p;
 *                    Rmin + Rmax = 323 < 420, so the packs never touch (constant 97 gap)
 *   pack peek        the packs drawn AGAIN in the band y 468.5..500 (under the label's shadow to
 *                    the head cavity), dimmed by black 0.45 and faded out from y 481: the fuller
 *                    reel shows through the clear shell below the label, as in the ad (onset at
 *                    r 149.5 → p ≈ 0.40 take-up / 0.60 supply, so mid-song both peek ~12 units)
 *   hub direction    BOTH hubs ANTICLOCKWISE (natural frame, and so on the portrait screen too):
 *                    the tape runs supply → take-up along the HEAD edge, so each pack's bottom
 *                    moves left → right; the angle fed to `rotate` (clockwise-positive) falls
 *   reel wind        a position JUMP (≥ 5 s of track time AND ≥ 1 % of the tape: a seek elsewhere,
 *                    a repeat-one wrap) winds the packs to the new radii instead of snapping —
 *                    350..900 ms by √(pack distance), a trapezoidal motor curve — and the hubs
 *                    with them, one signed tape velocity peaking at 1080°/s on the empty hub
 *                    (18° per 60 Hz frame, under the teeth's aliasing limit); CassetteReelWind.kt
 *   between packs    NOTHING but the centre pin: the ad's diagonal strand (Cris, 2026-09-23) and
 *                    the run along the window's flat bottom (2026-09-24) were both deleted — the
 *                    tape path is implied by the pinch rollers and the head recess
 *   title box        x 172..828 (656), baseline 118, Playfair 46 → floor 24 (then ellipsis),
 *                    tracking 0.087 em; artist the same box, baseline 154, 27 → 18
 *   meta / SIDE / fine print  baselines 184 / 298 + 332 / 441 + 453.6 (≥ 9.4 units)
 *   flip (A ↔ B)     rotationY — about the SHORT axis (natural y), as a real shell is turned over:
 *                    the head edge stays at the bottom (portrait: on the RIGHT) and the reel that
 *                    was on the right lands on the left; in portrait the outer −90° makes it a
 *                    turn about the screen's horizontal axis, the phone's TOP end coming toward
 *                    you (the right end in landscape) — ALWAYS: a track change has no direction
 *                    here (previous song plays the same flip / eject as next, Cris 2026-09-24).
 *                    Camera: 12 half-LONG-sides away;
 *                    the face is scaled by 12 / (12 + |sin θ|) so the near end, which perspective
 *                    grows up to 12/11, never outgrows the full-bleed short side (clipped before)
 *   eject            translationY −shift × travel — out through the TITLE edge (natural −y, the
 *                    edge opposite the head: LEFT in portrait, UP in landscape), and the fresh
 *                    shell back in through the same edge, in both directions. travel = the short
 *                    side × 1.04 + the letterbox band on that side, so the shell clears the stage
 *                    (at rest; while a rotation settle turns the stage, see the rotation row)
 *   rotation         (2026-09-26) while the overlay is up the window asks for a JUMP CUT instead of
 *                    the system's rotate animation (CassetteRotationAnimation, CassetteOverlay.kt):
 *                    the old frame gives way to the new one with no system turn. The shell's angle
 *                    ON THE GLASS is stageRotationZ + the display's content rotation (Surface
 *                    ROTATION_n × 90). Portrait → landscape CLOCKWISE (ROTATION_270), and back, leaves
 *                    it unchanged, so NOTHING plays. Anticlockwise (ROTATION_90), back from there,
 *                    or a turn-over changes it by 180°: the stage's first frame in the new rotation
 *                    is drawn where the old one left the shell (head edge on the wrong side) and
 *                    SETTLES 180° into the new rest angle over OrientationTurnMs, FastOutSlowIn,
 *                    scaled by spinFitScale (≈ 0.638 crosswise) so it never leaves the stage. It
 *                    turns clockwise when the content turned clockwise (+90 or a half turn) and
 *                    anticlockwise for −90. A rotation mid-settle carries the rest of the settle;
 *                    a size-only change (unfolding) and a rotation that changes the smallest width
 *                    (a fold that also rotated) never settle. The reels keep turning through it,
 *                    and an eject keeps clearing the stage: its translation sits INSIDE the turned,
 *                    shrunk layer, so during a settle its travel is ejectTravelTurned = short ×
 *                    0.54 + the stage's half-extent along the eject axis / settleScale (equal to
 *                    the rest travel at a rest angle). The whole 980 ms eject can overlap a settle,
 *                    the parked outgoing shell included
 *   head edge        trapezoid 168..832 from y 486; capstans (367|633, 573); pinch rollers
 *                    (272|728, 590) r 27; guide rollers (122|878, 557) r 46; head recess 418..582
 *
 * ── Performance ──────────────────────────────────────────────────────────────────────────────
 * Per frame only the hub angles move, and the pack radii — once a second on a tick, every frame
 * through a wind. Everything else is recorded into two GraphicsLayers per (size, palette, label,
 * side) in `drawWithCache`; the text is measured in composition (keyed on label, side, strings and
 * pixel size, NOT the palette — colour is applied at draw time). Each face's frame loop runs while
 * it is spinning OR winding and parks otherwise. `progress` is sampled only by the face's
 * snapshotFlow (never in composition, never in the draw): the draw reads only the face's own
 * position ([ReelSpin.shown]) and writes nothing.
 */

/**
 * One face's reels: the [ReelTracker] (the position the packs show, and any wind toward a new one)
 * and the two hub angles (degrees as DrawScope `rotate` takes them — clockwise-positive, so the
 * hubs' anticlockwise play turn makes them fall; see [advanceHubAngle]). [shown] and the angles
 * are snapshot state read ONLY in the draw phase (one redraw per change, no recomposition);
 * [winding] is read ONLY by the frame loop's park. The face's effect is the only writer — the
 * draw writes nothing — so a face whose effect is cancelled (the outgoing face of a flip or an
 * eject, `live = false`) stays exactly where it was last drawn.
 */
@Stable
private class ReelSpin(initial: Float) {
    val tracker = ReelTracker(initial)
    var supplyDeg by mutableFloatStateOf(0f)
    var takeUpDeg by mutableFloatStateOf(0f)
    var shown by mutableFloatStateOf(tracker.displayed)
    var winding by mutableStateOf(false)

    /** Mirrors the tracker into the snapshot state after every change (an equal write is free). */
    fun publish() {
        shown = tracker.displayed
        winding = tracker.winding
    }
}

/**
 * One snapshotFlow sample. The position is LIVE — `progress` reads PlayerScreen's state directly —
 * but the duration is this face's last COMPOSED value (`rememberUpdatedState`). On a track change
 * the collector usually runs before the face recomposes, so for one sample the new position is
 * classified against the OUTGOING track's length; the next sample carries the new length, if the
 * face is still live (the skip-edge residual in [CassetteImpl]'s collector comment).
 */
private data class ReelSample(val progress: Float, val durationMs: Long)

/**
 * The cassette in its NATURAL orientation: long edge horizontal, head edge (the exposed tape,
 * the capstan holes, the shield) at the BOTTOM, label upright. Fills `modifier`'s bounds, which
 * the CALLER has sized at [CASSETTE_ASPECT] (see [cassetteFit]); nothing here letterboxes.
 *
 * `progress` is a lambda sampled by the face's reel tracker (a snapshotFlow — never read in
 * composition), and the draw reads the tracker's position, so the per-second tick never recomposes
 * the label. `spinning` drives the hubs (a frame clock while true, frozen while false — paused
 * music). This overload never winds — its duration is unknown — so the packs follow `progress`
 * exactly (the Settings preview); the player's stage winds through [CassetteImpl].
 */
@Composable
fun Cassette(
    palette : CassettePalette,
    label   : CassetteLabel,
    side    : CassetteSide,
    progress: () -> Float,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) = CassetteImpl(
    palette    = palette,
    label      = label,
    side       = side,
    progress   = progress,
    durationMs = 0L,
    spinning   = spinning,
    live       = true,
    debug      = null,
    modifier   = modifier,
)

/**
 * [Cassette] with the reel wind's inputs (CassetteReelWind.kt). `durationMs` = the track's length,
 * 0 when unknown (the reels then never wind: they follow `progress`, as the Settings preview does).
 * `live` = this face follows playback; false freezes it where it was last drawn — the stage's
 * outgoing face — because its collector, its hold and its frame loop live in ONE effect keyed on
 * it. `debug` = the preview's reel clock ([ReelDebug]); null in production.
 */
@Composable
internal fun CassetteImpl(
    palette   : CassettePalette,
    label     : CassetteLabel,
    side      : CassetteSide,
    progress  : () -> Float,
    durationMs: Long,
    spinning  : Boolean,
    live      : Boolean,
    debug     : ReelDebug?,
    modifier  : Modifier,
) {
    val strings = CassetteArtStrings(
        stereo        = stringResource(R.string.cassette_stereo),
        brand         = stringResource(R.string.cassette_brand),
        sideWord      = stringResource(R.string.cassette_side),
        sideLetter    = stringResource(if (side == CassetteSide.A) R.string.cassette_side_letter_a else R.string.cassette_side_letter_b),
        boilerplate   = stringResource(R.string.cassette_fine_print_boilerplate),
        metaSeparator = stringResource(R.string.cassette_meta_separator),
    )
    val measurer = rememberTextMeasurer()
    // Seeded with the position at the face's first composition, read WITHOUT observation (a read
    // here would recompose this scope on every tick); the collector's first value then primes the
    // tracker, so a new face — a new track, the overlay coming up — never winds.
    val spin = remember { ReelSpin(Snapshot.withoutReadObservation { progress() }) }
    val currentProgress by rememberUpdatedState(progress)
    val currentDuration by rememberUpdatedState(durationMs)
    val currentSpinning by rememberUpdatedState(spinning)

    LaunchedEffect(live, debug) {
        if (!live) return@LaunchedEffect                 // the outgoing face: frozen at spin.shown

        // COLLECTOR: classifies every value; never owns a wind — collectLatest cancels its block
        // on every tick, and the exact-0 hold is the ONE thing the next value should cancel.
        //
        // The skip-edge residual (accepted): a new track's id and position land in ONE snapshot,
        // and THIS face's collector may classify the new position before the stage's key tracking
        // turns the face outgoing (live = false). A jump then only makes a hold or a new UNSTAMPED
        // wind — a retarget when one was running, never a fold into it (ReelTracker.windTo) — and
        // the cancellation comes first: the faces are composed inside the stage's
        // BoxWithConstraints, a SubcomposeLayout, so they recompose in the measure pass of the very
        // frame whose animation callbacks can at most STAMP that wind. A stamp moves no pack, and a
        // retarget's stamp keeps the hubs on the replaced wind's speed (ReelTracker.onFrame), so
        // the old face's packs never move and its hubs never reverse: they take one last step their
        // own way, as they would have anyway. But a new position that is NO jump is accepted here
        // and, if the face is idle, snaps it at the flip's first frame (a skip inside a song's first
        // seconds, say; a running wind only gets a re-based end, which moves nothing at once — see
        // ReelTracker's Span.moveEnd). The threshold is measured against the OUTGOING
        // track's length (the face has not recomposed with the new one yet — see ReelSample), so
        // the snap is at most 5 s of the outgoing face's own tape: ≤ 3.3 units on a 3-minute song,
        // up to ~30 on a 20 s interlude. When the outgoing track is the LONGER one, the stale length
        // only makes a jump more likely — a cancelled unstamped wind, so the face does not move.
        // Exact would need the track id read in the same snapshot as the position.
        launch {
            snapshotFlow { ReelSample(currentProgress(), currentDuration) }.collectLatest { sample ->
                val input = spin.tracker.onProgress(sample.progress, sample.durationMs)
                spin.publish()
                if (input == ReelInput.Hold) {           // the ONLY suspension; the next value cancels it
                    delay(CassetteTiming.WindZeroHoldMs)
                    spin.tracker.onHoldExpired(currentDuration)
                    spin.publish()
                }
            }
        }

        // FRAME LOOP: the hubs' play drive plus any wind. ω = v / r per hub, one signed tape
        // velocity for both (anticlockwise in play). It parks while neither is needed, so the hubs
        // freeze exactly where they are when paused; on waking the first frame only stamps the
        // clock (and the tracker stamps any new wind on it), so nothing snaps.
        val timeScale = debug?.timeScale ?: 1f
        var last = -1L
        while (true) {
            if (!currentSpinning && !spin.winding) {
                last = -1L
                snapshotFlow { currentSpinning || spin.winding }.first { it }
            }
            withFrameNanos { now ->
                val s = spin.tracker.onFrame(now, timeScale, debug?.freezeFraction)
                if (last >= 0L) {
                    val dt = (now - last) / 1_000_000_000f * timeScale
                    val v = windTapeVelocity(s, currentSpinning)
                    val r = packRadii(spin.tracker.displayed)
                    spin.supplyDeg = advanceHubAngle(spin.supplyDeg, r.supply, dt, v, CassetteWind.HubMaxStepDeg)
                    spin.takeUpDeg = advanceHubAngle(spin.takeUpDeg, r.takeUp, dt, v, CassetteWind.HubMaxStepDeg)
                }
                last = now
                spin.publish()
            }
        }
    }

    BoxWithConstraints(modifier) {
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 0f
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else w / CASSETTE_ASPECT
        val u = min(w / CassetteGeometry.Width, h / CassetteGeometry.Height)
        if (u <= 0f) return@BoxWithConstraints
        val text = remember(measurer, label, strings, u) { measureCassetteText(measurer, label, strings, u) }

        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val scale = min(size.width / CassetteGeometry.Width, size.height / CassetteGeometry.Height)
                    val ox = (size.width - CassetteGeometry.Width * scale) / 2f
                    val oy = (size.height - CassetteGeometry.Height * scale) / 2f
                    val kit = CassetteArtKit(palette)
                    val under = obtainGraphicsLayer().apply {
                        record {
                            translate(ox, oy) {
                                scale(scale, pivot = Offset.Zero) {
                                    drawShellBase(palette)
                                    drawLabelCard(palette, kit)
                                    drawWindowBack(palette, kit)
                                }
                            }
                        }
                    }
                    val over = obtainGraphicsLayer().apply {
                        record {
                            translate(ox, oy) {
                                scale(scale, pivot = Offset.Zero) {
                                    drawWindowGlass(palette, kit)
                                    drawHeadEdge(palette)
                                    drawLabelMarks(palette)
                                }
                                drawLabelText(text, palette, scale)
                                scale(scale, pivot = Offset.Zero) {
                                    drawScrews(palette)
                                    drawShellGloss(palette)
                                }
                            }
                        }
                    }
                    onDrawBehind {
                        // The face's OWN position — never `progress()`, which would flash the
                        // target for a frame before a wind starts. The draw writes nothing.
                        val radii = packRadii(spin.shown)
                        drawLayer(under)
                        translate(ox, oy) {
                            scale(scale, pivot = Offset.Zero) {
                                drawReels(palette, kit, radii, spin.supplyDeg, spin.takeUpDeg)
                            }
                        }
                        drawLayer(over)
                    }
                },
        )
    }
}

/**
 * Orientation + fit + choreography over [Cassette]. Fills `modifier`'s bounds (the whole window,
 * insets ignored — the caller is immersive), paints [CassettePalette.background] behind, sizes the
 * shell with [cassetteFit] (no margin, never stretched), turns it 90° anticlockwise in portrait,
 * and shows a change of `trackKey` as a FLIP or an EJECT per [CassetteChoreographer] — the same
 * alternation whatever the change's direction. The outgoing face keeps the label it had; the
 * incoming face gets the new one.
 *
 * `durationMs` = the current track's length, 0 when unknown (the reels then never wind). A
 * playback-position JUMP on the same track — a seek made elsewhere, a repeat-one wrap — winds the
 * incoming face's packs and hubs to the new position instead of snapping (CassetteReelWind.kt);
 * the outgoing face stays frozen where it was.
 *
 * A screen rotation that turns the image ON THE GLASS by 180° (anticlockwise out of portrait,
 * back from there, a turn-over) starts the new frame where the old one left the shell and settles
 * it into the new rest angle ([CassetteTiming.OrientationTurnMs]); one that leaves the image where
 * it was (clockwise out of portrait, and back) plays nothing. The hosting window must ask for a
 * jump cut rather than the system's rotate animation ([CassetteRotationAnimation]) — see the
 * header's rotation row. An eject under way when the phone turns keeps both shells clear of the
 * stage while it settles ([ejectTravelTurned]).
 */
@Composable
fun CassetteStage(
    palette   : CassettePalette,
    label     : CassetteLabel,
    trackKey  : String?,
    progress  : () -> Float,
    durationMs: Long,
    spinning  : Boolean,
    modifier  : Modifier = Modifier,
) = CassetteStageImpl(palette, label, trackKey, progress, durationMs, spinning, modifier, freeze = null, reelDebug = null)

/** DEBUG ONLY (the src/debug preview): hold a [move] at `fraction` of its timeline instead of
 *  playing it, so a screenshot can catch a mid-flip / mid-eject frame. Production passes null. */
internal data class CassetteFreeze(val move: CassetteMove, val fraction: Float)

/** DEBUG ONLY (the src/debug preview): slow the reels' clock — winds AND hubs — by `timeScale`, or
 *  hold any wind at `freezeFraction` of its timeline so a screenshot catches it mid-wind (the hubs
 *  keep turning at that fraction's speed). Production passes null. A data class, so an equal
 *  instance is a stable effect key. */
internal data class ReelDebug(val timeScale: Float = 1f, val freezeFraction: Float? = null)

/** One face of the stage: a shell showing `label` on `side`. `id` is its composition identity. */
private data class StageFace(val id: Int, val label: CassetteLabel, val side: CassetteSide)

/**
 * The stage's choreography bookkeeping, assigned DURING COMPOSITION when the key changes
 * (PaneStateHolder-style, deliberately not snapshot state) so the outgoing face never shows the
 * new text. [finished] is the one snapshot value: the generation whose move has completed, which
 * recomposes the outgoing face away. No reel position lives here: each face owns its own
 * ([ReelSpin], kept across the key(face.id) loop), and the outgoing face — `live = false`, its
 * effect cancelled in the frame the key changes — stays at the position it was last drawn at.
 */
private class StageState(initial: CassetteLabel) {
    var lastKey: String? = null
    var nextId = 1
    var current = StageFace(0, initial, CassetteSide.A)
    var outgoing: StageFace? = null
    var move: CassetteMove? = null
    var generation = 0
    val finished = mutableIntStateOf(0)
}

/**
 * The stage's rotation settle. [state] is the pure bookkeeping ([OrientationTurnState]), fed in
 * composition. [settle] is the running settle's Animatable, kept so that a rotation landing
 * mid-settle starts from where the shell actually is. Plain fields, like [StageState].
 */
private class StageTurn {
    val state = OrientationTurnState()
    var settle: Animatable<Float, AnimationVector1D>? = null

    /** What is left of the running settle. Read WITHOUT observation, because a read in composition
     *  would recompose the stage's content on every frame of the settle. */
    fun inFlightDeg(): Float = settle?.let { s -> Snapshot.withoutReadObservation { s.value } } ?: 0f
}

@Composable
internal fun CassetteStageImpl(
    palette   : CassettePalette,
    label     : CassetteLabel,
    trackKey  : String?,
    progress  : () -> Float,
    durationMs: Long,
    spinning  : Boolean,
    modifier  : Modifier,
    freeze    : CassetteFreeze?,
    reelDebug : ReelDebug?,
) {
    val choreographer = remember { CassetteChoreographer() }
    val state = remember { StageState(label) }

    // ── Key tracking, in composition. The first key and null → key are not changes. ──────────
    if (trackKey != state.lastKey) {
        val previous = state.lastKey
        state.lastKey = trackKey
        if (previous != null && trackKey != null) {
            val move = choreographer.advance()
            // A move still running is snapped to its end: its incoming face becomes the outgoing
            // one at rest, and the new move starts from 0 on a FRESH Animatable (below).
            state.outgoing = state.current
            state.current = StageFace(state.nextId++, label, choreographer.side)
            state.move = move
            state.generation++
        } else if (label != state.current.label) {
            state.current = state.current.copy(label = label)
        }
    } else if (label != state.current.label) {
        state.current = state.current.copy(label = label)   // same track, e.g. the copyright arrived
    }

    val generation = state.generation
    val move = state.move
    val anim = remember(generation) { Animatable(0f) }
    LaunchedEffect(generation) {
        if (move == null) return@LaunchedEffect
        val total = if (move == CassetteMove.FLIP) CassetteTiming.FlipMs else EjectTotalMs
        if (freeze != null && freeze.move == move) {
            anim.snapTo(freeze.fraction.coerceIn(0f, 1f) * total)
            return@LaunchedEffect
        }
        anim.animateTo(total.toFloat(), tween(durationMillis = total, easing = LinearEasing))
        state.finished.intValue = generation
    }
    val animating = move != null && state.finished.intValue != generation
    val outgoing = state.outgoing
    val faces = if (animating && outgoing != null) listOf(outgoing, state.current) else listOf(state.current)

    BoxWithConstraints(
        modifier.fillMaxSize().clipToBounds().background(palette.background),
        contentAlignment = Alignment.Center,
    ) {
        val fit = cassetteFit(maxWidth.value, maxHeight.value)
        // The eject must clear the STAGE (it clips), letterbox included — in px, the unit of the
        // face layer's own size.height (= the shell's short side) that the travel is built from.
        val stageWPx = constraints.maxWidth.toFloat()
        val stageHPx = constraints.maxHeight.toFloat()
        val ejectBandPx = ejectBand(stageWPx, stageHPx, cassetteFit(stageWPx, stageHPx).short, fit.portrait)

        // ── Rotation settle (the header's rotation row). Reading LocalConfiguration SUBSCRIBES the
        // stage to every rotation: each one dispatches a new Configuration (a 180° turn-over changes
        // nothing in it but the window configuration's rotation, which Configuration.updateFrom
        // reports, so Compose provides a new one), and the stage recomposes and re-reads the display
        // rotation. The rotation is read FRESH at every composition, never remembered on the
        // configuration: Display.getRotation() on the Activity's display reads the Activity's
        // resources configuration, which ActivityThread updates synchronously inside
        // ViewRootImpl.performConfigurationChange, before performMeasure, on the resize-message path
        // AND on the path where a relayout of our own hands back the new configuration
        // mid-traversal, so it is never older than this pass's constraints. LocalConfiguration is not
        // like that: it lags until its provider recomposes, one pass later on the relayout path. A
        // rotation remembered on it paired the NEW constraints with the OLD rotation in that measure
        // pass and corrupted the bookkeeping (review, 2026-09-26). The bookkeeping's orientation and
        // smallest width come from that same Configuration, never from `fit` (the constraints);
        // OrientationTurnState's KDoc says why that order is safe. The fit still decides what is
        // DRAWN (the layer below).
        val configuration = LocalConfiguration.current
        val rotation = ContextCompat.getDisplayOrDefault(LocalContext.current).rotation
        val stageTurn = remember { StageTurn() }
        stageTurn.state.update(
            rotation        = rotation,
            configPortrait  = configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
            smallestWidthDp = configuration.smallestScreenWidthDp,
            inFlightDeg     = stageTurn::inFlightDeg,
        )
        val turnGeneration = stageTurn.state.generation
        val turnFrom = stageTurn.state.from
        // A FRESH Animatable per settle, created AT turnFrom in this very composition, so the first
        // frame of the new rotation already draws the shell where the old frame left it on the glass
        // (the same pattern as the move's `anim` above).
        val turn = remember(turnGeneration) { Animatable(turnFrom) }
        stageTurn.settle = turn
        LaunchedEffect(turnGeneration) {
            if (turnGeneration > 0 && turnFrom != 0f) {
                turn.animateTo(0f, tween(durationMillis = CassetteTiming.OrientationTurnMs, easing = FastOutSlowInEasing))
            }
        }
        val stageW = maxWidth.value
        val stageH = maxHeight.value

        // (long × short) in the natural frame; in portrait the SAME box turned −90° about its
        // centre, overflowing its slot on purpose (requiredSize, centred; only the stage's own
        // bounds clip it). A settle adds its turn and shrinks the shell so its bounding box stays
        // inside the stage; at rest (extra == 0, which a finished settle lands on exactly) the
        // layer is what it always was. settleScale is the ONE copy of that scale: the eject below
        // reads it too.
        Box(
            Modifier
                .requiredSize(fit.long.dp, fit.short.dp)
                .graphicsLayer {
                    val extra = turn.value
                    rotationZ = stageRotationZ(fit.portrait) + extra
                    val k = settleScale(fit.portrait, extra, fit.long, fit.short, stageW, stageH)
                    scaleX = k
                    scaleY = k
                },
        ) {
            for (face in faces) {
                key(face.id) {
                    val incoming = face.id == state.current.id
                    CassetteImpl(
                        palette    = palette,
                        label      = face.label,
                        side       = face.side,
                        progress   = progress,
                        durationMs = durationMs,
                        spinning   = incoming && spinning,
                        live       = incoming,
                        debug      = reelDebug,
                        modifier   = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val t = anim.value
                                when (move) {
                                    CassetteMove.FLIP -> {
                                        // about the SHORT axis (natural y): the head edge stays
                                        // at the bottom and the right reel lands on the left.
                                        // Negated: a positive rotationY brings the natural −x
                                        // (supply) end toward the viewer (emulator-measured); the
                                        // flip brings the +x end — the TOP of the portrait phone,
                                        // the right end in landscape — toward you instead
                                        cameraDistance = flipCameraDistance(size.width)
                                        val a = flipAngleAt(t)
                                        rotationY = -flipFaceRotation(a, incoming)
                                        // the near end would outgrow a full-bleed short side
                                        val k = flipNearEdgeScale(a)
                                        scaleX = k; scaleY = k
                                        alpha = if (flipShowsIncoming(a) == incoming) 1f else 0f
                                    }
                                    CassetteMove.EJECT -> {
                                        // out through the TITLE edge (natural −y: LEFT in
                                        // portrait, UP in landscape), back in through the same.
                                        // This translation lives INSIDE the stage layer, which a
                                        // rotation settle turns and shrinks: then the travel is
                                        // rebuilt for the turned stage with the layer's own
                                        // scale, so a shell parked outside stays outside and the
                                        // incoming one starts clear. At rest the plain travel,
                                        // bit-identical. turn.value is read here, in the layer
                                        // only: no recomposition.
                                        val e = ejectFrameAt(t)
                                        val extra = turn.value
                                        val travel = if (extra == 0f) {
                                            ejectTravel(size.height, ejectBandPx)
                                        } else {
                                            ejectTravelTurned(
                                                short  = size.height,
                                                zDeg   = stageRotationZ(fit.portrait) + extra,
                                                k      = settleScale(fit.portrait, extra, fit.long, fit.short, stageW, stageH),
                                                stageW = stageWPx,
                                                stageH = stageHPx,
                                            )
                                        }
                                        translationY = -(if (incoming) e.incomingShift else e.outgoingShift) * travel
                                        if (incoming) { scaleX = e.incomingScale; scaleY = e.incomingScale }
                                    }
                                    null -> Unit
                                }
                            },
                    )
                }
            }
        }
    }
}
