plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The test vectors in tests/vectors (shared with the C# tests) become Kotlin constants, so the vector
// tests are common code and run on every target, the iOS simulator included.
val vectorFiles: Map<String, File> = mapOf(
    "NOISE_VECTORS_JSON" to rootProject.file("tests/vectors/noise-vectors.json"),
    "PROTOCOL_VECTORS_JSON" to rootProject.file("tests/vectors/protocol-vectors.json"),
)
val generateNoiseVectors = tasks.register("generateNoiseVectors") {
    val sources = vectorFiles
    val outDir = layout.buildDirectory.dir("generated/noiseVectors/kotlin")
    sources.forEach { (name, file) -> inputs.file(file).withPropertyName(name).withPathSensitivity(PathSensitivity.NONE) }
    outputs.dir(outDir)
    doLast {
        outDir.get().asFile.deleteRecursively() // no stale files from earlier layouts
        val text = StringBuilder()
        text.append("// Generated from tests/vectors by :shared:generateNoiseVectors. Do not edit.\n")
        text.append("package com.gametimer.protocol.noise\n")
        for ((name, file) in sources) {
            val json = file.readText()
            require("\"\"\"" !in json && "$" !in json) { "${file.name} cannot be embedded in a raw string" }
            text.append("\ninternal val $name: String = \"\"\"\n$json\n\"\"\"\n")
        }
        val out = outDir.get().file("com/gametimer/protocol/noise/VectorsData.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(text.toString())
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
