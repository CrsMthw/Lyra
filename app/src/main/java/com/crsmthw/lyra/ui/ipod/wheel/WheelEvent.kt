package com.crsmthw.lyra.ui.ipod.wheel

/** The five physical controls of the Classic's click wheel. */
enum class WheelButton { MENU, SELECT, PREVIOUS, NEXT, PLAY_PAUSE }

/** What the click wheel reports. The wheel owns its own feedback (haptic tick + click sound). */
sealed interface WheelEvent {
    /**
     * The wheel crossed [steps] detents. Positive = clockwise = down / forward through a list;
     * negative = counter-clockwise = up / back. |steps| > 1 only when the wheel batches detents
     * at speed — the consumer applies the whole delta.
     */
    data class Scroll(val steps: Int) : WheelEvent
    data class Press(val button: WheelButton) : WheelEvent
}
