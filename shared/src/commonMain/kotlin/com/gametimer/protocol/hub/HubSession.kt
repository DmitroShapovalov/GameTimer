package com.gametimer.protocol.hub

import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.noise.CipherState
import com.gametimer.protocol.noise.HandshakeState
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.NoiseException
import com.gametimer.protocol.noise.NoisePattern
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a session ended: the agent's `bye` code (§3), or null for a dropped connection. */
public class SessionClosedException(public val byeCode: String?) : Exception("session closed" + (byeCode?.let { ": $it" } ?: ""))

/**
 * One `/v2` session (§4–§7): IK handshake against the pinned key, `ready`, `hello`, then requests and
 * events. Encrypted `ping` every 30 s keeps it alive (§3). Use [open]; when it ends, [closed] completes.
 */
public class HubSession private constructor(
    private val socket: FrameSocket,
    private val send: CipherState,
    private val recv: CipherState,
    /** The agent's `hello` (§6): agent info, your id and role, capabilities, ak, state. */
    public val hello: JsonObject,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendLock = Mutex()
    private val pendingLock = Mutex()
    private val pending = HashMap<Long, CompletableDeferred<JsonObject>>()
    private var nextId = 0L
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)

    /** Agent events (`ev.*`), in order. */
    public val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    /** Completes when the session ends, with the bye code (null = connection dropped). */
    public val closed: CompletableDeferred<String?> = CompletableDeferred()
    private var byeCode: String? = null

    private fun start() {
        scope.launch { readLoop() }
        scope.launch {
            while (isActive) {
                delay(30.seconds)
                try { sendLock.withLock { socket.sendJson(send, buildJsonObject { put("t", "ping") }) } } catch (e: Exception) { end(null) }
            }
        }
    }

    /**
     * Sends a request (§5) and waits for its `result` / `error`. [fields] are added to the envelope;
     * pass [rid] for state-changing requests (the same rid when resending after a reconnect).
     * Returns the reply object. Throws [SessionClosedException] if the session ends first.
     */
    public suspend fun request(type: String, fields: JsonObject? = null, rid: String? = null): JsonObject {
        val reply = CompletableDeferred<JsonObject>()
        val id = pendingLock.withLock {
            if (closed.isCompleted) throw SessionClosedException(byeCode)
            val id = ++nextId
            pending[id] = reply
            id
        }
        val message = buildJsonObject {
            put("t", type); put("id", id)
            if (rid != null) put("rid", rid)
            fields?.forEach { (k, v) -> if (k != "t" && k != "id" && k != "rid") put(k, v) }
        }
        try {
            sendLock.withLock { socket.sendJson(send, message) }
        } catch (e: Exception) {
            end(null)
        }
        return reply.await()
    }

    public suspend fun close() {
        end(null)
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val frame = socket.receive() ?: break
                val message = decodeJson(recv.decrypt(frame))
                when (val t = message.type()) {
                    "result", "error" -> {
                        val re = message.long("re") ?: continue
                        pendingLock.withLock { pending.remove(re) }?.complete(message)
                    }
                    "bye" -> { end(message.string("code")); return }
                    "pong" -> Unit
                    else -> if (t != null && t.startsWith("ev.")) _events.emit(message)
                }
            }
        } catch (e: Exception) {
            // decryption failure, bad JSON or a dropped socket all end the session
        }
        end(null)
    }

    private suspend fun end(code: String?) {
        pendingLock.withLock { if (!closed.isCompleted) byeCode = code }
        if (!closed.complete(code)) return
        val waiting = pendingLock.withLock { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.completeExceptionally(SessionClosedException(code)) }
        socket.close()
        scope.cancel()
    }

    public companion object {
        /**
         * Connects to [agent] at [host]:[port] (default: the last known address), runs the handshake and
         * `ready`, and returns the session once `hello` arrived. Throws [SessionClosedException] if the
         * agent refused (not paired / revoked: it closes without msg 2) and [HubProtocolException] if it
         * answered with the wrong key or id.
         */
        public suspend fun open(
            connector: Connector,
            agent: PairedAgent,
            hubKey: KeyPair,
            appName: String,
            host: String = agent.host,
            port: Int = agent.port,
        ): HubSession {
            val agentKey = agent.agentKeyBytes()
            val socket = connector.connect(host, port, "/v2")
            try {
                val hs = HandshakeState(NoisePattern.IK, true, PROLOGUE, hubKey, agentKey)
                socket.send(hs.writeMessage(encodeJson(buildJsonObject { put("t", "connect") })))
                val msg2 = withTimeoutOrNull(10.seconds) { socket.receive() } ?: throw SessionClosedException("not_accepted")
                try { hs.readMessage(msg2) } catch (e: NoiseException) { throw HubProtocolException("the PC's key doesn't match") }
                val (send, recv) = hs.split()
                // §4.3: the first transport message must be ready, sent at once
                socket.sendJson(send, buildJsonObject {
                    put("t", "ready"); put("protocol", PROTOCOL_MAJOR); put("minor", PROTOCOL_MINOR); put("app", appName)
                })
                val hello = withTimeoutOrNull(10.seconds) { socket.receiveJson(recv) } ?: throw SessionClosedException(null)
                when (hello.type()) {
                    "hello" -> Unit
                    "bye" -> throw SessionClosedException(hello.string("code"))
                    else -> throw HubProtocolException("expected hello, got ${hello.type()}")
                }
                // §4.4: the hub checks the agent id against the pinned key
                val id = (hello["agent"] as? JsonObject)?.string("id")
                if (id != Ids.agentId(agentKey)) throw HubProtocolException("hello.agent.id doesn't match the pinned key")
                return HubSession(socket, send, recv, hello).also { it.start() }
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
    }
}
