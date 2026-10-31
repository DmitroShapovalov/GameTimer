plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase for push wake-ups (protocol §10.6), without the google-services plugin: four values from
// google-services.json, which is not in the repository. CI: the secret GOOGLE_SERVICES_JSON (the file's
// content); locally: -PgoogleServicesJson=<path>. Without it the app builds with push off.
val firebase: Map<String, String> = run {
    val text = System.getenv("GOOGLE_SERVICES_JSON")?.takeIf { it.isNotBlank() }
        ?: (findProperty("googleServicesJson") as String?)?.let { p -> file(p).takeIf { it.isFile }?.readText() }
        ?: return@run emptyMap()
    @Suppress("UNCHECKED_CAST")
    fun Any?.obj() = this as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    fun Any?.list() = this as List<Any?>
    val root = groovy.json.JsonSlurper().parseText(text).obj()
    val info = root["project_info"].obj()
    val client = root["client"].list().map { it.obj() }.firstOrNull {
        it["client_info"].obj()["android_client_info"].obj()["package_name"] == "com.gametimer.parent"
    } ?: error("google-services.json has no Android app com.gametimer.parent")
    mapOf(
        "projectId" to info["project_id"].toString(),
        "senderId" to info["project_number"].toString(),
        "appId" to client["client_info"].obj()["mobilesdk_app_id"].toString(),
        "apiKey" to client["api_key"].list().first().obj()["current_key"].toString(),
    )
}

android {
    namespace = "com.gametimer.parent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gametimer.parent"
        minSdk = 26
        targetSdk = 36
        // Release builds get both from the tag (release.yml): v1.7.0 → "1.7.0", 1070099
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("appVersionName") as String?) ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FCM_PROJECT_ID", "\"${firebase["projectId"] ?: ""}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${firebase["senderId"] ?: ""}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${firebase["appId"] ?: ""}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${firebase["apiKey"] ?: ""}\"")
    }

    // The release key lives only in GitHub secrets (and the owner's backup); CI passes it by environment.
    // Without it a release build stays unsigned and can't be installed — debug builds are for testing.
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}"
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.libsodium.bindings)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.biometric)
    implementation(libs.fragment)
    implementation(libs.firebase.messaging)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
