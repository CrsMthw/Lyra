package com.crsmthw.lyra.ui.cassette

import android.view.accessibility.AccessibilityManager
import android.view.Window
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.crsmthw.lyra.R
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.screenTransitionSpec
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * The full player's cassette idle overlay (docs/CASSETTE.md). PlayerScreen decides WHEN it is up
 * (the idle gate); this file owns everything that happens WHILE it is up: the immersive window,
 * keep-screen-on, the dim, the burn-in drift, the gestures, the back rule, the ON_STOP exit and
 * the "Double-tap to exit" hint chip. The painting itself is `CassetteStage` (Cassette.kt).
 *
 * Every window-level change is keyed on `visible` and undone in `onDispose`, so an exit — or the
 * player leaving composition with the cassette up — always hands back the bars, the cutout mode,
 * the rotation animation, the orientation lock, the brightness and the screen timeout.
 */

/** No label yet (the feed has not emitted) — the stage still draws a blank label. */
private val EmptyLabel = CassetteLabel(title = "", artist = "")

/**
 * The window's rotation animation while the cassette is up: a JUMP CUT, the old frame giving way to
 * the new one with no system turn (2026-09-26). Since 2026-09-27 the window is also LOCKED in place
 * while the cassette is up ([CassetteOverlay]'s window effect), so on a phone no rotation happens at
 * all; this attribute and the stage's own settle are what a LARGE screen gets, where Android ignores
 * an app's orientation request.
 *
 * The default, ROTATE, turns a screenshot of the old frame by the quarter turn while the new frame
 * turns in. Turning the phone CLOCKWISE out of portrait leaves the shell exactly where it was on the
 * glass (the head edge stays on the same physical side), yet ROTATE still spun it a quarter turn
 * and back (Cris). With a jump cut, the stage draws its first new frame where the old one left the
 * shell, so the swap cannot be seen. A rotation that DOES flip the image by 180° is played by the
 * stage's own settle (Cassette.kt, the header's rotation row).
 *
 * Why not ROTATION_ANIMATION_SEAMLESS, the camera-app mode, which looks the same when it is honoured.
 * Under shell transitions (Android 14+, so the Fold) a SEAMLESS request that cannot be honoured falls
 * back to ROTATE. Its documented CROSSFADE fallback is the legacy DisplayRotation path.
 * WMShell's DefaultTransitionHandler.getRotationAnimationHint starts its hint at ROTATE and leaves
 * it there for a task that asks for SEAMLESS. It rejects seamless when a system-alert window is
 * shown, when the nav bar cannot change sides (a ≥ 600 dp screen, unless the device allows it), and
 * for the upside-down rotation. JUMPCUT is passed through by the server
 * (Transition.getTaskRotationAnimation) and skips the shell's rotation animation with no
 * conditions. On the legacy path it is rotation_animation_jump_exit. Read in AOSP main, 2026-09-26;
 * an OEM shell may differ. SEAMLESS is the one-line alternative.
 */
internal const val CassetteRotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT

/** The burn-in drift's fixed ring: eight points around the origin, [CassetteTiming.DriftMaxPx] out. */
private val DriftRing: List<IntOffset> = CassetteTiming.DriftMaxPx.roundToInt().let { d ->
    listOf(
        IntOffset(-d, -d), IntOffset(0, -d), IntOffset(d, -d), IntOffset(d, 0),
        IntOffset(d, d), IntOffset(0, d), IntOffset(-d, d), IntOffset(-d, 0),
    )
}

/** The drift's own glide — a finite tween, so a 2 px move reads as settling, not jumping. */
private const val DriftGlideMs = 1_000

/**
 * @param visible   the idle gate's verdict; the overlay fades + scales in / fades out on it.
 * @param seed      the palette seed colour PlayerScreen picked from the colour source. It slides
 *                  (800 ms, like the player's own accent) when the track changes the album colour.
 * @param trackKey  the current item's id — a change is shown as a flip / eject by the stage.
 * @param progress  playback progress 0..1, sampled by the stage's reel tracker (a snapshotFlow) —
 *                  never read in composition.
 * @param durationMs the current item's length in ms; 0 = unknown, and the reels then never wind.
 *                  With it, a position jump (a seek elsewhere, a repeat-one wrap) winds the reels.
 * @param isPlaying drives the hubs and the keep-screen-on hold (paused = frozen hubs, screen may sleep).
 * @param onExit    called on every exit this overlay detects (double-tap, back, ON_STOP, TalkBack).
 */
@Composable
fun CassetteOverlay(
    visible   : Boolean,
    settings  : CassetteSettings,
    seed      : Color,
    label     : CassetteLabel?,
    trackKey  : String?,
    progress  : () -> Float,
    durationMs: Long,
    isPlaying : Boolean,
    onExit    : () -> Unit,
    modifier  : Modifier = Modifier,
) {
    val activity = LocalActivity.current
    val window   = activity?.window
    val view    = LocalView.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // ── Immersive window: hidden bars (swipe reveals them transiently) + the cutout + the lock ──
    // iLyra's recipe (ILyraRoot), with the orientation LOCKED IN PLACE rather than to portrait
    // (Cris, 2026-09-27): whatever rotation the window is in when the cassette comes up is the one
    // it keeps until the cassette exits — an old Walkman's tape does not turn in its player, it is
    // upright or upside down by how you hold it — so on a phone no rotation, no cut and no hub
    // hitch ever happens while the cassette is up, however the phone is waved to the beat. Lock to
    // the CURRENT rotation, never to portrait: a portrait lock would rotate a landscape player at the
    // very moment the cassette slides in. A LARGE screen (sw ≥ 600 dp — the unfolded Fold, a tablet)
    // ignores an app's orientation request under targetSdk 36+, so there the cassette still follows
    // rotation, with the jump cut (CassetteRotationAnimation) and the stage's own settle. The
    // cutout mode, the rotation animation and the orientation are restored to what they WERE, not
    // blindly to the defaults; one attributes write each way. The lock outlives a fold/unfold (same
    // Activity, no recreation); LOCKED resolves to "the last rotation" whenever WindowManager next
    // decides, so folding back keeps the cover screen where it was.
    DisposableEffect(visible, window) {
        if (!visible || window == null || activity == null) return@DisposableEffect onDispose { }
        val previousCutoutMode = window.attributes.layoutInDisplayCutoutMode
        val previousRotationAnimation = window.attributes.rotationAnimation
        val previousOrientation = activity.requestedOrientation
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            it.rotationAnimation = CassetteRotationAnimation
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = previousCutoutMode
                it.rotationAnimation = previousRotationAnimation
            }
            activity.requestedOrientation = previousOrientation
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Lyra's window has focus. Lost in split-screen / a pop-up window while the user works in the
    // other app, and when the notification shade is pulled down. It is NOT an exit (pulling the
    // shade down must not eject the cassette), but both window-wide holds below need it: a visible
    // window's keep-on holds the WHOLE display awake, and WindowManager takes the screen-brightness
    // override from any visible window, so an unfocused cassette would dim the other app too.
    val focused = LocalWindowInfo.current.isWindowFocused

    // ── Keep screen on: only while up AND music is playing (a paused cassette may time out) ──
    val keepOn = visible && focused && settings.keepScreenOn && isPlaying
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    // ── Dim: sub-setting of keep-screen-on. Every touch undims and restarts the countdown ────
    // Losing focus restores the brightness at once; regaining it restarts the full countdown.
    val dimEnabled = visible && focused && settings.keepScreenOn && settings.dimAfterDelay
    var touchGeneration by remember { mutableIntStateOf(0) }
    val dimState = remember { DimState() }
    LaunchedEffect(dimEnabled, touchGeneration, window) {
        if (window == null) return@LaunchedEffect
        dimState.restore(window)
        if (!dimEnabled) return@LaunchedEffect
        delay(CassetteTiming.DimDelayMs)
        dimState.dim(window)
    }
    DisposableEffect(window) { onDispose { window?.let(dimState::restoreAlways) } }

    // ── Exits the overlay detects on its own ──────────────────────────────────────────────────
    if (visible) {
        // Composed only while up, so it is the most recently registered back handler and outranks
        // NavHost's pop (the pop-out host's handler is disabled on this route).
        // A plain exit: in gesture navigation the SYSTEM consumes the first edge swipe to reveal the
        // hidden bars (nothing reaches the app), so the back that arrives here is already the second
        // swipe; in 3-button navigation the bar was revealed to tap it. See CassetteRules.kt.
        BackHandler { onExit() }
        // Leaving the app or turning the screen off ends the idle session.
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onExit() }
    }

    val currentOnExit   by rememberUpdatedState(onExit)
    val currentShowHint by rememberUpdatedState(settings.showExitHint)
    val exitLabel   = stringResource(R.string.cassette_exit_action)
    val overlayDesc = stringResource(R.string.cd_cassette_overlay)

    AnimatedVisibility(
        visible  = visible,
        enter    = fadeIn(screenTransitionSpec()) + scaleIn(screenTransitionSpec(), initialScale = 0.96f),
        exit     = fadeOut(screenTransitionSpec()),
        modifier = modifier.fillMaxSize(),
    ) {
        // Session-scoped state: a fresh hint and drift every time the cassette comes up.
        var hintGeneration by remember { mutableIntStateOf(0) }
        var hintVisible    by remember { mutableStateOf(false) }
        LaunchedEffect(hintGeneration) {
            if (hintGeneration == 0) return@LaunchedEffect
            hintVisible = true
            delay(CassetteTiming.HintVisibleMs)
            hintVisible = false
        }

        val drift = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
        LaunchedEffect(Unit) {
            var i = 0
            while (true) {
                delay(CassetteTiming.DriftPeriodMs)
                drift.animateTo(DriftRing[i], tween(DriftGlideMs))
                i = (i + 1) % DriftRing.size
            }
        }

        // A transient null item (PlayerScreen waits one poll before it exits for that) keeps the
        // last label and key, so the stage neither blanks its label nor plays a flip / eject for
        // "no track" and another one back. Plain fields written after each composition: read only
        // on a pass where the incoming value is null, i.e. the pass that change itself caused.
        // The POSITION is held by PlayerScreen (ReelItemHold), not here: `trackKey` is null for a
        // local file's whole track and `label` is a separate flow, so neither says "the item is
        // gone" in the same snapshot as the position.
        val held = remember { HeldItem() }
        SideEffect {
            if (label != null) held.label = label
            if (trackKey != null) held.trackKey = trackKey
        }
        val shownLabel    = label ?: held.label
        val shownTrackKey = trackKey ?: held.trackKey

        val animatedSeed by animateColorAsState(seed, tween(800), label = "cassetteSeed")
        val palette = remember(animatedSeed) { CassettePalette.from(animatedSeed) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics {
                    contentDescription = overlayDesc
                    onClick(label = exitLabel) { currentOnExit(); true }
                }
                // While the exit fade runs the overlay stops taking touches, so the player under it
                // answers at once instead of after the 300 ms fade.
                .then(
                    if (visible) Modifier
                        // Observes every down WITHOUT consuming it: undims + restarts the dim countdown.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitPointerEvent(PointerEventPass.Initial)
                                touchGeneration++
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { if (currentShowHint) hintGeneration++ },
                                onDoubleTap = {
                                    haptics.confirm()
                                    currentOnExit()
                                },
                            )
                        }
                    else Modifier
                ),
        ) {
            CassetteStage(
                palette    = palette,
                label      = shownLabel ?: EmptyLabel,
                trackKey   = shownTrackKey,
                progress   = progress,
                durationMs = durationMs,
                spinning   = isPlaying,
                modifier   = Modifier
                    .fillMaxSize()
                    .offset { drift.value },
            )
            AnimatedVisibility(
                visible  = hintVisible,
                enter    = fadeIn(screenTransitionSpec()),
                exit     = fadeOut(screenTransitionSpec()),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
            ) {
                Text(
                    text     = stringResource(R.string.cassette_exit_hint),
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** The last non-null label and track key of this cassette session. NOT snapshot state (see use). */
private class HeldItem {
    var label   : CassetteLabel? = null
    var trackKey: String?        = null
}

/**
 * The dim's bookkeeping. NOT snapshot state: nothing in composition reads it, and a restore must
 * be a no-op unless a dim actually happened — `window.attributes = …` is a WindowManager relayout,
 * too costly to issue on every touch.
 */
private class DimState {
    private var dimmed = false

    fun dim(window: Window) {
        window.attributes = window.attributes.also { it.screenBrightness = CassetteTiming.DimBrightness }
        dimmed = true
    }

    fun restore(window: Window) {
        if (dimmed) restoreAlways(window)
    }

    fun restoreAlways(window: Window) {
        window.attributes = window.attributes.also {
            it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        dimmed = false
    }
}

/**
 * Is TalkBack's touch exploration on — OBSERVED, so turning TalkBack on or off while the player is
 * up is seen at once. The idle gate never arms while it is (see [cassetteEligible]).
 */
@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    return produceState(initialValue = manager?.isTouchExplorationEnabled == true, manager) {
        if (manager == null) return@produceState
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { value = it }
        manager.addTouchExplorationStateChangeListener(listener)
        // Re-read after registering: a change between the seed and the listener is not lost.
        value = manager.isTouchExplorationEnabled
        awaitDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }.value
}
