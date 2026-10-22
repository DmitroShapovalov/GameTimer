plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    // android target arrives with the Android SDK in A4

    compilerOptions {
        allWarningsAsErrors.set(true)
        optIn.add("kotlin.ExperimentalUnsignedTypes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.libsodium.bindings)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.serialization.json)
        }
    }
}

tasks.withType<Test>().configureEach {
    // The same vectors the C# tests run
    systemProperty("noiseVectors", rootProject.file("tests/vectors/noise-vectors.json").absolutePath)
    // Kotlin <-> C# interop: -PcsharpPeer=<path to NoisePeer.dll> (CI always sets it; local runs skip without it)
    providers.gradleProperty("csharpPeer").orNull?.let { systemProperty("csharpPeer", it) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}
