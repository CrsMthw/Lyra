package com.crsmthw.lyra.ui.ipod

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.launch

/**
 * The whole iPod mode. Composed by MainActivity INSTEAD of LyraNavGraph while `ipodEnabled` is
 * true (the flag is read before the first frame, so neither UI flashes). Owns:
 *  - the immersive window state (system bars hidden, swipe-to-reveal), the portrait request,
 *    the display-cutout mode, forced LTR — all restored on dispose;
 *  - the body layout: fills a portrait window; on a wider window a body of at most
 *    [IPodDimens.BodyMaxAspect] is centred on [IPodColors.Surround] with the portrait hint
 *    under it;
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
    val vm: IPodViewModel = viewModel(factory = IPodViewModelFactory(container))
    val playerVm: PlayerViewModel = viewModel(factory = PlayerViewModelFactory(container))
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
                )
                is IPodEffect.PlayLikedSong -> playerVm.playFromLikedSongs(effect.uri)
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
    SideEffect { sounds.enabled = state.clickSoundsEnabled }

    // ── Exit dialog ─────────────────────────────────────────────────────────
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = !showExitDialog) { showExitDialog = true }

    // ── Layout ──────────────────────────────────────────────────────────────
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(IPodColors.Surround),
            contentAlignment = Alignment.Center,
        ) {
            // Letterboxing: decide from the full window BEFORE consuming height, so the
            // hint space is reserved and the body does not push it off-screen.
            val letterboxed = maxWidth > maxHeight * IPodDimens.BodyMaxAspect
            val hintSpace = if (letterboxed) 40.dp else 0.dp
            val bodyHeight = (maxHeight - hintSpace).coerceAtLeast(0.dp)
            val bodyWidth = min(maxWidth, bodyHeight * IPodDimens.BodyMaxAspect)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // ── iPod body ────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .width(bodyWidth)
                        .height(bodyHeight)
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(IPodDimens.BodyPadding)
                            // Pad for the display cutout so the LCD sits below the camera hole
                            // while the silver body runs behind it.
                            .windowInsetsPadding(WindowInsets.displayCutout),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // LCD: 4:3, fills the padded width and respects the cutout
                        // inset applied by windowInsetsPadding above.
                        LcdScreen(
                            state = state,
                            battery = battery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(IPodDimens.LcdAspect),
                        )

                        // ── Wheel area: fills the remaining space ────────────
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            val wheelSize = min(
                                bodyWidth * IPodDimens.WheelDiameterFraction,
                                maxHeight * 0.92f,
                            ).coerceAtLeast(0.dp)
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

                // ── Portrait hint (shown only when letterboxed) ──────────────
                if (letterboxed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ipod_portrait_hint),
                        color = IPodColors.SurroundText,
                        fontFamily = IPodFontFamily,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
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
