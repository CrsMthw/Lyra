package com.crsmthw.lyra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.crsmthw.lyra.ui.navigation.LyraNavGraph
import com.crsmthw.lyra.ui.theme.LyraTheme
import com.crsmthw.lyra.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as LyraApplication).container

        setContent {
            val themeMode    by container.dataStore.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val amoledBlack  by container.dataStore.amoledBlack.collectAsState(initial = false)
            val dynamicColor by container.dataStore.dynamicColor.collectAsState(initial = true)

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
                    LyraNavGraph(container = container)
                }
            }
        }
    }
}
