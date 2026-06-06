package com.crsmthw.lyra.ui.screens.settings

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsmthw.lyra.BuildConfig
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack   : () -> Unit,
    onLogout : () -> Unit,
) {
    val themeMode       by viewModel.themeMode.collectAsStateWithLifecycle()
    val amoledBlack     by viewModel.amoledBlack.collectAsStateWithLifecycle()
    val dynamicColor    by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val imageCacheBytes        by viewModel.imageCacheBytes.collectAsStateWithLifecycle()
    val libraryCacheBytes      by viewModel.libraryCacheBytes.collectAsStateWithLifecycle()
    var showLogoutDialog  by remember { mutableStateOf(false) }
    var showThemeDialog   by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier     = Modifier.statusBarsPadding(),
                windowInsets = WindowInsets(0),
                title        = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Spotify ───────────────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_spotify))

            SettingsItem(
                icon    = Icons.Default.Key,
                title   = stringResource(R.string.settings_client_id),
                subtitle= viewModel.clientIdMasked(),
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Appearance ────────────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_appearance))

            // Theme picker
            SettingsItem(
                icon     = Icons.Default.Palette,
                title    = stringResource(R.string.settings_theme),
                subtitle = when (themeMode) {
                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                    ThemeMode.LIGHT  -> stringResource(R.string.settings_theme_light)
                    ThemeMode.DARK   -> stringResource(R.string.settings_theme_dark)
                },
                onClick  = { showThemeDialog = true },
            )

            // AMOLED toggle (only meaningful when dark is active)
            SettingsToggleItem(
                icon    = Icons.Default.DarkMode,
                title   = stringResource(R.string.settings_amoled),
                subtitle= stringResource(R.string.settings_amoled_desc),
                checked = amoledBlack,
                onCheckedChange = viewModel::setAmoledBlack,
            )

            // Material You toggle
            SettingsToggleItem(
                icon    = Icons.Default.ColorLens,
                title   = stringResource(R.string.settings_dynamic_color),
                subtitle= stringResource(R.string.settings_dynamic_color_desc),
                checked = dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Notifications ─────────────────────────────────────────────────
            if (Build.VERSION.SDK_INT >= 36) {
                val context = LocalContext.current
                val nm = context.getSystemService(NotificationManager::class.java)
                var liveNotifsEnabled by remember { mutableStateOf(nm.canPostPromotedNotifications()) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    liveNotifsEnabled = nm.canPostPromotedNotifications()
                }
                var showLiveNotifHelpDialog by remember { mutableStateOf(false) }

                SettingsSectionHeader(stringResource(R.string.settings_notifications))
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
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                showLiveNotifHelpDialog = false
                            }) { Text(stringResource(R.string.settings_live_notif_dialog_open_dev_options)) }
                        },
                        dismissButton  = {
                            TextButton(onClick = { showLiveNotifHelpDialog = false }) {
                                Text(stringResource(R.string.settings_live_notif_dialog_got_it))
                            }
                        },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

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
                        onClick  = { showClearCacheDialog = true },
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
                            viewModel.clearImageCache()
                            showClearCacheDialog = false
                        }) { Text("Clear") }
                    },
                    dismissButton    = {
                        TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
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
                        onClick  = { showClearLibraryDialog = true },
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
                            viewModel.clearLibraryCache()
                            showClearLibraryDialog = false
                        }) { Text("Clear") }
                    },
                    dismissButton    = {
                        TextButton(onClick = { showClearLibraryDialog = false }) { Text("Cancel") }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Account ───────────────────────────────────────────────────────
            SettingsSectionHeader("Account")

            SettingsItem(
                icon    = Icons.AutoMirrored.Filled.Logout,
                title   = stringResource(R.string.settings_logout),
                subtitle= "Clears tokens and returns to login",
                onClick = { showLogoutDialog = true },
                tintError = true,
            )

            // ── About ─────────────────────────────────────────────────────────────
            AboutSection()

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Theme picker dialog ───────────────────────────────────────────────────
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title            = { Text("Theme") },
            text             = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier          = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick  = {
                                    viewModel.setThemeMode(mode)
                                    showThemeDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(when (mode) {
                                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                ThemeMode.LIGHT  -> stringResource(R.string.settings_theme_light)
                                ThemeMode.DARK   -> stringResource(R.string.settings_theme_dark)
                            })
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── Logout confirmation dialog ────────────────────────────────────────────
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title            = { Text("Disconnect Spotify?") },
            text             = { Text("This will clear your tokens. You'll need to reconnect your Client ID.") },
            confirmButton    = {
                TextButton(onClick = { showLogoutDialog = false; onLogout() },
                           colors  = ButtonDefaults.textButtonColors(
                               contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Disconnect")
                }
            },
            dismissButton    = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ── About section ────────────────────────────────────────────────────────────

@Composable
private fun AboutSection() {
    val context = LocalContext.current

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
    val iconTint = if (tintError) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.onSurfaceVariant

    ListItem(
        leadingContent   = { Icon(icon, contentDescription = null, tint = iconTint) },
        headlineContent  = {
            Text(title, color = if (tintError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent= subtitle?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        modifier         = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
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
    ListItem(
        leadingContent   = { Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent  = { Text(title) },
        supportingContent= subtitle?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent  = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}


