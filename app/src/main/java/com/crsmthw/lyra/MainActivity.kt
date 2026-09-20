package com.crsmthw.lyra

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.crsmthw.lyra.ui.ipod.IPodRoot
import com.crsmthw.lyra.ui.navigation.LyraNavGraph
import com.crsmthw.lyra.ui.theme.LyraTheme
import com.crsmthw.lyra.ui.theme.ThemeMode
import com.crsmthw.lyra.util.HapticsConfig
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // The VIEW intent that brought us here, cold start or warm. LyraNavGraph funnels it into the
    // link-resolver destination itself — no destination declares a navDeepLink any more, so
    // NavHost's own automatic handleDeepLink on the launch intent has nothing to match.
    private var pendingDeepLinkIntent by mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode is singleTop, so a link tapped while Lyra is alive arrives here rather than
        // in a second instance. Keep getIntent() in step with what we are about to act on.
        setIntent(intent)
        pendingDeepLinkIntent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Cold-start deep link — seeded only on a FRESH launch. On an Activity recreation the
        // same VIEW intent is re-delivered, and re-firing it would push a second copy of the
        // destination on top of the back stack Navigation has just restored.
        if (savedInstanceState == null) pendingDeepLinkIntent = intent
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()

        val container = (application as LyraApplication).container

        setContent {
            val themeMode    by container.dataStore.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val amoledBlack  by container.dataStore.amoledBlack.collectAsState(initial = false)
            val dynamicColor by container.dataStore.dynamicColor.collectAsState(initial = true)

            // Mirror the haptics preference into the process-wide gate read by all haptic helpers.
            val hapticsEnabled by container.dataStore.hapticsEnabled.collectAsState(initial = true)
            LaunchedEffect(hapticsEnabled) { HapticsConfig.enabled = hapticsEnabled }

            // ── iPod flag: read before the first frame ────────────────────────
            // `Boolean?` — null means DataStore has not emitted yet; the splash stays on screen
            // until it does (or the safety timeout fires, so a stuck DataStore never holds the
            // splash forever). The three-way branch below avoids composing LyraNavGraph while
            // the flag is still null, which would create and immediately tear down the
            // NavController, LibraryViewModel.init, PlayerPanelHost, and the deep-link funnel.
            val ipodEnabled by container.dataStore.ipodEnabled.collectAsState(initial = null)
            var ipodFlagLoaded by remember { mutableStateOf(false) }
            var flagTimedOut by remember { mutableStateOf(false) }
            LaunchedEffect(ipodEnabled) {
                if (ipodEnabled != null) ipodFlagLoaded = true
            }
            LaunchedEffect(Unit) {
                delay(1_500L)
                flagTimedOut = true
            }
            splash.setKeepOnScreenCondition { !ipodFlagLoaded && !flagTimedOut }

            // Deep link while in iPod mode: auto-exit so the normal funnel handles it.
            LaunchedEffect(pendingDeepLinkIntent, ipodEnabled) {
                val intent = pendingDeepLinkIntent ?: return@LaunchedEffect
                if (ipodEnabled == true && intent.action == Intent.ACTION_VIEW && intent.data != null) {
                    container.settingsRepository.setIpodEnabled(false)
                }
            }

            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.DARK   -> true
                ThemeMode.LIGHT  -> false
                ThemeMode.SYSTEM -> systemDark
            }

            // Re-apply edge-to-edge style whenever dark/light flips so status
            // bar and nav bar icon colors follow the in-app theme, not the system theme.
            // Skipped while iPod mode is active: it is its own immersive world and the
            // light/dark icon colours are irrelevant with system bars hidden.
            if (ipodEnabled != true) {
                SideEffect {
                    val barStyle = if (isDark)
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                }
            }

            LyraTheme(
                themeMode    = themeMode,
                amoledBlack  = amoledBlack,
                dynamicColor = dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        ipodEnabled == true && container.authManager.isAuthenticated() ->
                            IPodRoot(container)
                        ipodEnabled != null || flagTimedOut ->
                            LyraNavGraph(
                                container             = container,
                                pendingDeepLinkIntent = pendingDeepLinkIntent,
                            )
                        // else: splash is still up, compose nothing
                    }
                }
            }
        }
    }
}
