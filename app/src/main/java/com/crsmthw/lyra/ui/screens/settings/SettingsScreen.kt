package com.crsmthw.lyra.ui.screens.settings

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import com.crsmthw.lyra.util.tick
import com.crsmthw.lyra.util.toggle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsmthw.lyra.BuildConfig
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.components.HeroBandHeight
import com.crsmthw.lyra.ui.components.TitlePill
import com.crsmthw.lyra.ui.components.TopActionPill
import com.crsmthw.lyra.ui.components.TopScrim
import com.crsmthw.lyra.ui.components.rememberHeroScrollProgress
import com.crsmthw.lyra.ui.theme.ThemeMode
import com.crsmthw.lyra.util.visualizer.VisualizerStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack   : () -> Unit,
    onLogout : () -> Unit,
) {
    val themeMode           by viewModel.themeMode.collectAsStateWithLifecycle()
    val amoledBlack         by viewModel.amoledBlack.collectAsStateWithLifecycle()
    val dynamicColor        by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val visualizerEnabled   by viewModel.visualizerEnabled.collectAsStateWithLifecycle()
    val visualizerStyle     by viewModel.visualizerStyle.collectAsStateWithLifecycle()
    val visualizerResolution by viewModel.visualizerResolution.collectAsStateWithLifecycle()
    val visualizerDramatic  by viewModel.visualizerDramatic.collectAsStateWithLifecycle()
    val visualizerResolutionBottom by viewModel.visualizerResolutionBottom.collectAsStateWithLifecycle()
    val visualizerResolutionSync by viewModel.visualizerResolutionSync.collectAsStateWithLifecycle()
    val visualizerGain      by viewModel.visualizerGain.collectAsStateWithLifecycle()
    val visualizerGainBottom by viewModel.visualizerGainBottom.collectAsStateWithLifecycle()
    val visualizerGainSync  by viewModel.visualizerGainSync.collectAsStateWithLifecycle()
    val hapticsEnabled      by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val haptics              = LocalHapticFeedback.current
    val imageCacheBytes        by viewModel.imageCacheBytes.collectAsStateWithLifecycle()
    val libraryCacheBytes      by viewModel.libraryCacheBytes.collectAsStateWithLifecycle()
    var showLogoutDialog  by remember { mutableStateOf(false) }
    var showThemeSheet    by remember { mutableStateOf(false) }
    var showVisualizerSheet by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        val density        = LocalDensity.current
        val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val scrimHeight    = navBarBottomDp + 48.dp
        val background     = MaterialTheme.colorScheme.background
        val scrollState    = rememberScrollState()
        val heroHeight     = HeroBandHeight
        val titlePillAlpha = rememberHeroScrollProgress(scrollState, heroHeight)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {

            // ── Settings hero ──────────────────────────────────────────────────
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(heroHeight),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text     = stringResource(R.string.settings_title),
                    style    = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }

            // ── Spotify ───────────────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_spotify))

            SettingsItem(
                icon    = Icons.Default.Key,
                title   = stringResource(R.string.settings_client_id),
                subtitle= viewModel.clientIdMasked(),
            )

            SettingsItem(
                icon      = Icons.AutoMirrored.Filled.Logout,
                title     = stringResource(R.string.settings_logout),
                subtitle  = stringResource(R.string.settings_logout_desc),
                onClick   = { showLogoutDialog = true },
                tintError = true,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Lyra ──────────────────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_lyra))

            // Theme — opens the display-theme modal sheet (mode + AMOLED + Material You)
            SettingsItem(
                icon     = Icons.Default.Palette,
                title    = stringResource(R.string.settings_theme),
                subtitle = when (themeMode) {
                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                    ThemeMode.LIGHT  -> stringResource(R.string.settings_theme_light)
                    ThemeMode.DARK   -> stringResource(R.string.settings_theme_dark)
                },
                onClick  = { showThemeSheet = true },
            )

            // Haptic feedback toggle (app-wide)
            SettingsToggleItem(
                icon    = Icons.Default.Vibration,
                title   = stringResource(R.string.settings_haptics),
                subtitle= stringResource(R.string.settings_haptics_desc),
                checked = hapticsEnabled,
                onCheckedChange = viewModel::setHapticsEnabled,
            )

            // Visualizer toggle
            SettingsToggleItem(
                icon            = Icons.Default.Equalizer,
                title           = stringResource(R.string.player_visualizer),
                subtitle        = stringResource(R.string.settings_visualizer_desc),
                checked         = visualizerEnabled,
                onCheckedChange = viewModel::setVisualizerEnabled,
            )

            // Advanced visualizer settings — revealed only when the visualizer is on; opens a
            // bottom sheet (style / resolution / dramatic peaks) to keep the main list tidy.
            AnimatedVisibility(
                visible = visualizerEnabled,
                enter   = fadeIn() + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()),
                exit    = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()) + fadeOut(),
            ) {
                SettingsItem(
                    icon     = Icons.Default.Tune,
                    title    = stringResource(R.string.settings_visualizer_advanced),
                    subtitle = stringResource(R.string.settings_visualizer_advanced_desc),
                    onClick  = { showVisualizerSheet = true },
                )
            }

            // Live notifications (Android 16+) — sleep timer as a pinned Live notification.
            if (Build.VERSION.SDK_INT >= 36) {
                val context = LocalContext.current
                val nm = context.getSystemService(NotificationManager::class.java)
                var liveNotifsEnabled by remember { mutableStateOf(nm.canPostPromotedNotifications()) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    liveNotifsEnabled = nm.canPostPromotedNotifications()
                }
                var showLiveNotifHelpDialog by remember { mutableStateOf(false) }

                ListItem(
                    leadingContent   = {
                        Icon(Icons.Default.Timer, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    headlineContent  = { Text(stringResource(R.string.settings_live_notifications)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (liveNotifsEnabled) R.string.settings_live_notifications_on
                                else R.string.settings_live_notifications_off
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent  = {
                        if (liveNotifsEnabled) {
                            Icon(Icons.Default.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = {
                                haptics.press()
                                if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                                    showLiveNotifHelpDialog = true
                                } else {
                                    @Suppress("NewApi")
                                    val launched = try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            }
                                        ); true
                                    } catch (_: android.content.ActivityNotFoundException) { false }
                                    if (!launched) context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        }
                                    )
                                }
                            }) {
                                Text(stringResource(R.string.settings_live_notifications_enable))
                            }
                        }
                    },
                )

                if (showLiveNotifHelpDialog) {
                    AlertDialog(
                        onDismissRequest = { showLiveNotifHelpDialog = false },
                        title  = { Text(stringResource(R.string.settings_live_notif_dialog_title)) },
                        text   = { Text(stringResource(R.string.settings_live_notif_dialog_samsung)) },
                        confirmButton  = {
                            TextButton(onClick = {
                                haptics.confirm()
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                showLiveNotifHelpDialog = false
                            }) { Text(stringResource(R.string.settings_live_notif_dialog_open_dev_options)) }
                        },
                        dismissButton  = {
                            TextButton(onClick = { haptics.press(); showLiveNotifHelpDialog = false }) {
                                Text(stringResource(R.string.settings_live_notif_dialog_got_it))
                            }
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Storage ───────────────────────────────────────────────────────
            SettingsSectionHeader("Storage")

            var showClearCacheDialog by remember { mutableStateOf(false) }
            ListItem(
                leadingContent   = { Icon(Icons.Default.Image, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                headlineContent  = { Text("Image Cache") },
                supportingContent = { Text(formatBytes(imageCacheBytes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent  = {
                    TextButton(
                        onClick  = { haptics.press(); showClearCacheDialog = true },
                        enabled  = imageCacheBytes > 0L,
                    ) { Text("Clear") }
                },
            )

            if (showClearCacheDialog) {
                AlertDialog(
                    onDismissRequest = { showClearCacheDialog = false },
                    title            = { Text("Clear Image Cache?") },
                    text             = { Text("Playlist artwork will be re-downloaded next time it's needed.") },
                    confirmButton    = {
                        TextButton(onClick = {
                            haptics.confirm()
                            viewModel.clearImageCache()
                            showClearCacheDialog = false
                        }) { Text("Clear") }
                    },
                    dismissButton    = {
                        TextButton(onClick = { haptics.press(); showClearCacheDialog = false }) { Text("Cancel") }
                    },
                )
            }

            var showClearLibraryDialog by remember { mutableStateOf(false) }
            ListItem(
                leadingContent   = { Icon(Icons.Default.LibraryMusic, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                headlineContent  = { Text("Library Cache") },
                supportingContent = { Text(formatBytes(libraryCacheBytes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent  = {
                    TextButton(
                        onClick  = { haptics.press(); showClearLibraryDialog = true },
                        enabled  = libraryCacheBytes > 0L,
                    ) { Text("Clear") }
                },
            )

            if (showClearLibraryDialog) {
                AlertDialog(
                    onDismissRequest = { showClearLibraryDialog = false },
                    title            = { Text("Clear Library Cache?") },
                    text             = { Text("Playlist and track data will be re-fetched from Spotify next time you open the library.") },
                    confirmButton    = {
                        TextButton(onClick = {
                            haptics.confirm()
                            viewModel.clearLibraryCache()
                            showClearLibraryDialog = false
                        }) { Text("Clear") }
                    },
                    dismissButton    = {
                        TextButton(onClick = { haptics.press(); showClearLibraryDialog = false }) { Text("Cancel") }
                    },
                )
            }

            // ── About ─────────────────────────────────────────────────────────────
            AboutSection()

            Spacer(Modifier.height(scrimHeight))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scrimHeight)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, background)))
            )

            // Top scrim — fades content under the status bar (mirror of the bottom scrim).
            TopScrim(
                color    = background,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // Floating back pill + title pill (fades in once the hero scrolls away) — top-left cluster.
            Row(
                modifier              = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TopActionPill {
                    IconButton(onClick = { haptics.press(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back))
                    }
                }
                TitlePill(
                    text     = stringResource(R.string.settings_title),
                    modifier = Modifier.graphicsLayer { alpha = titlePillAlpha.value },
                )
            }
        }
    }

    // ── Display-theme modal sheet ─────────────────────────────────────────────
    if (showThemeSheet) {
        ThemeSheet(
            themeMode    = themeMode,
            amoledBlack  = amoledBlack,
            dynamicColor = dynamicColor,
            onThemeMode  = viewModel::setThemeMode,
            onAmoled     = viewModel::setAmoledBlack,
            onDynamic    = viewModel::setDynamicColor,
            onDismiss    = { showThemeSheet = false },
        )
    }

    // ── Advanced visualizer modal sheet ───────────────────────────────────────
    if (showVisualizerSheet) {
        VisualizerSheet(
            style              = visualizerStyle,
            resolution         = visualizerResolution,
            resolutionBottom   = visualizerResolutionBottom,
            resolutionSync     = visualizerResolutionSync,
            gain               = visualizerGain,
            gainBottom         = visualizerGainBottom,
            gainSync           = visualizerGainSync,
            dramatic           = visualizerDramatic,
            onStyle            = viewModel::setVisualizerStyle,
            onResolution       = viewModel::setVisualizerResolution,
            onResolutionBottom = viewModel::setVisualizerResolutionBottom,
            onResolutionSync   = viewModel::setVisualizerResolutionSync,
            onGain             = viewModel::setVisualizerGain,
            onGainBottom       = viewModel::setVisualizerGainBottom,
            onGainSync         = viewModel::setVisualizerGainSync,
            onDramatic         = viewModel::setVisualizerDramatic,
            onReset            = viewModel::resetVisualizerSettings,
            onDismiss          = { showVisualizerSheet = false },
        )
    }

    // ── Logout confirmation dialog ────────────────────────────────────────────
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title            = { Text("Disconnect Spotify?") },
            text             = { Text("This will clear your tokens. You'll need to reconnect your Client ID.") },
            confirmButton    = {
                TextButton(onClick = { haptics.confirm(); showLogoutDialog = false; onLogout() },
                           colors  = ButtonDefaults.textButtonColors(
                               contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Disconnect")
                }
            },
            dismissButton    = {
                TextButton(onClick = { haptics.press(); showLogoutDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Display-theme modal sheet ──────────────────────────────────────────────────

/**
 * All display-theme controls in one place: a connected mode picker (System / Light / Dark) plus the
 * AMOLED and Material You toggles. The mode picker uses [ConnectedChoiceRow] so each segment
 * spring-morphs its shape on selection / press. Toggles reuse [SettingsToggleItem] (inherits haptics).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeSheet(
    themeMode   : ThemeMode,
    amoledBlack : Boolean,
    dynamicColor: Boolean,
    onThemeMode : (ThemeMode) -> Unit,
    onAmoled    : (Boolean) -> Unit,
    onDynamic   : (Boolean) -> Unit,
    onDismiss   : () -> Unit,
) {
    // Hidden ↔ Expanded only (no half-height partial detent) so the sheet doesn't settle into a
    // partial detent shortly after opening and clip its lower toggles. (positional: initialValue, values)
    val sheetState = rememberBottomSheetState(
        SheetValue.Hidden,
        setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
    ) {
        // Header
        Row(
            modifier          = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Palette, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                text  = stringResource(R.string.settings_theme_display),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        // Mode label + connected segment picker
        Text(
            text     = stringResource(R.string.settings_theme_mode),
            style    = MaterialTheme.typography.labelLarge,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        ConnectedChoiceRow(
            options  = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system_short),
                ThemeMode.LIGHT  to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK   to stringResource(R.string.settings_theme_dark),
            ),
            selected = themeMode,
            onSelect = onThemeMode,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SettingsToggleItem(
            icon            = Icons.Default.DarkMode,
            title           = stringResource(R.string.settings_amoled),
            subtitle        = stringResource(R.string.settings_amoled_desc),
            checked         = amoledBlack,
            onCheckedChange = onAmoled,
        )
        SettingsToggleItem(
            icon            = Icons.Default.ColorLens,
            title           = stringResource(R.string.settings_dynamic_color),
            subtitle        = stringResource(R.string.settings_dynamic_color_desc),
            checked         = dynamicColor,
            onCheckedChange = onDynamic,
        )

        Spacer(Modifier.navigationBarsPadding())
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Advanced visualizer settings bottom sheet — surfaces (style), resolution (band count) and the
 * dramatic-peaks (RMS vs mean) toggle. Mirrors [ThemeSheet]: Hidden↔Expanded only, connected
 * segment picker for style, [SettingsToggleItem] for the toggle. Opened from the main settings list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VisualizerSheet(
    style             : VisualizerStyle,
    resolution        : Int,
    resolutionBottom  : Int,
    resolutionSync    : Boolean,
    gain              : Int,
    gainBottom        : Int,
    gainSync          : Boolean,
    dramatic          : Boolean,
    onStyle           : (VisualizerStyle) -> Unit,
    onResolution      : (Int) -> Unit,
    onResolutionBottom: (Int) -> Unit,
    onResolutionSync  : (Boolean) -> Unit,
    onGain            : (Int) -> Unit,
    onGainBottom      : (Int) -> Unit,
    onGainSync        : (Boolean) -> Unit,
    onDramatic        : (Boolean) -> Unit,
    onReset           : () -> Unit,
    onDismiss         : () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        SheetValue.Hidden,
        setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val both = style == VisualizerStyle.BOTH
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
    ) {
        // Header
        Row(
            modifier          = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Equalizer, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                text  = stringResource(R.string.settings_visualizer_advanced),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Surfaces ──
        VisualizerSectionLabel(stringResource(R.string.settings_visualizer_style_label))
        ConnectedChoiceRow(
            options  = listOf(
                VisualizerStyle.CIRCLE to stringResource(R.string.settings_visualizer_style_circle),
                VisualizerStyle.BOTTOM to stringResource(R.string.settings_visualizer_style_bottom),
                VisualizerStyle.BOTH   to stringResource(R.string.settings_visualizer_style_both),
            ),
            selected = style,
            onSelect = onStyle,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        VisualizerTip(stringResource(R.string.settings_visualizer_surfaces_tip))

        HorizontalDivider(modifier = Modifier.padding(16.dp))

        // ── Resolution (sync splits circle/bottom only when style is Both) ──
        VisualizerSectionLabel(stringResource(R.string.settings_visualizer_resolution_section))
        if (both) {
            SyncToggleRow(stringResource(R.string.settings_visualizer_sync), resolutionSync, onResolutionSync)
            if (resolutionSync) {
                ResolutionSliderRow(null, resolution, onResolution)
            } else {
                ResolutionSliderRow(stringResource(R.string.settings_visualizer_label_circle), resolution, onResolution)
                ResolutionSliderRow(stringResource(R.string.settings_visualizer_label_bottom), resolutionBottom, onResolutionBottom)
            }
        } else {
            ResolutionSliderRow(null, resolution, onResolution)
        }
        VisualizerTip(stringResource(R.string.settings_visualizer_resolution_tip))

        HorizontalDivider(modifier = Modifier.padding(16.dp))

        // ── Gain offset ──
        VisualizerSectionLabel(stringResource(R.string.settings_visualizer_gain_section))
        if (both) {
            SyncToggleRow(stringResource(R.string.settings_visualizer_sync), gainSync, onGainSync)
            if (gainSync) {
                GainSliderRow(null, gain, onGain)
            } else {
                GainSliderRow(stringResource(R.string.settings_visualizer_label_circle), gain, onGain)
                GainSliderRow(stringResource(R.string.settings_visualizer_label_bottom), gainBottom, onGainBottom)
            }
        } else {
            GainSliderRow(null, gain, onGain)
        }
        VisualizerTip(stringResource(R.string.settings_visualizer_gain_tip))

        HorizontalDivider(modifier = Modifier.padding(16.dp))

        // ── Averaging method (RMS vs mean) — same connected picker as Surfaces ──
        VisualizerSectionLabel(stringResource(R.string.settings_visualizer_averaging_section))
        ConnectedChoiceRow(
            options  = listOf(
                true  to stringResource(R.string.settings_visualizer_avg_rms),
                false to stringResource(R.string.settings_visualizer_avg_mean),
            ),
            selected = dramatic,
            onSelect = onDramatic,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        VisualizerTip(stringResource(R.string.settings_visualizer_averaging_tip))

        // ── Reset (elevated card, error-tinted, confirmation dialog) ──
        val resetHaptics = LocalHapticFeedback.current
        var showResetConfirm by remember { mutableStateOf(false) }
        ElevatedCard(
            onClick  = { resetHaptics.press(); showResetConfirm = true },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(
                    text  = stringResource(R.string.settings_visualizer_reset),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                icon  = { Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(stringResource(R.string.settings_visualizer_reset_title)) },
                text  = { Text(stringResource(R.string.settings_visualizer_reset_message)) },
                confirmButton = {
                    TextButton(
                        onClick = { resetHaptics.confirm(); onReset(); showResetConfirm = false },
                        colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.settings_visualizer_reset_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { resetHaptics.press(); showResetConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        Spacer(Modifier.navigationBarsPadding())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VisualizerSectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun VisualizerTip(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.bodySmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SyncToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier          = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { haptics.toggle(it); onCheckedChange(it) })
    }
}

/** Resolution slider (4..128 bands). [prefix] = null shows just "N bands"; otherwise "<prefix> · N bands". */
@Composable
private fun ResolutionSliderRow(prefix: String?, bands: Int, onBands: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val resolutions = remember { listOf(4, 8, 16, 24, 32, 64, 128) }
    var pos by remember(bands) { mutableFloatStateOf(resolutions.indexOf(bands).coerceAtLeast(0).toFloat()) }
    val n = resolutions[pos.roundToInt()]
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text  = if (prefix == null) stringResource(R.string.settings_visualizer_res_bands, n)
                    else stringResource(R.string.settings_visualizer_res_bands_labeled, prefix, n),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value                 = pos,
            onValueChange         = { v -> if (v.roundToInt() != pos.roundToInt()) haptics.tick(); pos = v },
            onValueChangeFinished = { onBands(resolutions[pos.roundToInt()]) },
            valueRange            = 0f..(resolutions.size - 1).toFloat(),
            steps                 = resolutions.size - 2,
        )
    }
}

/** Gain-offset slider (-3..+3). [prefix] = null shows just the signed value; otherwise "<prefix> · +N". */
@Composable
private fun GainSliderRow(prefix: String?, offset: Int, onOffset: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pos by remember(offset) { mutableFloatStateOf(offset.toFloat()) }
    val cur = pos.roundToInt()
    val signed = if (cur > 0) "+$cur" else cur.toString()
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text  = if (prefix == null) signed
                    else stringResource(R.string.settings_visualizer_gain_labeled, prefix, signed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value                 = pos,
            onValueChange         = { v -> if (v.roundToInt() != pos.roundToInt()) haptics.tick(); pos = v },
            onValueChangeFinished = { onOffset(pos.roundToInt()) },
            valueRange            = -3f..3f,
            steps                 = 5,
        )
    }
}

// ── Connected single-choice picker (M3 Expressive morph) ────────────────────────

/**
 * A connected single-choice picker (theme mode, visualizer style) built on the full M3 Expressive
 * [ButtonGroup]: each `toggleableItem` segment gets the connected leading/middle/trailing shape morph
 * (rounds → squircle on select, squish on press) AND the inter-button press-squeeze — pressing one
 * segment expands it while compressing its neighbours, then springs back. A real overflow indicator
 * is supplied so the measure pass always has a home for an item that can't fit, never the
 * empty-overflow path that mis-measures in genuinely tight layouts. Picking fires a `press` haptic.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedChoiceRow(
    options : List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    ButtonGroup(
        overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
        modifier          = modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            toggleableItem(
                checked         = selected == value,
                label           = label,
                onCheckedChange = { isChecked ->
                    if (isChecked && selected != value) { haptics.press(); onSelect(value) }
                },
                weight          = 1f,
            )
        }
    }
}

// ── About section ────────────────────────────────────────────────────────────

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    Column(
        modifier            = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(28.dp))

        // App icon — large rounded square tile
        Image(
            painter            = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier           = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(26.dp))
                .scale(1.5f),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text      = stringResource(R.string.app_name),
            style     = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )

        Text(
            text      = stringResource(R.string.about_tagline),
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Text(
            text      = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // Credits card
        Card(
            modifier  = Modifier.fillMaxWidth(),
            shape     = RoundedCornerShape(16.dp),
            colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text  = stringResource(R.string.about_credits_section),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFE91E63), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.about_credit_app), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.about_credit_app_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFE91E63), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.about_credit_icon), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.about_credit_icon_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // GitHub card — tappable
        Card(
            modifier  = Modifier
                .fillMaxWidth()
                .clickable {
                    haptics.press()
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/CrsMthw".toUri()))
                },
            shape     = RoundedCornerShape(16.dp),
            colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Row(
                modifier          = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier         = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier           = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.about_github), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.about_github_url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(20.dp))

        // Buy Me a Coffee — actual BMC PNG, clickable
        Image(
            painter            = painterResource(R.drawable.bmc_button),
            contentDescription = stringResource(R.string.about_bmac_desc),
            modifier           = Modifier
                .fillMaxWidth(0.7f)
                .clickable {
                    haptics.press()
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://buymeacoffee.com/crsmthw".toUri()))
                },
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text      = stringResource(R.string.about_bottom_note),
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L     -> "%.1f KB".format(bytes / 1_024.0)
    bytes > 0L          -> "$bytes B"
    else                -> "Empty"
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsItem(
    icon      : androidx.compose.ui.graphics.vector.ImageVector,
    title     : String,
    subtitle  : String? = null,
    onClick   : (() -> Unit)? = null,
    tintError : Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val iconTint = if (tintError) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.onSurfaceVariant

    ListItem(
        leadingContent   = { Icon(icon, contentDescription = null, tint = iconTint) },
        headlineContent  = {
            Text(title, color = if (tintError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent= subtitle?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        modifier         = if (onClick != null) Modifier.clickable { haptics.press(); onClick() } else Modifier,
    )
}

@Composable
private fun SettingsToggleItem(
    icon           : androidx.compose.ui.graphics.vector.ImageVector,
    title          : String,
    subtitle       : String? = null,
    checked        : Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val toggleWithHaptic: (Boolean) -> Unit = { enabled -> haptics.toggle(enabled); onCheckedChange(enabled) }
    ListItem(
        leadingContent   = { Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent  = { Text(title) },
        supportingContent= subtitle?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent  = {
            Switch(checked = checked, onCheckedChange = toggleWithHaptic)
        },
        modifier = Modifier.clickable { toggleWithHaptic(!checked) },
    )
}
