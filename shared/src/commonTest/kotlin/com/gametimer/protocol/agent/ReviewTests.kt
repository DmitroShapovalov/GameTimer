@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.protocol.agent

import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.PairingCode
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Behaviours the independent review asked to pin down (store errors, stray cancellations, limits, I-JSON). */
class ReviewTests {
    @BeforeTest
    fun init() = TestSupport.initSodium()

    @Test
    fun aStoreThatCannotLoadStopsTheHostInsteadOfReplacingTheIdentity() = runTest {
        var saves = 0
        val store = object : AgentStore {
            override fun load(): AgentStateData? = throw IllegalStateException("keystore locked")
            override fun save(state: AgentStateData) { saves++ }
        }
        val f = AgentFixture(this)
        assertFailsWith<IllegalStateException> { AgentHost(store, f.backend, f.ui, scope = backgroundScope) }
        assertEquals(0, saves)
        f.host.close()
    }

    @Test
    fun aTimeoutInsideTheBackendIsAnsweredInternalAndTheSessionLives() = runTest {
        val f = AgentFixture(this)
        val (key, _) = f.pair()
        f.host.close()
        val timingOut = object : AgentBackend by f.backend {
            override suspend fun handle(type: String, message: JsonObject, context: RequestContext): Reply =
                if (type == "timer.extend") withTimeout(10.milliseconds) { awaitCancellation() } else f.backend.handle(type, message, context)
        }
        val host = AgentHost(f.store, timingOut, f.ui, clock = { AgentFixture.BASE_TIME }, scope = backgroundScope,
            timeSource = testScheduler.timeSource)
        f.backend.host = host
        val (hub, _) = TestHub.connect(host, key, backgroundScope)
        assertEquals("internal", hub.request("timer.extend", buildJsonObject { put("minutes", 5) }, withRid = true).s("code"))
        assertEquals("result", hub.request("state.get").t())
        host.close()
    }

    @Test
    fun aDialogEndingWithItsOwnCancellationIsADenial() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        f.ui.answer = { withTimeout(10.milliseconds) { awaitCancellation() } }
        val (hub, _) = f.handshake(KeyPair.generate(), true, offer.secret)
        hub.sendJson(AgentFixture.pairHello())
        assertEquals("denied", hub.receiveJson().s("code"))
    }

    @Test
    fun sixteenSessionsInTotalThenBusy() = agentTest { f ->
        val keys = (0 until 9).map { f.pair("H$it").first }
        val hubs = keys.take(8).flatMap { k -> (0 until 2).map { f.connect(k).first } }
        assertEquals(AgentHost.MAX_SESSIONS, f.host.liveSessionCount)
        val lastSeen = f.host.peerList.single { it.id == Ids.hubId(keys[8].publicKey) }.lastSeen
        // keys[8] has no session yet: a new one is refused, and doesn't count as seen
        val (extra, ok) = f.handshake(keys[8], pairing = false)
        assertTrue(ok)
        extra.sendJson(buildJsonObject { put("t", "ready"); put("protocol", 2); put("minor", 0) })
        assertEquals("busy", extra.receiveJson().s("code"))
        assertEquals(lastSeen, f.host.peerList.single { it.id == Ids.hubId(keys[8].publicKey) }.lastSeen)
        assertEquals("result", hubs[0].request("state.get").t())
    }

    @Test
    fun tenFailedHandshakesAMinuteCloseThePairingDoorForThatAddress() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        repeat(AgentHost.MAX_FAILED_HANDSHAKES_PER_MINUTE) {
            val (_, got) = f.handshake(KeyPair.generate(), true, ByteArray(32), ip = "192.168.1.66") // wrong secret
            assertFalse(got)
        }
        // The right secret from that address gets nothing now; from another address it still works
        assertFalse(f.handshake(KeyPair.generate(), true, offer.secret, ip = "192.168.1.66").second)
        val (phone, ok) = f.handshake(KeyPair.generate(), true, offer.secret, ip = "192.168.1.20")
        assertTrue(ok)
        val code = PairingCode.derive(phone.handshakeHash!!)
        f.ui.answer = { code }
        phone.sendJson(AgentFixture.pairHello())
        assertEquals("pair.result", phone.receiveJson().t())
        // A minute later the address may try again
        kotlinx.coroutines.delay(61_000)
        val again = f.host.startPairing("PC")
        assertTrue(f.handshake(KeyPair.generate(), true, again.secret, ip = "192.168.1.66").second)
    }

    @Test
    fun anEvictedRelayStreamCanNoLongerProveItsHub() = agentTest { f ->
        f.host.remoteAccess = true
        val t1 = f.host.tryAdmitRelay("A")!!
        f.host.tryAdmitRelay("A")!!
        f.host.tryAdmitRelay("A")!! // evicts t1
        assertTrue(t1.isEvicted)
        assertFalse(f.host.tryProve(t1))
        val t2 = f.host.tryAdmitRelay("B")!!
        t2.release()
        assertFalse(f.host.tryProve(t2)) // released: over
    }

    @Test
    fun strictJsonTakesOnlyAsciiDigitsAndFourHexDigitEscapes() {
        for (bad in listOf("{\"id\":1\u0662}", "{\"t\":\"\\u+04A\"}", "{\"t\":\"\\u-001\"}", "{\"id\":\u0661}")) {
            assertFailsWith<ProtocolViolation>(bad) { StrictJson.parse(bad.encodeToByteArray()) }
        }
        assertEquals("A", StrictJson.parse("{\"t\":\"\\u0041\"}".encodeToByteArray()).s("t"))
        assertEquals("12", StrictJson.parse("{\"id\":12}".encodeToByteArray())["id"].toString())
    }

    @Test
    fun aPeerNeverPrintsItsSecrets() {
        val p = Peer("ID", "KEY", "Phone", PeerRole.Admin, 0, null, ak = "SECRET-AK", pushToken = "SECRET-TOKEN")
        assertFalse("SECRET" in p.toString())
        assertNull(p.forApp().pushToken)
    }
}
