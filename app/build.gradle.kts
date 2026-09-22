import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("androidx.baselineprofile")
}

// ─── Release signing ─────────────────────────────────────────────────────────
// Credentials live in local.properties (gitignored, never committed): KEYSTORE_PATH,
// KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD. They are read here at BUILD time only and are not
// packaged into the APK — verified: the APK contains no property name, no passphrase bytes, and no
// buildConfigField exposes them. What ships in the APK is the signature, not the secret.
//
// ⚠️ `Properties` MUST be IMPORTED (see the top of this file), never written fully-qualified as
// `java.util.Properties`. Inside a build script `java` resolves to Gradle's JavaPluginExtension
// accessor, so `java.util` fails with "Unresolved reference 'util'" and every downstream
// `getProperty` call then reports "Unresolved reference" as cascading fallout. That shadowing — NOT
// `Properties.getProperty` itself, and nothing about the AGP/Gradle/Kotlin versions — is why signing
// was wired manually via apksigner for so long.
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Null on a fresh clone with no local.properties, or if the keystore is missing — in which case no
// signing config is registered and the release build stays unsigned rather than failing outright.
val keystoreFile = keystoreProps.getProperty("KEYSTORE_PATH")
    ?.let { file(it) }
    ?.takeIf { it.exists() }

android {
    namespace   = "com.crsmthw.lyra"
    compileSdk  = 37

    defaultConfig {
        applicationId = "com.crsmthw.lyra"
        minSdk        = 35
        targetSdk     = 37
        versionCode   = 16
        versionName   = "4.0.0"

        // AppAuth redirect scheme – must match AndroidManifest intent-filter
        manifestPlaceholders["appAuthRedirectScheme"] = "com.crsmthw.lyra"
    }

    signingConfigs {
        // Registered only when the keystore is actually present, so a clone without
        // local.properties can still run assembleRelease (it just gets an unsigned APK).
        if (keystoreFile != null) {
            create("release") {
                storeFile     = keystoreFile
                storePassword = keystoreProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias      = keystoreProps.getProperty("KEY_ALIAS")
                keyPassword   = keystoreProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // findByName (not getByName) — null when unconfigured, which leaves the APK unsigned
            // instead of failing the build with "SigningConfig 'release' not found".
            signingConfig      = signingConfigs.findByName("release")
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures { compose = true; buildConfig = true }
}

kotlin { jvmToolchain(21) }

baselineProfile {
    // Never generate during a normal assembleRelease — Cris's build must not slow down or
    // need the emulator. Run :app:generateBaselineProfile explicitly when regenerating.
    automaticGenerationDuringBuild = false
    // Merge into src/main so the profile applies to all build types and avoids creating a
    // src/release source set that silently scopes future additions to release-only.
    mergeIntoMain = true
}

// Host tests (JVM unit tests) are enabled; device tests (androidTest) stay disabled.
// The device-test component wiring is what trips Gradle's "Project object as a dependency
// notation" deprecation (from AGP's own VariantDependenciesBuilder, not our code).
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.deviceTests.forEach { (_, deviceTest) -> deviceTest.enable = false }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    // Compose BOM + UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    // navigation-compose only depends on this at RUNTIME, so it needs declaring to reach the
    // compile classpath: PlayerPanelHost observes NavigationEventDispatcher.transitionState to seek
    // the mini player with a predictive-back gesture. `navigationevent` arrives as its api dep.
    implementation(libs.androidx.navigationevent.compose)

    // Persistence
    implementation(libs.androidx.datastore.preferences)

    // Adaptive / Window
    implementation(libs.androidx.window)
    implementation(libs.androidx.material3.adaptive)

    // OAuth PKCE
    implementation(libs.appauth)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.graphics.shapes)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Home-screen widgets (Glance + Material 3)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // FFT interpolation for audio visualizer
    implementation(libs.commons.math3)

    // Drag-and-drop reorder for LazyColumn (playlist reorder mode)
    implementation(libs.reorderable)

    // ─── Spotify App Remote SDK ────────────────────────────────────────────
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    // ───────────────────────────────────────────────────────────────────────

    // ─── Unit tests ────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ───────────────────────────────────────────────────────────────────────
    // Baseline Profile — allows the profile to be consumed at install time
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
}
