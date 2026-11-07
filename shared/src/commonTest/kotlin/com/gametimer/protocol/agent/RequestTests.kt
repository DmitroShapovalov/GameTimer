@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.protocol.agent

import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Envelope, routing, idempotency and event ordering (protocol §5, §7). Port of the C# RequestTests. */
class RequestTests {
    @BeforeTest
    fun init() = TestSupport.initSodium()

    private fun extend(minutes: Int = 5) = buildJsonObject { put("minutes", minutes) }
    private fun ping() = buildJsonObject { put("t", "ping") }

    @Test
    fun resultToTheRequesterAndEventToTheOthersOnly() = agentTest { f ->
        val (a, _) = f.pair("A")
        val (b, _) = f.pair("B")
        val (hubA, _) = f.connect(a)
        val (hubB, _) = f.connect(b)

        val r = hubA.request("timer.extend", extend(), withRid = true)
        assertEquals("result", r.t())
        assertEquals(1, r.obj("state")["extends"]!!.i)

        assertEquals("ev.state", hubB.receiveJson().t())

        // The requester does not get the event: the next thing it receives is the pong
        hubA.sendJson(ping())
        assertEquals("pong", hubA.receiveJson().t())
    }

    @Test
    fun workStartedByARequestKeepsPublishingToEveryone() = agentTest { f ->
        val (a, _) = f.pair("A")
        val (b, _) = f.pair("B")
        val (hubA, _) = f.connect(a)
        val (hubB, _) = f.connect(b)

        hubA.request("timer.start", buildJsonObject { put("mode", "interval"); put("hours", 1); put("minutes", 0) }, withRid = true)

        for (hub in listOf(hubA, hubB)) {
            assertEquals(1, hub.receiveJson()["n"]!!.i)
            assertEquals(2, hub.receiveJson()["n"]!!.i)
        }
        // the late event attributed to the request goes to the others only
        assertEquals("true", hubB.receiveJson().s("late"))
        hubA.sendJson(ping())
        assertEquals("pong", hubA.receiveJson().t())
    }

    @Test
    fun helloIsAlwaysTheFirstMessage() = agentTest { f ->
        val (key, _) = f.pair()
        // An event published while the agent is still building hello
        f.backend.beforeState = { f.host.publish("ev.alert", buildJsonObject { put("minutes", 5) }) }
        val (hub, _) = f.connect(key) // asserts hello first
        assertEquals("ev.alert", hub.receiveJson().t())
    }

    @Test
    fun aRequestArrivingAfterARevokeIsNotExecuted() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        f.host.revokeByOwner(hub.id)
        // The phone hasn't read the bye yet and sends one more request
        hub.sendJson(buildJsonObject { put("t", "timer.extend"); put("id", 1); put("rid", newRid()); put("minutes", 5) })
        delay(200.milliseconds)
        assertEquals(0, f.backend.extends)
        assertEquals("revoked", hub.receiveJson().s("code"))
        assertFalse(f.store.state!!.rids.containsKey(hub.id))
    }

    @Test
    fun observersGetEventsInTheOrderTheStateChanged() = agentTest { f ->
        val (a, _) = f.pair("A")
        val (b, _) = f.pair("B")
        val (hubA, _) = f.connect(a)
        val (hubB, _) = f.connect(b)
        hubA.request("timer.extend", extend(), withRid = true)
        f.host.publish("ev.alert", buildJsonObject { put("minutes", 1) })
        assertEquals("ev.state", hubB.receiveJson().t())
        assertEquals("ev.alert", hubB.receiveJson().t())
    }

    @Test
    fun aBurstOfEventsWhileHelloIsBuiltIsKept() = agentTest { f ->
        val (key, _) = f.pair()
        f.backend.beforeState = { repeat(256) { f.host.publish("ev.tick", buildJsonObject { put("n", it) }) } }
        val (hub, _) = f.connect(key)
        repeat(256) { assertEquals(it, hub.receiveJson()["n"]!!.i) }
    }

    @Test
    fun eventsFromTheDeviceReachEveryone() = agentTest { f ->
        val (a, _) = f.pair()
        val (hub, _) = f.connect(a)
        f.host.publish("ev.alert", buildJsonObject { put("minutes", 5) })
        val ev = hub.receiveJson()
        assertEquals("ev.alert", ev.t())
        assertEquals(5, ev["minutes"]!!.i)
    }

    @Test
    fun anEventTypeMustStartWithEv() = agentTest { f ->
        kotlin.test.assertFailsWith<IllegalArgumentException> { f.host.publish("alert", buildJsonObject { }) }
    }

    @Test
    fun sameRidRunsOnceAndReplaysTheStoredReplyEvenInANewSession() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        val rid = newRid()

        val first = hub.request("timer.extend", extend(), rid)
        val again = hub.request("timer.extend", extend(), rid)
        assertEquals(1, f.backend.extends)
        assertEquals(first["state"], again["state"])
        assertNotEquals(first["re"], again["re"])

        // Reconnect (e.g. Wi-Fi dropped before the reply arrived) and resend with the same rid
        val (hub2, _) = f.connect(key)
        assertEquals("result", hub2.request("timer.extend", extend(), rid).t())
        assertEquals(1, f.backend.extends)
    }

    @Test
    fun sameRidWithADifferentBodyIsABadRequest() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        val rid = newRid()
        hub.request("timer.extend", extend(5), rid)
        assertEquals("bad_request", hub.request("timer.extend", extend(10), rid).s("code"))
        assertEquals(1, f.backend.extends)
    }

    @Test
    fun errorsAreCachedToo() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        val rid = newRid()
        assertEquals("not_running", hub.request("timer.stop", null, rid).s("code"))
        val calls = f.backend.calls
        assertEquals("not_running", hub.request("timer.stop", null, rid).s("code"))
        assertEquals(calls, f.backend.calls)
    }

    @Test
    fun ridCacheSurvivesARestartAndExpiresAfter10Minutes() = runTest {
        val store = MemoryAgentStore()
        val rid = newRid()
        val first = AgentFixture(this, store)
        val (key, _) = first.pair()
        val (hub, _) = first.connect(key)
        hub.request("timer.extend", extend(), rid)
        first.host.close()

        val second = AgentFixture(this, store) // the device restarted
        val (h1, _) = second.connect(key)
        h1.request("timer.extend", extend(), rid)
        assertEquals(0, second.backend.extends) // not executed again

        advanceTimeBy(11.minutes)
        val (h2, _) = second.connect(key)
        h2.request("timer.extend", extend(), rid)
        assertEquals(1, second.backend.extends) // the entry expired
        second.host.close()
    }

    @Test
    fun aStateChangingRequestNeedsAValidRid() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        for (rid in listOf(null, "short", "AAAAAAAAAAAAAAAAAAAAAAAA" /* 18 bytes */)) {
            val fields = buildJsonObject { put("minutes", 5); if (rid != null) put("rid", rid) }
            assertEquals("bad_request", hub.request("timer.extend", fields).s("code"))
        }
        assertEquals(0, f.backend.calls)
    }

    @Test
    fun unknownCommandAndUnsupportedCapability() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        assertEquals("unknown_command", hub.request("timer.explode").s("code"))
        assertEquals("unsupported", hub.request("schedule.get").s("code"))
        assertEquals("result", hub.request("settings.get").t())
    }

    @Test
    fun requestIdMustIncrease() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        hub.request("state.get")
        hub.sendJson(buildJsonObject { put("t", "state.get"); put("id", 1) })
        assertEquals("protocol_error", hub.receiveJson().s("code"))
    }

    @Test
    fun malformedMessagesEndTheSession() {
        for (json in listOf(
            """{"t":"state.get","id":1,"id":2}""", // duplicate key
            """{"t":"state.get","id":9007199254740992}""", // beyond 2^53-1
            """["state.get"]""", // not an object
            """{"t":"state.get"""", // broken JSON
            """{"id":1}""", // no t
            """{"t":"state.get"}""", // request without id
            """{"t":"state.get","id":"1"}""", // id as a string
            """{"t":"state.get","id":1.5}""", // id not an integer
        )) {
            agentTest { f ->
                val (key, _) = f.pair()
                val (hub, _) = f.connect(key)
                hub.sendRaw(json.encodeToByteArray())
                val bye = hub.receiveJson()
                assertEquals("bye", bye.t(), json)
                assertEquals("protocol_error", bye.s("code"), json)
                assertNull(hub.tryReceiveJson())
            }
        }
    }

    @Test
    fun aTextMessageEndsTheSession() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        hub.end.sendText()
        assertEquals("protocol_error", hub.receiveJson().s("code"))
    }

    @Test
    fun aTamperedTransportMessageEndsTheSession() = agentTest { f ->
        val (key, _) = f.pair()
        val (hub, _) = f.connect(key)
        hub.end.send(ByteArray(40))
        assertEquals("protocol_error", hub.receiveJson().s("code"))
    }

    @Test
    fun aFailingBackendIsAnsweredInternalAndNotCached() = runTest {
        var calls = 0
        val f = AgentFixture(this)
        val failing = object : AgentBackend by f.backend {
            override suspend fun handle(type: String, message: kotlinx.serialization.json.JsonObject, context: RequestContext): Reply {
                calls++
                throw IllegalStateException("boom")
            }
        }
        f.host.close()
        val host = AgentHost(MemoryAgentStore(), failing, f.ui, clock = { AgentFixture.BASE_TIME }, scope = backgroundScope,
            timeSource = testScheduler.timeSource)
        val offer = host.startPairing("x")
        val key = KeyPair.generate()
        val (p, ok) = TestHub.handshake(host, key, pairing = true, psk = offer.secret, firstType = "pair", scope = backgroundScope)
        assertTrue(ok)
        val code = com.gametimer.protocol.identity.PairingCode.derive(p.handshakeHash!!)
        f.ui.answer = { code }
        p.sendJson(AgentFixture.pairHello())
        assertEquals("pair.result", p.receiveJson().t())

        val (s, _) = TestHub.connect(host, key, backgroundScope)
        val rid = newRid()
        assertEquals("internal", s.request("timer.extend", extend(), rid).s("code"))
        assertEquals("internal", s.request("timer.extend", extend(), rid).s("code")) // not cached: it ran again
        assertEquals(2, calls)
        assertEquals("result", s.request("peers.list").t()) // the session survives
        host.close()
    }
}
