package com.gametimer.parent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.KtorConnector
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.PairOutcome
import com.gametimer.protocol.hub.PairingLink
import com.gametimer.protocol.hub.RequestOutcome
import com.gametimer.protocol.hub.pair
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real Android runtime (CI emulator): libsodium's JNI, the Keystore-wrapped hub key, Ktor/OkHttp
 * WebSockets, pairing and control of the production C# agent (tests/interop/TestAgent --emulator,
 * reached as 10.0.2.2:5123).
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GameTimerApp

    /** CI passes requireAgent=true: then an unreachable agent fails the test instead of skipping it. */
    private val requireAgent get() = InstrumentationRegistry.getArguments().getString("requireAgent") == "true"

    @Test
    fun hubKeyIsStableAcrossLoads() {
        val first = app.store.loadHubKey().publicKey
        val again = HubStore(app).loadHubKey().publicKey // a fresh store reads the Keystore-wrapped file
        assertArrayEquals(first, again)
    }

    @Test
    fun pairsWithAndControlsTheAgent(): Unit = runBlocking {
        val agentUrl = "http://10.0.2.2:5123"
        val linkText = try { withContext(Dispatchers.IO) { URL("$agentUrl/control/pair").readText() } } catch (e: Exception) { null }
        if (requireAgent) assertTrue("TestAgent not reachable at $agentUrl", linkText != null)
        Assume.assumeTrue("TestAgent not running on the host", linkText != null)
        val link = PairingLink.parse(linkText!!)!!
        val connector = KtorConnector()
        val hubKey = withContext(Dispatchers.IO) { app.store.loadHubKey() }

        val outcome = withTimeout(90.seconds) {
            pair(link, hubKey, "Emulator", "test", connector) { code ->
                Thread { URL("$agentUrl/control/code?c=$code").readText() }.start()
            }
        }
        assertTrue("pairing: $outcome", outcome is PairOutcome.Paired)
        val agent = (outcome as PairOutcome.Paired).agent

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val l = AgentLink(agent, hubKey, connector, "test", scope).also { it.start() }
            withTimeout(30.seconds) { l.status.first { it is LinkStatus.Online } }
            val start = l.send("timer.start", buildJsonObject { put("mode", "interval"); put("hours", 0); put("minutes", 10) })
            assertTrue("start: $start", start is RequestOutcome.Ok)
            assertTrue(l.state.value!!.remainingSecondsNow() in 590..600)
            assertEquals(RequestOutcome.Refused("already_running"),
                l.send("timer.start", buildJsonObject { put("mode", "interval"); put("hours", 0); put("minutes", 10) }))
            assertTrue(l.send("timer.stop") is RequestOutcome.Ok)
            l.stop()
        } finally {
            scope.cancel()
        }
    }
}
