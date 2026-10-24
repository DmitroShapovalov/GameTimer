// Kotlin part of GameTimer: the protocol v2 core shared by the Android and iOS hubs (:shared).
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
