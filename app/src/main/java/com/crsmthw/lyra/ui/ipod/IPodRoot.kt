package com.crsmthw.lyra.ui.ipod

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crsmthw.lyra.R
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.ipod.lcd.BatteryState
import com.crsmthw.lyra.ui.ipod.lcd.LcdScreen
import com.crsmthw.lyra.ui.ipod.lcd.rememberBatteryState
import com.crsmthw.lyra.ui.ipod.wheel.ClickSounds
import com.crsmthw.lyra.ui.ipod.wheel.ClickWheel
import com.crsmthw.lyra.ui.screens.player.PlayerViewModel
import com.crsmthw.lyra.ui.screens.player.PlayerViewModelFactory
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.press
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The whole iPod mode. Composed by MainActivity INSTEAD of LyraNavGraph while `ipodEnabled` is
 * true (the flag is read before the first frame, so neither UI flashes). Owns:
 *  - the immersive window state (system bars hidden, swipe-to-reveal), the portrait request,
 *    the display-cutout mode, forced LTR — all restored on dispose;
 *  - the body layout: the silver body ALWAYS fills the full window (width AND height; the rounded
 *    corners stay). Inside the body padding and cutout inset: the LCD's width =
 *    min(available width, bodyHeight * [IPodDimens.LcdMaxHeightFraction] * [IPodDimens.LcdAspect]),
 *    centred horizontally, height = width / LcdAspect. The wheel = min(bodyWidth *
 *    [IPodDimens.WheelDiameterFraction], remaining height * 0.92), centred in the remaining space.
 *    In landscape (maxWidth > maxHeight) a small "works best in portrait" hint overlays the body's
 *    bottom edge;
 *  - the LCD (4:3, top) and the ClickWheel (bottom), wiring wheel events into [IPodViewModel]
 *    and [IPodEffect]s out to the Activity-scoped [PlayerViewModel];
 *  - the exit confirmation (BackHandler -> dialog -> setIpodEnabled(false)), the session-expired
 *    collector, and [ClickSounds]' lifetime.
 */
@Composable
fun IPodRoot(container: AppContainer, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // ── Immersive window: portrait + hidden bars + cutout ────────────────────
    DisposableEffect(Unit) {
        val act = activity ?: return@DisposableEffect onDispose { }
        val window = act.window
        val view = window.decorView

        // Request portrait (honoured on the cover screen; ignored on sw>=600dp under targetSdk 37).
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Display cutout: render into the cutout area so the silver body fills behind it.
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Hide system bars; swipe-to-reveal lets the user reach them transiently.
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ── ViewModels ───────────────────────────────────────────────────────────
    // PlayerViewModel resolves against the ACTIVITY (the same instance LyraNavGraph uses), so
    // playback state, the poll/tick jobs and the App Remote pre-connect carry across the swap.
    val playerVm: PlayerViewModel = viewModel(factory = PlayerViewModelFactory(container))
    // IPodViewModel lives exactly as long as the iPod is on screen: its own store, cleared on
    // dispose. An Activity-scoped instance survived an exit with its stack frozen (re-entry
    // opened on the iPod's Settings menu) and kept the 1 Hz player mirror running for nobody.
    val sessionStore = remember { IPodSessionStoreOwner() }
    DisposableEffect(sessionStore) { onDispose { sessionStore.viewModelStore.clear() } }
    val vm: IPodViewModel = viewModel(
        viewModelStoreOwner = sessionStore,
        factory = IPodViewModelFactory(container),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val battery: BatteryState = rememberBatteryState()

    // ── Effects: VM -> PlayerViewModel ───────────────────────────────────────
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is IPodEffect.PlayTrack -> playerVm.playTrack(
                    uri = effect.uri,
                    contextUri = effect.contextUri,
                    uris = effect.uris,
                    index = effect.index,
                    startPositionMs = effect.startPositionMs,
                    shuffle = effect.shuffle,
                )
                is IPodEffect.PlayLikedSong -> playerVm.playFromLikedSongs(
                    trackUri = effect.uri,
                    shuffle = effect.shuffle,
                )
                is IPodEffect.ShuffleContext -> playerVm.shuffleContext(effect.contextUri)
                IPodEffect.PlayPause -> playerVm.playPause()
                IPodEffect.Next -> playerVm.skipNext()
                IPodEffect.Previous -> playerVm.skipPrevious()
                is IPodEffect.SeekTo -> playerVm.seekTo(effect.fraction)
            }
        }
    }

    // ── Session expired: disable iPod mode, MainActivity then lands on Auth ──
    LaunchedEffect(Unit) {
        container.authManager.sessionExpired.collect {
            container.settingsRepository.setIpodEnabled(false)
        }
    }

    // ── Click sounds ────────────────────────────────────────────────────────
    val sounds = remember { ClickSounds(context) }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    // Why a LaunchedEffect + Flow instead of SideEffect: the outer recomposition scope reads
    // nothing from `state` at composition time (all snapshot reads happen inside the
    // BoxWithConstraints content lambda, which is a separate scope), so a SideEffect here
    // only re-ran when something IT DID read changed — the sole composition-time read in this
    // scope was `rememberBatteryState()`, meaning the ~1-minute battery broadcast. A Flow
    // collector reacts to every emission, not just recompositions of this scope.
    LaunchedEffect(vm, sounds) {
        vm.uiState.map { it.clickSounds }.distinctUntilChanged().collect { config ->
            sounds.configure(config)
        }
    }

    // ── Exit dialog ─────────────────────────────────────────────────────────
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = !showExitDialog) { showExitDialog = true }

    // ── Layout ──────────────────────────────────────────────────────────────
    // The body ALWAYS fills the whole window (no letterboxing). In landscape
    // (maxWidth > maxHeight) a small hint overlays the body's bottom edge.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(IPodColors.Surround)
                .clip(RoundedCornerShape(IPodDimens.BodyCornerRadius))
                .background(
                    Brush.verticalGradient(
                        listOf(IPodColors.BodyTop, IPodColors.BodyBottom),
                    ),
                )
                .border(
                    width = 1.dp,
                    color = IPodColors.BodyEdge,
                    shape = RoundedCornerShape(IPodDimens.BodyCornerRadius),
                ),
        ) {
            val isLandscape = maxWidth > maxHeight

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(IPodDimens.BodyPadding)
                    // Pad for the display cutout so the LCD sits below the camera hole
                    // while the silver body runs behind it.
                    .windowInsetsPadding(WindowInsets.displayCutout),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Read available size AFTER padding + cutout inset, so the LCD cap
                // is computed against the space it actually lives in.
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val innerWidth = maxWidth
                    val innerHeight = maxHeight

                    // LCD width = min(available width, height * LcdMaxHeightFraction * LcdAspect)
                    // so on a tall phone the width wins and the LCD spans the body; on a
                    // near-square (unfolded) window it is capped at 46 % of the height.
                    val lcdWidth = min(
                        innerWidth,
                        innerHeight * IPodDimens.LcdMaxHeightFraction * IPodDimens.LcdAspect,
                    )
                    val lcdHeight = lcdWidth / IPodDimens.LcdAspect
                    // In landscape the hint overlays the bottom edge; shrink the wheel's
                    // budget so it doesn't paint behind the text (~30dp: font + padding).
                    val hintAllowance = if (isLandscape) 30.dp else 0.dp
                    val remainingHeight = (innerHeight - lcdHeight - hintAllowance).coerceAtLeast(0.dp)

                    // Wheel = min(body width * WheelDiameterFraction, remaining * 0.92)
                    val wheelSize = min(
                        innerWidth * IPodDimens.WheelDiameterFraction,
                        remainingHeight * 0.92f,
                    ).coerceAtLeast(0.dp)

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // ── LCD: 4:3, centred horizontally ───────────────────
                        LcdScreen(
                            state = state,
                            battery = battery,
                            modifier = Modifier
                                .width(lcdWidth)
                                .aspectRatio(IPodDimens.LcdAspect),
                        )

                        // ── Wheel area: centred in the remaining space ───────
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            ClickWheel(
                                onEvent = vm::onWheelEvent,
                                sounds = sounds,
                                modifier = Modifier.size(wheelSize),
                                enabled = !showExitDialog,
                            )
                        }

                        // Small bottom spacer so the wheel doesn't touch the edge.
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // ── Landscape hint (overlays the body's bottom edge) ─────────
            AnimatedVisibility(
                visible = isLandscape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = IPodDimens.BodyPadding),
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(300)),
            ) {
                Text(
                    text = stringResource(R.string.ipod_portrait_hint),
                    color = IPodColors.LandscapeHintText,
                    fontFamily = IPodFontFamily,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    // ── Exit confirmation dialog ────────────────────────────────────────────
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = {
                // The dialog is its own window and pops system bars back unless it also goes
                // immersive. Apply the same hide from inside the dialog's composition scope
                // where LocalView resolves to the dialog's view tree.
                ImmersiveDialogEffect()
                Text(stringResource(R.string.ipod_exit_title))
            },
            text = null,
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    showExitDialog = false
                    scope.launch { container.settingsRepository.setIpodEnabled(false) }
                }) { Text(stringResource(R.string.ipod_exit_yes)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    haptics.press()
                    showExitDialog = false
                }) { Text(stringResource(R.string.ipod_exit_no)) }
            },
        )
    }
}

/** A ViewModelStore that lives for one iPod session — see the ViewModels block in [IPodRoot]. */
private class IPodSessionStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/**
 * Hides system bars in the dialog's own window, so it doesn't flash the status/nav bars
 * while the exit dialog is showing. Must be called from inside a dialog slot lambda
 * (title, text, etc.) where [LocalView] resolves to the dialog's view tree, not the
 * activity's.
 */
@Composable
private fun ImmersiveDialogEffect() {
    val view = LocalView.current
    SideEffect {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val controller = WindowInsetsControllerCompat(dialogWindow, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
