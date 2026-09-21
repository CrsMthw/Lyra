package com.crsmthw.lyra.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a Baseline Profile for the Lyra app's cold-start path.
 *
 * On a fresh emulator (no Spotify token) the app lands on the Auth screen after
 * the splash. This journey captures everything that runs during:
 *   - Application / AppContainer / EncryptedPrefs / DataStore / Compose / Coil init
 *   - The splash screen hold (iLyra flag read + 1.5 s timeout)
 *   - Auth screen composition (edge-to-edge, Material 3 theme, text fields)
 *
 * It does NOT cover Library, Player, Search or any authenticated code path because
 * the emulator has no Spotify token. To extend the profile to those paths, run
 * the generator on a device/emulator that is already authenticated.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateProfile() {
        rule.collect(
            packageName = "com.crsmthw.lyra",
            includeInStartupProfile = true,
        ) {
            // Cold-start the app.
            startActivityAndWait()

            // The splash holds up to 1.5 s for the iLyra DataStore flag.
            // Wait for the Auth screen to appear — look for any clickable element
            // (the permission dialog may also appear first).
            device.wait(Until.hasObject(By.pkg("com.crsmthw.lyra").depth(0)), 5_000)

            // Dismiss the POST_NOTIFICATIONS permission dialog if it appears.
            val allowButton = device.wait(
                Until.findObject(By.text("Allow")),
                3_000,
            )
            allowButton?.click()

            // Wait for idle after any dialog interaction.
            device.waitForIdle()

            // The Auth screen should now be visible. Wait for it to settle.
            device.wait(Until.hasObject(By.pkg("com.crsmthw.lyra").depth(0)), 3_000)
            device.waitForIdle()
        }
    }
}
