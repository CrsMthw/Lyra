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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.crsmthw.lyra.ui.navigation.LyraNavGraph
import com.crsmthw.lyra.ui.theme.LyraTheme
import com.crsmthw.lyra.ui.theme.ThemeMode
import com.crsmthw.lyra.util.HapticsConfig

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Warm-start deep link: set in onNewIntent, read by LyraNavGraph via handleDeepLink.
    private var pendingDeepLinkIntent by mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDeepLinkIntent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
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

            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.DARK   -> true
                ThemeMode.LIGHT  -> false
                ThemeMode.SYSTEM -> systemDark
            }

            // Re-apply edge-to-edge style whenever dark/light flips so status
            // bar and nav bar icon colors follow the in-app theme, not the system theme.
            SideEffect {
                val barStyle = if (isDark)
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }

            LyraTheme(
                themeMode    = themeMode,
                amoledBlack  = amoledBlack,
                dynamicColor = dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LyraNavGraph(
                        container              = container,
                        pendingDeepLinkIntent  = pendingDeepLinkIntent,
                    )
                }
            }
        }
    }
}
