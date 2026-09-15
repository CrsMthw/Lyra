package com.crsmthw.lyra.util

import android.util.Log
import androidx.compose.animation.SharedTransitionScope.SharedContentState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

// ⚠ TEMPORARY — DELETE THIS FILE, and every `morphLog` / `rememberMorphDiag` call, once the
// `"album-art"` morph question is settled on device (2026-09-15).
//
// Why it exists: the container transform between the mini player and the full player / pop-out
// panel breaks on the gesture AFTER a cancelled predictive back, and two rounds of source-traced
// repairs have not fixed it on device. The traced mechanism (a stranded
// `SharedTransitionStateMachine.targetBoundsProvider`) is only ONE of several states a cancelled
// seek can leave behind, and the sources argue it should self-heal within a frame — which it
// demonstrably does not. So this round stops theorising and logs ground truth instead: read it with
//
//     adb logcat -s LyraMorph
//
// The questions it has to answer for one "cancel, then back again" run: did the surface transition
// SEEK on the second gesture; did a match form; which participants were enabled and composed;
// where was each copy actually placed when the match formed; and what did the settle counters do
// after the cancel.

/** Master switch. `false` makes every [morphLog] call disappear at the call site (it is inline). */
internal const val MORPH_DIAG = true

/** Logcat tag: `adb logcat -s LyraMorph`. */
private const val MorphDiagTag = "LyraMorph"

/**
 * Logs one diagnostic line. Inline + a lambda, so with [MORPH_DIAG] off there is no call, no
 * string building and no lambda allocation left at the call site.
 */
internal inline fun morphLog(msg: () -> String) {
    if (MORPH_DIAG) Log.i(MorphDiagTag, msg())
}

/**
 * Non-snapshot cell for "what did this participant's match state read last time it was placed".
 *
 * Deliberately not a `MutableState`: it is written from a layout callback, and a snapshot write
 * there would invalidate composition from the layout pass — i.e. the diagnostics would change the
 * invalidation behaviour of the exact thing being measured.
 */
private class MatchLogState(var lastMatch: Boolean? = null)

/**
 * Diagnostics for ONE `"album-art"` shared-element participant, in the scope it is registered in.
 *
 * Installs, for the life of the registration:
 * - an ENTER / EXIT line carrying the element KEY — which is also the self-test for the fresh-key
 *   fix: at the first settle after startup every participant must log an EXIT of `album-art#n`
 *   followed by an ENTER of `album-art#n+1`. No such pair means the re-key never took effect and
 *   nothing else about it matters;
 * - one line per change of [SharedContentState.isMatchFound], emitted from the participant's
 *   PLACEMENT (not from a `snapshotFlow` — `isMatchFound` reads two plain `var`s and only one
 *   snapshot-backed field, so a flow over it can silently stop emitting), carrying the copy's
 *   root-relative bounds at that moment. That is what says WHERE each copy was when the match
 *   formed, which is the whole question for a morph that does not fly.
 *
 * @param who short label for the participant + scope, e.g. `"mini/nav"`.
 */
@Composable
internal fun rememberMorphDiag(who: String, state: SharedContentState): Modifier {
    DisposableEffect(state) {
        morphLog { "$who ENTER key=${state.key}" }
        onDispose { morphLog { "$who EXIT key=${state.key}" } }
    }
    val log = remember(state) { MatchLogState() }
    return remember(who, state, log) {
        Modifier.onGloballyPositioned { coords ->
            // withoutReadObservation: this must not subscribe the layout pass to `state`.
            val match = Snapshot.withoutReadObservation { state.isMatchFound }
            if (log.lastMatch != match) {
                log.lastMatch = match
                val pos  = coords.positionInRoot()
                val size = coords.size
                morphLog {
                    "$who match=$match key=${state.key}" +
                        " at=${pos.x.toInt()},${pos.y.toInt()} size=${size.width}x${size.height}"
                }
            }
        }
    }
}
