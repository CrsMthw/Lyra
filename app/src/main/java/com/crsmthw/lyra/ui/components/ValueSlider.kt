package com.crsmthw.lyra.ui.components

import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * The value-driven `Slider` that Material3 1.5.0-alpha28 deprecated, rebuilt over the surviving
 * `SliderState` overload. The state is remembered per ([steps], [valueRange]), RE-SYNCED from
 * [value] on every composition, and the two callbacks pass straight through — exactly what the
 * deprecated overload did internally (`Slider.kt`: `remember(steps, valueRange) { SliderState(…) }`
 * then `state.value = value`), so nothing observable changes at the five call sites:
 *
 * - the two seek bars (`PlayerScreen`, `PlayerCardContent`) keep their hoisted scrub pattern —
 *   `value = if (isDragging) dragValue else state.progress`, `onValueChangeFinished` seeks;
 * - the device volume slider and the two Settings visualizer sliders keep their hoisted `Float`.
 *
 * Writing `state.value` during composition is the library's own idiom for this overload (a snapshot
 * write of an unchanged value does not invalidate). If a slider is ever rebuilt state-first, do it
 * at the call site with `rememberSliderState`; this wrapper exists so the migration is ONE place.
 */
@Composable
fun ValueSlider(
    value                 : Float,
    onValueChange         : (Float) -> Unit,
    modifier              : Modifier = Modifier,
    enabled               : Boolean = true,
    valueRange            : ClosedFloatingPointRange<Float> = 0f..1f,
    steps                 : Int = 0,
    onValueChangeFinished : (() -> Unit)? = null,
    colors                : SliderColors = SliderDefaults.colors(),
) {
    val state = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
    state.value = value
    Slider(
        state                 = state,
        modifier              = modifier,
        enabled               = enabled,
        onValueChange         = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors                = colors,
    )
}
