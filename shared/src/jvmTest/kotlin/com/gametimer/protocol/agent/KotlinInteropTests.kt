package com.gametimer.protocol.agent

import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.AgentSchedule
import com.gametimer.protocol.hub.AgentSettings
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.Connector
import com.gametimer.protocol.hub.FrameSocket
import com.gametimer.protocol.hub.HistoryPage
import com.gametimer.protocol.hub.KtorConnector
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.OfflineReason
import com.gametimer.protocol.hub.PairOutcome
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.hub.PairingLink
import com.gametimer.protocol.hub.PeerInfo
import com.gametimer.protocol.hub.RequestOutcome
import com.gametimer.protocol.hub.pair
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Kotlin ↔ Kotlin: the parent app's hub client (shared/.../hub: pair(), AgentLink, AgentApi) against the Kotlin
 * AgentHost behind a real WebSocket server (Ktor/OkHttp ↔ Ktor CIO). The same scenarios as HubInteropTests
 * runs against the C# agent, so both agents are held to one behaviour.
 */
class KotlinInteropTests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connector = KtorConnector()
    private lateinit var agent: TestAgentServer

    @BeforeTest
    fun start() {
        TestSupport.initSodium()
        agent = TestAgentServer()
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        agent.close()
    }

    /** Pairs a new hub; the "owner" types [typed] (default: the code the phone shows). */
    private suspend fun pairHub(name: String, typed: ((String) -> String)? = null): Pair<KeyPair, PairOutcome> {
        val key = KeyPair.generate()
        val link = PairingLink.parse(agent.link())!!
        val outcome = pair(link, key, name, "test", connector) { code -> agent.ui.answer(typed?.invoke(code) ?: code) }
        return key to outcome
    }

    private fun link(key: KeyPair, paired: PairedAgent, c: Connector = connector) =
        AgentLink(paired, key, c, "test", scope).also { it.start() }

    private suspend fun AgentLink.awaitOnline() = withTimeout(20.seconds) { status.first { it is LinkStatus.Online } } as LinkStatus.Online

    private fun minutes(m: Int) = buildJsonObject { put("mode", "interval"); put("hours", 0); put("minutes", m) }

    @Test
    fun pairsAsAdminWithTheTypedCode(): Unit = runBlocking {
        val (key, outcome) = pairHub("Dmytro's phone")
        val paired = assertIs<PairOutcome.Paired>(outcome).agent
        assertEquals("admin", paired.role)
        assertEquals(agent.host.agentId, paired.agentId)
        assertEquals("Kids phone", paired.name)
        assertEquals("android", paired.platform)
        assertEquals(Ids.hubId(key.publicKey), agent.host.peerList.single().id)
        assertEquals("Dmytro's phone", agent.ui.expectAsked())
    }

    @Test
    fun wrongCodeIsDenied(): Unit = runBlocking {
        val (_, outcome) = pairHub("Phone") { code -> if (code == "000000") "000001" else "000000" }
        assertEquals(PairOutcome.Denied, outcome)
        assertTrue(agent.host.peerList.isEmpty())
    }

    @Test
    fun aQrThatIsNotOpenIsRejected(): Unit = runBlocking {
        val real = PairingLink.parse(agent.link())!!
        val fake = PairingLink(real.host, real.port, real.agentKey, ByteArray(32), "PC")
        assertEquals(PairOutcome.Rejected, pair(fake, KeyPair.generate(), "Phone", "test", connector) {})
    }

    @Test
    fun twoPhonesWithOneQrCancelThePairing(): Unit = runBlocking {
        val link = PairingLink.parse(agent.link())!!
        val child = async(Dispatchers.Default) { pair(link, KeyPair.generate(), "Child", "test", connector) {} }
        agent.ui.expectAsked() // the owner is asked about the first phone, and doesn't answer yet
        val parent = pair(link, KeyPair.generate(), "Parent", "test", connector) {}
        assertEquals(PairOutcome.CodeUsed, parent)
        assertEquals(PairOutcome.Denied, child.await())
        assertEquals(PairingAbortReason.TwoPhones, agent.ui.aborted.poll(10, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun sessionRequestsAndTheLocalCountdown(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val l = link(key, assertIs<PairOutcome.Paired>(outcome).agent)
        val hello = l.awaitOnline().hello
        val caps = (hello["capabilities"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("timer", "settings", "schedule", "history", "peers", "push"), caps)

        assertIs<RequestOutcome.Ok>(l.send("timer.start", minutes(30)))
        val left = l.state.value!!.remainingSecondsNow()
        assertTrue(left in 1790..1800, "remaining $left")
        assertIs<RequestOutcome.Ok>(l.send("timer.extend", buildJsonObject { put("minutes", 15) }))
        assertTrue(l.state.value!!.remainingSecondsNow() > 2600)
        assertIs<RequestOutcome.Ok>(l.send("timer.stop"))
        assertEquals(RequestOutcome.Refused("not_running"), l.send("timer.stop"))
        assertIs<RequestOutcome.Ok>(l.send("peers.list", changesState = false))
    }

    @Test
    fun anotherParentSeesTheChangeAndRevokeEndsTheLink(): Unit = runBlocking {
        val (k1, o1) = pairHub("A")
        val (k2, o2) = pairHub("B")
        assertEquals("parent", assertIs<PairOutcome.Paired>(o2).agent.role)
        val a = link(k1, assertIs<PairOutcome.Paired>(o1).agent)
        val b = link(k2, o2.agent)
        a.awaitOnline(); b.awaitOnline()

        // Subscribed before the request goes out: the in-process agent answers faster than a dispatch
        val seen = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(20.seconds) { b.events.filter { it["t"].let { t -> (t as JsonPrimitive).content } == "ev.state" }.first() } }
        assertIs<RequestOutcome.Ok>(a.send("timer.start", minutes(10)))
        seen.await()
        assertTrue(withTimeout(5.seconds) { b.state.first { it?.isRunning == true } }!!.isRunning)

        assertTrue(agent.host.revokeByOwner(Ids.hubId(k2.publicKey)))
        assertEquals(LinkStatus.Revoked, withTimeout(20.seconds) { b.status.first { it is LinkStatus.Revoked } })
    }

    /** The first /v2 socket swallows the reply to the request after [dropAfterSends] frames and closes. */
    private class DroppingConnector(private val inner: Connector, private val dropAfterSends: Int) : Connector {
        @Volatile var dropped = false
        @Volatile var connects = 0
        override suspend fun connect(host: String, port: Int, path: String): FrameSocket {
            val socket = inner.connect(host, port, path)
            if (path == "/v2") connects++
            if (dropped || path != "/v2") return socket
            return object : FrameSocket {
                var sends = 0
                @Volatile var swallowNext = false
                override suspend fun send(frame: ByteArray) {
                    socket.send(frame)
                    if (++sends == dropAfterSends) swallowNext = true
                }
                override suspend fun receive(): ByteArray? {
                    val f = socket.receive() ?: return null
                    if (!swallowNext) return f
                    dropped = true
                    socket.close()
                    return null
                }
                override suspend fun close() = socket.close()
            }
        }
    }

    @Test
    fun aLostReplyIsResentWithTheSameRidAndRunsOnce(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val dropping = DroppingConnector(connector, dropAfterSends = 3) // msg 1, ready, then timer.start
        val l = link(key, assertIs<PairOutcome.Paired>(outcome).agent, dropping)
        l.awaitOnline()
        // Executed twice it would answer already_running; with the same rid the stored reply comes back
        assertIs<RequestOutcome.Ok>(withTimeout(60.seconds) { l.send("timer.start", minutes(20)) })
        assertTrue(dropping.dropped && dropping.connects >= 2, "the reply must have been lost and resent on a new session")
        assertEquals(RequestOutcome.Refused("already_running"), l.send("timer.start", minutes(20)))
    }

    @Test
    fun concurrentRequestsKeepIdsInOrder(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val l = link(key, assertIs<PairOutcome.Paired>(outcome).agent)
        l.awaitOnline()
        val replies = (1..40).map { async(Dispatchers.Default) { l.send("state.get", changesState = false) } }.map { it.await() }
        assertTrue(replies.all { it is RequestOutcome.Ok }, replies.filterNot { it is RequestOutcome.Ok }.toString())
        assertIs<LinkStatus.Online>(l.status.value)
    }

    @Test
    fun aDifferentAgentKeyIsNeverAccepted(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val real = assertIs<PairOutcome.Paired>(outcome).agent
        val forged = real.copy(agentKey = com.gametimer.protocol.encodings.Base64Url.encode(KeyPair.generate().publicKey))
        val l = link(key, forged)
        assertEquals(LinkStatus.Offline(OfflineReason.Refused), withTimeout(20.seconds) { l.status.first { it is LinkStatus.Offline } })
    }

    private suspend fun api(name: String = "Phone"): Pair<AgentApi, AgentLink> {
        val (key, outcome) = pairHub(name)
        val l = link(key, assertIs<PairOutcome.Paired>(outcome).agent)
        l.awaitOnline()
        return AgentApi(l) to l
    }

    @Test
    fun settingsRoundTripAndAStaleEditIsAConflict(): Unit = runBlocking {
        val (a, _) = api("A")
        val (b, _) = api("B")
        val loaded = assertIs<ApiResult.Ok<AgentSettings>>(a.settings()).value
        val stale = assertIs<ApiResult.Ok<AgentSettings>>(b.settings()).value

        val saved = assertIs<ApiResult.Ok<AgentSettings>>(a.saveSettings(loaded.copy(action = "shutdown", redThreshold = 3))).value
        assertEquals("shutdown", saved.action)
        assertEquals(3, saved.redThreshold)
        assertTrue(saved.rev > loaded.rev)

        val conflict = assertIs<ApiResult.Conflict<AgentSettings>>(b.saveSettings(stale.copy(language = "nl")))
        assertEquals("shutdown", conflict.current.action)
        assertEquals("en", conflict.current.language)

        val invalid = assertIs<ApiResult.Failed>(a.saveSettings(conflict.current.copy(colorRed = "red")))
        assertEquals(RequestOutcome.Refused("invalid_color"), invalid.outcome)
    }

    @Test
    fun scheduleRoundTrip(): Unit = runBlocking {
        val (a, _) = api()
        val loaded = assertIs<ApiResult.Ok<AgentSchedule>>(a.schedule()).value
        assertEquals(7, loaded.week.size)
        val edited = loaded.copy(enabled = true, week = loaded.week.mapIndexed { i, d -> if (i == 0) d.copy(allowed = false) else d.copy(limitMinutes = 90) })
        val saved = assertIs<ApiResult.Ok<AgentSchedule>>(a.saveSchedule(edited)).value
        assertTrue(saved.enabled)
        assertEquals(false, saved.week[0].allowed)
        assertEquals(90, saved.week[3].limitMinutes)
    }

    @Test
    fun historyPagesNewestFirst(): Unit = runBlocking {
        val (a, _) = api()
        assertIs<RequestOutcome.Ok>(a.startInterval(0, 30))
        assertIs<RequestOutcome.Ok>(a.extend(15))
        assertIs<RequestOutcome.Ok>(a.stop())
        val first = assertIs<ApiResult.Ok<HistoryPage>>(a.history(limit = 2)).value
        assertEquals(listOf("stopped", "extended"), first.events.map { it.type })
        assertTrue(first.more)
        val second = assertIs<ApiResult.Ok<HistoryPage>>(a.history(limit = 2, before = first.next)).value
        assertEquals(listOf("started"), second.events.map { it.type })
        assertEquals(false, second.more)
        assertEquals("Phone", second.events[0].device)
    }

    @Test
    fun rolesAndRevokeThroughTheApi(): Unit = runBlocking {
        val (admin, _) = api("Admin")
        val (parent, parentLink) = api("Parent")
        val peers = assertIs<ApiResult.Ok<List<PeerInfo>>>(parent.peers()).value
        val me = peers.single { it.current }
        val other = peers.single { !it.current }
        assertEquals("parent", me.role)

        assertEquals(RequestOutcome.Refused("forbidden"), assertIs<ApiResult.Failed>(parent.revoke(other.id)).outcome)
        assertIs<ApiResult.Ok<List<PeerInfo>>>(admin.setRole(me.id, admin = true))
        assertEquals("admin", assertIs<ApiResult.Ok<List<PeerInfo>>>(parent.peers()).value.single { it.current }.role)

        // This phone disconnects itself: result, then the link ends as Revoked
        assertIs<ApiResult.Ok<List<PeerInfo>>>(parent.revoke(me.id))
        withTimeout(20.seconds) { parentLink.status.first { it is LinkStatus.Revoked } }
    }
}
