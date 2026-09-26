package com.crsmthw.lyra.ui.cassette

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.crsmthw.lyra.ui.theme.LyraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DEBUG ONLY — the painter's eyes. Shows [CassetteStage] full screen, immersive, with fake data
 * from intent extras, so a shell can launch it on the emulator and screencap the result:
 *
 * ```
 * adb -s emulator-5554 shell am start -n com.crsmthw.lyra/.ui.cassette.CassettePreviewActivity \
 *   --es title "MASS OF THE UNSAID" --es artist "Feather & Plumbs" --es album "Mass of the Unsaid" \
 *   --es year 2026 --es copyright "© 2026 Velvet Circuit Sound Co." --ef progress 0.35 --ez spinning true
 * ```
 * Extras: title / artist / album / year / copyright (strings), seed (ARGB int), progress (float),
 * spinning, demo (every 5 s a new track — alternating a 40-character and a short title — so the
 * flip and the eject play; progress sweeps 0 → 1 over 60 s, starting at 0 — with demo `--ef
 * progress` is ignored, so the first sweep value is a tick, not a jump), sideB (one flip at start),
 * flipAt / ejectAt (freeze that move at a fraction of its timeline; every change is the same
 * move, so ejectAt plays the flip to B first and freezes the eject after it).
 *
 * The reel wind (CassetteReelWind.kt). `--el` extras are LONGS, `--ef` floats, `--ez` booleans; a
 * wrong-typed extra (an `--ei` for an `--el`, say) is ignored — it falls back to the default, or
 * to "not given" for an optional one. Every scripted event is anchored at seekAfterMs:
 * - `--el durationMs D` — the track length the stage winds against: default 200000; 60000 with
 *   demo, so its 60 s sweep matches (and its wrap then lands WITH a key change — the atomic
 *   track-change case, for free). 0 = unknown: the reels never wind.
 * - `--ez tick true` — +1000 ms of progress per second while spinning, capped at the end.
 * - `--ef seekTo X` — progress := X on the same key at `--el seekAfterMs` (default 2000).
 * - `--el seekEveryMs P` — then keep alternating between the start progress and X every P ms.
 * - `--el correctMs C` — a signed C ms correction after every seek, `--el correctAfterMs` later
 *   (default 300); keep |C| under 5 s to exercise a correction folded into the running wind.
 * - `--ez repeatOne true` — implies tick; at 1.0 holds 2 s, then 1500 ms / D on the same key.
 * - `--ez skipReset true` — at seekAfterMs 0 on the same key (Lyra's own optimistic reset), then
 *   800 ms later the key changes with progress 300 ms / D, in one turn.
 * - `--ef zeroThen X` — at seekAfterMs 0, then X 800 ms later, on the same key.
 * - `--el keyAfterMs K` — the key changes K ms after the first seek, with `--ef keyProgress`
 *   (default 0.1), in one turn. With it, flipAt only freezes and schedules no key change of its
 *   own (sideB and ejectAt keep theirs).
 * - `--ef timeScale S` — the reels' clock, winds AND hubs, × S ([ReelDebug.timeScale]).
 * - `--ef windAt F` — hold every wind at fraction F of its timeline ([ReelDebug.freezeFraction]).
 */
class CassettePreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val args = PreviewArgs.from(intent)
        setContent {
            LyraTheme {
                ImmersiveWindow(this)
                CassettePreview(args)
            }
        }
    }
}

/*
 * The scripts' own timings. They imitate what the PLAYER and Spotify do (the poll's delay after a
 * skip, a repeat-one wrap as the next poll reports it), not the feature's durations, which all live
 * in CassetteTiming — so they stay here, in the debug source set.
 */
/** The fake track's length: a 3 min 20 s song, long enough that a tick never winds. */
private const val PreviewDefaultDurationMs = 200_000L
/** With demo: the length its 60 s progress sweep implies, so a demo tick is a real second. */
private const val PreviewDemoDurationMs    = 60_000L
/** When the scripted events fire, after the first composition: the stage has settled by then. */
private const val PreviewSeekAfterMs       = 2_000L
/** How long after a seek its correction lands: inside even the shortest (350 ms) wind. */
private const val PreviewCorrectAfterMs    = 300L
/** The player's progress tick. */
private const val PreviewTickMs            = 1_000L
/** How long the repeat-one script sits at 1.0 before the next "poll" reports the restart. */
private const val PreviewRepeatHoldMs      = 2_000L
/** Where the repeat-one restart is reported: the song restarted this long ago. */
private const val PreviewRepeatRestartMs   = 1_500L
/** The "poll" after Lyra's own optimistic 0 (skipReset, zeroThen). */
private const val PreviewZeroPollMs        = 800L
/** Where the skipReset's new track is when its first poll lands. */
private const val PreviewSkipLandMs        = 300L

private data class PreviewArgs(
    val label         : CassetteLabel,
    val seed          : Int,
    val progress      : Float,
    val spinning      : Boolean,
    val demo          : Boolean,
    val sideB         : Boolean,
    val flipAt        : Float?,
    val ejectAt       : Float?,
    val durationMs    : Long,
    val tick          : Boolean,
    val seekTo        : Float?,
    val seekAfterMs   : Long,
    val seekEveryMs   : Long?,
    val correctMs     : Long?,
    val correctAfterMs: Long,
    val repeatOne     : Boolean,
    val skipReset     : Boolean,
    val zeroThen      : Float?,
    val keyAfterMs    : Long?,
    val keyProgress   : Float,
    val reelDebug     : ReelDebug?,
) {
    /** `ms` of track time as a fraction of the preview's duration (0 while it is unknown). */
    fun fraction(ms: Long): Float = if (durationMs > 0L) ms.toFloat() / durationMs else 0f

    companion object {
        /** A float extra, or null when absent OR wrong-typed: "not given" must differ from any
         *  value, and an `--ei windAt 1` must not read as 0 (every wind frozen at its start). The
         *  Bundle returns the default on a type mismatch, so the default is a sentinel. */
        private fun Intent.floatOrNull(name: String) = getFloatExtra(name, Float.NaN).takeIf { !it.isNaN() }

        /** A LONG extra (`--el`), or null when absent OR wrong-typed: an `--ei keyAfterMs 400` must
         *  not read as 0 (the key change at the seek instant, and flipAt's own one suppressed). */
        private fun Intent.longOrNull(name: String) = getLongExtra(name, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

        fun from(i: Intent): PreviewArgs {
            val demo = i.getBooleanExtra("demo", false)
            val timeScale = i.getFloatExtra("timeScale", 1f).takeIf { it > 0f } ?: 1f
            val windAt = i.floatOrNull("windAt")
            return PreviewArgs(
                label = CassetteLabel(
                    title     = i.getStringExtra("title") ?: "MASS OF THE UNSAID",
                    artist    = i.getStringExtra("artist") ?: "Feather & Plumbs",
                    album     = i.getStringExtra("album"),
                    year      = i.getStringExtra("year"),
                    copyright = i.getStringExtra("copyright"),
                ),
                seed           = if (i.hasExtra("seed")) i.getIntExtra("seed", CASSETTE_DEFAULT_CUSTOM_COLOR) else CASSETTE_DEFAULT_CUSTOM_COLOR,
                progress       = i.getFloatExtra("progress", 0.35f),
                spinning       = i.getBooleanExtra("spinning", true),
                demo           = demo,
                sideB          = i.getBooleanExtra("sideB", false),
                flipAt         = i.floatOrNull("flipAt"),
                ejectAt        = i.floatOrNull("ejectAt"),
                durationMs     = i.getLongExtra("durationMs", if (demo) PreviewDemoDurationMs else PreviewDefaultDurationMs),
                tick           = i.getBooleanExtra("tick", false),
                seekTo         = i.floatOrNull("seekTo"),
                seekAfterMs    = i.getLongExtra("seekAfterMs", PreviewSeekAfterMs),
                seekEveryMs    = i.longOrNull("seekEveryMs")?.takeIf { it > 0L },
                correctMs      = i.longOrNull("correctMs"),
                correctAfterMs = i.getLongExtra("correctAfterMs", PreviewCorrectAfterMs),
                repeatOne      = i.getBooleanExtra("repeatOne", false),
                skipReset      = i.getBooleanExtra("skipReset", false),
                zeroThen       = i.floatOrNull("zeroThen"),
                keyAfterMs     = i.longOrNull("keyAfterMs"),
                keyProgress    = i.getFloatExtra("keyProgress", 0.1f),
                reelDebug      = if (timeScale != 1f || windAt != null) ReelDebug(timeScale, windAt) else null,
            )
        }
    }
}

/** iLyra's window recipe: cutout ALWAYS + hidden bars, swipe to reveal transiently. */
@Composable
private fun ImmersiveWindow(activity: ComponentActivity) {
    DisposableEffect(Unit) {
        val window = activity.window
        val view = window.decorView
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private val LongTitle = CassetteLabel(
    title = "THE LONGEST TITLE ON THE B-SIDE OF A TAPE", // 41 characters with the spaces
    artist = "Velvet Circuit Ensemble & The Long Names Orchestra",
    album = "Forty Characters Is Plenty For One Title", year = "1987",
    copyright = "© 1987 Velvet Circuit Sound Co.",
)
private val ShortTitle = CassetteLabel(title = "HALO", artist = "Plumbs", album = null, year = "2024")

@Composable
private fun CassettePreview(a: PreviewArgs) {
    var step by remember { mutableIntStateOf(0) }
    // demo starts where its sweep starts: seeded at `a.progress` (0.35 by default), the sweep's first
    // value 100 ms later was a JUMP back to ~0, and the preview wound a rewind nobody had scripted
    var progress by remember { mutableFloatStateOf(if (a.demo) 0f else a.progress) }
    val freeze = when {
        a.flipAt != null  -> CassetteFreeze(CassetteMove.FLIP, a.flipAt)
        a.ejectAt != null -> CassetteFreeze(CassetteMove.EJECT, a.ejectAt)
        else              -> null
    }
    LaunchedEffect(Unit) {
        // ── Key changes of the choreography previews ──
        launch {
            when {
                a.sideB || (a.flipAt != null && a.keyAfterMs == null) -> { delay(900); step = 1 }
                // a flip to B first, then the eject
                a.ejectAt != null -> { delay(900); step = 1; delay(1300); step = 2 }
                a.demo -> {
                    val start = System.nanoTime()
                    var next = 5_000L
                    while (true) {
                        delay(100)
                        val ms = (System.nanoTime() - start) / 1_000_000
                        progress = (ms % 60_000L) / 60_000f
                        if (ms >= next) { step++; next += 5_000L }
                    }
                }
            }
        }
        // ── The reel-wind scripts. A key change and its position are written in ONE turn (no
        //    suspension between them), as PlayerViewModel mirrors id + progress in one update. ──
        if (a.tick || a.repeatOne) launch {
            while (true) {
                delay(PreviewTickMs)
                if (!a.spinning) continue
                progress = (progress + a.fraction(PreviewTickMs)).coerceAtMost(1f)
                if (a.repeatOne && progress >= 1f) {
                    delay(PreviewRepeatHoldMs)
                    progress = a.fraction(PreviewRepeatRestartMs)
                }
            }
        }
        a.seekTo?.let { x ->
            launch {
                delay(a.seekAfterMs)
                var towardX = true
                while (true) {
                    progress = if (towardX) x else a.progress
                    a.correctMs?.let { c ->
                        // a child, so the correction never skews seekEveryMs
                        launch {
                            delay(a.correctAfterMs)
                            progress = (progress + a.fraction(c)).coerceIn(0f, 1f)
                        }
                    }
                    val every = a.seekEveryMs ?: break
                    delay(every)
                    towardX = !towardX
                }
            }
        }
        if (a.skipReset) launch {
            delay(a.seekAfterMs)
            progress = 0f
            delay(PreviewZeroPollMs)
            step++
            progress = a.fraction(PreviewSkipLandMs)
        }
        a.zeroThen?.let { x ->
            launch {
                delay(a.seekAfterMs)
                progress = 0f
                delay(PreviewZeroPollMs)
                progress = x
            }
        }
        a.keyAfterMs?.let { k ->
            launch {
                delay(a.seekAfterMs + k)
                step++
                progress = a.keyProgress
            }
        }
    }
    val label = when {
        a.demo      -> if (step == 0) a.label else if (step % 2 == 1) LongTitle else ShortTitle
        a.sideB     -> a.label
        step == 0   -> a.label
        step == 1   -> ShortTitle
        else        -> a.label
    }
    CassetteStageImpl(
        palette    = remember(a.seed) { CassettePalette.from(Color(a.seed)) },
        label      = label,
        trackKey   = "track-$step",
        progress   = { progress },
        durationMs = a.durationMs,
        spinning   = a.spinning,
        modifier   = Modifier.fillMaxSize(),
        freeze     = freeze,
        reelDebug  = a.reelDebug,
    )
}
