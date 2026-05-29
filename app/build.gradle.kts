plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace   = "com.crsmthw.lyra"
    compileSdk  = 37

    defaultConfig {
        applicationId = "com.crsmthw.lyra"
        minSdk        = 35
        targetSdk     = 37
        versionCode   = 6
        versionName   = "1.2.0"

        // AppAuth redirect scheme – must match AndroidManifest intent-filter
        manifestPlaceholders["appAuthRedirectScheme"] = "com.crsmthw.lyra"
    }

    buildTypes {
        release {
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

    // Persistence
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Adaptive / Window
    implementation(libs.androidx.window)

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

    // ─── Spotify App Remote SDK ────────────────────────────────────────────
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    // ───────────────────────────────────────────────────────────────────────
}
