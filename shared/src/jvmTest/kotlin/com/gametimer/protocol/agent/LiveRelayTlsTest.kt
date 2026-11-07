package com.gametimer.protocol.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * The relay socket's engine (CIO, its own TLS 1.2 stack) against the deployed relay over wss://: a ping is
 * answered. Only with -PliveRelay (needs the internet); CI's relay workflow and a release check can pass it.
 */
class LiveRelayTlsTest {
    @Test
    fun theDeployedRelayAnswersAPingOverTls(): Unit = runBlocking {
        val url = System.getProperty("liveRelay")
        Assume.assumeTrue("liveRelay not set", url != null)
        val socket = KtorRelaySocketFactory().connect(url!!)
        try {
            socket.send(RelayFrames.ping(byteArrayOf(1, 2)))
            val pong = withTimeout(15_000) {
                var f = socket.receive()!!
                while (f[0] != RelayFrames.PONG) f = socket.receive()!!
                f
            }
            assertEquals(listOf(RelayFrames.PONG, 1.toByte(), 2.toByte()), pong.toList())
        } finally {
            socket.close()
        }
    }
}
