@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.identity.DiscoveryTag
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.RouteToken
import com.gametimer.protocol.noise.HandshakeState
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.NoisePattern
import com.gametimer.protocol.noise.TestSupport
import com.gametimer.protocol.noise.hex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The host's relay, discovery and push parts (§9, §10.1, §10.4, §10.6). Port of the host-level C# tests in
 * DiscoveryTests, RouteTokenTests and RelayClientTests (the relay client itself is not part of the core).
 */
class HostTests {
    @BeforeTest
    fun init() = TestSupport.initSodium()

    private fun ak(r: JsonObject) = Base64Url.tryDecode(r.s("ak"))!!

    @Test
    fun discoveryTagsAreDerivedFromEachHubsAk() = agentTest { f ->
        val day = "2026-10-24"
        assertTrue(f.host.discoveryTags(day).isEmpty())
        val (_, r1) = f.pair("A")
        val (_, r2) = f.pair("B")
        assertEquals(listOf(r1, r2).map { DiscoveryTag.derive(ak(it), day) }, f.host.discoveryTags(day))
        assertNotEquals(f.host.discoveryTags(day), f.host.discoveryTags("2026-10-25"))
        assertEquals(day, f.host.utcToday)
    }

    @Test
    fun knownNetworksAreKeptMostRecentLastCappedAndPersisted() = agentTest { f ->
        assertFalse(f.host.isKnownNetwork("A"))
        assertTrue(f.host.rememberNetwork("A"))
        assertFalse(f.host.rememberNetwork("A")) // already known
        assertTrue(f.host.isKnownNetwork("A"))
        repeat(AgentHost.MAX_KNOWN_NETWORKS) { f.host.rememberNetwork("N$it") }
        assertFalse(f.host.isKnownNetwork("A")) // the oldest is dropped
        f.host.rememberNetwork("N0") // used again: now the most recent
        f.host.rememberNetwork("X")
        assertTrue(f.host.isKnownNetwork("N0"))
        assertFalse(f.host.isKnownNetwork("N1"))
        assertEquals(AgentHost.MAX_KNOWN_NETWORKS, f.store.state!!.knownNetworks.size)
        assertEquals("X", f.store.state!!.knownNetworks.last())
        assertFalse(f.host.rememberNetwork(""))
        assertFalse(f.host.rememberNetwork("x".repeat(129)))
    }

    @Test
    fun sessionStartIsReportedWithTheRemoteAddress() = agentTest { f ->
        val (key, _) = f.pair()
        val started = mutableListOf<String>()
        f.host.onSessionStarted = { started.add(it); throw IllegalStateException("a broken app handler") }
        val (hub, _) = f.connect(key, ip = "192.168.1.33")
        eventually { started.isNotEmpty() }
        assertEquals(listOf("192.168.1.33"), started)
        // The throwing handler didn't break the session
        assertEquals("result", hub.request("state.get").t())
        // A hub this agent doesn't know never gets that far
        f.handshake(KeyPair.generate(), pairing = false)
        delay(200.milliseconds)
        assertEquals(1, started.size)
    }

    @Test
    fun theHostRegistersYesterdayTodayAndTomorrowPerHub() = agentTest { f ->
        val day = "2026-10-24"
        assertTrue(f.host.routeClaims(day).isEmpty())
        val (_, r1) = f.pair("A")
        val (_, r2) = f.pair("B")
        val expected = listOf(r1, r2).flatMap { r -> listOf("2026-10-23", "2026-10-24", "2026-10-25").map { RouteToken.claim(ak(r), it).hex() } }.sorted()
        assertEquals(expected, f.host.routeClaims(day).map { it.hex() }.sorted())
        val routes = f.host.relayRoutes(day)
        assertTrue(routes.all { RouteToken.route(it.claim).let { r -> com.gametimer.protocol.encodings.Base32.encode(r) } == it.route })
        assertEquals(setOf(Ids.hubId(f.host.peerList[0].publicKey.let { Base64Url.tryDecode(it)!! }), f.host.peerList[1].id),
            routes.map { it.hubId }.toSet())
    }

    @Test
    fun theHostCapsTheClaimsByWholeHubsKeepingTheMostRecentlyUsed() = agentTest { f ->
        val hubs = AgentHost.MAX_ROUTE_TOKENS / 3 + 2
        val results = (0 until hubs).map { delay(1000); f.pair("H$it") }
        // The first hub paired connects again: it is now the most recently used
        delay(1000)
        f.connect(results[0].first)
        val day = "2026-10-24"
        val claims = f.host.routeClaims(day).map { it.hex() }.toSet()
        assertEquals(AgentHost.MAX_ROUTE_TOKENS, claims.size)
        assertTrue(RouteToken.claim(ak(results[0].second), day).hex() in claims)
        assertTrue(RouteToken.claim(ak(results.last().second), day).hex() in claims)
        // Two hubs fall out: those paired earliest and not seen since
        assertFalse(RouteToken.claim(ak(results[1].second), day).hex() in claims)
        assertFalse(RouteToken.claim(ak(results[2].second), day).hex() in claims)
    }

    @Test
    fun replayedMsg1HoldsAtMostTwoStreamsPerHub() = agentTest { f ->
        f.host.remoteAccess = true
        val (key, _) = f.pair("A")
        val hubId = Ids.hubId(key.publicKey)
        // The relay saw one real msg 1 and replays it on new streams that never send ready
        val msg1 = HandshakeState(NoisePattern.IK, true, AgentHost.PROLOGUE, key, f.host.agentPublicKey)
            .writeMessage("{\"t\":\"connect\"}".encodeToByteArray())
        suspend fun getsMsg2(): Boolean {
            val (agentEnd, hubEnd) = MemoryTransport.pair()
            val ticket = f.host.tryAdmitRelay(hubId)!!
            f.scope.launch { f.host.run(agentEnd, ticket, pairing = false) }
            hubEnd.send(msg1)
            return withTimeoutOrNull(1.seconds) { hubEnd.receive() } != null
        }
        assertTrue(getsMsg2())
        assertTrue(getsMsg2())
        assertFalse(getsMsg2()) // the third waits for nothing: refused
        assertEquals(0, f.host.relayPendingCount) // and none of them sits in the evictable pool
    }

    @Test
    fun aRouteOnlyWorksForItsOwnHubKey() = agentTest { f ->
        f.host.remoteAccess = true
        val (a, _) = f.pair("A")
        val (b, _) = f.pair("B")
        val (agentEnd, hubEnd) = MemoryTransport.pair()
        val ticket = f.host.tryAdmitRelay(Ids.hubId(b.publicKey))!! // B's route…
        f.scope.launch { f.host.run(agentEnd, ticket, pairing = false) }
        hubEnd.send(HandshakeState(NoisePattern.IK, true, AgentHost.PROLOGUE, a, f.host.agentPublicKey) // …A's key
            .writeMessage("{\"t\":\"connect\"}".encodeToByteArray()))
        assertNull(withTimeoutOrNull(5.seconds) { hubEnd.receive() })
    }

    @Test
    fun aSilentRelayStreamGivesItsSlotBackAfter2Seconds() = agentTest { f ->
        f.host.remoteAccess = true
        val (agentEnd, _) = MemoryTransport.pair()
        val ticket = f.host.tryAdmitRelay("A")!!
        val run = f.scope.launch { f.host.run(agentEnd, ticket, pairing = false) }
        delay(1900.milliseconds)
        assertEquals(1, f.host.relayPendingCount)
        delay(200.milliseconds)
        assertTrue(run.isCompleted)
        assertEquals(0, f.host.relayPendingCount)
    }

    @Test
    fun relaySessionsWorkAndEndWhenRemoteAccessIsTurnedOff() = agentTest { f ->
        val (key, _) = f.pair("A")
        assertNull(f.host.tryAdmitRelay(Ids.hubId(key.publicKey))) // off: no relayed streams at all
        f.host.remoteAccess = true
        val (agentEnd, hubEnd) = MemoryTransport.pair()
        val ticket = f.host.tryAdmitRelay(Ids.hubId(key.publicKey))!!
        val started = mutableListOf<String>()
        f.host.onSessionStarted = { started.add(it) }
        f.scope.launch { f.host.run(agentEnd, ticket, pairing = false) }
        val hs = HandshakeState(NoisePattern.IK, true, AgentHost.PROLOGUE, key, f.host.agentPublicKey)
        hubEnd.send(hs.writeMessage("{\"t\":\"connect\"}".encodeToByteArray()))
        hs.readMessage(hubEnd.receive()!!)
        val (send, recv) = hs.split()
        hubEnd.send(send.encrypt("""{"t":"ready","protocol":2,"minor":3}""".encodeToByteArray()))
        val hello = kotlinx.serialization.json.Json.parseToJsonElement(recv.decrypt(hubEnd.receive()!!).decodeToString()) as JsonObject
        assertEquals("hello", hello.t())
        assertEquals("true", hello.s("remoteAccess"))
        assertTrue(started.isEmpty()) // a relayed session says nothing about the local network

        f.host.remoteAccess = false
        f.host.endRelaySessions()
        val bye = kotlinx.serialization.json.Json.parseToJsonElement(recv.decrypt(hubEnd.receive()!!).decodeToString()) as JsonObject
        assertEquals("remote_off", bye.s("code"))
    }

    @Test
    fun theRelayPoolIsApartAndEvictsTheOldest() = agentTest { f ->
        f.host.remoteAccess = true
        val lan = (0 until AgentHost.MAX_PENDING_HANDSHAKES / AgentHost.MAX_PENDING_PER_IP).flatMap { i ->
            (0 until AgentHost.MAX_PENDING_PER_IP).map { f.host.tryAdmit("192.168.1.$i") }
        }
        assertTrue(lan.all { it != null })
        assertNull(f.host.tryAdmit("192.168.1.99")) // the LAN pool is full…
        val a1 = f.host.tryAdmitRelay("A")!! // …the relay pool is not
        val a2 = f.host.tryAdmitRelay("A")!!
        val a3 = f.host.tryAdmitRelay("A")!! // 2 per hub: A's oldest is evicted
        assertTrue(a1.isEvicted)
        assertFalse(a2.isEvicted)
        val b1 = f.host.tryAdmitRelay("B")!!
        val c1 = f.host.tryAdmitRelay("C")!! // a2, a3, b1, c1: the pool is full
        assertEquals(AgentHost.MAX_RELAY_PENDING, f.host.relayPendingCount)
        assertFalse(a2.isEvicted)
        val d1 = f.host.tryAdmitRelay("D")!! // D has none: the hub with the most waiting (A) gives its oldest
        assertEquals(AgentHost.MAX_RELAY_PENDING, f.host.relayPendingCount)
        assertTrue(a2.isEvicted)
        assertFalse(a3.isEvicted || b1.isEvicted || c1.isEvicted || d1.isEvicted)
        // A flood on one hub's route (leaked) only ever evicts that hub's own streams
        val flood = (0 until 5).map { f.host.tryAdmitRelay("D")!! }
        assertFalse(a3.isEvicted || b1.isEvicted || c1.isEvicted)
        assertTrue(d1.isEvicted)
        (listOf(a1, a2, a3, b1, c1, d1) + flood).forEach { it.release() }
        assertEquals(0, f.host.relayPendingCount)
        assertNull(f.host.tryAdmit("192.168.1.99")) // relay tickets never touched the LAN pool
        lan.forEach { it!!.release() }
        assertNotNull(f.host.tryAdmit("192.168.1.99"))
    }

    @Test
    fun pushSetStoresTheTokenAndEventsWakeHubsWithoutASession() = agentTest { f ->
        f.host.remoteAccess = true
        val woken = mutableListOf<String>()
        f.host.onWakeNeeded = { woken.addAll(it) }
        val (keyA, _) = f.pair("A")
        val (keyB, _) = f.pair("B")
        val (keyC, _) = f.pair("C")
        fun hist(type: String, source: String = "phone", hub: String? = null) = buildJsonObject {
            put("event", buildJsonObject { put("type", type); put("source", source); put("hub", hub?.let { JsonPrimitive(it) } ?: JsonNull) })
        }
        fun token(t: Any?) = buildJsonObject { if (t is String) put("token", t) else if (t is Int) put("token", t) else put("token", JsonNull) }

        val (a, helloA) = f.connect(keyA)
        assertTrue(JsonPrimitive("push") in (helloA["capabilities"] as JsonArray))
        assertEquals("result", a.request("push.set", token("token-A"), withRid = true).t())
        assertEquals("bad_request", a.request("push.set", token("has space"), withRid = true).s("code"))
        assertEquals("bad_request", a.request("push.set", token(5), withRid = true).s("code"))
        assertEquals("bad_request", a.request("push.set", buildJsonObject { put("token", "t"); put("events", buildJsonArray { add(JsonPrimitive("a-b")) }) }, withRid = true).s("code"))
        // B wants only "time's up"; C everything
        val (b, _) = f.connect(keyB)
        b.request("push.set", buildJsonObject { put("token", "token-B"); put("events", buildJsonArray { add(JsonPrimitive("expired")) }) }, withRid = true)
        val (c, _) = f.connect(keyC)
        c.request("push.set", token("token-C"), withRid = true)
        assertEquals("token-A", f.store.state!!.peers.single { it.name == "A" }.pushToken) // persisted
        assertNull(f.host.peerList.single { it.name == "A" }.pushToken) // never handed to the app
        assertFalse("token-" in a.request("peers.list").toString()) // never shown to other hubs

        // A stays connected (it sees the events itself); B and C go away
        b.end.close()
        c.end.close()
        eventually { f.host.liveSessionCount == 1 }

        fun wokenBy(publish: () -> Unit): List<String> {
            woken.clear()
            publish()
            return woken.toList()
        }
        // Someone stopped the timer: C (all kinds) — not B (only expired), not A (online)
        assertEquals(listOf("token-C"), wokenBy { f.host.publish("ev.history", hist("stopped")) })
        // Automatic events (source system) wake nobody, alerts neither unless asked (§10.6)
        assertEquals(emptyList(), wokenBy { f.host.publish("ev.history", hist("started", "system")) })
        assertEquals(emptyList(), wokenBy { f.host.publish("ev.alert", buildJsonObject { put("minutes", 5) }) })
        a.request("push.set", buildJsonObject { put("token", "token-A2"); put("events", buildJsonArray { add(JsonPrimitive("alert")) }) }, withRid = true)
        a.end.close()
        eventually { f.host.liveSessionCount == 0 }
        assertEquals(listOf("token-A2"), wokenBy { f.host.publish("ev.alert", buildJsonObject { put("minutes", 5) }) })
        // Time's up (system) wakes B and C
        assertEquals(listOf("token-B", "token-C"), wokenBy { f.host.publish("ev.history", hist("expired", "system")) }.sorted())
        // Nothing at all while remote access is off
        f.host.remoteAccess = false
        assertEquals(emptyList(), wokenBy { f.host.publish("ev.history", hist("closed", "system")) })
        assertEquals(listOf("token-A2", "token-B", "token-C"), f.host.pushTokensForFarewell().sorted())
        f.host.remoteAccess = true
        // The hub that caused an event isn't woken for it
        val hubC = f.host.peerList.single { it.name == "C" }.id
        assertFalse("token-C" in wokenBy { f.host.publish("ev.history", hist("extended", hub = hubC)) })

        // A held-back wake-up: still to wake only if the hub hasn't connected since
        val before = f.host.now
        assertTrue(f.host.stillToWake("token-B", before))
        delay(1000)
        val (b2, _) = f.connect(keyB)
        b2.end.close()
        eventually { f.host.liveSessionCount == 0 }
        assertFalse(f.host.stillToWake("token-B", before))

        val (a2, _) = f.connect(keyA)
        a2.request("push.set", token(null), withRid = true)
        assertNull(f.store.state!!.peers.single { it.name == "A" }.pushToken)
    }

    @Test
    fun aNewParentAppWakesTheOthers() = agentTest { f ->
        f.host.remoteAccess = true
        val woken = mutableListOf<String>()
        f.host.onWakeNeeded = { woken.addAll(it) }
        val (keyB, _) = f.pair("B")
        val (b, _) = f.connect(keyB)
        b.request("push.set", buildJsonObject { put("token", "token-B"); put("events", buildJsonArray { add(JsonPrimitive("peerAdded")) }) }, withRid = true)
        b.end.close()
        eventually { f.host.liveSessionCount == 0 }
        f.pair("Child's phone?")
        assertEquals(listOf("token-B"), woken)
    }

    @Test
    fun storeErrorsAreReportedAndTheStateStaysInMemory() = agentTest { f ->
        val errors = mutableListOf<Exception>()
        f.host.onStoreError = { errors.add(it) }
        val failingStore = object : AgentStore {
            override fun load(): AgentStateData? = f.store.load()
            override fun save(state: AgentStateData) = throw IllegalStateException("disk full")
        }
        val host = AgentHost(failingStore, f.backend, f.ui, scope = f.scope)
        host.onStoreError = { errors.add(it) }
        assertTrue(host.rememberNetwork("N"))
        assertTrue(host.isKnownNetwork("N"))
        assertEquals(1, errors.size)
        host.close()
    }

    @Test
    fun isoTimesAndDates() {
        assertEquals("2026-10-24T00:00:00.000Z", isoUtc(AgentFixture.BASE_TIME))
        assertEquals("1970-01-01T00:00:01.234Z", isoUtc(1234))
        assertEquals(AgentFixture.BASE_TIME, parseUtcDate("2026-10-24"))
        assertEquals(0L, parseUtcDate("1970-01-01"))
        assertEquals("2024-02-29", DiscoveryTag.utcDate(parseUtcDate("2024-02-29")))
    }
}
