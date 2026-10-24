package com.gametimer.protocol.noise

import com.ionspin.kotlin.crypto.LibsodiumInitializer

/** libsodium must be loaded once per process before any Noise call. */
public object Sodium {
    public suspend fun initialize() {
        if (!LibsodiumInitializer.isInitialized()) LibsodiumInitializer.initialize()
    }

    internal fun requireInitialized() {
        check(LibsodiumInitializer.isInitialized()) { "call Sodium.initialize() first" }
    }
}
