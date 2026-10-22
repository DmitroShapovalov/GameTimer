// Kotlin Multiplatform part of GameTimer: the protocol v2 core shared by the Android and iOS hubs.
// The Windows agent (C#) builds separately with dotnet; see docs/protocol.md.
rootProject.name = "gametimer"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":shared")
