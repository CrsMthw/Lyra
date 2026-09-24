package com.crsmthw.lyra.ui.cassette

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

/*
 * The cassette idle screen — SHARED CONTRACT (2026-09-23).
 *
 * Everything in this file is consumed by three independently built parts: the painter
 * (`Cassette` / `CassetteStage`, ui/cassette), the Settings UI (SettingsScreen's cassette sheet)
 * and the player integration (PlayerScreen's `CassetteOverlay` + PlayerViewModel). Change a
 * signature here only with all three call sites in view. Design record: docs/CASSETTE.md.
 */

/** Where the cassette's accent colour comes from. Persisted by `name`. */
enum class CassetteColorSource { ALBUM_ART, MATERIAL_YOU, CUSTOM }

/** Which face of the shell is up. Side B is the same track's label with a different corner tag. */
enum class CassetteSide { A, B }

/** The ad's purple, as the default custom colour (ARGB). */
const val CASSETTE_DEFAULT_CUSTOM_COLOR: Int = 0xFF8B6BD1.toInt()

/** Physical compact-cassette shell: 100 mm × 63.8 mm. Every layout fits this ratio, never stretches. */
const val CASSETTE_ASPECT: Float = 100f / 63.8f

/** Every cassette preference as ONE immutable snapshot (one DataStore flow, one collect). */
@Immutable
data class CassetteSettings(
    val enabled       : Boolean             = false,
    /** Hold the screen awake while the cassette is up AND music is playing. */
    val keepScreenOn  : Boolean             = false,
    /** Sub-setting of [keepScreenOn]: drop the window brightness after [CassetteTiming.DimDelayMs]. */
    val dimAfterDelay : Boolean             = false,
    val colorSource   : CassetteColorSource = CassetteColorSource.ALBUM_ART,
    /** ARGB. Only read when [colorSource] is [CassetteColorSource.CUSTOM]. */
    val customColor   : Int                 = CASSETTE_DEFAULT_CUSTOM_COLOR,
    /** Show the "Double-tap to exit" hint chip on a single tap. */
    val showExitHint  : Boolean             = true,
)

/** What the label prints. `side` is NOT here — the stage decides it (see [CassetteChoreographer]). */
@Immutable
data class CassetteLabel(
    /** The big serif line. */
    val title      : String,
    /** The second line. A podcast episode passes the show name here. */
    val artist     : String,
    val album      : String? = null,
    /** Four digits, or null. */
    val year       : String? = null,
    /** Spotify's album copyright line (e.g. "© 2019 Big Machine Label Group"), or null → boilerplate only. */
    val copyright  : String? = null,
    /** The record label (Spotify `label`), or null. */
    val recordLabel: String? = null,
)

/** Every duration the feature uses, in one place. */
object CassetteTiming {
    /** No touch on the player for this long → the cassette slides over it. */
    const val IdleDelayMs   = 7_000L
    /** With keep-screen-on + dim: no touch on the cassette for this long → window brightness drops. */
    const val DimDelayMs    = 20_000L
    /** The dimmed window brightness (0..1). */
    const val DimBrightness = 0.10f
    /** How long the single-tap hint chip stays. */
    const val HintVisibleMs = 1_600L
    /** Burn-in guard: the whole cassette drifts by up to [DriftMaxPx] this often. */
    const val DriftPeriodMs = 60_000L
    const val DriftMaxPx    = 2f
    /** The flip (A↔B) and the eject/insert choreography. */
    const val FlipMs        = 650
    const val EjectOutMs    = 380
    const val EjectGapMs    = 120
    const val InsertMs      = 480
}

/** How a track change is shown. */
enum class CassetteMove { FLIP, EJECT }

/**
 * Side A → FLIP → side B → EJECT (a fresh cassette, side A) → FLIP → … A real shell has two sides,
 * so every second song is a new cassette. EVERY track change plays this same sequence, whatever
 * its direction (Cris, 2026-09-24): the player cannot tell a previous-song from a
 * next-song change it only learns about from the poll, so a previous song flips / ejects exactly
 * as a next one does. The side always toggles; the move alternates. Pure Kotlin, unit-tested.
 */
class CassetteChoreographer(initialSide: CassetteSide = CassetteSide.A) {
    var side: CassetteSide = initialSide
        private set

    /** Records a track change (any direction) and returns how to show it. */
    fun advance(): CassetteMove {
        val move = if (side == CassetteSide.A) CassetteMove.FLIP else CassetteMove.EJECT
        side = if (side == CassetteSide.A) CassetteSide.B else CassetteSide.A
        return move
    }
}

/**
 * The cassette's on-screen box for a window of `width` × `height` (any unit): the long edge runs
 * along the window's long edge, the ratio is [CASSETTE_ASPECT], the size is the largest that fits
 * with NO margin — full bleed on the short edge whenever the window is at least as elongated as the
 * shell (the Fold 8 cover screen is 1.58:1 against 1.567:1), letterboxed along the long edges
 * otherwise (the unfolded 1.32:1 screen). `portrait` = the shell is drawn turned 90° anticlockwise,
 * head edge on the RIGHT, label reading bottom-to-top — the ad's framing. Landscape = the shell's
 * natural orientation, head edge at the BOTTOM, label upright.
 */
@Immutable
data class CassetteFit(val long: Float, val short: Float, val portrait: Boolean)

fun cassetteFit(width: Float, height: Float): CassetteFit {
    val portrait = height > width
    val l = max(width, height)
    val s = min(width, height)
    val long = min(l, s * CASSETTE_ASPECT)
    return CassetteFit(long = long, short = long / CASSETTE_ASPECT, portrait = portrait)
}
