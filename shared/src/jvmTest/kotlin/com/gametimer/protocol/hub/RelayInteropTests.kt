package com.gametimer.protocol.hub

import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assume

/**
 * The Kotlin hub through the real relay (protocol §10): the C# TestAgent with its RelayClient, the
 * GameTimer.Relay process, and AgentLink's LAN-first / relay race. Gradle passes -PcsharpAgent and
 * -PcsharpRelay (CI always does).
 */
class RelayInteropTests {
    private lateinit var agent: Proc
    private lateinit var relay: Process
    private var relayUrl = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connector = KtorConnector()

    @BeforeTest
    fun start() {
        TestSupport.initSodium()
        val agentDll = System.getProperty("csharpAgent")
        val relayDll = System.getProperty("csharpRelay")
        if (agentDll == null || relayDll == null) check(System.getenv("CI") != "true") { "CI run without -PcsharpAgent / -PcsharpRelay" }
        Assume.assumeTrue("csharpAgent / csharpRelay not set: relay interop skipped", agentDll != null && relayDll != null)
        check(File(relayDll!!).isFile) { "csharpRelay not found: $relayDll" }
        val port = java.net.ServerSocket(0).use { it.localPort }
        relayUrl = "ws://127.0.0.1:$port"
        relay = ProcessBuilder("dotnet", relayDll)
            .apply { environment()["ASPNETCORE_URLS"] = "http://127.0.0.1:$port" }
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val deadline = System.nanoTime() + 30_000_000_000
        while (true) {
            val ok = runCatching {
                (URI("http://127.0.0.1:$port/healthz").toURL().openConnection() as HttpURLConnection).run {
                    connectTimeout = 500; readTimeout = 500; responseCode == 200
                }
            }.getOrDefault(false)
            if (ok) break
            check(System.nanoTime() < deadline) { "the relay didn't start" }
            Thread.sleep(200)
        }
        agent = Proc(agentDll!!)
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        if (::agent.isInitialized) agent.close()
        if (::relay.isInitialized) { relay.destroy(); relay.waitFor(5, TimeUnit.SECONDS) }
    }

    /** The C# agent process, driven line by line (as in HubInteropTests). */
    private class Proc(dll: String) {
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

        fun expect(prefix: String, seconds: Long = 20): String {
            val deadline = System.nanoTime() + seconds * 1_000_000_000
            while (true) {
                val left = deadline - System.nanoTime()
                check(left > 0) { "agent: no '$prefix' line" }
                val l = lines.poll(left, TimeUnit.NANOSECONDS) ?: continue
                if (l.startsWith(prefix)) return l.removePrefix(prefix)
            }
        }

        fun close() {
            runCatching { send("quit") }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private suspend fun pairHub(): Pair<KeyPair, PairedAgent> {
        val key = KeyPair.generate()
        agent.send("pair")
        val link = PairingLink.parse(agent.expect("LINK "))!!
        val outcome = pair(link, key, "Phone", "test", connector) { code ->
            Thread { agent.expect("ASK "); agent.send("code $code") }.start()
        }
        return key to assertIs<PairOutcome.Paired>(outcome).agent
    }

    private fun relayOn() {
        agent.send("relay $relayUrl")
        agent.expect("RELAY Connected")
    }

    private fun deadPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private suspend fun AgentLink.awaitOnline(): LinkStatus.Online =
        withTimeout(20.seconds) { status.first { it is LinkStatus.Online } } as LinkStatus.Online

    @Test
    fun awayFromHomeTheRelayCarriesTheSession(): Unit = runBlocking {
        val (key, paired) = pairHub()
        relayOn()
        val learned = CopyOnWriteArrayList<Boolean>()
        // At home first: hello says remoteAccess is on, the link remembers it
        val home = AgentLink(paired, key, connector, "test", scope, relay = RelayPath(relayUrl, connector),
            onRemoteAccess = { learned += it }).also { it.start() }
        val online = home.awaitOnline()
        assertFalse(online.viaRelay)
        assertEquals(true, (online.hello["remoteAccess"] as JsonPrimitive).booleanOrNull)
        assertEquals(listOf(true), learned)
        home.stop()

        // Away: the stored LAN address doesn't answer; off Wi-Fi the relay is dialled at once
        val away = AgentLink(home.current.copy(host = "127.0.0.1", port = deadPort()), key, connector, "test", scope,
            relay = RelayPath(relayUrl, connector, onLocalNetwork = { false })).also { it.start() }
        val o = away.awaitOnline()
        assertTrue(o.viaRelay)
        val api = AgentApi(away)
        assertIs<RequestOutcome.Ok>(api.startInterval(0, 15))
        assertTrue(away.state.value!!.isRunning)
        away.stop()
    }

    @Test
    fun onWifiTheLanWinsAndTheRelayWaits(): Unit = runBlocking {
        val (key, paired) = pairHub()
        relayOn()
        val dialled = CopyOnWriteArrayList<String>()
        val counting = RelayConnector { url -> dialled += url; connector.connect(url) }
        val l = AgentLink(paired.copy(remoteAccess = true), key, connector, "test", scope,
            relay = RelayPath(relayUrl, counting, onLocalNetwork = { true })).also { it.start() }
        assertFalse(l.awaitOnline().viaRelay)
        assertTrue(dialled.isEmpty()) // the LAN answered within 2 s: the relay was never dialled
        l.stop()

        // On Wi-Fi but the LAN address is dead: the relay takes over as soon as the LAN failed
        val away = AgentLink(paired.copy(remoteAccess = true, host = "127.0.0.1", port = deadPort()), key, connector, "test", scope,
            relay = RelayPath(relayUrl, counting, onLocalNetwork = { true })).also { it.start() }
        assertTrue(away.awaitOnline().viaRelay)
        away.stop()
    }

    @Test
    fun turningItOffOnThePcEndsTheRelayedSessionAndStopsDialling(): Unit = runBlocking {
        val (key, paired) = pairHub()
        relayOn()
        val learned = CopyOnWriteArrayList<Boolean>()
        val dialled = CopyOnWriteArrayList<String>()
        val counting = RelayConnector { url -> dialled += url; connector.connect(url) }
        val l = AgentLink(paired.copy(remoteAccess = true, host = "127.0.0.1", port = deadPort()), key, connector, "test", scope,
            relay = RelayPath(relayUrl, counting, onLocalNetwork = { false }), onRemoteAccess = { learned += it }).also { it.start() }
        assertTrue(l.awaitOnline().viaRelay)
        agent.send("relayoff")
        agent.expect("OK")
        withTimeout(20.seconds) { l.status.first { it is LinkStatus.Offline } }
        assertEquals(listOf(false), learned) // bye remote_off
        assertFalse(l.current.remoteAccess)
        val before = dialled.size
        kotlinx.coroutines.delay(6_000) // several LAN reconnect attempts: none goes to the relay now
        assertEquals(before, dialled.size)
        l.stop()
    }

    @Test
    fun theRelayIsNotDialledForAPcWithoutRemoteAccess(): Unit = runBlocking {
        val (key, paired) = pairHub()
        relayOn()
        val dialled = CopyOnWriteArrayList<String>()
        val counting = RelayConnector { url -> dialled += url; connector.connect(url) }
        val l = AgentLink(paired.copy(host = "127.0.0.1", port = deadPort()), key, connector, "test", scope,
            relay = RelayPath(relayUrl, counting, onLocalNetwork = { false })).also { it.start() }
        withTimeout(20.seconds) { l.status.first { it is LinkStatus.Offline } }
        assertTrue(dialled.isEmpty()) // remoteAccess unknown (false) for this PC: LAN only (§10.3)
        l.stop()
    }

    @Test
    fun leavingTheScreenEndsTheRelayedSessionAndComingBackDialsAtOnce(): Unit = runBlocking {
        val (key, paired) = pairHub()
        relayOn()
        val allowedFlag = java.util.concurrent.atomic.AtomicBoolean(true)
        val dialled = CopyOnWriteArrayList<String>()
        val counting = RelayConnector { url -> dialled += url; connector.connect(url) }
        val l = AgentLink(paired.copy(remoteAccess = true, host = "127.0.0.1", port = deadPort()), key, connector, "test", scope,
            relay = RelayPath(relayUrl, counting, onLocalNetwork = { false }, allowed = { allowedFlag.get() })).also { it.start() }
        assertTrue(l.awaitOnline().viaRelay)
        allowedFlag.set(false)
        l.relayPolicyChanged()
        withTimeout(10.seconds) { l.status.first { it is LinkStatus.Offline } } // closed; the LAN is dead
        val before = dialled.size
        kotlinx.coroutines.delay(4_000)
        assertEquals(before, dialled.size) // not allowed: no relay
        assertFalse(l.status.value is LinkStatus.Stopped) // the link itself keeps running
        allowedFlag.set(true)
        val started = System.nanoTime()
        l.relayPolicyChanged()
        assertTrue(l.awaitOnline().viaRelay)
        assertTrue(System.nanoTime() - started < 8_000_000_000) // at once, not after the 30 s back-off
        l.stop()
    }

    @Test
    fun aRouteNobodyHoldsIsReportedAsNotFound(): Unit = runBlocking {
        // The real relay's 404 through Ktor/OkHttp becomes RelayRouteNotFound (only that moves to the next route)
        assertFailsWith<RelayRouteNotFound> { connector.connect("$relayUrl/v2/hub/AAAAAAAAAAAAAAAAAAAAAAAAAA") }
    }
}
