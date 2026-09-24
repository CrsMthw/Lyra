package com.crsmthw.lyra.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.cassette.CASSETTE_ASPECT
import com.crsmthw.lyra.ui.cassette.CASSETTE_DEFAULT_CUSTOM_COLOR
import com.crsmthw.lyra.ui.cassette.CASSETTE_IDLE_DEFAULT_SECONDS
import com.crsmthw.lyra.ui.cassette.CASSETTE_IDLE_MAX_SECONDS
import com.crsmthw.lyra.ui.cassette.CASSETTE_IDLE_MIN_SECONDS
import com.crsmthw.lyra.ui.cassette.Cassette
import com.crsmthw.lyra.ui.cassette.CassetteColorSource
import com.crsmthw.lyra.ui.cassette.CassetteLabel
import com.crsmthw.lyra.ui.cassette.CassettePalette
import com.crsmthw.lyra.ui.cassette.CassetteSettings
import com.crsmthw.lyra.ui.cassette.CassetteSide
import com.crsmthw.lyra.ui.cassette.CassetteTiming
import com.crsmthw.lyra.ui.cassette.clampCassetteIdleSeconds
import com.crsmthw.lyra.ui.cassette.toHsl
import com.crsmthw.lyra.ui.components.CappedModalBottomSheet
import com.crsmthw.lyra.ui.components.ConnectedChoiceRow
import com.crsmthw.lyra.ui.components.ValueSlider
import com.crsmthw.lyra.ui.components.sheetTopGap
import com.crsmthw.lyra.util.screenTransitionSpec
import com.crsmthw.lyra.util.tick
import com.crsmthw.lyra.util.toggle
import kotlin.math.roundToInt

/*
 * The cassette idle screen's options sheet (docs/CASSETTE.md), opened from Settings → Lyra →
 * Cassette options. Mirrors VisualizerSheet: a CappedModalBottomSheet whose single scrollable child
 * is capped a status-bar height below the screen. Top to bottom: a live preview, the colour source
 * (+ the custom picker), the idle delay field, the screen toggles, the exit-hint toggle and a footer
 * tip.
 *
 * The idle field needs no `imePadding()`: M3's ModalBottomSheet (1.5.0-alpha28) runs in its own
 * window with SOFT_INPUT_ADJUST_NOTHING and pads its content by `BottomSheetDefaults.modalWindowInsets`
 * = safeDrawing (IME included) — the sheet itself rises above the keyboard and CONSUMES the IME
 * inset, so an `imePadding()` below it would add 0. The capped Column's viewport then shrinks by the
 * keyboard, and the scroller's ContentInViewNode keeps the focused field in view across that shrink.
 */

// ── Custom colour: pure helpers (unit-tested in CassetteSheetHelpersTest) ─────────────────────

/**
 * The saturation window the Saturation slider offers. It is EXACTLY the clamp in
 * `CassettePalette.from` (0.35..0.80) — a wider slider would have dead zones where the thumb moves
 * and the cassette does not. Keep the two in step if the palette's clamp ever changes.
 */
internal const val CASSETTE_SAT_MIN = 0.35f
internal const val CASSETTE_SAT_MAX = 0.80f

/**
 * The lightness a custom seed is stored at. It is NOT a user control: the palette fixes every
 * lightness itself (it reads only hue + saturation from the seed), so hue + saturation fully define
 * the look. 0.56 is the palette's own `hub` lightness, so the stored seed IS the hub colour.
 */
internal const val CASSETTE_SEED_LIGHTNESS = 0.56f

/** The two slider positions a custom colour maps to. */
internal data class CassetteHueSat(val hue: Float, val saturation: Float)

/** Slider positions → the seed. Inputs are clamped first — `Color.hsl` throws outside its ranges. */
internal fun cassetteSeedColor(hue: Float, saturation: Float): Color = Color.hsl(
    hue        = hue.coerceIn(0f, 360f),
    saturation = saturation.coerceIn(CASSETTE_SAT_MIN, CASSETTE_SAT_MAX),
    lightness  = CASSETTE_SEED_LIGHTNESS,
)

/** A stored custom colour (ARGB) → the slider positions, saturation clamped into the slider's range. */
internal fun cassetteHueSat(argb: Int): CassetteHueSat {
    val hsl = Color(argb).toHsl()
    return CassetteHueSat(
        hue        = hsl.h.coerceIn(0f, 360f),
        saturation = hsl.s.coerceIn(CASSETTE_SAT_MIN, CASSETTE_SAT_MAX),
    )
}

/**
 * The values this sheet has itself sent to DataStore for ONE preference and not yet seen come back —
 * one instance for the custom colour (ARGB), one for the idle delay (seconds).
 *
 * The sliders must NOT be re-seeded from the sheet's own write landing: the stored ARGB reads back
 * through `toHsl()`, whose hue is in [0, 360) — a Hue released at the right end (exactly 360, red
 * with green == blue) would come back as 0 and snap the thumb across the track — and a drag begun
 * before the echo lands would be reset under the finger. So every write (a slider release AND a
 * swatch tap, which re-seeds the sliders itself at the tap) is [record]ed, and an emission is
 * treated as an outside change only when [isEcho] says it is not one of ours.
 *
 * A plain queue rather than a single "last written" value: two quick releases (Hue, then
 * Saturation) send A then B, and if A's emission arrives on its own it must still count as ours.
 * The idle field uses it the same way: its own keystroke's echo must not rewrite the text under
 * the cursor (a "12" typed on the way to "120" echoing back as "12" after the "0").
 *
 * Not snapshot state — it is read and written only from callbacks and effects.
 */
internal class CassetteOwnWrites {
    private val pending = ArrayDeque<Int>()

    /** Call BEFORE handing [argb] to the persister. */
    fun record(argb: Int) { pending.addLast(argb) }

    /**
     * Whether a persisted [argb] is the echo of one of our writes. The OLDEST matching write is
     * consumed together with every write older than it (those were conflated away, or never emitted
     * because they equalled the stored value) — oldest, so writes A, B, A arriving in order are all
     * recognised. A miss is an outside change, which supersedes everything we had in flight, so the
     * queue is cleared.
     */
    fun isEcho(argb: Int): Boolean {
        val at = pending.indexOf(argb)
        return if (at >= 0) {
            repeat(at + 1) { pending.removeFirst() }
            true
        } else {
            pending.clear()
            false
        }
    }

    /**
     * What the store will hold once our in-flight writes land: the newest pending write, else
     * [stored]. A composed `stored` lags a write by one echo, so "is this new?" asks this instead.
     */
    fun expected(stored: Int): Int = pending.lastOrNull() ?: stored
}

// ── Idle delay: pure helpers (unit-tested in CassetteSheetHelpersTest) ────────────────────────

/** The longest accepted delay has three digits (600), so the field never holds more. */
internal val CASSETTE_IDLE_MAX_DIGITS: Int = CASSETTE_IDLE_MAX_SECONDS.toString().length

/**
 * What the field keeps of an edit, or null to REJECT it (the field keeps its previous text and
 * nothing is saved). ASCII digits only, leading zeros dropped (a lone "0" stays, so a typed zero is
 * visible and shows the range error). An edit with more than [CASSETTE_IDLE_MAX_DIGITS] significant
 * digits is rejected, never truncated: cutting it would keep the FIRST three digits wherever the new
 * one was inserted ("1" typed before "600" → "160"), an in-range number the user never typed, and
 * the field saves an in-range value on the keystroke. Dropping leading zeros is what lets "0700"
 * reach 700 (the range error, clamped to 600 on Done) instead of stopping at "070" = 70 s.
 */
internal fun cassetteIdleDigits(raw: String): String? {
    val digits = raw.filter { it in '0'..'9' }
    val significant = digits.trimStart('0').ifEmpty { if (digits.isEmpty()) "" else "0" }
    return significant.takeIf { it.length <= CASSETTE_IDLE_MAX_DIGITS }
}

/** The typed delay if it is already acceptable (persisted at once), else null (the field shows an error). */
internal fun cassetteIdleTyped(text: String): Int? =
    text.toIntOrNull()?.takeIf { it in CASSETTE_IDLE_MIN_SECONDS..CASSETTE_IDLE_MAX_SECONDS }

/** Done / focus loss: the typed delay clamped into range, or [stored] when the field is empty. */
internal fun cassetteIdleCommitted(text: String, stored: Int): Int =
    text.toIntOrNull()?.let(::clampCassetteIdleSeconds) ?: stored

/** One preset swatch: its ARGB and the name TalkBack reads. */
internal data class CassetteSwatch(val argb: Int, @param:StringRes val nameRes: Int)

/** The ten presets, the ad's purple first. */
internal val CassetteSwatches: List<CassetteSwatch> = listOf(
    CassetteSwatch(CASSETTE_DEFAULT_CUSTOM_COLOR, R.string.settings_cassette_swatch_purple),
    CassetteSwatch(0xFF1DB954.toInt(),            R.string.settings_cassette_swatch_green),
    CassetteSwatch(0xFFE53935.toInt(),            R.string.settings_cassette_swatch_red),
    CassetteSwatch(0xFFFB8C00.toInt(),            R.string.settings_cassette_swatch_orange),
    CassetteSwatch(0xFFFDD835.toInt(),            R.string.settings_cassette_swatch_yellow),
    CassetteSwatch(0xFF00897B.toInt(),            R.string.settings_cassette_swatch_teal),
    CassetteSwatch(0xFF1E88E5.toInt(),            R.string.settings_cassette_swatch_blue),
    CassetteSwatch(0xFFD81B60.toInt(),            R.string.settings_cassette_swatch_pink),
    CassetteSwatch(0xFF8D6E63.toInt(),            R.string.settings_cassette_swatch_brown),
    CassetteSwatch(0xFF90A4AE.toInt(),            R.string.settings_cassette_swatch_blue_grey),
)

/** The preview's tape position — a constant, read in the painter's draw phase. */
private val PreviewProgress: () -> Float = { 0.35f }

// ── The sheet ─────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CassetteSheet(
    settings      : CassetteSettings,
    onKeepScreenOn: (Boolean) -> Unit,
    onDim         : (Boolean) -> Unit,
    onColorSource : (CassetteColorSource) -> Unit,
    onCustomColor : (Int) -> Unit,
    onShowExitHint: (Boolean) -> Unit,
    onIdleSeconds : (Int) -> Unit,
    onDismiss     : () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    // The seed the preview is painted from, per source. Album art has no album here, so the
    // theme's primary stands in. Material You reads the SYSTEM dynamic scheme directly (minSdk 35,
    // always available) — the same source the player uses, independent of Lyra's own toggle.
    val albumStandIn    = MaterialTheme.colorScheme.primary
    val materialYouSeed = remember(context) { dynamicDarkColorScheme(context).primary }

    // Custom picker: the in-drag values live here so the preview follows the finger; they are
    // persisted on release only. They are re-seeded from the stored colour ONLY on an outside change
    // (the first real value landing over the flow's default, say) — never by the sheet's own write
    // coming back (see CassetteOwnWrites). A swatch tap re-seeds them itself, at the tap.
    val ownWrites = remember { CassetteOwnWrites() }
    var hue     by remember { mutableFloatStateOf(cassetteHueSat(settings.customColor).hue) }
    var sat     by remember { mutableFloatStateOf(cassetteHueSat(settings.customColor).saturation) }
    // Until a slider moves, the preview shows the STORED colour exactly (a swatch's own ARGB, not
    // its re-derived hsl(h, s, 0.56)); after, it shows the sliders.
    var dragged by remember { mutableStateOf(false) }
    LaunchedEffect(settings.customColor) {
        if (!ownWrites.isEcho(settings.customColor)) {
            val stored = cassetteHueSat(settings.customColor)
            hue     = stored.hue
            sat     = stored.saturation
            dragged = false
        }
    }
    val customSeed = if (dragged) cassetteSeedColor(hue, sat) else Color(settings.customColor)
    val persistCustom = {
        val argb = cassetteSeedColor(hue, sat).toArgb()
        ownWrites.record(argb)
        onCustomColor(argb)
    }
    val pickSwatch = { argb: Int ->
        val picked = cassetteHueSat(argb)
        hue     = picked.hue
        sat     = picked.saturation
        dragged = false
        ownWrites.record(argb)
        onCustomColor(argb)
    }

    val seed = when (settings.colorSource) {
        CassetteColorSource.ALBUM_ART    -> albumStandIn
        CassetteColorSource.MATERIAL_YOU -> materialYouSeed
        CassetteColorSource.CUSTOM       -> customSeed
    }
    val palette = remember(seed) { CassettePalette.from(seed) }

    val previewTitle  = stringResource(R.string.app_name)
    val previewArtist = stringResource(R.string.settings_cassette_preview_artist)
    val previewLabel  = remember(previewTitle, previewArtist) {
        CassetteLabel(title = previewTitle, artist = previewArtist)
    }
    val previewCd = stringResource(R.string.settings_cassette_preview_cd)

    CappedModalBottomSheet(onDismissRequest = onDismiss) {
        BoxWithConstraints {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight - sheetTopGap())
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Voicemail, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text     = stringResource(R.string.settings_cassette_options),
                        style    = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Preview: the real painter on the stage's own black, natural orientation ──
                // A 12dp black margin inside the card so the stage reads as a stage around the shell,
                // and the shell is capped at 420dp wide (the ConnectedChoiceRow cap) so an unfolded
                // sheet does not grow a ~400dp-tall preview; the card stays full width.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.background)
                        .semantics { contentDescription = previewCd }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Cassette(
                        palette  = palette,
                        label    = previewLabel,
                        side     = CassetteSide.A,
                        progress = PreviewProgress,
                        spinning = true,
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .aspectRatio(CASSETTE_ASPECT),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── Colour ──
                VisualizerSectionLabel(stringResource(R.string.settings_cassette_color_section))
                ConnectedChoiceRow(
                    options  = listOf(
                        CassetteColorSource.ALBUM_ART    to stringResource(R.string.settings_cassette_color_album),
                        CassetteColorSource.MATERIAL_YOU to stringResource(R.string.settings_cassette_color_material_you),
                        CassetteColorSource.CUSTOM       to stringResource(R.string.settings_cassette_color_custom),
                    ),
                    selected = settings.colorSource,
                    onSelect = onColorSource,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                RevealSection(visible = settings.colorSource == CassetteColorSource.ALBUM_ART) {
                    VisualizerTip(stringResource(R.string.settings_cassette_color_album_tip))
                }
                RevealSection(visible = settings.colorSource == CassetteColorSource.MATERIAL_YOU) {
                    VisualizerTip(stringResource(R.string.settings_cassette_color_material_you_tip))
                }
                RevealSection(visible = settings.colorSource == CassetteColorSource.CUSTOM) {
                    Column {
                        // Preset swatches — a radio group; a tap persists at once. Each target is
                        // 48dp around a 36dp circle, so the targets abut (a 12dp gap between the
                        // circles) and the padding is trimmed by the 6dp margin to keep the first
                        // circle on the 16dp content edge.
                        Row(
                            modifier              = Modifier
                                .fillMaxWidth()
                                .selectableGroup()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            CassetteSwatches.forEach { swatch ->
                                SwatchCircle(
                                    color    = Color(swatch.argb),
                                    name     = stringResource(swatch.nameRes),
                                    selected = swatch.argb == settings.customColor,
                                    onClick  = { haptics.tick(); pickSwatch(swatch.argb) },
                                )
                            }
                        }
                        CassetteSliderRow(
                            label                 = stringResource(R.string.settings_cassette_hue),
                            dot                   = customSeed,
                            value                 = hue,
                            valueRange            = 0f..360f,
                            onValueChange         = { hue = it; dragged = true },
                            onValueChangeFinished = persistCustom,
                        )
                        Spacer(Modifier.height(4.dp))
                        CassetteSliderRow(
                            label                 = stringResource(R.string.settings_cassette_saturation),
                            dot                   = customSeed,
                            value                 = sat,
                            valueRange            = CASSETTE_SAT_MIN..CASSETTE_SAT_MAX,
                            onValueChange         = { sat = it; dragged = true },
                            onValueChangeFinished = persistCustom,
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(16.dp))

                // ── Idle delay ──
                VisualizerSectionLabel(stringResource(R.string.settings_cassette_idle_section))
                CassetteIdleDelayField(stored = settings.idleSeconds, onIdleSeconds = onIdleSeconds)
                VisualizerTip(
                    pluralStringResource(
                        R.plurals.settings_cassette_idle_tip,
                        CASSETTE_IDLE_DEFAULT_SECONDS,
                        CASSETTE_IDLE_DEFAULT_SECONDS,
                    ),
                )

                HorizontalDivider(modifier = Modifier.padding(16.dp))

                // ── Screen ──
                VisualizerSectionLabel(stringResource(R.string.settings_cassette_screen_section))
                TwoLineToggleRow(
                    title           = stringResource(R.string.settings_cassette_keep_screen_on),
                    subtitle        = stringResource(R.string.settings_cassette_keep_screen_on_desc),
                    checked         = settings.keepScreenOn,
                    onCheckedChange = onKeepScreenOn,
                )
                // Dim is a sub-setting of keep-screen-on: hidden (not cleared) while it is off.
                RevealSection(visible = settings.keepScreenOn) {
                    val dimSeconds = (CassetteTiming.DimDelayMs / 1_000L).toInt()
                    TwoLineToggleRow(
                        title           = pluralStringResource(R.plurals.settings_cassette_dim, dimSeconds, dimSeconds),
                        subtitle        = stringResource(
                            R.string.settings_cassette_dim_desc,
                            (CassetteTiming.DimBrightness * 100f).roundToInt(),
                        ),
                        checked         = settings.dimAfterDelay,
                        onCheckedChange = onDim,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(16.dp))

                // ── Hint ──
                VisualizerSectionLabel(stringResource(R.string.settings_cassette_hint_section))
                TwoLineToggleRow(
                    title           = stringResource(R.string.settings_cassette_show_exit_hint),
                    subtitle        = stringResource(R.string.settings_cassette_show_exit_hint_desc),
                    checked         = settings.showExitHint,
                    onCheckedChange = onShowExitHint,
                )

                HorizontalDivider(modifier = Modifier.padding(16.dp))

                VisualizerTip(
                    pluralStringResource(
                        R.plurals.settings_cassette_footer_tip,
                        settings.idleSeconds,
                        settings.idleSeconds,
                    ),
                )

                Spacer(Modifier.navigationBarsPadding())
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── Idle delay field ──────────────────────────────────────────────────────────────────────────

/**
 * The idle delay in seconds, typed. A number already inside 5..600 is persisted at each keystroke
 * (no error); an empty or out-of-range field shows the range as an error and persists NOTHING. On
 * Done (which only clears focus), on focus loss, and when the sheet goes away with the field still
 * focused, the text is clamped into range — an empty field falls back to the stored value — then
 * persisted and written back. The text is re-seeded from the store only on an OUTSIDE change, never
 * by this field's own write coming back ([CassetteOwnWrites]).
 */
@Composable
private fun CassetteIdleDelayField(stored: Int, onIdleSeconds: (Int) -> Unit) {
    val focusManager   = LocalFocusManager.current
    val ownWrites      = remember { CassetteOwnWrites() }
    val currentStored  by rememberUpdatedState(stored)
    val currentPersist by rememberUpdatedState(onIdleSeconds)
    var text           by rememberSaveable { mutableStateOf(stored.toString()) }
    // The stored value the text last reflected — saveable with the text, so a restored in-progress
    // entry is not overwritten by the (unchanged) stored value on the first composition.
    var seenStored     by rememberSaveable { mutableIntStateOf(stored) }
    LaunchedEffect(stored) {
        if (stored != seenStored) {
            seenStored = stored
            if (!ownWrites.isEcho(stored)) text = stored.toString()
        }
    }

    val persist = { seconds: Int ->
        if (seconds != ownWrites.expected(currentStored)) {
            ownWrites.record(seconds)
            currentPersist(seconds)
        }
    }
    val commit = {
        val committed = cassetteIdleCommitted(text, ownWrites.expected(currentStored))
        text = committed.toString()
        persist(committed)
    }
    // Focus is tracked outside composition (read only by the callbacks below).
    val hadFocus = remember { booleanArrayOf(false) }
    val currentCommit by rememberUpdatedState(commit)
    DisposableEffect(Unit) {
        // Dismissed with the field focused (a back after the keyboard went, a scrim tap): the
        // focus-loss callback is not guaranteed on the way out, so commit here; a second commit is
        // a no-op (persist skips a value the store will already hold).
        onDispose { if (hadFocus[0]) currentCommit() }
    }

    val isError = cassetteIdleTyped(text) == null
    OutlinedTextField(
        value           = text,
        onValueChange   = { raw ->
            // An over-length edit is rejected whole: the text stays as it was and nothing is saved.
            val digits = cassetteIdleDigits(raw) ?: return@OutlinedTextField
            text = digits
            cassetteIdleTyped(digits)?.let(persist)
        },
        label           = { Text(stringResource(R.string.settings_cassette_idle_label)) },
        suffix          = { Text(stringResource(R.string.settings_cassette_idle_suffix)) },
        supportingText  = if (isError) {
            {
                Text(
                    pluralStringResource(
                        R.plurals.settings_cassette_idle_error,
                        CASSETTE_IDLE_MAX_SECONDS,
                        CASSETTE_IDLE_MIN_SECONDS,
                        CASSETTE_IDLE_MAX_SECONDS,
                    ),
                )
            }
        } else null,
        isError         = isError,
        singleLine      = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        // Done only clears focus; the commit runs once, from the focus-loss path.
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier        = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .onFocusChanged { state ->
                if (hadFocus[0] && !state.hasFocus) commit()
                hadFocus[0] = state.hasFocus
            },
    )
}

// ── Row helpers ───────────────────────────────────────────────────────────────────────────────

/** Reveal/hide with the Settings list's own finite expand + fade (never a spring). */
@Composable
private fun RevealSection(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn(screenTransitionSpec()) + expandVertically(screenTransitionSpec<IntSize>()),
        exit    = shrinkVertically(screenTransitionSpec<IntSize>()) + fadeOut(screenTransitionSpec()),
    ) { content() }
}

/**
 * A title + subtitle row with a trailing switch, as ONE `toggleable(Role.Switch)` node with an inert
 * `Switch` inside — the two-line sibling of [SyncToggleRow], without [SettingsToggleItem]'s icon.
 */
@Composable
internal fun TwoLineToggleRow(
    title          : String,
    subtitle       : String,
    checked        : Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = { haptics.toggle(it); onCheckedChange(it) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A 36dp preset circle centred in a 48dp touch target (the app's minimum); the selected one is
 * ringed in `onSurface` with a gap. The target carries the selectable + its name, one focus node.
 */
@Composable
private fun SwatchCircle(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier         = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .then(if (selected) Modifier.border(2.dp, ring, CircleShape).padding(5.dp) else Modifier)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** "Hue" / "Saturation": the label above, the current seed as a dot at the start of the slider. */
@Composable
private fun CassetteSliderRow(
    label                : String,
    dot                  : Color,
    value                : Float,
    valueRange           : ClosedFloatingPointRange<Float>,
    onValueChange        : (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(12.dp))
            ValueSlider(
                value                 = value,
                onValueChange         = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange            = valueRange,
                modifier              = Modifier.weight(1f).semantics { contentDescription = label },
            )
        }
    }
}
