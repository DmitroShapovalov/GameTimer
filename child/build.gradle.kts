// GameTimer Child — Phase C: the agent on the child's phone (protocol v2 agent side from :shared).
// A foreground service with the AgentHost, the relay (always on) and a LAN server on Wi-Fi, pairing by QR behind
// a parent PIN (C2); game limits, block page and strict mode (C3); tamper reports (C4). Released with the parent
// app from the same tag (release.yml), signed with the same release key.
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
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("appVersionName") as String?) ?: "2.0.0-dev"
    }

    // As the parent app: the release key only from CI's environment; without it a release build stays unsigned
    val keystore = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storeType = "pkcs12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // no R8: Ktor and the libsodium JNI bindings would need keep rules
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}"
    }
}

kotlin {
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.zxing.core)
}
