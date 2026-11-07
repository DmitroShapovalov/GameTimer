package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.KtorConnector
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.PairOutcome
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.hub.PairingLink
import com.gametimer.protocol.hub.RelayPath
import com.gametimer.protocol.hub.RelayRouteNotFound
import com.gametimer.protocol.hub.RequestOutcome
import com.gametimer.protocol.hub.pair
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.RouteToken
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * The Kotlin agent's RelayClient against the real relay (GameTimer.Relay, C#): it registers its routes, the
 * Kotlin hub reaches it away from home through its route, revoking withdraws the route, stopping ends relayed
 * sessions. Gradle passes -PcsharpRelay (CI always does).
 */
class RelayClientInteropTests {
    private lateinit var relay: Process
    private lateinit var agent: TestAgentServer
    private lateinit var client: RelayClient
    private var relayUrl = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connector = KtorConnector()

    @BeforeTest
    fun start() {
        val relayDll = System.getProperty("csharpRelay")
        if (relayDll == null) check(System.getenv("CI") != "true") { "CI run without -PcsharpRelay" }
        Assume.assumeTrue("csharpRelay not set: relay client interop skipped", relayDll != null)
        check(File(relayDll!!).isFile) { "csharpRelay not found: $relayDll" }
        TestSupport.initSodium()
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
        agent = TestAgentServer()
        agent.host.remoteAccess = true
        client = RelayClient(agent.host, "$relayUrl/v2/agent", scope = agent.scope)
    }

    @AfterTest
    fun stop() {
        if (::client.isInitialized) runBlocking { client.stop() }
        scope.cancel()
        if (::agent.isInitialized) agent.close()
        if (::relay.isInitialized) { relay.destroy(); relay.waitFor(5, TimeUnit.SECONDS) }
    }

    private suspend fun pairHub(name: String = "Phone"): Pair<KeyPair, PairedAgent> {
        val key = KeyPair.generate()
        val link = PairingLink.parse(agent.link())!!
        val outcome = pair(link, key, name, "test", connector) { code -> agent.ui.answer(code) }
        return key to assertIs<PairOutcome.Paired>(outcome).agent
    }

    private fun deadPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    /** The link of a parent away from home: the LAN address is dead, the relay is dialled at once. */
    private fun away(key: KeyPair, paired: PairedAgent) =
        AgentLink(paired.copy(remoteAccess = true, host = "127.0.0.1", port = deadPort()), key, connector, "test", scope,
            relay = RelayPath(relayUrl, connector, onLocalNetwork = { false })).also { it.start() }

    private suspend fun connected() = withTimeout(20.seconds) { client.status.first { it == RelayStatus.Connected } }

    /** Whether the relay knows the route now: true / false, null when it can't tell (e.g. 429, too many dials). */
    private suspend fun routeAnswers(ak: ByteArray): Boolean? {
        val route = RouteToken.derive(ak, agent.host.utcToday)
        return try {
            connector.connect("$relayUrl/v2/hub/$route").close()
            true
        } catch (e: RelayRouteNotFound) {
            false
        } catch (e: Exception) {
            null
        }
    }

    @Test
    fun awayFromHomeTheHubReachesTheKotlinAgentThroughTheRelay(): Unit = runBlocking {
        val (key, paired) = pairHub()
        client.start()
        connected()
        val l = away(key, paired)
        val online = withTimeout(30.seconds) { l.status.first { it is LinkStatus.Online } } as LinkStatus.Online
        assertTrue(online.viaRelay)
        val api = AgentApi(l)
        assertIs<RequestOutcome.Ok>(api.startInterval(0, 15))
        assertTrue(l.state.value!!.isRunning)
        assertEquals(1, agent.host.liveSessionCount)
        l.stop()
    }

    @Test
    fun revokingWithdrawsTheRouteAndAPairingAddsOne(): Unit = runBlocking {
        client.start()
        connected()
        val (k1, p1) = pairHub("A")
        val ak = Base64Url.tryDecode(p1.ak)!!
        withTimeout(30.seconds) { while (routeAnswers(ak) != true) delay(1000) } // registered after PeersChanged
        assertTrue(agent.host.revokeByOwner(Ids.hubId(k1.publicKey)))
        withTimeout(30.seconds) { while (routeAnswers(ak) != false) delay(1000) }
    }

    @Test
    fun turningRemoteAccessOffEndsTheRelayedSession(): Unit = runBlocking {
        val (key, paired) = pairHub()
        client.start()
        connected()
        val l = away(key, paired)
        assertTrue((withTimeout(30.seconds) { l.status.first { it is LinkStatus.Online } } as LinkStatus.Online).viaRelay)
        agent.host.remoteAccess = false
        agent.host.endRelaySessions()
        client.stop()
        assertEquals(RelayStatus.Off, client.status.value)
        withTimeout(20.seconds) { l.status.first { it is LinkStatus.Offline } }
        assertEquals(false, l.current.remoteAccess) // bye remote_off: the hub stops dialling the relay
        l.stop()
    }
}
