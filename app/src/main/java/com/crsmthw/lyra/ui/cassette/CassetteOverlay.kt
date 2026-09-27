package com.crsmthw.lyra.ui.cassette

import android.app.Activity
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
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.app.OnMultiWindowModeChangedProvider
import androidx.core.util.Consumer
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
 * the orientation lock, keep-screen-on, the dim, the burn-in drift, the gestures, the back rule,
 * the ON_STOP exit and the "Double-tap to exit" hint chip. The painting itself is `CassetteStage`
 * (Cassette.kt).
 *
 * Every window-level change is undone in `onDispose`, so an exit — or the player leaving
 * composition with the cassette up — always hands back the bars, the cutout mode, the rotation
 * animation, the orientation lock, the brightness and the screen timeout. The bars, the cutout
 * mode, the brightness and the timeout follow `visible`, so they come back as the exit fade STARTS.
 * The orientation lock and the rotation animation are held by the fading content itself, so they
 * come back as the fade ENDS (at once on a stop), and the lock is dropped in multi-window: see
 * [RotationHold].
 */

/** No label yet (the feed has not emitted) — the stage still draws a blank label. */
private val EmptyLabel = CassetteLabel(title = "", artist = "")

/**
 * The window's rotation animation while the cassette is up: a JUMP CUT, the old frame giving way to
 * the new one with no system turn (2026-09-26). Since 2026-09-27 the window is also LOCKED in place
 * while the cassette is up ([RotationHold]). AOSP 16/17 honours that on a LARGE screen too. The
 * targetSdk 36+ rule that ignores an app's orientation request at sw ≥ 600 dp exempts
 * SCREEN_ORIENTATION_LOCKED at both of its layers: ActivityRecord.isRestrictedFixedOrientation
 * ("not explicit portrait or landscape") and DisplayArea.shouldIgnoreOrientationRequest ("for the
 * compatibility of camera apps"). And DisplayRotation.rotationForOrientation's LOCKED branch holds
 * the last rotation above the sensor and user_rotation. So on the unfolded Fold or a tablet no
 * rotation normally reaches the cassette either. A rotation still reaches it where the lock does
 * not hold:
 * - split screen or a pop-up window, where [RotationHold] drops it. There the shell may still
 *   choose its own animation (device pass 1 saw the system spin with a pop-up window over Lyra),
 *   but the stage's settle plays either way;
 * - a per-app override that remaps LOCKED. AppCompatOrientationPolicy does that for a user's
 *   aspect-ratio choice in Settings → Apps (on Android 16 the full-screen one gives USER; on 17 the
 *   minimum-ratio ones give PORTRAIT) and for an OEM compat override;
 * - an OEM build that departs from AOSP.
 * This attribute and the stage's own settle are what those rotations get.
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

    // ── Immersive window: hidden bars (swipe reveals them transiently) + the cutout ──────────
    // iLyra's recipe (ILyraRoot), minus its portrait request: the cassette's own orientation lock
    // and its rotation animation are [RotationHold]'s, taken by the overlay's content below so they
    // outlive the exit fade. The bars and the cutout mode follow `visible`, so they come back as
    // the fade STARTS and the player gets its insets back at once. The cutout mode is restored to
    // what it WAS, not blindly to the default.
    DisposableEffect(visible, window) {
        if (!visible || window == null) return@DisposableEffect onDispose { }
        val previousCutoutMode = window.attributes.layoutInDisplayCutoutMode
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = previousCutoutMode }
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val rotationHold = remember { RotationHold() }

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
        // ── The orientation lock + the jump cut, for as long as THIS content is composed ─────────
        // That is to the END of the exit fade, and a re-entry during the fade keeps both. The lock
        // is dropped while Lyra is in multi-window and taken again, at the rotation current then,
        // on the way back to full screen. A stop lets go of both at once. Why: [RotationHold].
        val inMultiWindow = rememberInMultiWindowMode(activity)
        DisposableEffect(activity, inMultiWindow) {
            if (activity != null && !inMultiWindow) rotationHold.lock(activity)
            onDispose { rotationHold.unlock() }
        }
        DisposableEffect(window) {
            if (window != null) rotationHold.cut(window)
            onDispose { rotationHold.uncut() }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { rotationHold.releaseAll() }

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
 * The two window writes the cassette holds until its exit fade ENDS rather than until it starts:
 * the orientation LOCK and the jump-cut rotation animation ([CassetteRotationAnimation]). NOT
 * snapshot state: nothing in composition reads it. Each take captures what it replaces. Each
 * release restores that value and clears its mark, so a stop's release and the fade's later
 * `onDispose` never write twice, and a later take (back from split screen) captures afresh.
 *
 * **The lock** (Cris, 2026-09-27) is `SCREEN_ORIENTATION_LOCKED`. The window keeps whatever rotation
 * it has when the cassette comes up, until the cassette has gone. An old Walkman's tape does not
 * turn in its player; it is upright or upside down by how you hold it. So while the cassette is up
 * there is no rotation, no cut and no hub hitch, however the phone is waved to the beat. It locks
 * the CURRENT rotation, never portrait: a portrait lock would rotate a landscape player at the very
 * moment the cassette slides in. AOSP 16/17 honours it on a large screen as well (see
 * [CassetteRotationAnimation]), so the unfolded Fold and a tablet lock too. A fold or unfold with
 * the cassette up keeps the lock (same Activity, no recreation). A physical display switch does not
 * reset the display's rotation (DisplayRotation.physicalDisplayChanged only pauses the sensor), so
 * the whole round trip stays in the rotation the lock was taken in. The device pass decides for
 * One UI.
 *
 * **Why to the end of the fade.** When both were restored as the fade STARTED, an owed rotation
 * landed at once over the still-opaque cassette. That is the common exit now: the phone was turned
 * while the cassette was up. The rotation animation was already back on ROTATE, so the system spun
 * a screenshot of the cassette a quarter turn, the very spin the jump cut is there to avoid. Held to
 * the end, the player turns afterwards with its own ROTATE, as it does after iLyra.
 *
 * **A stop is the exception.** The recomposer pauses its frame clock while Lyra is stopped, so the
 * fade, and the release at its end, would wait for the return. A user who came back holding the
 * phone differently would meet a stale rotation. Nothing is on screen to protect then, so the stop
 * releases both at once, whether the cassette is up or already fading.
 *
 * **Multi-window drops the lock.** In split screen WindowManager does not ignore LOCKED; it turns it
 * into a fixed-orientation LETTERBOX (TaskFragment.canSpecifyOrientation is false outside full
 * screen, and ActivityRecord letterboxes a MULTI_WINDOW parent "even with ignoreOrientationRequest
 * disabled", i.e. on the cover screen too). Once the half's shape disagrees with the orientation
 * the lock was taken in, the cassette shrinks into a box with bars inside its half until the exit.
 * A pop-up window reports multi-window too, and the lock does nothing there anyway. Entering split
 * may still show one letterboxed frame, because WindowManager resolves the split before the
 * multi-window callback reaches the app.
 */
private class RotationHold {
    private var lockedIn: Activity? = null
    private var orientationBefore = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var cutIn: Window? = null
    private var animationBefore = WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE

    fun lock(activity: Activity) {
        if (lockedIn != null) return
        orientationBefore = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        lockedIn = activity
    }

    fun unlock() {
        val activity = lockedIn ?: return
        lockedIn = null
        activity.requestedOrientation = orientationBefore
    }

    fun cut(window: Window) {
        if (cutIn != null) return
        animationBefore = window.attributes.rotationAnimation
        window.attributes = window.attributes.also { it.rotationAnimation = CassetteRotationAnimation }
        cutIn = window
    }

    fun uncut() {
        val window = cutIn ?: return
        cutIn = null
        window.attributes = window.attributes.also { it.rotationAnimation = animationBefore }
    }

    /** The stop's release: both at once, the rotation animation back before the lock lets go. */
    fun releaseAll() {
        uncut()
        unlock()
    }
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

/**
 * Is [activity] in multi-window mode (split screen, a pop-up window)? It is OBSERVED, so entering or
 * leaving it while the cassette is up is seen at once. The orientation lock is dropped there (see
 * [RotationHold]). A null activity reads `false`. An activity that does not report the change (not
 * a ComponentActivity) keeps the value it had at the first composition.
 */
@Composable
internal fun rememberInMultiWindowMode(activity: Activity?): Boolean =
    produceState(initialValue = activity?.isInMultiWindowMode == true, activity) {
        val host = activity ?: return@produceState
        val provider = host as? OnMultiWindowModeChangedProvider ?: return@produceState
        val listener = Consumer<MultiWindowModeChangedInfo> { value = it.isInMultiWindowMode }
        provider.addOnMultiWindowModeChangedListener(listener)
        // Re-read after registering: a change between the seed and the listener is not lost.
        value = host.isInMultiWindowMode
        awaitDispose { provider.removeOnMultiWindowModeChangedListener(listener) }
    }.value
