package com.crsmthw.lyra.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.screens.auth.AuthScreen
import com.crsmthw.lyra.ui.screens.library.LibraryScreen
import com.crsmthw.lyra.ui.screens.library.LibraryViewModel
import com.crsmthw.lyra.ui.screens.library.LibraryViewModelFactory
import com.crsmthw.lyra.ui.screens.player.PlayerScreen
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.screens.player.PlayerViewModelFactory
import com.crsmthw.lyra.ui.screens.search.SearchScreen
import com.crsmthw.lyra.ui.screens.search.SearchViewModel
import com.crsmthw.lyra.ui.screens.search.SearchViewModelFactory
import com.crsmthw.lyra.ui.screens.settings.SettingsScreen
import com.crsmthw.lyra.ui.screens.settings.SettingsViewModel
import com.crsmthw.lyra.ui.screens.settings.SettingsViewModelFactory

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LyraNavGraph(container: AppContainer) {
    val navController: NavHostController = rememberNavController()
    val playerVm = viewModel<PlayerViewModel>(factory = PlayerViewModelFactory(container))

    // ── Global back-tap debounce guard ───────────────────────────────────────
    var lastNavTime by remember { mutableLongStateOf(0L) }

    fun safeNavigateUp() {
        val now = System.currentTimeMillis()
        if (now - lastNavTime >= 350L) {
            lastNavTime = now
            navController.navigateUp()
        }
    }

    fun safePush(route: String) {
        val now = System.currentTimeMillis()
        if (now - lastNavTime >= 350L) {
            lastNavTime = now
            navController.navigate(route) {
                launchSingleTop = true
                restoreState    = true
            }
        }
    }

    val startDestination = if (container.authManager.isAuthenticated())
        Screen.Library.route
    else
        Screen.Auth.route

    SharedTransitionLayout {
        NavHost(
            navController        = navController,
            startDestination     = startDestination,
            enterTransition      = { slideInHorizontally  { it / 4 } + fadeIn()  },
            exitTransition       = { slideOutHorizontally { -(it / 4) } + fadeOut() },
            popEnterTransition   = { slideInHorizontally  { -(it / 4) } + fadeIn()  },
            popExitTransition    = { slideOutHorizontally { it / 4 } + fadeOut() },
        ) {

            composable(Screen.Auth.route) {
                AuthScreen(
                    encryptedPrefs  = container.encryptedPrefs,
                    authManager     = container.authManager,
                    onAuthenticated = {
                        navController.navigate(Screen.Library.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Screen.Library.route) {
                val vm = viewModel<LibraryViewModel>(factory = LibraryViewModelFactory(container))
                LibraryScreen(
                    viewModel             = vm,
                    playerViewModel       = playerVm,
                    onOpenPlayer          = { safePush(Screen.Player.route) },
                    onOpenSearch          = { safePush(Screen.Search.route) },
                    onOpenSettings        = { safePush(Screen.Settings.route) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope  = this@composable,
                )
            }

            composable(Screen.Player.route) {
                PlayerScreen(
                    viewModel             = playerVm,
                    onBack                = ::safeNavigateUp,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope  = this@composable,
                )
            }

            composable(Screen.Search.route) {
                val vm = viewModel<SearchViewModel>(factory = SearchViewModelFactory(container))
                SearchScreen(
                    viewModel    = vm,
                    onBack       = ::safeNavigateUp,
                    onOpenPlayer = { safePush(Screen.Player.route) },
                    onTrackClick = { uri, uris ->
                        playerVm.playTrack(uri, uris = uris)
                        safePush(Screen.Player.route)
                    },
                )
            }

            composable(Screen.Settings.route) {
                val vm = viewModel<SettingsViewModel>(factory = SettingsViewModelFactory(container))
                SettingsScreen(
                    viewModel = vm,
                    onBack    = ::safeNavigateUp,
                    onLogout  = {
                        container.authManager.logout()
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
