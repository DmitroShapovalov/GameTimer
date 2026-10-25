package com.gametimer.protocol.hub

import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.concurrent.Volatile
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

/**
 * The Kotlin hub client against the production C# AgentHost over real WebSockets (Ktor/OkHttp ↔
 * Kestrel): pairing with the typed code, sessions, requests, events, revoke, the two-phones rule.
 * The agent is tests/interop/TestAgent; Gradle passes -PcsharpAgent=<TestAgent.dll> (CI always does).
 */
class HubInteropTests {
    private lateinit var agent: Agent
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connector = KtorConnector()

    @BeforeTest
    fun start() {
        TestSupport.initSodium()
        val dll = System.getProperty("csharpAgent")
        if (dll == null) check(System.getenv("CI") != "true") { "CI run without -PcsharpAgent" }
        Assume.assumeTrue("csharpAgent not set: hub interop skipped", dll != null)
        check(File(dll!!).isFile) { "csharpAgent not found: $dll" }
        agent = Agent(dll)
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        if (::agent.isInitialized) agent.close()
    }

    /** The C# agent process, driven line by line. */
    private class Agent(dll: String) {
        private val process = ProcessBuilder("dotnet", dll).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val lines = LinkedBlockingQueue<String>()
        val port: Int

        init {
            Thread { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }.apply { isDaemon = true; start() }
            port = expect("PORT ").toInt()
        }

        fun send(line: String) {
            process.outputStream.write((line + "\n").toByteArray())
            process.outputStream.flush()
        }

        /** Waits for the next line starting with [prefix] (others are skipped) and returns the rest. */
        fun expect(prefix: String, seconds: Long = 20): String {
            val deadline = System.nanoTime() + seconds * 1_000_000_000
            while (true) {
                val left = deadline - System.nanoTime()
                check(left > 0) { "agent: no '$prefix' line" }
                val l = lines.poll(left, TimeUnit.NANOSECONDS) ?: continue
                if (l.startsWith(prefix)) return l.removePrefix(prefix)
            }
        }

        fun link(): PairingLink {
            send("pair")
            return PairingLink.parse(expect("LINK "))!!
        }

        fun close() {
            runCatching { send("quit") }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** Pairs a new hub; the "owner" types [typed] (default: the code the phone shows). */
    private suspend fun pairHub(name: String, typed: ((String) -> String)? = null): Pair<KeyPair, PairOutcome> {
        val key = KeyPair.generate()
        val link = agent.link()
        val outcome = pair(link, key, name, "test", connector) { code ->
            Thread {
                agent.expect("ASK ")
                agent.send("code " + (typed?.invoke(code) ?: code))
            }.start()
        }
        return key to outcome
    }

    private fun link(key: KeyPair, paired: PairedAgent) = AgentLink(paired, key, connector, "test", scope).also { it.start() }

    private suspend fun AgentLink.awaitOnline() = withTimeout(20.seconds) { status.first { it is LinkStatus.Online } } as LinkStatus.Online

    private fun minutes(m: Int) = buildJsonObject { put("mode", "interval"); put("hours", 0); put("minutes", m) }

    @Test
    fun pairsAsAdminWithTheTypedCode(): Unit = runBlocking {
        val (key, outcome) = pairHub("Dmytro's phone")
        val paired = assertIs<PairOutcome.Paired>(outcome).agent
        assertEquals("admin", paired.role)
        assertEquals(Ids.agentId(paired.agentKeyBytes()), paired.agentId)
        assertEquals(Ids.hubId(key.publicKey), agent.expect("PAIRED "))
    }

    @Test
    fun wrongCodeIsDenied(): Unit = runBlocking {
        val (_, outcome) = pairHub("Phone") { code -> if (code == "000000") "000001" else "000000" }
        assertEquals(PairOutcome.Denied, outcome)
    }

    @Test
    fun aQrThatIsNotOpenIsRejected(): Unit = runBlocking {
        val real = agent.link()
        val fake = PairingLink(real.host, real.port, real.agentKey, ByteArray(32), "PC")
        assertEquals(PairOutcome.Rejected, pair(fake, KeyPair.generate(), "Phone", "test", connector) {})
    }

    @Test
    fun twoPhonesWithOneQrCancelThePairing(): Unit = runBlocking {
        val link = agent.link()
        val child = async(Dispatchers.Default) { pair(link, KeyPair.generate(), "Child", "test", connector) {} }
        agent.expect("ASK ") // the owner is asked about the first phone, and doesn't answer yet
        val parent = pair(link, KeyPair.generate(), "Parent", "test", connector) {}
        assertEquals(PairOutcome.CodeUsed, parent)
        assertEquals(PairOutcome.Denied, child.await())
        agent.expect("ABORTED ")
    }

    @Test
    fun sessionRequestsAndTheLocalCountdown(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val l = link(key, assertIs<PairOutcome.Paired>(outcome).agent)
        val hello = l.awaitOnline().hello
        val caps = (hello["capabilities"] as JsonArray).map { (it as JsonPrimitive).content }
        assertTrue("timer" in caps && "peers" in caps)

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

        val seen = async { withTimeout(20.seconds) { b.events.filter { it.type() == "ev.state" }.first() } }
        assertIs<RequestOutcome.Ok>(a.send("timer.start", minutes(10)))
        seen.await()
        assertTrue(withTimeout(5.seconds) { b.state.first { it?.isRunning == true } }!!.isRunning)

        agent.send("revoke " + Ids.hubId(k2.publicKey))
        assertEquals(LinkStatus.Revoked, withTimeout(20.seconds) { b.status.first { it is LinkStatus.Revoked } })
    }

    /**
     * The first /v2 socket delivers [dropAfterSends] frames to the agent, then swallows the next frame it
     * receives (the reply) and closes: the request ran on the PC, the phone never learned it.
     */
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
                    while (true) {
                        val f = socket.receive() ?: return null
                        if (!swallowNext) return f
                        dropped = true
                        socket.close()
                        return null
                    }
                }
                override suspend fun close() = socket.close()
            }
        }
    }

    @Test
    fun aLostReplyIsResentWithTheSameRidAndRunsOnce(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val paired = assertIs<PairOutcome.Paired>(outcome).agent
        // msg 1, ready, then timer.start: the agent starts the timer, but the reply never arrives
        val dropping = DroppingConnector(connector, dropAfterSends = 3)
        val l = AgentLink(paired, key, dropping, "test", scope).also { it.start() }
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
        val status = withTimeout(20.seconds) { l.status.first { it is LinkStatus.Offline } }
        assertEquals(LinkStatus.Offline(OfflineReason.Refused), status) // the real agent can't answer for another key
    }

    @Test
    fun theCountdownRunsLocallyAndSendAfterRevokeReturnsAtOnce(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val l = link(key, assertIs<PairOutcome.Paired>(outcome).agent)
        l.awaitOnline()
        assertIs<RequestOutcome.Ok>(l.send("timer.start", minutes(5)))
        val first = l.state.value!!.remainingSecondsNow()
        kotlinx.coroutines.delay(2100)
        assertTrue(l.state.value!!.remainingSecondsNow() <= first - 2)

        agent.send("revoke " + Ids.hubId(key.publicKey))
        withTimeout(20.seconds) { l.status.first { it is LinkStatus.Revoked } }
        val t0 = System.nanoTime()
        assertEquals(RequestOutcome.Revoked, l.send("timer.stop"))
        assertTrue(System.nanoTime() - t0 < 1_000_000_000)
    }

    // ---------------- §7.2 typed API (A5) ----------------

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

        // B edited the old version: it gets the current value back instead of overwriting A's change
        val conflict = assertIs<ApiResult.Conflict<AgentSettings>>(b.saveSettings(stale.copy(language = "nl")))
        assertEquals("shutdown", conflict.current.action)
        assertEquals("en", conflict.current.language)

        // A validation error comes back as its §7.2 code
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

    /** A port on this machine where nothing listens (connect fails at once). */
    private fun deadPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test
    fun aMovedAgentIsFoundByItsTagAndTheNewAddressIsAdopted(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val real = assertIs<PairOutcome.Paired>(outcome).agent
        val stale = real.copy(host = "127.0.0.1", port = deadPort())
        val asked = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val moved = java.util.concurrent.CopyOnWriteArrayList<PairedAgent>()
        val today = com.gametimer.protocol.identity.DiscoveryTag.derive(
            com.gametimer.protocol.encodings.Base64Url.tryDecode(real.ak)!!,
            com.gametimer.protocol.identity.DiscoveryTag.utcDate(System.currentTimeMillis()))
        val locator = Locator { tags, _ ->
            asked += tags
            if (today in tags) listOf(AgentAddress("127.0.0.1", agent.port)) else emptyList()
        }
        val l = AgentLink(stale, key, connector, "test", scope, locator) { moved += it }.also { it.allowLoopbackCandidates = true; it.start() }
        l.awaitOnline()
        assertEquals(3, asked.single().size) // yesterday, today, tomorrow
        assertEquals(agent.port, moved.single().port)
        assertEquals(agent.port, l.current.port)
        assertEquals(real.agentKey, l.current.agentKey)
    }

    @Test
    fun aFoundAddressThatFailsTheHandshakeIsNotAdopted(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val real = assertIs<PairOutcome.Paired>(outcome).agent
        // Pinned to another key: the real agent at the "found" address can't prove it is that PC
        val forged = real.copy(host = "127.0.0.1", port = deadPort(),
            agentKey = com.gametimer.protocol.encodings.Base64Url.encode(KeyPair.generate().publicKey))
        val tried = kotlinx.coroutines.CompletableDeferred<Unit>()
        val recording = Connector { host, port, path ->
            try { connector.connect(host, port, path) } finally { if (port == agent.port) tried.complete(Unit) }
        }
        val moved = java.util.concurrent.CopyOnWriteArrayList<PairedAgent>()
        val locator = Locator { _, _ -> listOf(AgentAddress("127.0.0.1", agent.port)) }
        val l = AgentLink(forged, key, recording, "test", scope, locator) { moved += it }.also { it.allowLoopbackCandidates = true; it.start() }
        withTimeout(20.seconds) { tried.await() }
        withTimeout(20.seconds) { l.status.first { it is LinkStatus.Offline } }
        kotlinx.coroutines.delay(500)
        assertTrue(moved.isEmpty())
        assertEquals(forged.port, l.current.port)
        // The failed candidate doesn't replace the stored address's reason (no fake "wrong device" in the UI)
        assertEquals(LinkStatus.Offline(OfflineReason.Unreachable), l.status.value)
    }

    @Test
    fun loopbackCandidatesAreNeverDialled(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val real = assertIs<PairOutcome.Paired>(outcome).agent
        val stale = real.copy(host = "127.0.0.1", port = deadPort())
        val located = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hosts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recording = Connector { host, port, path -> hosts += "$host:$port"; connector.connect(host, port, path) }
        val l = AgentLink(stale, key, recording, "test", scope, { _, _ ->
            located.complete(Unit); listOf(AgentAddress("127.0.0.1", agent.port))
        }).also { it.start() }
        withTimeout(20.seconds) { located.await() }
        kotlinx.coroutines.delay(1500)
        assertTrue(hosts.none { it.endsWith(":${agent.port}") }, hosts.toString()) // the found 127.0.0.1 was never tried
        assertEquals(stale.port, l.current.port)
    }

    @Test
    fun aNonLanAddressFromTheLocatorIsIgnored(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val real = assertIs<PairOutcome.Paired>(outcome).agent
        val stale = real.copy(host = "127.0.0.1", port = deadPort())
        val located = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hosts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recording = Connector { host, port, path -> hosts += host; connector.connect(host, port, path) }
        val l = AgentLink(stale, key, recording, "test", scope, { _, _ ->
            located.complete(Unit)
            listOf(AgentAddress("8.8.8.8", 5123), AgentAddress("example.com", 5123))
        }).also { it.allowLoopbackCandidates = true; it.start() }
        withTimeout(20.seconds) { located.await() }
        kotlinx.coroutines.delay(1500) // the next attempt (after the back-off) still goes to the stored address
        assertTrue(hosts.size >= 2)
        assertTrue(hosts.all { it == "127.0.0.1" }, hosts.toString())
        assertEquals("127.0.0.1", l.current.host)
    }

    @Test
    fun aFakeAnnouncementFirstDoesNotHideTheRealAgent(): Unit = runBlocking {
        val (key, outcome) = pairHub("Phone")
        val real = assertIs<PairOutcome.Paired>(outcome).agent
        val stale = real.copy(host = "127.0.0.1", port = deadPort())
        val fake = deadPort() // a copied tag announced by someone else; nothing answers there
        val moved = java.util.concurrent.CopyOnWriteArrayList<PairedAgent>()
        val locator = Locator { _, _ -> listOf(AgentAddress("127.0.0.1", fake), AgentAddress("127.0.0.1", agent.port)) }
        val l = AgentLink(stale, key, connector, "test", scope, locator) { moved += it }.also { it.allowLoopbackCandidates = true; it.start() }
        l.awaitOnline() // well before the next search (20 s): both candidates were tried in one go
        assertEquals(agent.port, moved.single().port)
    }

    @Test
    fun liftEndNowAndEndAt(): Unit = runBlocking {
        val (a, l) = api()
        assertTrue(AgentApi.supportsTimerControls(l.awaitOnline().hello)) // minor 1 on both sides

        // End at: the running timer now ends at a chosen time (here: about 90 minutes from now)
        assertIs<RequestOutcome.Ok>(a.startInterval(0, 30))
        val at = java.time.LocalTime.now().plusMinutes(90)
        assertIs<RequestOutcome.Ok>(a.setEnd(at.hour, at.minute))
        val left = l.state.value!!.remainingSecondsNow()
        assertTrue(left in (89 * 60 - 60)..(90 * 60), "remaining $left")

        // End now: the time is up at once
        assertIs<RequestOutcome.Ok>(a.endNow())
        assertEquals("expired", l.state.value!!.timer?.get("state")?.let { (it as JsonPrimitive).content })
        assertEquals(RequestOutcome.Refused("not_running"), a.endNow())
        assertEquals(RequestOutcome.Refused("not_running"), a.setEnd(12, 0))

        // Lift: without a schedule there is nothing to lift once the timer is off
        assertEquals(RequestOutcome.Refused("not_running"), a.lift(skipScheduleToday = true))
        // With a schedule: stop the timer and set the schedule aside for today
        val s = assertIs<ApiResult.Ok<AgentSchedule>>(a.schedule()).value
        assertIs<ApiResult.Ok<AgentSchedule>>(a.saveSchedule(s.copy(enabled = true)))
        assertIs<RequestOutcome.Ok>(a.startInterval(0, 20))
        assertIs<RequestOutcome.Ok>(a.lift(skipScheduleToday = true))
        val st = l.state.value!!
        assertTrue(!st.isRunning)
        assertEquals("skippedToday", ((st.json["schedule"] as kotlinx.serialization.json.JsonObject)["status"] as JsonPrimitive).content)
        assertIs<RequestOutcome.Ok>(a.lift(skipScheduleToday = true)) // a repeated lift is fine
        fun scheduleStatus() = ((l.state.value!!.json["schedule"] as kotlinx.serialization.json.JsonObject)["status"] as JsonPrimitive).content
        // A timer started during the lift keeps it while it runs; when it runs out, the schedule is back
        assertIs<RequestOutcome.Ok>(a.startInterval(0, 10))
        assertEquals("skippedToday", scheduleStatus())
        assertIs<RequestOutcome.Ok>(a.endNow())
        assertEquals("allowed", scheduleStatus())
        // "End at" a time that just passed means "earlier", not tomorrow: refused
        assertIs<RequestOutcome.Ok>(a.startInterval(0, 30))
        val past = java.time.LocalTime.now().minusMinutes(5)
        assertEquals(RequestOutcome.Refused("invalid_time"), a.setEnd(past.hour, past.minute))
    }
}
