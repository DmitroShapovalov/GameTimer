@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.PairingCode
import com.gametimer.protocol.noise.CipherState
import com.gametimer.protocol.noise.HandshakeState
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.NoisePattern
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlin.concurrent.Volatile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** An in-memory WebSocket: two ends, binary frames, close from either side. Mirrors the C# MemoryTransport. */
internal class MemoryTransport private constructor(
    private val inbox: Channel<ByteArray?>,
    private val outbox: Channel<ByteArray?>,
) : FrameTransport {
    @Volatile var closed: Boolean = false
        private set
    lateinit var peer: MemoryTransport
        private set

    companion object {
        /** (agent end, hub end) */
        fun pair(): Pair<MemoryTransport, MemoryTransport> {
            val a = Channel<ByteArray?>(Channel.UNLIMITED)
            val b = Channel<ByteArray?>(Channel.UNLIMITED)
            val agent = MemoryTransport(a, b)
            val hub = MemoryTransport(b, a)
            agent.peer = hub
            hub.peer = agent
            return agent to hub
        }

        private val TEXT_FRAME = ByteArray(1)
    }

    /** Next frame from the other end, or null once closed (this end, or the peer after its frames). */
    override suspend fun receive(): ByteArray? {
        if (closed) return null
        val frame = inbox.receiveCatching().getOrNull() ?: return null
        if (frame === TEXT_FRAME) throw ProtocolViolation("text message")
        return frame
    }

    override suspend fun send(frame: ByteArray) {
        if (closed) throw IllegalStateException("closed")
        outbox.trySend(frame) // dropped if the peer has closed, like a socket
    }

    /** Simulates a text WebSocket message arriving at the other end. */
    suspend fun sendText() = send(TEXT_FRAME)

    override suspend fun close() {
        if (closed) return
        closed = true
        // like a WebSocket close: the peer reads what was sent, then null; its own sends are dropped
        outbox.trySend(null)
        outbox.close()
        inbox.close()
    }
}

internal class FakePairingUi : PairingUi {
    /** Answers the code prompt; default: cancel. */
    var answer: suspend (String) -> String? = { null }
    val asked = mutableListOf<String>()
    val aborts = mutableListOf<PairingAbortReason>()

    override suspend fun askForCode(hubName: String): String? {
        asked.add(hubName)
        return answer(hubName)
    }

    override fun pairingAborted(reason: PairingAbortReason) {
        aborts.add(reason)
    }
}

internal class FakeBackend(private val scope: CoroutineScope) : AgentBackend {
    lateinit var host: AgentHost
    override val info = AgentInfo("Kids phone", "android", "2.0.0-test")
    override var capabilities: Collection<String> = listOf("timer", "settings")
    var calls = 0
    var extends = 0

    /** Runs inside state() (as a backend that hops to its main thread would). */
    var beforeState: (() -> Unit)? = null

    override suspend fun state(): JsonObject {
        kotlinx.coroutines.yield()
        beforeState?.invoke()
        return buildJsonObject { put("timer", buildJsonObject { put("state", "notStarted"); put("extends", extends) }) }
    }

    override suspend fun handle(type: String, message: JsonObject, context: RequestContext): Reply {
        calls++
        return when (type) {
            "state.get" -> Reply.ok(buildJsonObject { put("state", state()) })
            "timer.extend" -> {
                val m = (message["minutes"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (m == null || m == 0) return Reply.error("invalid_minutes")
                extends++
                // like a real backend: the state change is published during the request
                host.publish("ev.state", buildJsonObject { put("state", buildJsonObject { put("extends", extends) }) }, context.origin)
                Reply.ok(buildJsonObject { put("state", buildJsonObject { put("extends", extends) }) })
            }
            "timer.stop" -> Reply.error("not_running")
            "timer.start" -> {
                // Like a real countdown: work started by the request keeps publishing after it ended
                scope.launch {
                    delay(100)
                    host.publish("ev.tick", buildJsonObject { put("n", 1) })
                    delay(100)
                    host.publish("ev.tick", buildJsonObject { put("n", 2) })
                    // a late effect still attributed to the request: the requester already has its result
                    host.publish("ev.state", buildJsonObject { put("late", true) }, context.origin)
                }
                Reply.ok(buildJsonObject { put("state", JsonObject(emptyMap())) })
            }
            "settings.get" -> Reply.ok(buildJsonObject { put("settings", buildJsonObject { put("rev", 1) }) })
            else -> Reply.error("unknown_command")
        }
    }
}

internal fun JsonObject.t(): String? = (this["t"] as? JsonPrimitive)?.content
internal fun JsonObject.s(name: String): String? = (this[name] as? JsonPrimitive)?.content
internal fun JsonObject.obj(name: String): JsonObject = this[name]!!.jsonObject
internal val JsonElement.i: Int get() = jsonPrimitive.int

internal fun newRid(): String = Base64Url.encode(LibsodiumRandom.buf(16).asByteArray())

private val json = Json

/** A scripted hub (the parent app's side) over a [MemoryTransport]. */
internal class TestHub(
    val key: KeyPair,
    val end: MemoryTransport,
    val run: Job,
    val msg1: ByteArray,
) {
    var send: CipherState? = null
    var recv: CipherState? = null
    var handshakeHash: ByteArray? = null
    var firstTransport: ByteArray? = null
    private var nextId = 0L

    val id: String get() = Ids.hubId(key.publicKey)

    suspend fun sendJson(message: JsonObject) {
        val frame = send!!.encrypt(json.encodeToString(JsonObject.serializer(), message).encodeToByteArray())
        if (firstTransport == null) firstTransport = frame
        end.send(frame)
    }

    suspend fun sendRaw(plaintext: ByteArray) = end.send(send!!.encrypt(plaintext))

    /** The next message, or null if the agent closed. */
    suspend fun tryReceiveJson(timeout: Duration = 10.seconds): JsonObject? {
        val frame = withTimeout(timeout) { end.receive() } ?: return null
        return json.parseToJsonElement(recv!!.decrypt(frame).decodeToString()).jsonObject
    }

    suspend fun receiveJson(): JsonObject = tryReceiveJson() ?: throw IllegalStateException("agent closed")

    /** Sends a request with the next id (and a rid if asked) and waits for its result / error, skipping events. */
    suspend fun request(type: String, fields: JsonObject? = null, rid: String? = null, withRid: Boolean = false): JsonObject {
        val id = ++nextId
        val m = LinkedHashMap<String, JsonElement>()
        m["t"] = JsonPrimitive(type)
        m["id"] = JsonPrimitive(id)
        if (rid != null || withRid) m["rid"] = JsonPrimitive(rid ?: newRid())
        fields?.forEach { (k, v) -> m[k] = v }
        sendJson(JsonObject(m))
        while (true) {
            val r = receiveJson()
            val t = r.t()
            if ((t == "result" || t == "error") && r["re"]!!.jsonPrimitive.long == id) return r
            if (t == "bye") throw ByeException(r.s("code"))
        }
    }

    class ByeException(val code: String?) : Exception("bye $code")

    companion object {
        val PROLOGUE = "GameTimer/2".encodeToByteArray()

        /** Opens /v2 or /v2/pair and runs msg 1 / msg 2. The Boolean is false if the agent closed silently. */
        suspend fun handshake(
            host: AgentHost, key: KeyPair, pairing: Boolean, psk: ByteArray? = null, ip: String = "192.168.1.20",
            replayMsg1: ByteArray? = null, firstType: String = "connect", scope: CoroutineScope,
        ): Pair<TestHub, Boolean> {
            val (agentEnd, hubEnd) = MemoryTransport.pair()
            val ticket = host.tryAdmit(ip) ?: throw IllegalStateException("not admitted")
            val run = scope.launch { host.run(agentEnd, ticket, pairing) }
            val hs = if (replayMsg1 == null) {
                HandshakeState(if (pairing) NoisePattern.IKpsk1 else NoisePattern.IK, true, PROLOGUE, key, host.agentPublicKey, psk)
            } else null
            val msg1 = replayMsg1 ?: hs!!.writeMessage("{\"t\":\"$firstType\"}".encodeToByteArray())
            hubEnd.send(msg1)
            val hub = TestHub(key, hubEnd, run, msg1)
            val msg2 = withTimeout(10.seconds) { hubEnd.receive() } ?: return hub to false
            if (replayMsg1 != null) return hub to true // the replayer can't read msg 2
            hs!!.readMessage(msg2)
            hub.handshakeHash = hs.handshakeHash()
            val (s, r) = hs.split()
            hub.send = s
            hub.recv = r
            return hub to true
        }

        /** A full /v2 session: handshake, ready, hello. */
        suspend fun connect(host: AgentHost, key: KeyPair, scope: CoroutineScope, ip: String = "192.168.1.20"): Pair<TestHub, JsonObject> {
            val (hub, ok) = handshake(host, key, pairing = false, ip = ip, scope = scope)
            assertTrue(ok, "agent refused the handshake")
            hub.sendJson(buildJsonObject { put("t", "ready"); put("protocol", 2); put("minor", 0); put("app", "test") })
            val hello = hub.receiveJson()
            assertEquals("hello", hello.t())
            return hub to hello
        }
    }
}

/** An agent with fakes, plus helpers to pair hubs. Lives inside one runTest (virtual time). */
internal class AgentFixture(val test: TestScope, val store: MemoryAgentStore = MemoryAgentStore()) {
    val backend = FakeBackend(test.backgroundScope)
    val ui = FakePairingUi()
    val host = AgentHost(
        store, backend, ui,
        clock = { BASE_TIME + test.testScheduler.currentTime },
        scope = test.backgroundScope,
        timeSource = test.testScheduler.timeSource,
    )

    init {
        backend.host = host
    }

    val scope: CoroutineScope get() = test.backgroundScope

    suspend fun connect(key: KeyPair, ip: String = "192.168.1.20") = TestHub.connect(host, key, scope, ip)

    suspend fun handshake(
        key: KeyPair, pairing: Boolean, psk: ByteArray? = null, ip: String = "192.168.1.20",
        replayMsg1: ByteArray? = null, firstType: String = if (pairing) "pair" else "connect",
    ) = TestHub.handshake(host, key, pairing, psk, ip, replayMsg1, firstType, scope)

    /** Pairs a new hub: the owner types the right code. Returns its key and the pair.result. */
    suspend fun pair(name: String = "Phone"): Pair<KeyPair, JsonObject> {
        val offer = host.startPairing("Kids phone")
        val key = KeyPair.generate()
        val (hub, ok) = handshake(key, pairing = true, psk = offer.secret)
        assertTrue(ok)
        val code = PairingCode.derive(hub.handshakeHash!!)
        ui.answer = { code }
        hub.sendJson(pairHello(name))
        val result = hub.receiveJson()
        assertEquals("pair.result", result.t())
        host.cancelPairing()
        return key to result
    }

    companion object {
        /** 2026-10-24T00:00:00Z */
        const val BASE_TIME = 1_792_800_000_000L

        fun pairHello(name: String = "Phone") = buildJsonObject {
            put("t", "pair.hello"); put("protocol", 2); put("minor", 0); put("name", name); put("app", "test")
        }
    }
}

/** Runs one agent test in virtual time; the host is closed at the end. */
internal fun agentTest(body: suspend TestScope.(AgentFixture) -> Unit) = runTest {
    val f = AgentFixture(this)
    try {
        body(f)
    } finally {
        f.host.close()
    }
}

/** Waits (virtual time) until [condition] holds, polling. */
internal suspend fun eventually(condition: () -> Boolean) {
    withTimeoutOrNull(10.seconds) { while (!condition()) delay(20.milliseconds) }
    assertTrue(condition(), "condition not reached")
}
