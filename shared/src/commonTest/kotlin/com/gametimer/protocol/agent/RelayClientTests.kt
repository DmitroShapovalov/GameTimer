@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base32
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.RouteToken
import com.gametimer.protocol.noise.HandshakeState
import com.gametimer.protocol.noise.NoisePattern
import com.gametimer.protocol.noise.TestSupport
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** RelayClient against a scripted relay (frames of §10.2), in virtual time. */
class RelayClientTests {
    @BeforeTest
    fun init() = TestSupport.initSodium()

    /** The relay's end of one agent socket. */
    private class FakeRelay : RelaySocket {
        val toAgent = Channel<ByteArray?>(Channel.UNLIMITED)
        val fromAgent = Channel<ByteArray>(Channel.UNLIMITED)
        var closed = false
        override suspend fun send(frame: ByteArray) { if (closed) throw IllegalStateException("closed"); fromAgent.send(frame) }
        override suspend fun receive(): ByteArray? = if (closed) null else toAgent.receive()
        override suspend fun close() { closed = true; toAgent.trySend(null) }

        /** The next frame from the agent of [type], skipping others (pings). */
        suspend fun next(type: Byte): ByteArray = withTimeout(60.seconds) {
            var f = fromAgent.receive()
            while (f[0] != type) f = fromAgent.receive()
            f
        }
    }

    private class Rig(val f: AgentFixture) {
        val sockets = Channel<FakeRelay>(Channel.UNLIMITED)
        var connects = 0
        val client = RelayClient(f.host, "wss://relay.test/v2/agent", { connects++; FakeRelay().also { sockets.trySend(it) } }, scope = f.scope)
        suspend fun socket(): FakeRelay = withTimeout(60.seconds) { sockets.receive() }
    }

    private fun claimsOf(register: ByteArray): Set<String> =
        (0 until (register.size - 1) / 16).map { Base32.encode(register.copyOfRange(1 + 16 * it, 17 + 16 * it)) }.toSet()

    @Test
    fun registersTheClaimsAndReregistersWhenPeersChange() = agentTest { f ->
        val (_, r1) = f.pair("A")
        val rig = Rig(f)
        rig.client.start()
        val relay = rig.socket()
        val first = relay.next(RelayFrames.REGISTER)
        assertEquals(3, (first.size - 1) / 16)
        assertEquals(RelayStatus.Connected, rig.client.status.value)
        val ak = com.gametimer.protocol.encodings.Base64Url.tryDecode(r1.s("ak"))!!
        assertTrue(Base32.encode(RouteToken.claim(ak, f.host.utcToday)) in claimsOf(first))
        f.pair("B")
        val second = relay.next(RelayFrames.REGISTER)
        assertEquals(6, (second.size - 1) / 16)
        rig.client.stop()
        assertEquals(RelayStatus.Off, rig.client.status.value)
    }

    @Test
    fun pingIsAnsweredAndSilenceIsPinged() = agentTest { f ->
        f.pair("A")
        val rig = Rig(f)
        rig.client.start()
        val relay = rig.socket()
        relay.next(RelayFrames.REGISTER)
        relay.toAgent.send(byteArrayOf(RelayFrames.PING, 7))
        assertEquals(listOf(RelayFrames.PONG, 7.toByte()), relay.next(RelayFrames.PONG).toList())
        advanceTimeBy(32.seconds)
        relay.next(RelayFrames.PING) // the agent's own keepalive after 30 s without sending
        rig.client.stop()
    }

    @Test
    fun anUnknownRouteIsRefusedAndAKnownOneCarriesAHandshake() = agentTest { f ->
        f.host.remoteAccess = true
        val (key, r) = f.pair("A")
        val rig = Rig(f)
        rig.client.start()
        val relay = rig.socket()
        relay.next(RelayFrames.REGISTER)

        relay.toAgent.send(byteArrayOf(RelayFrames.OPEN, 0, 0, 0, 1) + ByteArray(16))
        val refused = relay.next(RelayFrames.CLOSE)
        assertEquals(1u, RelayFrames.streamOf(refused))
        assertEquals(1008, ((refused[5].toInt() and 0xff) shl 8) or (refused[6].toInt() and 0xff))

        val ak = com.gametimer.protocol.encodings.Base64Url.tryDecode(r.s("ak"))!!
        val route = RouteToken.route(RouteToken.claim(ak, f.host.utcToday))
        relay.toAgent.send(byteArrayOf(RelayFrames.OPEN, 0, 0, 0, 2) + route)
        val hs = HandshakeState(NoisePattern.IK, true, AgentHost.PROLOGUE, key, f.host.agentPublicKey)
        relay.toAgent.send(RelayFrames.data(2u, hs.writeMessage("{\"t\":\"connect\"}".encodeToByteArray())))
        val msg2 = relay.next(RelayFrames.DATA)
        hs.readMessage(msg2.copyOfRange(5, msg2.size))
        val (send, recv) = hs.split()
        relay.toAgent.send(RelayFrames.data(2u, send.encrypt("""{"t":"ready","protocol":2,"minor":3}""".encodeToByteArray())))
        val helloFrame = relay.next(RelayFrames.DATA)
        val hello = Json.parseToJsonElement(recv.decrypt(helloFrame.copyOfRange(5, helloFrame.size)).decodeToString()) as JsonObject
        assertEquals("hello", hello.t())
        assertEquals(Ids.hubId(key.publicKey), hello.obj("you").s("id"))
        assertEquals(1, f.host.liveSessionCount)

        // The relay drops the socket: the session ends, the client reconnects
        relay.toAgent.send(null)
        val again = rig.socket()
        again.next(RelayFrames.REGISTER)
        eventually { f.host.liveSessionCount == 0 }
        rig.client.stop()
    }

    @Test
    fun aMalformedFrameMeansReconnect() = agentTest { f ->
        f.pair("A")
        val rig = Rig(f)
        rig.client.start()
        val relay = rig.socket()
        relay.next(RelayFrames.REGISTER)
        relay.toAgent.send(byteArrayOf(RelayFrames.REGISTER)) // never sent by a relay
        rig.socket().next(RelayFrames.REGISTER)
        assertEquals(2, rig.connects)
        rig.client.stop()
    }

    @Test
    fun wakeUpsArePacedPerPhoneWithOneTrailingWakeUp() = agentTest { f ->
        f.host.remoteAccess = true
        val (key, _) = f.pair("B")
        val (b, _) = f.connect(key)
        b.request("push.set", buildJsonObject { put("token", "token-B") }, withRid = true)
        b.end.close()
        eventually { f.host.liveSessionCount == 0 }
        val rig = Rig(f)
        rig.client.start()
        val relay = rig.socket()
        relay.next(RelayFrames.REGISTER)
        fun stopped() = buildJsonObject { put("event", buildJsonObject { put("type", "stopped"); put("source", "pc") }) }

        f.host.publish("ev.history", stopped()) // at once
        f.host.publish("ev.history", stopped()) // held back
        assertEquals("token-B", relay.next(RelayFrames.WAKE).copyOfRange(1, 8).decodeToString())
        runCurrent()
        assertTrue(relay.fromAgent.tryReceive().getOrNull()?.get(0) != RelayFrames.WAKE)
        advanceTimeBy(31.seconds)
        assertEquals("token-B", relay.next(RelayFrames.WAKE).copyOfRange(1, 8).decodeToString()) // the trailing one
        delay(1)
        rig.client.stop()
    }
}
