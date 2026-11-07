@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.protocol.agent

import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** /v2 sessions: handshake, the ready rule, hello, limits and timeouts (protocol §3, §4, §6). Port of the C# SessionTests. */
class SessionTests {
    @BeforeTest
    fun init() = TestSupport.initSodium()

    private fun ping() = buildJsonObject { put("t", "ping") }

    @Test
    fun helloAfterReady() = agentTest { f ->
        val (key, pairResult) = f.pair()
        val (hub, hello) = f.connect(key)

        assertEquals(2, hello["protocol"]!!.i)
        assertEquals(0, hello["minor"]!!.i)
        assertEquals(hub.id, hello.obj("you").s("id"))
        assertEquals("admin", hello.obj("you").s("role"))
        assertEquals(pairResult.s("ak"), hello.s("ak"))
        assertEquals(listOf("timer", "settings", "peers", "push"), (hello["capabilities"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals("notStarted", hello.obj("state").obj("timer").s("state"))
        assertEquals(f.host.agentId, hello.obj("agent").s("id"))
        assertEquals("android", hello.obj("agent").s("platform"))
        assertEquals(false, (hello["remoteAccess"] as JsonPrimitive).content.toBooleanStrict())
        assertNotNull(f.host.peerList[0].lastSeen)
    }

    @Test
    fun helloOffersTheHubsMinorUpToOurs() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, ok) = f.handshake(key, pairing = false)
        assertTrue(ok)
        hub.sendJson(buildJsonObject { put("t", "ready"); put("protocol", 2); put("minor", 99) })
        assertEquals(AgentHost.PROTOCOL_MINOR, hub.receiveJson()["minor"]!!.i)
    }

    @Test
    fun unknownHubKeyGetsNoMessage2() = agentTest { f ->
        f.pair()
        val (_, gotMsg2) = f.handshake(KeyPair.generate(), pairing = false)
        assertFalse(gotMsg2)
    }

    @Test
    fun message1MustBeConnect() = agentTest { f ->
        val (key, _) = f.pair()
        val (_, gotMsg2) = f.handshake(key, pairing = false, firstType = "timer.stop")
        assertFalse(gotMsg2)
    }

    @Test
    fun firstTransportMessageMustBeReady() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, ok) = f.handshake(key, pairing = false)
        assertTrue(ok)
        hub.sendJson(buildJsonObject { put("t", "timer.extend"); put("id", 1); put("rid", newRid()); put("minutes", 5) })
        assertNull(hub.tryReceiveJson())
        assertEquals(0, f.backend.calls)
    }

    @Test
    fun readyWithAnotherMajorIsRefused() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.handshake(key, pairing = false)
        hub.sendJson(buildJsonObject { put("t", "ready"); put("protocol", 3); put("minor", 0) })
        assertNull(hub.tryReceiveJson())
    }

    @Test
    fun replayedMessage1AndReadyNeverReachTheBackendOrEvictSessions() = agentTest { f ->
        val (key, _) = f.pair()
        val (real, _) = f.connect(key)
        real.request("timer.extend", buildJsonObject { put("minutes", 5) }, withRid = true)
        val lastSeen = f.host.peerList[0].lastSeen

        // An attacker recorded msg 1 + ready + the extend request and replays them, more often than the per-hub limit
        repeat(3) {
            val (replay, gotMsg2) = f.handshake(KeyPair.generate(), pairing = false, ip = "192.168.1.66", replayMsg1 = real.msg1)
            assertTrue(gotMsg2) // Noise accepts a replayed IK msg 1...
            replay.end.send(real.firstTransport!!) // ...but the old "ready" doesn't decrypt
            assertNull(withTimeout(10.seconds) { replay.end.receive() })
            replay.run.join()
        }

        assertEquals(1, f.backend.extends)
        assertEquals(lastSeen, f.host.peerList[0].lastSeen)
        assertEquals("result", real.request("state.get").t()) // the real session was not evicted
    }

    @Test
    fun pingPong() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        hub.sendJson(ping())
        assertEquals("pong", hub.receiveJson().t())
    }

    @Test
    fun thirdSessionOfAHubClosesItsOldest() = agentTest { f ->
        val (key, _) = f.pair()
        val (s1, _) = f.connect(key)
        val (s2, _) = f.connect(key)
        val (s3, _) = f.connect(key)

        assertEquals("busy", s1.receiveJson().s("code"))
        assertEquals("result", s2.request("state.get").t())
        assertEquals("result", s3.request("state.get").t())
    }

    @Test
    fun admissionLimitsPendingHandshakes() = agentTest { f ->
        val a1 = f.host.tryAdmit("10.0.0.1")
        val a2 = f.host.tryAdmit("10.0.0.1")
        assertNotNull(a1)
        assertNotNull(a2)
        assertNull(f.host.tryAdmit("10.0.0.1")) // 2 per IP

        val others = (2..7).map { f.host.tryAdmit("10.0.0.$it") }
        assertTrue(others.all { it != null })
        assertNull(f.host.tryAdmit("10.0.0.99")) // 8 in total

        a1.release()
        a1.release() // twice is harmless
        assertNotNull(f.host.tryAdmit("10.0.0.99"))
    }

    @Test
    fun handshakeTimesOutAfter5Seconds() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, ok) = f.handshake(key, pairing = false)
        assertTrue(ok)
        // no ready
        advanceTimeBy(4.9.seconds)
        runCurrent()
        assertFalse(hub.end.peer.closed)
        advanceTimeBy(0.2.seconds)
        runCurrent()
        assertNull(withTimeout(1.seconds) { hub.end.receive() })
        hub.run.join()
    }

    @Test
    fun idleSessionIsClosedAfter90Seconds() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        advanceTimeBy(60.seconds)
        hub.sendJson(ping()) // activity resets the idle timer
        assertEquals("pong", hub.receiveJson().t())
        advanceTimeBy(60.seconds)
        runCurrent()
        assertFalse(hub.end.peer.closed)
        advanceTimeBy(31.seconds)
        runCurrent()
        assertNull(hub.tryReceiveJson(1.seconds))
        assertTrue(hub.end.peer.closed)
    }

    @Test
    fun sessionExpiresAfter24Hours() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        repeat(24 * 60 - 1) { // keep it alive with a ping every minute
            advanceTimeBy(1.minutes)
            hub.sendJson(ping())
            assertEquals("pong", hub.receiveJson().t())
        }
        advanceTimeBy(1.minutes)
        runCurrent()
        val bye = hub.receiveJson()
        assertEquals("bye", bye.t())
        assertEquals("expired", bye.s("code"))
    }

    @Test
    fun identityAndPeersSurviveARestart() = runTest {
        val store = MemoryAgentStore()
        val first = AgentFixture(this, store)
        val (hubKey, _) = first.pair()
        val agentKey = first.host.agentPublicKey
        first.host.close()

        val second = AgentFixture(this, store)
        assertContentEquals(agentKey, second.host.agentPublicKey)
        val (_, hello) = second.connect(hubKey)
        assertEquals("admin", hello.obj("you").s("role"))
        second.host.close()
    }

    @Test
    fun anUnreadableStoreMeansANewIdentity() = runTest {
        val store = MemoryAgentStore()
        store.state = AgentStateData(privateKey = "not a key")
        val f = AgentFixture(this, store)
        assertNull(f.host.initialSaveError)
        assertEquals(32, com.gametimer.protocol.encodings.Base64Url.tryDecode(store.state!!.privateKey)!!.size)
        f.host.close()
    }

    @Test
    fun shutdownSaysBye() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        f.host.shutdown()
        assertEquals("shutdown", hub.receiveJson().s("code"))
    }

    @Test
    fun cancellingTheEndpointSaysShutdown() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        hub.run.cancel()
        assertEquals("shutdown", hub.receiveJson().s("code"))
    }

    @Test
    fun aClosedHostAdmitsNothing() = agentTest { f ->
        f.host.close()
        assertNull(f.host.tryAdmit("10.0.0.1"))
    }
}
