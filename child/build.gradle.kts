// GameTimer Child — Phase C, step C0: a throw-away spike on a test phone (English only, debug builds).
// It answers: how fast a game is blocked (polling vs the accessibility service), what a child can switch off
// with and without strict mode, and how the background service survives the vendor's battery manager.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gametimer.child"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gametimer.child"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
}
