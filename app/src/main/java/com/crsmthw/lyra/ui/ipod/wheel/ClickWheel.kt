package com.crsmthw.lyra.ui.ipod.wheel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.ipod.IPodColors
import com.crsmthw.lyra.ui.ipod.IPodDimens
import com.crsmthw.lyra.ui.ipod.IPodFontFamily
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.scrollTick
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

// ── Tunable constants — all meant to be adjusted on the Fold 8 ──────────

/** Base angular distance (degrees) the finger must travel for one detent tick at low speed. */
private const val BASE_DETENT_DEG = 12f

/** Minimum detent size (degrees) at maximum scrolling velocity. */
private const val MIN_DETENT_DEG = 5f

/** Angular velocity (deg/s) below which the detent stays at BASE_DETENT_DEG. */
private const val VEL_LOW_THRESHOLD = 300f

/** Angular velocity (deg/s) above which the detent is clamped at MIN_DETENT_DEG. */
private const val VEL_HIGH_THRESHOLD = 900f

/** Angular velocity (deg/s) above which each detent emits Scroll(+/-2) instead of +/-1. */
private const val BATCH_VELOCITY = 900f

/** Maximum milliseconds from down to up for a tap to register as a button press. */
private const val TAP_MAX_MS = 350L

/** Maximum angular travel (degrees) from down to up for a tap classification. */
private const val TAP_MAX_TRAVEL_DEG = 6f

/** Minimum milliseconds between consecutive tick feedback events (haptic + sound). */
private const val MIN_TICK_INTERVAL_MS = 25L

/** EMA smoothing factor for angular velocity (0-1; higher = more responsive). */
private const val VELOCITY_ALPHA = 0.3f

/** Size of the transport icon glyphs as a fraction of the wheel diameter. */
private const val ICON_SIZE_FRACTION = 0.07f

/** Radial position of the labels (fraction of the wheel radius from centre). */
private const val LABEL_RADIUS_FRACTION = 0.78f

/** Width of the wheel's outer edge stroke in density-independent pixels. */
private const val WHEEL_EDGE_STROKE_DP = 1.2f

/** Width of the centre button edge stroke in density-independent pixels. */
private const val CENTER_EDGE_STROKE_DP = 1f

/** Alpha for the faint inner shadow at the wheel's outer rim. */
private const val INNER_SHADOW_ALPHA = 0.12f

/** Width of the inner shadow ring in density-independent pixels. */
private const val INNER_SHADOW_WIDTH_DP = 3f

/**
 * The click wheel: drawn in Compose (ring, four labels, centre button) and driven by a rotary
 * pointer gesture around the ring's centre. A drag around the ring emits [WheelEvent.Scroll]
 * per detent (clockwise = positive), with detents shrinking as angular velocity rises; a tap on
 * the ring emits the button under it; a tap in the centre emits SELECT. The wheel fires its own
 * feedback from the gesture lambdas: `scrollTick()` + [ClickSounds.tick] per detent, `press()`
 * for MENU/PREVIOUS/NEXT/PLAY_PAUSE, `confirm()` + [ClickSounds.select] for SELECT.
 *
 * The composable is square; its size is decided by the caller (IPodRoot).
 */
@Composable
fun ClickWheel(
    /** Returns whether the event MOVED something (highlight, scrub); detent feedback fires only then. */
    onEvent: (WheelEvent) -> Boolean,
    sounds: ClickSounds?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Capture latest callback refs so the pointerInput(enabled) lambda never goes stale.
    val onEventState = rememberUpdatedState(onEvent)
    val soundsState = rememberUpdatedState(sounds)
    val enabledState = rememberUpdatedState(enabled)

    val haptics = LocalHapticFeedback.current
    val cd = stringResource(R.string.ipod_cd_click_wheel)
    val menuLabel = stringResource(R.string.ipod_wheel_menu)

    // Which sector is pressed: null = nothing, -1 = centre, 0-3 = MENU/NEXT/PLAY_PAUSE/PREVIOUS.
    val pressedSector = remember { mutableStateOf<Int?>(null) }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val wheelEdgeStrokePx = with(density) { WHEEL_EDGE_STROKE_DP.dp.toPx() }
    val centerEdgeStrokePx = with(density) { CENTER_EDGE_STROKE_DP.dp.toPx() }
    val innerShadowWidthPx = with(density) { INNER_SHADOW_WIDTH_DP.dp.toPx() }
    // Divide by both screen density and font scale so the label is pixel-proportional to the wheel.
    val spDivisor = density.density * density.fontScale

    Canvas(
        modifier = modifier
            .semantics { contentDescription = cd }
            .fillMaxSize()
            .pointerInput(Unit) {
                wheelGestureLoop(
                    enabledState = enabledState,
                    onEventState = onEventState,
                    soundsState = soundsState,
                    haptics = haptics,
                    pressedSector = pressedSector,
                )
            }
    ) {
        drawWheel(
            menuLabel = menuLabel,
            textMeasurer = textMeasurer,
            pressedSector = pressedSector.value,
            wheelEdgeStrokePx = wheelEdgeStrokePx,
            centerEdgeStrokePx = centerEdgeStrokePx,
            innerShadowWidthPx = innerShadowWidthPx,
            spDivisor = spDivisor,
        )
    }
}

// ── Drawing ──────────────────────────────────────────────────────────────

private fun DrawScope.drawWheel(
    menuLabel: String,
    textMeasurer: TextMeasurer,
    pressedSector: Int?,
    wheelEdgeStrokePx: Float,
    centerEdgeStrokePx: Float,
    innerShadowWidthPx: Float,
    spDivisor: Float,
) {
    val diameter = min(size.width, size.height)
    val radius = diameter / 2f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val centre = Offset(cx, cy)
    // CenterButtonFraction is diameter/diameter (0.38), so multiply by radius directly to get the
    // centre button's radius.  Round A halved again and shipped a button at half the correct size.
    val centreRadius = radius * IPodDimens.CenterButtonFraction

    // ── 1. Wheel ring: radial gradient top -> bottom ───────────────────
    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(IPodColors.WheelTop, IPodColors.WheelBottom),
            startY = cy - radius,
            endY = cy + radius,
        ),
        radius = radius,
        center = centre,
    )

    // ── 2. Outer edge stroke ────────────────────────────────────────
    drawCircle(
        color = IPodColors.WheelEdge,
        radius = radius - wheelEdgeStrokePx / 2f,
        center = centre,
        style = Stroke(width = wheelEdgeStrokePx),
    )

    // ── 3. Faint inner shadow at the outer rim ──────────────────────
    // Derive from WheelEdge (not a hardcoded colour).
    drawCircle(
        color = IPodColors.WheelEdge.copy(alpha = INNER_SHADOW_ALPHA),
        radius = radius - innerShadowWidthPx / 2f,
        center = centre,
        style = Stroke(width = innerShadowWidthPx),
    )

    // ── 4. Pressed wedge overlay ────────────────────────────────────
    if (pressedSector != null && pressedSector >= 0) {
        // Sector angles: MENU=0 (top, 315-45 deg), NEXT=1 (right, 45-135 deg),
        // PLAY_PAUSE=2 (bottom, 135-225 deg), PREVIOUS=3 (left, 225-315 deg).
        // drawArc's 0 deg is at 3 o'clock, so subtract 90 to convert from our wheel coords.
        val arcStartWheel = pressedSector * 90f - 45f  // wheel coords
        val arcStartCanvas = arcStartWheel - 90f       // canvas coords (0 deg = 3 o'clock)
        drawArc(
            color = IPodColors.WheelPressed,
            startAngle = arcStartCanvas,
            sweepAngle = 90f,
            useCenter = true,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(diameter, diameter),
        )
    }

    // ── 5. Centre button (covers the wedge's inner tip) ─────────────
    if (pressedSector == SECTOR_CENTER) {
        drawCircle(
            color = IPodColors.WheelPressed,
            radius = centreRadius,
            center = centre,
        )
    }
    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(IPodColors.CenterTop, IPodColors.CenterBottom),
            startY = cy - centreRadius,
            endY = cy + centreRadius,
        ),
        radius = centreRadius,
        center = centre,
    )
    drawCircle(
        color = IPodColors.CenterEdge,
        radius = centreRadius - centerEdgeStrokePx / 2f,
        center = centre,
        style = Stroke(width = centerEdgeStrokePx),
    )

    // ── 6. Labels ───────────────────────────────────────────────────
    val labelDist = radius * LABEL_RADIUS_FRACTION
    val iconSize = diameter * ICON_SIZE_FRACTION

    // MENU text at 12 o'clock — size is pixel-proportional to the wheel diameter.
    val menuStyle = TextStyle(
        fontFamily = IPodFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (diameter * 0.038f / spDivisor).sp,
        color = IPodColors.WheelLabel,
        textAlign = TextAlign.Center,
        letterSpacing = 1.5.sp,
    )
    val menuResult = textMeasurer.measure(menuLabel, menuStyle)
    drawText(
        textLayoutResult = menuResult,
        topLeft = Offset(
            cx - menuResult.size.width / 2f,
            cy - labelDist - menuResult.size.height / 2f,
        ),
    )

    // Previous (left): two left-pointing triangles + bar
    drawPreviousIcon(cx - labelDist, cy, iconSize)

    // Next (right): two right-pointing triangles + bar
    drawNextIcon(cx + labelDist, cy, iconSize)

    // Play/Pause (bottom): triangle + two bars
    drawPlayPauseIcon(cx, cy + labelDist, iconSize)
}

/** Draw two left-pointing triangles + a bar on the left, centred at (cx, cy). */
private fun DrawScope.drawPreviousIcon(cx: Float, cy: Float, size: Float) {
    val half = size / 2f
    val barW = size * 0.14f
    val triW = half * 0.85f

    val path = Path().apply {
        // Left bar
        addRect(Rect(cx - half, cy - half * 0.7f, cx - half + barW, cy + half * 0.7f))
        // First triangle (pointing left)
        moveTo(cx - half + barW, cy)
        lineTo(cx - half + barW + triW, cy - half * 0.7f)
        lineTo(cx - half + barW + triW, cy + half * 0.7f)
        close()
        // Second triangle
        moveTo(cx - half + barW + triW, cy)
        lineTo(cx - half + barW + triW * 2f, cy - half * 0.7f)
        lineTo(cx - half + barW + triW * 2f, cy + half * 0.7f)
        close()
    }
    drawPath(path, IPodColors.WheelLabel)
}

/** Draw two right-pointing triangles + a bar on the right, centred at (cx, cy). */
private fun DrawScope.drawNextIcon(cx: Float, cy: Float, size: Float) {
    val half = size / 2f
    val barW = size * 0.14f
    val triW = half * 0.85f

    val path = Path().apply {
        // First triangle (pointing right)
        moveTo(cx - half, cy - half * 0.7f)
        lineTo(cx - half, cy + half * 0.7f)
        lineTo(cx - half + triW, cy)
        close()
        // Second triangle
        moveTo(cx - half + triW, cy - half * 0.7f)
        lineTo(cx - half + triW, cy + half * 0.7f)
        lineTo(cx - half + triW * 2f, cy)
        close()
        // Right bar
        addRect(Rect(cx + half - barW, cy - half * 0.7f, cx + half, cy + half * 0.7f))
    }
    drawPath(path, IPodColors.WheelLabel)
}

/** Draw play triangle + pause bars, centred at (cx, cy). */
private fun DrawScope.drawPlayPauseIcon(cx: Float, cy: Float, size: Float) {
    val half = size / 2f
    val barW = size * 0.16f
    val gap = size * 0.10f

    val path = Path().apply {
        // Play triangle (pointing right, left half)
        moveTo(cx - half, cy - half * 0.7f)
        lineTo(cx - half, cy + half * 0.7f)
        lineTo(cx - half + half * 0.85f, cy)
        close()
        // Pause bars (right half)
        val barStart = cx + gap / 2f
        addRect(Rect(barStart, cy - half * 0.7f, barStart + barW, cy + half * 0.7f))
        addRect(Rect(barStart + barW + gap, cy - half * 0.7f, barStart + barW * 2f + gap, cy + half * 0.7f))
    }
    drawPath(path, IPodColors.WheelLabel)
}

// ── Gesture ──────────────────────────────────────────────────────────────

/** Sentinel for the centre button in pressedSector. */
private const val SECTOR_CENTER = -1

/**
 * Compute the angle in degrees from the wheel centre, 0 deg at 12 o'clock, clockwise positive,
 * range [0, 360).
 */
private fun angleFromCenter(dx: Float, dy: Float): Float {
    // atan2(dx, -dy): x-axis rightward, -dy makes up positive -> 0 deg at 12 o'clock, CW positive.
    val deg = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
    return (deg + 360f) % 360f
}

/**
 * The signed shortest angular delta from [from] to [to], in degrees.
 * Result is in (-180, 180]; positive = clockwise.
 */
private fun angleDelta(from: Float, to: Float): Float {
    var d = to - from
    // Wrap to (-180, 180]
    while (d > 180f) d -= 360f
    while (d <= -180f) d += 360f
    return d
}

/** Map angle in [0,360) to a sector index: 0=MENU(top), 1=NEXT(right), 2=PLAY_PAUSE(bottom), 3=PREVIOUS(left). */
private fun sectorForAngle(angle: Float): Int {
    val norm = (angle + 45f) % 360f
    return (norm / 90f).toInt().coerceIn(0, 3)
}

/** Map a sector index (0-3) to the corresponding [WheelButton]. */
private fun buttonForSector(sector: Int): WheelButton = when (sector) {
    0 -> WheelButton.MENU
    1 -> WheelButton.NEXT
    2 -> WheelButton.PLAY_PAUSE
    3 -> WheelButton.PREVIOUS
    else -> WheelButton.MENU // unreachable
}

/**
 * The effective detent size (degrees) at the given angular velocity (deg/s), linearly
 * interpolated between BASE_DETENT_DEG and MIN_DETENT_DEG over the velocity thresholds.
 */
private fun effectiveDetent(velocityDegPerSec: Float): Float {
    val clamped = velocityDegPerSec.coerceIn(VEL_LOW_THRESHOLD, VEL_HIGH_THRESHOLD)
    val t = (clamped - VEL_LOW_THRESHOLD) / (VEL_HIGH_THRESHOLD - VEL_LOW_THRESHOLD)
    return BASE_DETENT_DEG + t * (MIN_DETENT_DEG - BASE_DETENT_DEG)
}

/**
 * The main gesture loop.  Uses [awaitEachGesture] so a cancelled gesture unwinds cleanly and
 * the `finally` block always clears the pressed visual.  Every touch INSIDE the wheel circle
 * is consumed so nothing reaches behind it, even when [enabledState] is false (disabled only
 * suppresses event emission and feedback, not consumption).  Touches in the square's corners
 * (outside the wheel circle) are consumed on down and then the gesture is abandoned.
 *
 * Reads [size] from the [PointerInputScope] on each gesture start, so it stays correct after
 * a fold/unfold that changes the wheel dimensions.
 *
 * ### Drag zone
 *
 * The scrollable ring is `[centreRadius, radius]`.  A pointer that leaves this ring while
 * dragging pauses accumulation (the reference angle is lost); re-entering the ring re-seeds
 * the angle so there is no jump.  A gesture that STARTS in the centre is a SELECT candidate
 * and never scrolls, even if dragged onto the ring.  A gesture that starts outside the wheel
 * circle is consumed and ignored (no button, no scroll).
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.wheelGestureLoop(
    enabledState: androidx.compose.runtime.State<Boolean>,
    onEventState: androidx.compose.runtime.State<(WheelEvent) -> Boolean>,
    soundsState: androidx.compose.runtime.State<ClickSounds?>,
    haptics: HapticFeedback,
    pressedSector: androidx.compose.runtime.MutableState<Int?>,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()

        // Geometry and the enabled flag are read AFTER the touch lands. awaitEachGesture re-enters
        // this block the instant the previous gesture ends and then parks at awaitFirstDown, so
        // anything read above that await describes the world at the END of the last gesture — a
        // fold, or the exit dialog opening and closing in between, would leave the first touch
        // on stale values (a swallowed tap, or folded-screen geometry on the inner display).
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val diameter = min(w, h)
        val radius = diameter / 2f
        val cx = w / 2f
        val cy = h / 2f
        // CenterButtonFraction is diameter/diameter (0.38); multiply by radius for the button radius.
        val centreRadius = radius * IPodDimens.CenterButtonFraction
        val isEnabled = enabledState.value

        val downPos = down.position
        val dxDown = downPos.x - cx
        val dyDown = downPos.y - cy
        val downDist = hypot(dxDown, dyDown)

        // Outside the wheel circle (the square's corners) -> consumed, ignored.
        if (downDist > radius) return@awaitEachGesture

        val trackId: PointerId = down.id
        val downTimeMs = down.uptimeMillis
        val isCenter = downDist < centreRadius
        val downAngle = angleFromCenter(dxDown, dyDown)

        // Show the pressed visual immediately (only when enabled).
        if (isEnabled) {
            pressedSector.value = if (isCenter) SECTOR_CENTER else sectorForAngle(downAngle)
        }

        // Gesture state
        var accumulator = 0f          // partial detent progress (degrees, signed)
        var hasScrolled = false        // once true, the gesture is a scroll to the end
        var totalTravelDeg = 0f        // total angular displacement for tap classification
        // Seed the reference angle only when starting on the ring (not in the centre).
        var lastAngle: Float? = if (!isCenter) downAngle else null
        var lastTimeMs = downTimeMs
        var smoothedVelocity = 0f      // EMA of |angular velocity| in deg/s
        var lastTickTimeMs = 0L        // for the tick feedback floor

        try {
            // Track the pointer through move/up
            while (true) {
                val event = awaitPointerEvent()
                var foundRelease = false
                for (change in event.changes) {
                    if (change.id != trackId) {
                        change.consume() // ignore secondary pointers, consume to block pass-through
                        continue
                    }
                    change.consume()

                    // Disabled: consume everything, emit nothing.
                    if (!isEnabled) continue

                    val pos = change.position
                    val dx = pos.x - cx
                    val dy = pos.y - cy
                    val dist = hypot(dx, dy)

                    if (!change.pressed) {
                        // ── Pointer released ────────────────────────────
                        foundRelease = true

                        if (!hasScrolled) {
                            // Tap classification
                            val elapsed = change.uptimeMillis - downTimeMs
                            if (elapsed <= TAP_MAX_MS && totalTravelDeg < TAP_MAX_TRAVEL_DEG) {
                                if (isCenter) {
                                    haptics.confirm()
                                    soundsState.value?.select()
                                    onEventState.value(WheelEvent.Press(WheelButton.SELECT))
                                } else {
                                    // Button is determined by the DOWN angle (not up).
                                    val sector = sectorForAngle(downAngle)
                                    haptics.press()
                                    onEventState.value(WheelEvent.Press(buttonForSector(sector)))
                                }
                            }
                        }
                        break
                    }

                    // ── Pointer moved (still down) ──────────────────

                    // A gesture that started in the centre never scrolls.  The pressed-centre
                    // visual stays while the finger wanders onto the ring — accepted, same as
                    // holding the physical centre button.
                    if (isCenter) continue

                    val nowMs = change.uptimeMillis

                    // Outside the ring (inside centre or past the outer edge): pause
                    // accumulation and lose the reference angle.  Re-entering the ring
                    // re-seeds the angle so there is no angular jump.  Velocity is zeroed
                    // so the first delta after re-entry starts at the slow detent size,
                    // not the speed the finger had before it left the ring.
                    if (dist < centreRadius || dist > radius) {
                        lastAngle = null
                        smoothedVelocity = 0f
                        continue
                    }

                    val angle = angleFromCenter(dx, dy)
                    val prev = lastAngle
                    if (prev == null) {
                        // (Re-)entering the ring: seed the reference, update the time, emit nothing.
                        lastAngle = angle
                        lastTimeMs = nowMs
                        continue
                    }

                    val delta = angleDelta(prev, angle)
                    lastAngle = angle
                    totalTravelDeg += abs(delta)

                    // Velocity EMA
                    val dtMs = nowMs - lastTimeMs
                    if (dtMs > 0) {
                        val instantVel = abs(delta) / (dtMs / 1000f)
                        smoothedVelocity = VELOCITY_ALPHA * instantVel +
                            (1f - VELOCITY_ALPHA) * smoothedVelocity
                    }
                    lastTimeMs = nowMs

                    // Accumulate toward detents
                    accumulator += delta
                    val detent = effectiveDetent(smoothedVelocity)
                    val steps = (accumulator / detent).toInt()

                    if (steps != 0) {
                        // Consume the detent(s) from the accumulator
                        accumulator -= steps * detent

                        if (!hasScrolled) {
                            hasScrolled = true
                            // Clear the pressed sector visual on first scroll
                            pressedSector.value = null
                        }

                        // At high velocity each detent emits +/-2 instead of +/-1
                        val batchSize = if (smoothedVelocity >= BATCH_VELOCITY) 2 else 1
                        val sign = if (steps > 0) 1 else -1

                        // Emit scroll events: one per abs(steps), each with batchSize magnitude
                        for (i in 0 until abs(steps)) {
                            val moved = onEventState.value(WheelEvent.Scroll(sign * batchSize))

                            // Tick only when the highlight (or the scrub) actually moved: the Classic's
                            // clicker marks an item change, not the wheel turning, so the ends of a list
                            // are silent. The floor keeps a fast spin a texture rather than a smear.
                            val now = System.nanoTime() / 1_000_000L
                            if (moved && now - lastTickTimeMs >= MIN_TICK_INTERVAL_MS) {
                                lastTickTimeMs = now
                                haptics.scrollTick()
                                soundsState.value?.tick()
                            }
                        }
                    }
                }
                if (foundRelease) break
            }
        } finally {
            // Always clear the pressed visual, even on a cancelled gesture.
            pressedSector.value = null
        }
    }
}
