package com.crsmthw.lyra.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LyraDarkColorScheme = darkColorScheme(
    primary          = LyraDarkPrimary,
    onPrimary        = LyraDarkOnPrimary,
    secondary        = LyraDarkSecondary,
    onSecondary      = LyraDarkOnSecondary,
    background       = LyraDarkBackground,
    onBackground     = LyraDarkOnBackground,
    surface          = LyraDarkSurface,
    onSurface        = LyraDarkOnSurface,
    surfaceVariant   = LyraDarkSurfaceVar,
    error            = LyraDarkError,
)

private val LyraLightColorScheme = lightColorScheme(
    primary          = LyraLightPrimary,
    onPrimary        = LyraLightOnPrimary,
    secondary        = LyraLightSecondary,
    onSecondary      = LyraLightOnSecondary,
    background       = LyraLightBackground,
    onBackground     = LyraLightOnBackground,
    surface          = LyraLightSurface,
    onSurface        = LyraLightOnSurface,
    surfaceVariant   = LyraLightSurfaceVar,
    error            = LyraLightError,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyraTheme(
    themeMode    : ThemeMode = ThemeMode.SYSTEM,
    amoledBlack  : Boolean   = false,
    dynamicColor : Boolean   = true,
    content      : @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
    }

    val context = LocalContext.current

    // Resolve base scheme
    var colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else           dynamicLightColorScheme(context)
        }
        darkTheme -> LyraDarkColorScheme
        else      -> LyraLightColorScheme
    }

    // AMOLED pure black overlay – applied on top of whatever scheme is active
    if (darkTheme && amoledBlack) {
        colorScheme = colorScheme.copy(
            background    = Color.Black,
            surface       = Color.Black,
            surfaceVariant = Color(0xFF0D0D0D),
        )
    }

    MaterialExpressiveTheme(
        colorScheme  = colorScheme,
        typography   = LyraTypography,
        motionScheme = MotionScheme.expressive(),
        content      = content,
    )
}
