package com.crsmthw.lyra.ui.ilyra.wheel

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
    /**
     * The button was HELD past the wheel's long-press threshold without travel (Checkpoint D,
     * 2026-09-21). Emitted once, while the finger is still down, with the `longPress()` haptic; the
     * release that follows emits NO [Press]. The Classic's hold-the-centre-button gesture — over Now
     * Playing it opens the song's options menu. Implemented for the CENTRE button only for now.
     */
    data class LongPress(val button: WheelButton) : WheelEvent
}
