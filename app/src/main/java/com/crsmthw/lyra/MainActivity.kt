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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.ilyra.ILyraRoot
import com.crsmthw.lyra.ui.navigation.LyraNavGraph
import com.crsmthw.lyra.ui.theme.LyraTheme
import com.crsmthw.lyra.ui.theme.ThemeMode
import com.crsmthw.lyra.util.HapticsConfig
import com.crsmthw.lyra.util.screenTransitionSpec
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

            // ── iLyra flag: read before the first frame ────────────────────────
            // `Boolean?` — null means DataStore has not emitted yet; the splash stays on screen
            // until it does (or the safety timeout fires, so a stuck DataStore never holds the
            // splash forever). The three-way branch below avoids composing LyraNavGraph while
            // the flag is still null, which would create and immediately tear down the
            // NavController, LibraryViewModel.init, PlayerPanelHost, and the deep-link funnel.
            val ilyraEnabled by container.dataStore.ilyraEnabled.collectAsState(initial = null)
            var ilyraFlagLoaded by remember { mutableStateOf(false) }
            var flagTimedOut by remember { mutableStateOf(false) }
            LaunchedEffect(ilyraEnabled) {
                if (ilyraEnabled != null) ilyraFlagLoaded = true
            }
            LaunchedEffect(Unit) {
                delay(1_500L)
                flagTimedOut = true
            }
            splash.setKeepOnScreenCondition { !ilyraFlagLoaded && !flagTimedOut }

            // Deep link while in iLyra mode: auto-exit so the normal funnel handles it. Safe to key
            // on both because LyraNavGraph nulls the intent once it has acted on it
            // (onDeepLinkConsumed) — so re-enabling iLyra mode later cannot re-fire on a stale link.
            LaunchedEffect(pendingDeepLinkIntent, ilyraEnabled) {
                val intent = pendingDeepLinkIntent ?: return@LaunchedEffect
                if (ilyraEnabled == true && intent.action == Intent.ACTION_VIEW && intent.data != null) {
                    container.settingsRepository.setIlyraEnabled(false)
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
            // Skipped while iLyra mode is active: it is its own immersive world and the
            // light/dark icon colours are irrelevant with system bars hidden.
            if (ilyraEnabled != true) {
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

            // Null while the splash is still up: compose nothing (see the flag block above).
            // Once known it never goes back to null — `flagTimedOut` is sticky and the flag,
            // once emitted, stays non-null — so the switcher below is created exactly once and
            // its first surface is the initial state, shown without an animation.
            val rootSurface: RootSurface? = when {
                ilyraEnabled == true && container.authManager.isAuthenticated() -> RootSurface.ILyra
                ilyraEnabled != null || flagTimedOut -> RootSurface.Lyra
                else -> null
            }

            LyraTheme(
                themeMode    = themeMode,
                amoledBlack  = amoledBlack,
                dynamicColor = dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (rootSurface != null) {
                        RootSurfaceSwitcher(
                            surface               = rootSurface,
                            container             = container,
                            pendingDeepLinkIntent = pendingDeepLinkIntent,
                            onDeepLinkConsumed    = { pendingDeepLinkIntent = null },
                        )
                    }
                }
            }
        }
    }
}

/** What the Activity's root shows: the normal app, or the iLyra body in its place. */
private enum class RootSurface { Lyra, ILyra }

/** How far Lyra settles back while the iPod body slides over it (and springs from on the way back). */
private const val LyraRestingScale = 0.94f

/**
 * The animated swap between [LyraNavGraph] and [ILyraRoot] (2026-09-26 — before this the flag
 * flip was a hard cut). Entering iLyra: the body slides up from the bottom edge and fades in
 * over Lyra, which dims and settles back to [LyraRestingScale]; leaving: the exact reverse, the
 * body sliding back down over a Lyra that fades and scales up to rest. Both run on the ONE
 * finite [screenTransitionSpec] every content swap uses (docs/MOTION.md → THE HARD RULE).
 *
 * The iPod's z-index is pinned above Lyra's, so it is on top in BOTH directions — the leaving
 * body has to cover the graph that is being composed fresh underneath it. While the swap runs,
 * a full-size pointer sink on top eats every touch: the outgoing content is still composed and
 * still hit-testable under the incoming one (alpha never affects hit testing), and the body's
 * plain background Box is not a hit target, so without it a tap in the first 300 ms could land
 * on a Lyra button under the sliding body. The leaving iPod is additionally told it is not
 * `interactive`, which parks its back handler and wheel.
 *
 * [ILyraRoot]'s immersive window state (bars hidden, portrait) is applied on its first
 * composition, i.e. as the entry starts, and restored when it is disposed, i.e. as the exit
 * ends — the bars therefore change under the animation, never after it. The manifest's
 * `configChanges` covers orientation, so the portrait request never recreates the Activity
 * mid-swap.
 */
@Composable
private fun RootSurfaceSwitcher(
    surface: RootSurface,
    container: AppContainer,
    pendingDeepLinkIntent: Intent?,
    onDeepLinkConsumed: () -> Unit,
) {
    val transition = updateTransition(surface, label = "rootSurface")
    Box(modifier = Modifier.fillMaxSize()) {
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val swap = if (targetState == RootSurface.ILyra) {
                    (slideInVertically(screenTransitionSpec()) { it } + fadeIn(screenTransitionSpec()))
                        .togetherWith(
                            scaleOut(screenTransitionSpec(), targetScale = LyraRestingScale) +
                                fadeOut(screenTransitionSpec()),
                        )
                } else {
                    (scaleIn(screenTransitionSpec(), initialScale = LyraRestingScale) + fadeIn(screenTransitionSpec()))
                        .togetherWith(
                            slideOutVertically(screenTransitionSpec()) { it } +
                                fadeOut(screenTransitionSpec()),
                        )
                }
                swap.targetContentZIndex = if (targetState == RootSurface.ILyra) 1f else 0f
                swap
            },
        ) { target ->
            when (target) {
                RootSurface.ILyra -> ILyraRoot(
                    container   = container,
                    interactive = transition.targetState == RootSurface.ILyra,
                )
                RootSurface.Lyra -> LyraNavGraph(
                    container             = container,
                    pendingDeepLinkIntent = pendingDeepLinkIntent,
                    onDeepLinkConsumed    = onDeepLinkConsumed,
                )
            }
        }

        // Pointer sink for the duration of the swap — see the KDoc.
        if (transition.currentState != transition.targetState) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }
    }
}
