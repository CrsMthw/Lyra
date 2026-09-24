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
 * flip and the eject play; progress sweeps 0 → 1 over 60 s), sideB (one flip at start),
 * flipAt / ejectAt (freeze that move at a fraction of its timeline), backward (moves go back;
 * with ejectAt the one backward change from side A is the frozen eject).
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

private data class PreviewArgs(
    val label   : CassetteLabel,
    val seed    : Int,
    val progress: Float,
    val spinning: Boolean,
    val demo    : Boolean,
    val sideB   : Boolean,
    val flipAt  : Float?,
    val ejectAt : Float?,
    val backward: Boolean,
) {
    companion object {
        fun from(i: Intent) = PreviewArgs(
            label = CassetteLabel(
                title     = i.getStringExtra("title") ?: "MASS OF THE UNSAID",
                artist    = i.getStringExtra("artist") ?: "Feather & Plumbs",
                album     = i.getStringExtra("album"),
                year      = i.getStringExtra("year"),
                copyright = i.getStringExtra("copyright"),
            ),
            seed     = if (i.hasExtra("seed")) i.getIntExtra("seed", CASSETTE_DEFAULT_CUSTOM_COLOR) else CASSETTE_DEFAULT_CUSTOM_COLOR,
            progress = i.getFloatExtra("progress", 0.35f),
            spinning = i.getBooleanExtra("spinning", true),
            demo     = i.getBooleanExtra("demo", false),
            sideB    = i.getBooleanExtra("sideB", false),
            flipAt   = if (i.hasExtra("flipAt")) i.getFloatExtra("flipAt", 0.5f) else null,
            ejectAt  = if (i.hasExtra("ejectAt")) i.getFloatExtra("ejectAt", 0.5f) else null,
            backward = i.getBooleanExtra("backward", false),
        )
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
    var progress by remember { mutableFloatStateOf(a.progress) }
    val freeze = when {
        a.flipAt != null  -> CassetteFreeze(CassetteMove.FLIP, a.flipAt)
        a.ejectAt != null -> CassetteFreeze(CassetteMove.EJECT, a.ejectAt)
        else              -> null
    }
    LaunchedEffect(Unit) {
        when {
            a.sideB || a.flipAt != null -> { delay(900); step = 1 }
            // forward: a flip to B first, then the eject; backward: from a fresh side A the
            // FIRST change is already an eject (the previous shell comes back)
            a.ejectAt != null           -> { delay(900); step = 1; if (!a.backward) { delay(1300); step = 2 } }
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
    val label = when {
        a.demo      -> if (step == 0) a.label else if (step % 2 == 1) LongTitle else ShortTitle
        a.sideB     -> a.label
        step == 0   -> a.label
        step == 1   -> ShortTitle
        else        -> a.label
    }
    CassetteStageImpl(
        palette  = remember(a.seed) { CassettePalette.from(Color(a.seed)) },
        label    = label,
        trackKey = "track-$step",
        forward  = !a.backward,
        progress = { progress },
        spinning = a.spinning,
        modifier = Modifier.fillMaxSize(),
        freeze   = freeze,
    )
}
