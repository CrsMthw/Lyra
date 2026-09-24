package com.crsmthw.lyra.ui.cassette

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
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
 *   hub direction    BOTH hubs ANTICLOCKWISE (natural frame, and so on the portrait screen too):
 *                    the tape runs supply → take-up along the HEAD edge, so each pack's bottom
 *                    moves left → right; the angle fed to `rotate` (clockwise-positive) falls
 *   tape run         ONE run at y 410 along the window's flat bottom, under the packs (the tape
 *                    heading out to the rollers). NO strand between the packs: the ad's diagonal
 *                    is not a real tape path (Cris, 2026-09-23) and was deleted
 *   title box        x 172..828 (656), baseline 118, Playfair 46 → floor 24 (then ellipsis),
 *                    tracking 0.087 em; artist the same box, baseline 154, 27 → 18
 *   meta / SIDE / fine print  baselines 184 / 298 + 332 / 441 + 453.6 (≥ 9.4 units)
 *   head edge        trapezoid 168..832 from y 486; capstans (367|633, 573); pinch rollers
 *                    (272|728, 590) r 27; guide rollers (122|878, 557) r 46; head recess 418..582
 *
 * ── Performance ──────────────────────────────────────────────────────────────────────────────
 * Per frame only the hub angles move (and, once a second, the pack radii). Everything else is
 * recorded into two GraphicsLayers per (size, palette, label, side) in `drawWithCache`; the text
 * is measured in composition (keyed on label, side, strings and pixel size, NOT the palette —
 * colour is applied at draw time), and the hub frame loop runs only while `spinning`.
 */

/** The hub angles (degrees as DrawScope `rotate` takes them — clockwise-positive, so the hubs'
 *  anticlockwise turn makes them fall; see [advanceHubAngle]) and the pack radii the draw last used. The angles are
 *  snapshot state read ONLY in the draw phase (one redraw per frame, no recomposition); the
 *  radii are plain fields the draw writes and the frame loop reads, so `progress` is only ever
 *  called inside the draw block. */
@Stable
private class HubSpin {
    var supplyDeg by mutableFloatStateOf(0f)
    var takeUpDeg by mutableFloatStateOf(0f)
    var supplyR = CassetteGeometry.RMax
    var takeUpR = CassetteGeometry.RMin
}

/**
 * The cassette in its NATURAL orientation: long edge horizontal, head edge (the exposed tape,
 * the capstan holes, the shield) at the BOTTOM, label upright. Fills `modifier`'s bounds, which
 * the CALLER has sized at [CASSETTE_ASPECT] (see [cassetteFit]); nothing here letterboxes.
 *
 * `progress` is a lambda read inside the draw phase so the per-second tick never recomposes the
 * label. `spinning` drives the hubs (a frame clock while true, frozen while false — paused music).
 */
@Composable
fun Cassette(
    palette : CassettePalette,
    label   : CassetteLabel,
    side    : CassetteSide,
    progress: () -> Float,
    spinning: Boolean,
    modifier: Modifier = Modifier,
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
    val spin = remember { HubSpin() }

    // Hubs: ω = v / r per hub, both ANTICLOCKWISE (the tape runs left → right along the head edge). Frozen exactly where they are when `spinning`
    // goes false; on resume the first frame only stamps the clock, so nothing snaps.
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last >= 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    spin.supplyDeg = advanceHubAngle(spin.supplyDeg, spin.supplyR, dt)
                    spin.takeUpDeg = advanceHubAngle(spin.takeUpDeg, spin.takeUpR, dt)
                }
                last = now
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
                        val radii = packRadii(progress())
                        spin.supplyR = radii.supply
                        spin.takeUpR = radii.takeUp
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
 * and shows a change of `trackKey` as a FLIP or an EJECT per [CassetteChoreographer] (`forward`
 * is the direction of THAT change, sampled when the key changes). The outgoing face keeps the
 * label it had; the incoming face gets the new one.
 */
@Composable
fun CassetteStage(
    palette : CassettePalette,
    label   : CassetteLabel,
    trackKey: String?,
    forward : Boolean,
    progress: () -> Float,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) = CassetteStageImpl(palette, label, trackKey, forward, progress, spinning, modifier, freeze = null)

/** DEBUG ONLY (the src/debug preview): hold a [move] at `fraction` of its timeline instead of
 *  playing it, so a screenshot can catch a mid-flip / mid-eject frame. Production passes null. */
internal data class CassetteFreeze(val move: CassetteMove, val fraction: Float)

/** One face of the stage: a shell showing `label` on `side`. `id` is its composition identity. */
private data class StageFace(val id: Int, val label: CassetteLabel, val side: CassetteSide)

/**
 * The stage's choreography bookkeeping, assigned DURING COMPOSITION when the key changes
 * (PaneStateHolder-style, deliberately not snapshot state) so the outgoing face never shows the
 * new text. [finished] is the one snapshot value: the generation whose move has completed, which
 * recomposes the outgoing face away.
 */
private class StageState(initial: CassetteLabel) {
    var lastKey: String? = null
    var nextId = 1
    var current = StageFace(0, initial, CassetteSide.A)
    var outgoing: StageFace? = null
    var outgoingProgress = 0f
    var move: CassetteMove? = null
    var forward = true
    var generation = 0
    /** Written by the current face's draw: what the outgoing face freezes at. */
    var lastProgress = 0f
    val finished = mutableIntStateOf(0)
}

@Composable
internal fun CassetteStageImpl(
    palette : CassettePalette,
    label   : CassetteLabel,
    trackKey: String?,
    forward : Boolean,
    progress: () -> Float,
    spinning: Boolean,
    modifier: Modifier,
    freeze  : CassetteFreeze?,
) {
    val choreographer = remember { CassetteChoreographer() }
    val state = remember { StageState(label) }

    // ── Key tracking, in composition. The first key and null → key are not changes. ──────────
    if (trackKey != state.lastKey) {
        val previous = state.lastKey
        state.lastKey = trackKey
        if (previous != null && trackKey != null) {
            val move = choreographer.advance(forward)
            // A move still running is snapped to its end: its incoming face becomes the outgoing
            // one at rest, and the new move starts from 0 on a FRESH Animatable (below).
            state.outgoing = state.current
            state.outgoingProgress = state.lastProgress
            state.current = StageFace(state.nextId++, label, choreographer.side)
            state.move = move
            state.forward = forward
            state.generation++
        } else if (label != state.current.label) {
            state.current = state.current.copy(label = label)
        }
    } else if (label != state.current.label) {
        state.current = state.current.copy(label = label)   // same track, e.g. the copyright arrived
    }

    val generation = state.generation
    val move = state.move
    val flipForward = state.forward
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

    val liveProgress = remember(progress, state) { { progress().also { state.lastProgress = it } } }
    val frozenProgress = remember(generation) { val v = state.outgoingProgress; { v } }

    BoxWithConstraints(
        modifier.fillMaxSize().clipToBounds().background(palette.background),
        contentAlignment = Alignment.Center,
    ) {
        val fit = cassetteFit(maxWidth.value, maxHeight.value)
        // (long × short) in the natural frame; in portrait the SAME box turned −90° about its
        // centre, overflowing its slot on purpose (requiredSize, centred, nothing clips it).
        Box(
            Modifier
                .requiredSize(fit.long.dp, fit.short.dp)
                .graphicsLayer { rotationZ = stageRotationZ(fit.portrait) },
        ) {
            for (face in faces) {
                key(face.id) {
                    val incoming = face.id == state.current.id
                    Cassette(
                        palette  = palette,
                        label    = face.label,
                        side     = face.side,
                        progress = if (incoming) liveProgress else frozenProgress,
                        spinning = incoming && spinning,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val t = anim.value
                                when (move) {
                                    CassetteMove.FLIP -> {
                                        cameraDistance = flipCameraDistance(size.height)
                                        val a = flipAngleAt(t, flipForward)
                                        rotationX = flipFaceRotation(a, incoming)
                                        alpha = if (flipShowsIncoming(a) == incoming) 1f else 0f
                                    }
                                    CassetteMove.EJECT -> {
                                        val e = ejectFrameAt(t)
                                        translationX = (if (incoming) e.incomingShift else e.outgoingShift) * size.width
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

/**
 * The flip's camera distance for a shell whose SHORT side is `shortSidePx`: 12 half-short-sides
 * away, so the near edge grows at most ~8 % at 65° whatever the screen. The layer's camera
 * distance is in the platform camera's units of 72 px (the usual `12 × density` idiom was
 * measured on the emulator: it put the camera ~2 400 px from a 1 248 px-wide shell, and the near
 * edge ran off the cover screen by a third).
 */
internal fun flipCameraDistance(shortSidePx: Float): Float = FlipCameraHalfHeights * (shortSidePx / 2f) / 72f

internal const val FlipCameraHalfHeights = 12f
