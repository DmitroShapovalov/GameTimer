package com.gametimer.protocol.noise

import kotlinx.coroutines.test.runTest

/** Shared by the common (JVM + iOS simulator) and JVM-only tests. */
internal object TestSupport {
    val prologue: ByteArray = "GameTimer/2".encodeToByteArray()

    /** Call from @BeforeTest: loads libsodium (runTest blocks on JVM and native). */
    fun initSodium() {
        runTest { Sodium.initialize() }
    }
}

internal fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
