package com.crsmthw.lyra.util.visualizer

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

val LocalFftData: ProvidableCompositionLocal<StateFlow<ByteArray?>> =
    staticCompositionLocalOf { MutableStateFlow(null) }

// Dynamic (not static) so only FftWaveCanvas readers recompose when color animates
val LocalVisualizerAccentColor: ProvidableCompositionLocal<Color> =
    compositionLocalOf { Color.Unspecified }

// Whether the bottom wave should render. Provided once in LyraNavGraph from the
// master toggle + the chosen VisualizerStyle, so every FftWaveCanvas across all
// screens (which read LocalFftData) is gated uniformly without per-call-site wiring.
// Dynamic so only FftWaveCanvas readers recompose when the setting flips.
val LocalVisualizerBottomEnabled: ProvidableCompositionLocal<Boolean> =
    compositionLocalOf { true }

@Composable
fun FftWaveCanvas(
    modifier : Modifier = Modifier,
    color    : Color    = Color.White,
    alpha    : Float    = 0.25f,
) {
    // Bottom wave disabled (style is CIRCLE only, or visualizer off): keep the same
    // layout footprint via the caller's modifier but skip the data subscription,
    // per-frame loop, and draw entirely.
    if (!LocalVisualizerBottomEnabled.current) {
        Spacer(modifier = modifier)
        return
    }

    val fftBytes by LocalFftData.current.collectAsStateWithLifecycle(null)
    val painter  = remember { FftWavePainter() }
    val paint    = remember(color, alpha) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            this.color = color.copy(alpha = alpha).toArgb()
            style = AndroidPaint.Style.FILL
        }
    }
    val renderTick = remember { mutableLongStateOf(0L) }

    LaunchedEffect(fftBytes) {
        fftBytes?.let { painter.setFftData(it) }
    }

    LaunchedEffect(Unit) {
        var wasActive = false
        while (isActive) {
            val ms = withFrameMillis { it }
            painter.tickGravity()
            if (painter.isActive || wasActive) renderTick.longValue = ms
            wasActive = painter.isActive
        }
    }

    Spacer(modifier = modifier.clipToBounds().drawBehind {
        renderTick.longValue  // subscribe to draw-phase frame ticks
        drawIntoCanvas { canvas ->
            painter.draw(canvas.nativeCanvas, paint, size.width, size.height)
        }
    })
}

@Composable
fun FftCWaveCanvas(
    modifier : Modifier = Modifier,
    color    : Color    = Color.White,
    alpha    : Float    = 0.35f,
    enabled  : Boolean  = true,
) {
    val fftBytes   by LocalFftData.current.collectAsStateWithLifecycle(null)
    val painter    = remember { FftCWavePainter() }
    val paint      = remember(color, alpha) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            this.color = color.copy(alpha = alpha).toArgb()
            style = AndroidPaint.Style.FILL
        }
    }
    val renderTick  = remember { mutableLongStateOf(0L) }
    val enabledState = rememberUpdatedState(enabled)

    LaunchedEffect(fftBytes) {
        fftBytes?.let { painter.setFftData(it) }
    }

    LaunchedEffect(Unit) {
        var wasActive = false
        while (isActive) {
            val ms = withFrameMillis { it }
            painter.tickGravity()
            // Always render when enabled so the static base disc stays visible;
            // fall back to wasActive pattern when disabled (disc fades out with gravity).
            if (enabledState.value || painter.isActive || wasActive) renderTick.longValue = ms
            wasActive = painter.isActive
        }
    }

    Spacer(modifier = modifier.drawBehind {
        renderTick.longValue  // subscribe to draw-phase frame ticks
        drawIntoCanvas { canvas ->
            painter.draw(canvas.nativeCanvas, paint, size.width, size.height, enabledState.value)
        }
    })
}
