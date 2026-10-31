package com.gametimer.protocol.hub

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.RouteToken
import com.gametimer.protocol.identity.DiscoveryTag
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/** AgentLink's relay path against fake relays (§10.3): what an untrusted relay can and can't make the app say. */
class AgentLinkRelayTests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hubKey by lazy { KeyPair.generate() }
    private val ak = Random.nextBytes(32)
    private val agent by lazy {
        val pc = KeyPair.generate()
        PairedAgent(Ids.agentId(pc.publicKey), Base64Url.encode(pc.publicKey), "PC", "windows", "127.0.0.1", 1,
            Base64Url.encode(ak), "admin", remoteAccess = true)
    }
    private val noLan = Connector { _, _, _ -> throw IOException("no LAN") }

    @BeforeTest
    fun init() = TestSupport.initSodium()

    @AfterTest
    fun done() = scope.cancel()

    private fun link(start: Boolean = true, relay: RelayConnector) =
        AgentLink(agent, hubKey, noLan, "test", scope, relay = RelayPath("wss://relay.test", relay, onLocalNetwork = { false }))
            .also { if (start) it.start() }

    private suspend fun AgentLink.awaitOffline() =
        withTimeout(15.seconds) { status.first { it is LinkStatus.Offline } } as LinkStatus.Offline

    /** A socket that answers msg 1 with [reply] (then closes). */
    private class FakeSocket(private val reply: ByteArray?) : FrameSocket {
        val sent = CopyOnWriteArrayList<ByteArray>()
        private var answered = false
        override suspend fun send(frame: ByteArray) { sent += frame }
        override suspend fun receive(): ByteArray? = if (answered) null else { answered = true; reply }
        override suspend fun close() {}
    }

    @Test
    fun aRelayThatLiesIsOnlyUnreachableNeverAWrongDevice(): Unit = runBlocking {
        val sockets = CopyOnWriteArrayList<FakeSocket>()
        val l = link { FakeSocket(Random.nextBytes(48)).also { sockets += it } } // garbage msg 2
        assertEquals(OfflineReason.Unreachable, l.awaitOffline().reason)
        assertTrue(sockets.all { it.sent.size == 1 }) // msg 1 only: never ready on that path
        l.stop()

        val silent = link { FakeSocket(null) } // closes without msg 2 ("not_accepted" on the LAN)
        assertEquals(OfflineReason.Unreachable, silent.awaitOffline().reason)
        silent.stop()
    }

    @Test
    fun only404sMoveToTheNextRouteThenTheRelayWaits(): Unit = runBlocking {
        val dialled = CopyOnWriteArrayList<String>()
        val l = link { url -> dialled += url; throw RelayRouteNotFound() }
        l.awaitOffline()
        delay(3_000) // several LAN retries: the relay is not dialled again (back-off 20 s)
        val now = System.currentTimeMillis()
        val expected = listOf(0, 1, -1).map {
            "wss://relay.test/v2/hub/" + RouteToken.derive(ak, DiscoveryTag.utcDate(now + it * 86_400_000L))
        }
        assertEquals(expected, dialled.toList())
        l.stop()

        val other = CopyOnWriteArrayList<String>()
        val l2 = link { url -> other += url; throw IOException("429 or no network") }
        l2.awaitOffline()
        delay(3_000)
        assertEquals(1, other.size) // not a 404: no other route, straight to the back-off
        l2.stop()
    }

    @Test
    fun theRelayIsNotDialledWhenNotAllowed(): Unit = runBlocking {
        val dialled = CopyOnWriteArrayList<String>()
        val l = AgentLink(agent, hubKey, noLan, "test", scope,
            relay = RelayPath("wss://relay.test", { url -> dialled += url; throw RelayRouteNotFound() }, onLocalNetwork = { false }, allowed = { false }))
        l.start()
        l.awaitOffline()
        delay(2_000)
        assertTrue(dialled.isEmpty())
        l.stop()
    }

    @Test
    fun storedAgentsWithoutTheFieldDecodeAsOff() {
        val old = """{"agentId":"A","agentKey":"k","name":"PC","platform":"windows","host":"h","port":1,"ak":"x","role":"admin"}"""
        assertEquals(false, Json.decodeFromString(PairedAgent.serializer(), old).remoteAccess)
    }
}
