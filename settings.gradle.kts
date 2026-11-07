// Kotlin part of GameTimer: the protocol v2 core shared by the Android and iOS hubs (:shared), the Android
// parent app (:android) and the child's phone (:child, Phase C; for now the C0 spike).
// The Windows agent (C#) builds separately with dotnet; see docs/protocol.md.
rootProject.name = "gametimer"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":android")
include(":child")
