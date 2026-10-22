plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The published Noise vectors (tests/vectors, shared with the C# tests) become a Kotlin constant, so the
// vector test is common code and runs on every target, the iOS simulator included.
val vectorsJson: File = rootProject.file("tests/vectors/noise-vectors.json")
val generateNoiseVectors = tasks.register("generateNoiseVectors") {
    val source = vectorsJson
    val outDir = layout.buildDirectory.dir("generated/noiseVectors/kotlin")
    inputs.file(source).withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(outDir)
    doLast {
        val json = source.readText()
        require("\"\"\"" !in json && "$" !in json) { "vectors JSON cannot be embedded in a raw string" }
        val file = outDir.get().file("com/gametimer/protocol/noise/NoiseVectorsData.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Generated from tests/vectors/noise-vectors.json by :shared:generateNoiseVectors. Do not edit.\n" +
                "package com.gametimer.protocol.noise\n\n" +
                "internal val NOISE_VECTORS_JSON: String = \"\"\"\n$json\n\"\"\"\n",
        )
    }
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
        commonTest {
            kotlin.srcDir(generateNoiseVectors)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.serialization.json)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // Kotlin <-> C# interop: -PcsharpPeer=<path to NoisePeer.dll> (CI always sets it; local runs skip without it).
    // The peer's folder is a task input, so a rebuilt C# core re-runs the tests.
    providers.gradleProperty("csharpPeer").orNull?.let { peer ->
        systemProperty("csharpPeer", peer)
        inputs.dir(file(peer).parentFile).withPropertyName("csharpPeerDir").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    testLogging { events("passed", "failed", "skipped") }
}
