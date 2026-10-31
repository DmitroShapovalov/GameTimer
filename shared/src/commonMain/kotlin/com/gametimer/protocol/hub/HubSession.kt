package com.gametimer.protocol.hub

import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.noise.CipherState
import com.gametimer.protocol.noise.HandshakeState
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.NoiseException
import com.gametimer.protocol.noise.NoisePattern
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a session ended: the agent's `bye` code (§3), `not_accepted` (closed before msg 2), or null (dropped). */
public class SessionClosedException(public val byeCode: String?) : Exception("session closed" + (byeCode?.let { ": $it" } ?: ""))

/** No reply within the time the caller allowed; the session has been closed (a resend may follow). */
public class RequestTimeoutException : Exception("no reply in time")

/**
 * One `/v2` session (§4–§7): IK handshake against the pinned key, `ready`, `hello`, then requests and
 * events. Encrypted `ping` every 30 s; 90 s without anything from the agent ends it (§3).
 * Ends with its parent scope too. Use [open]; when it ends, [closed] completes.
 */
public class HubSession private constructor(
    private val socket: FrameSocket,
    private val send: CipherState,
    private val recv: CipherState,
    /** The agent's `hello` (§6): agent info, your id and role, capabilities, ak, state. */
    public val hello: JsonObject,
    parent: Job?,
) {
    private val scope = CoroutineScope(SupervisorJob(parent) + Dispatchers.Default)
    private val sendLock = Mutex()
    private val pending = HashMap<Long, CompletableDeferred<JsonObject>>() // guarded by sendLock / pendingLock
    private val pendingLock = Mutex()
    private val outstanding = Semaphore(MAX_OUTSTANDING)
    private var nextId = 0L
    private val _events = Channel<JsonObject>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<AgentState?>((hello["state"] as? JsonObject)?.let { AgentState(it, elapsedRealtimeNanos()) })
    @Volatile private var lastReceived = elapsedRealtimeNanos()
    @Volatile private var byeCode: String? = null

    /** Agent events (`ev.*`) in arrival order, for one consumer. */
    public val events: ReceiveChannel<JsonObject> get() = _events

    /**
     * The latest §7.1 state, updated in the order messages arrive (hello, results carrying a state,
     * ev.state, ev.tick), so an older state can never overwrite a newer one.
     */
    public val state: StateFlow<AgentState?> = _state.asStateFlow()

    /** Completes when the session ends, with the bye code (null = connection dropped / timed out). */
    public val closed: CompletableDeferred<String?> = CompletableDeferred()

    private fun start() {
        // The parent ended (e.g. the app closed the link): close the socket too, outside the dead scope
        scope.coroutineContext[Job]!!.invokeOnCompletion { if (!closed.isCompleted) CoroutineScope(Dispatchers.Default).launch { end(null) } }
        scope.launch { readLoop() }
        scope.launch {
            while (isActive) {
                delay(PING_INTERVAL)
                if (elapsedRealtimeNanos() - lastReceived > IDLE_LIMIT.inWholeNanoseconds) { end(null); return@launch } // half-open TCP
                try { sendLock.withLock { socket.sendJson(send, buildJsonObject { put("t", "ping") }) } } catch (e: Exception) { end(null) }
            }
        }
    }

    /**
     * Sends a request (§5) and waits for its `result` / `error`, at most [timeout]. Pass [rid] for
     * state-changing requests (the same rid when resending). Throws [SessionClosedException] if the
     * session ends first, [RequestTimeoutException] (after closing the session) on timeout.
     */
    public suspend fun request(type: String, fields: JsonObject? = null, rid: String? = null, timeout: Duration = 30.seconds): JsonObject =
        outstanding.withPermit {
            val reply = CompletableDeferred<JsonObject>()
            // id, registration and send in one critical section: ids reach the agent strictly increasing (§5)
            sendLock.withLock {
                val id = ++nextId
                pendingLock.withLock {
                    // checked under the same lock end() clears the map with: no request can slip past it
                    if (closed.isCompleted) throw SessionClosedException(byeCode)
                    pending[id] = reply
                }
                val message = buildJsonObject {
                    put("t", type); put("id", id)
                    if (rid != null) put("rid", rid)
                    fields?.forEach { (k, v) -> if (k != "t" && k != "id" && k != "rid") put(k, v) }
                }
                try { socket.sendJson(send, message) } catch (e: Exception) { end(null) } // end() never takes sendLock
            }
            withTimeoutOrNull(timeout) { reply.await() } ?: run {
                end(null)
                throw RequestTimeoutException()
            }
        }

    public suspend fun close() {
        end(null)
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val frame = socket.receive() ?: break
                lastReceived = elapsedRealtimeNanos()
                val message = decodeJson(recv.decrypt(frame))
                when (val t = message.type()) {
                    "result", "error" -> {
                        (message["state"] as? JsonObject)?.let { _state.value = AgentState(it, lastReceived) }
                        val re = message.long("re") ?: continue
                        pendingLock.withLock { pending.remove(re) }?.complete(message)
                    }
                    "bye" -> { end(message.string("code")); return }
                    "pong" -> Unit
                    else -> if (t != null && t.startsWith("ev.")) {
                        if (t == "ev.state" || t == "ev.tick") (message["state"] as? JsonObject)?.let { _state.value = AgentState(it, lastReceived) }
                        _events.trySend(message)
                    }
                }
            }
        } catch (e: Exception) {
            // decryption failure, bad JSON or a dropped socket all end the session
        }
        end(null)
    }

    private suspend fun end(code: String?) = withContext(NonCancellable) {
        pendingLock.withLock { if (!closed.isCompleted) byeCode = code }
        if (!closed.complete(code)) return@withContext
        val waiting = pendingLock.withLock { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.completeExceptionally(SessionClosedException(code)) }
        _events.close()
        socket.close()
        scope.cancel()
    }

    public companion object {
        internal val PING_INTERVAL = 30.seconds
        internal val IDLE_LIMIT = 90.seconds
        internal const val MAX_OUTSTANDING = 16

        /**
         * Connects to [agent] (default: its last known address), runs the handshake and `ready`, and returns
         * the session once `hello` arrived. The session ends when [parent] does. Throws
         * [SessionClosedException] if the agent refused (not paired / revoked: closed without msg 2 →
         * `not_accepted`), [HubProtocolException] for a wrong key or id, or the connector's I/O exception.
         */
        public suspend fun open(
            connector: Connector,
            agent: PairedAgent,
            hubKey: KeyPair,
            appName: String,
            parent: Job? = null,
            host: String = agent.host,
            port: Int = agent.port,
        ): HubSession = handshake(connector.connect(host, port, "/v2"), agent, hubKey).confirm(appName, parent)

        /**
         * The first half of [open] on an already opened [socket] (LAN or relay, §10.3): msg 1 → msg 2 against
         * the pinned key. Nothing is confirmed yet: the hub sends `ready` only on the path it picks
         * ([PendingSession.confirm]) and closes the other ([PendingSession.abandon]). Closes [socket] on failure.
         */
        public suspend fun handshake(socket: FrameSocket, agent: PairedAgent, hubKey: KeyPair): PendingSession {
            val agentKey = agent.agentKeyBytes()
            try {
                val hs = HandshakeState(NoisePattern.IK, true, PROLOGUE, hubKey, agentKey)
                socket.send(hs.writeMessage(encodeJson(buildJsonObject { put("t", "connect") })))
                val msg2 = withTimeoutOrNull(10.seconds) { socket.receive() } ?: throw SessionClosedException("not_accepted")
                try { hs.readMessage(msg2) } catch (e: NoiseException) { throw HubProtocolException("the PC's key doesn't match") }
                val (send, recv) = hs.split()
                return PendingSession(socket, send, recv, agentKey)
            } catch (e: Throwable) {
                withContext(NonCancellable) { socket.close() }
                throw e
            }
        }
    }

    /** A handshake that reached msg 2 (the PC's pinned key answered) but isn't confirmed yet (§4.3, §10.3). */
    public class PendingSession internal constructor(
        private val socket: FrameSocket,
        private val send: CipherState,
        private val recv: CipherState,
        private val agentKey: ByteArray,
    ) {
        /** Sends `ready` and waits for `hello`: the session. Closes the socket on failure. */
        public suspend fun confirm(appName: String, parent: Job?): HubSession {
            try {
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
                return HubSession(socket, send, recv, hello, parent).also { it.start() }
            } catch (e: Throwable) {
                withContext(NonCancellable) { socket.close() }
                throw e
            }
        }

        /** The other path won: close without `ready` (an unconfirmed handshake, not a failure, §10.3). */
        public suspend fun abandon() {
            withContext(NonCancellable) { socket.close() }
        }
    }
}
