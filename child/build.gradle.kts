// GameTimer Child — Phase C, step C0: a throw-away spike on a test phone (English only, debug builds).
// It answers: how fast a game can be blocked without Device Owner, what a child can switch off, how the
// background service survives the vendor's battery manager, and what Device Owner adds.
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
        manifestPlaceholders["testOnly"] = "false"
    }

    buildTypes {
        // For the Device Owner test only: testOnly, so `adb shell dpm remove-active-admin` can always remove it
        // (installed with `adb install -t`; the phone's installer refuses testOnly APKs, hence the normal debug build)
        create("deviceOwner") {
            initWith(getByName("debug"))
            manifestPlaceholders["testOnly"] = "true"
            matchingFallbacks += "debug"
        }
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
