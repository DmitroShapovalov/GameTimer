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
}
