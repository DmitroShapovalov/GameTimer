package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base32
import com.gametimer.protocol.hub.relayHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

public enum class RelayStatus { Off, Connecting, Connected }

/** The agent's WebSocket to the relay: binary frames of at most [RelayFrames.MAX_FRAME] bytes. */
public interface RelaySocket {
    public suspend fun send(frame: ByteArray)

    /** The next frame, or null once closed. A text or oversized frame throws (the client reconnects, §10.2). */
    public suspend fun receive(): ByteArray?

    /** Idempotent; makes a pending send or receive return or throw promptly. */
    public suspend fun close()
}

public fun interface RelaySocketFactory {
    public suspend fun connect(url: String): RelaySocket
}

/** The relay misbehaved or the socket is stuck: reconnect. */
public class RelayProtocolException(message: String) : Exception(message)

/**
 * The agent's side of the relay (docs/protocol.md §10.2), a Kotlin port of the C# `RelayClient`: keeps one
 * outbound WebSocket to `wss://<relay>/v2/agent`, registers the route claims of the paired hubs, and runs every
 * stream the relay opens through [AgentHost.run] like a LAN socket (with the relay pool and the route ↔ key
 * binding of §10.4). Sends push wake-ups (§10.6), at most one per phone every 30 s. Reconnects with back-off.
 * The app starts it while "Control from anywhere" is on (for the phone agent: always) and a hub is paired.
 */
public class RelayClient(
    private val host: AgentHost,
    private val url: String = DEFAULT_URL,
    private val connector: RelaySocketFactory = KtorRelaySocketFactory(),
    scope: CoroutineScope? = null,
    private val timeSource: TimeSource = host.timeSource,
) {
    public companion object {
        public const val DEFAULT_URL: String = "wss://gametimer-relay.fly.dev/v2/agent"

        internal val PING_AFTER = 30.seconds
        internal val DEAD_AFTER = 90.seconds
        internal val RECENT_SETS = 30.seconds
        internal val REGISTER_MIN_INTERVAL = 1100.milliseconds
        internal val RECHECK_EVERY = 30.seconds
        private val MIN_BACKOFF = 1.seconds
        private val MAX_BACKOFF = 5.minutes
        private val STABLE_AFTER = 60.seconds
        private val CONNECT_TIMEOUT = 30.seconds
        internal val SEND_TIMEOUT = 10.seconds

        /** §10.6: one wake-up per phone in this time; events in between are sent as one trailing wake-up. */
        internal var wakeEvery: Duration = 30.seconds // settable for tests

        /** Streams on one relay socket, counted here whatever the relay sends (§10.4): 4 waiting + 16 sessions. */
        public const val MAX_STREAMS: Int = AgentHost.MAX_RELAY_PENDING + AgentHost.MAX_SESSIONS
    }

    private val scope: CoroutineScope = scope ?: host.scope
    private val gate = newHostLock()
    private var job: Job? = null
    @Volatile private var current: Connection? = null
    private val _status = MutableStateFlow(RelayStatus.Off)

    public val status: StateFlow<RelayStatus> = _status.asStateFlow()

    /** False while the app is stopping: new streams are refused (1008) so no session starts after the bye went out. */
    @Volatile public var acceptStreams: Boolean = true

    public fun start() {
        gate.withLock {
            if (job != null) return
            host.relayPeersChanged = { current?.markDirty() }
            // Not connected: nothing to do (the phones catch up when they next connect)
            host.relayWake = { tokens -> current?.wake(tokens) }
            job = scope.launch { runLoop() }
        }
    }

    public suspend fun stop() {
        val j = gate.withLock {
            // Together with job: a start() right after must not lose its hooks to this stop
            host.relayPeersChanged = null
            host.relayWake = null
            job.also { job = null }
        } ?: return
        j.cancelAndJoin()
        if (gate.withLock { job == null }) _status.value = RelayStatus.Off
    }

    /** Wakes these phones now, past the 30 s pacing (the farewell when remote access goes off, §10.6). */
    public fun wakeNow(tokens: List<String>) {
        val c = current ?: return
        tokens.forEach { c.sendSoon(RelayFrames.wake(it)) }
    }

    private suspend fun runLoop() {
        var backoff = MIN_BACKOFF
        while (true) {
            _status.value = RelayStatus.Connecting
            val started = timeSource.markNow()
            try {
                val socket = withTimeout(CONNECT_TIMEOUT) { connector.connect(url) }
                val conn = Connection(socket)
                current = conn
                try { conn.run() } finally { current = null }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // stopped: out; a connect timeout: retry
            } catch (e: Exception) {
                // refused, dropped or malformed: reconnect
            }
            if (started.elapsedNow() >= STABLE_AFTER) backoff = MIN_BACKOFF
            _status.value = RelayStatus.Connecting
            delay(backoff * (0.5 + Random.nextDouble() / 2))
            backoff = minOf(backoff * 2, MAX_BACKOFF)
        }
    }

    private class WakeState(val last: TimeMark, val pending: Boolean, val eventAt: Long)

    /** One relay socket: register, frames in and out, the streams on it. */
    private inner class Connection(private val socket: RelaySocket) {
        private val sendLock = Mutex()
        private val lock = newHostLock()
        private val streams = mutableMapOf<UInt, RelayStream>() // under lock
        private var routes: Map<String, String> = emptyMap() // route → hub id, under lock
        private val recent = mutableListOf<Pair<TimeMark, Map<String, String>>>() // under lock
        private var registeredKey = ""
        @Volatile private var lastSent: TimeMark = timeSource.markNow()
        @Volatile private var lastReceived: TimeMark = timeSource.markNow()
        private var lastRegister: TimeMark? = null
        private var lastCheck: TimeMark = timeSource.markNow()
        private var dirty = true // under lock
        /** Pongs, closes and wake-ups from other loops, sent by one coroutine; full → the relay misbehaves. */
        private val control = Channel<ByteArray>(64)
        @Volatile private var pongQueued = false
        @Volatile private var ended = false
        private val wakes = mutableMapOf<String, WakeState>() // under lock

        fun markDirty() = lock.withLock { dirty = true }

        /** §10.6: a wake-up per token at most every [wakeEvery]; one too soon is sent when the time is up. */
        fun wake(tokens: List<String>) {
            val eventAt = host.now
            val due = mutableListOf<String>()
            lock.withLock {
                for (t in tokens) {
                    val w = wakes[t]
                    if (w == null || w.last.elapsedNow() >= wakeEvery) {
                        wakes[t] = WakeState(timeSource.markNow(), false, eventAt)
                        due.add(t)
                    } else {
                        wakes[t] = WakeState(w.last, true, eventAt)
                    }
                }
            }
            due.forEach { sendSoon(RelayFrames.wake(it)) }
        }

        private fun sendPendingWakes() {
            val due = mutableListOf<Pair<String, Long>>()
            lock.withLock {
                for ((t, w) in wakes.toList()) {
                    val age = w.last.elapsedNow()
                    if (w.pending && age >= wakeEvery) {
                        wakes[t] = WakeState(w.last, false, w.eventAt)
                        due.add(t to w.eventAt)
                    } else if (!w.pending && age > 10.minutes) {
                        wakes.remove(t)
                    }
                }
            }
            // Outside the lock (the host takes its own): a hub that connected meanwhile has caught up already
            for ((t, at) in due) {
                if (!host.stillToWake(t, at)) continue
                lock.withLock { wakes[t] = WakeState(timeSource.markNow(), false, at) }
                sendSoon(RelayFrames.wake(t))
            }
        }

        suspend fun run() = coroutineScope {
            try {
                registerIfChanged(force = true)
                _status.value = RelayStatus.Connected
                launch { guarded { maintainLoop() } }
                launch {
                    guarded {
                        for (f in control) {
                            if (f[0] == RelayFrames.PONG) pongQueued = false
                            send(f)
                        }
                    }
                }
                receiveLoop(this)
            } finally {
                coroutineContext.cancelChildren()
                lock.withLock { streams.values.toList().also { streams.clear() } }.forEach { it.remoteClosed() }
                ended = true
                control.close()
                withContext(NonCancellable) { closeSocket() }
            }
        }

        /** A loop that fails ends the socket (the receive loop then ends): reconnect instead of hanging. */
        private suspend fun guarded(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeSocket()
            }
        }

        private suspend fun closeSocket() {
            try { socket.close() } catch (e: Exception) { /* gone */ }
        }

        private suspend fun receiveLoop(scope: CoroutineScope) {
            while (true) {
                val f = socket.receive() ?: return
                lastReceived = timeSource.markNow()
                handle(f, scope)
            }
        }

        private fun handle(f: ByteArray, scope: CoroutineScope) {
            if (f.isEmpty()) throw RelayProtocolException("empty frame")
            when (f[0]) {
                RelayFrames.OPEN -> {
                    if (f.size != RelayFrames.STREAM_HEADER + 16) throw RelayProtocolException("bad open")
                    open(RelayFrames.streamOf(f), Base32.encode(f.copyOfRange(RelayFrames.STREAM_HEADER, f.size)), scope)
                }
                RelayFrames.DATA -> {
                    if (f.size < RelayFrames.STREAM_HEADER + 1) throw RelayProtocolException("bad data")
                    val s = lock.withLock { streams[RelayFrames.streamOf(f)] }
                    if (s != null && !s.deliver(f.copyOfRange(RelayFrames.STREAM_HEADER, f.size))) {
                        // The session isn't reading: end the stream rather than buffer without limit
                        s.remoteClosed()
                        sendSoon(RelayFrames.close(s.id, 1008))
                    }
                }
                RelayFrames.CLOSE -> {
                    if (f.size != RelayFrames.STREAM_HEADER + 2) throw RelayProtocolException("bad close")
                    lock.withLock { streams.remove(RelayFrames.streamOf(f)) }?.remoteClosed()
                }
                RelayFrames.PING -> {
                    if (f.size > 1 + RelayFrames.MAX_PING_DATA) throw RelayProtocolException("bad ping")
                    sendSoon(RelayFrames.pong(f.copyOfRange(1, f.size)))
                }
                RelayFrames.PONG -> if (f.size > 1 + RelayFrames.MAX_PING_DATA) throw RelayProtocolException("bad pong")
                RelayFrames.REGISTER -> throw RelayProtocolException("register from the relay")
                else -> Unit // §10.2: the agent ignores types it doesn't know
            }
        }

        private fun open(id: UInt, route: String, scope: CoroutineScope) {
            val stream: RelayStream
            lock.withLock {
                // §10.2: ids are never reused while the socket lives; a relay that does is broken: reconnect
                if (id == 0u || streams.containsKey(id)) throw RelayProtocolException("bad stream id")
                val hubId = findHubLocked(route)
                val ticket = if (hubId == null || !acceptStreams || streams.size >= MAX_STREAMS) null else host.tryAdmitRelay(hubId)
                if (ticket == null) {
                    sendSoon(RelayFrames.close(id, 1008))
                    return
                }
                stream = RelayStream(this, id)
                streams[id] = stream
                scope.launch {
                    try { host.run(stream, ticket, pairing = false) } finally { forget(stream) }
                }
            }
        }

        /** The hub of a route in the current set, or in a set replaced less than 30 s ago (§10.2). Under lock. */
        private fun findHubLocked(route: String): String? {
            routes[route]?.let { return it }
            recent.removeAll { it.first.elapsedNow() > RECENT_SETS }
            return recent.firstNotNullOfOrNull { it.second[route] }
        }

        private suspend fun maintainLoop() {
            while (true) {
                delay(1.seconds)
                if (lastReceived.elapsedNow() > DEAD_AFTER) {
                    closeSocket() // the receive loop ends; reconnect
                    return
                }
                val due = lock.withLock { (dirty || lastCheck.elapsedNow() >= RECHECK_EVERY).also { dirty = false } }
                if (due) {
                    lastCheck = timeSource.markNow()
                    if (!registerIfChanged(force = false)) markDirty() // too soon: next second
                }
                sendPendingWakes()
                if (lastSent.elapsedNow() > PING_AFTER) send(RelayFrames.ping())
            }
        }

        /**
         * Sends `register` when the membership of the set changed (a hub paired or revoked, the UTC date, a hub
         * moving in or out of the 21), at most once per second. False when it had to wait.
         */
        private suspend fun registerIfChanged(force: Boolean): Boolean {
            val list = host.relayRoutes(host.utcToday)
            val key = list.map { it.route }.sorted().joinToString(",")
            if (!force && key == registeredKey) return true
            val last = lastRegister
            if (last != null && last.elapsedNow() < REGISTER_MIN_INTERVAL) return false
            lock.withLock {
                // The new set is valid at once; the old one stays valid for opens already in flight
                recent.removeAll { it.first.elapsedNow() > RECENT_SETS }
                if (routes.isNotEmpty()) recent.add(timeSource.markNow() to routes)
                routes = list.associate { it.route to it.hubId }
            }
            registeredKey = key
            send(RelayFrames.register(list.map { it.claim }))
            lastRegister = timeSource.markNow() // after the send: the relay's 1 s counts from its arrival
            return true
        }

        suspend fun send(frame: ByteArray) {
            sendLock.withLock {
                // A send the relay doesn't take in time means a stuck socket: close it and reconnect
                val sent = withTimeoutOrNull(SEND_TIMEOUT) { socket.send(frame); true }
                if (sent == null) {
                    withContext(NonCancellable) { closeSocket() }
                    throw RelayProtocolException("the relay socket is stuck")
                }
                lastSent = timeSource.markNow()
            }
        }

        /** A frame for the control loop. One pong waits at a time; more than 64 frames waiting: reconnect. */
        fun sendSoon(frame: ByteArray) {
            if (frame[0] == RelayFrames.PONG) {
                if (pongQueued) return
                pongQueued = true
            }
            if (!control.trySend(frame).isSuccess && !ended) {
                this@RelayClient.scope.launch { closeSocket() }
            }
        }

        fun forget(s: RelayStream) {
            lock.withLock { if (streams[s.id] === s) streams.remove(s.id) }
        }
    }

    /** One relayed stream as the agent host sees it: one Noise message per `data` frame. */
    private inner class RelayStream(private val conn: Connection, val id: UInt) : FrameTransport {
        private val inbox = Channel<ByteArray>(INBOX_LIMIT)
        private val lock = newHostLock()
        private var queuedBytes = 0
        @Volatile private var remoteClosed = false
        @Volatile private var closed = false

        /** Queues a message for the session; false when the session doesn't keep up (64 messages / 128 KiB). */
        fun deliver(noise: ByteArray): Boolean {
            if (remoteClosed || closed) return true
            val fits = lock.withLock {
                if (queuedBytes + noise.size > INBOX_BYTES) false else { queuedBytes += noise.size; true }
            }
            if (!fits) return false
            if (!inbox.trySend(noise).isSuccess) {
                lock.withLock { queuedBytes -= noise.size }
                return false
            }
            return true
        }

        fun remoteClosed() {
            remoteClosed = true
            inbox.close()
        }

        override suspend fun receive(): ByteArray? {
            val m = inbox.receiveCatching().getOrNull() ?: return null
            lock.withLock { queuedBytes -= m.size }
            return m
        }

        override suspend fun send(frame: ByteArray) {
            if (remoteClosed || closed) throw RelayProtocolException("the relayed stream is closed")
            conn.send(RelayFrames.data(id, frame))
        }

        override suspend fun close() {
            val first = lock.withLock { (!closed).also { closed = true } }
            if (!first) return
            inbox.close()
            conn.forget(this)
            if (remoteClosed) return
            try {
                withTimeoutOrNull(2.seconds) { conn.send(RelayFrames.close(id, 1000)) }
            } catch (e: Exception) { /* the relay socket is gone: the relay closes the hub itself */ }
        }
    }
}

private const val INBOX_LIMIT = 64
private const val INBOX_BYTES = 128 * 1024

/**
 * [RelaySocketFactory] on Ktor: CIO on Android / JVM, which refuses inbound frames over [RelayFrames.MAX_FRAME]
 * before buffering them (Darwin on iOS). One client for the process. Sends no Origin header (§10.3).
 */
public class KtorRelaySocketFactory(private val client: HttpClient = sharedClient) : RelaySocketFactory {
    override suspend fun connect(url: String): RelaySocket = KtorRelaySocket(client.webSocketSession(url))

    private companion object {
        val sharedClient: HttpClient by lazy { relayHttpClient(RelayFrames.MAX_FRAME.toLong()) }
    }
}

private class KtorRelaySocket(private val session: DefaultClientWebSocketSession) : RelaySocket {
    override suspend fun send(frame: ByteArray) = session.send(Frame.Binary(true, frame))

    override suspend fun receive(): ByteArray? {
        while (true) {
            val frame = try { session.incoming.receive() } catch (e: ClosedReceiveChannelException) { return null }
            when (frame) {
                is Frame.Binary -> {
                    // One relay frame = one whole WebSocket message (§10.2): no fragments, nothing over the limit
                    if (!frame.fin || frame.data.size > RelayFrames.MAX_FRAME) throw RelayProtocolException("bad frame")
                    return frame.data
                }
                is Frame.Text -> throw RelayProtocolException("text from the relay")
                is Frame.Close -> return null
                else -> continue
            }
        }
    }

    override suspend fun close() {
        try {
            withTimeoutOrNull(1.seconds) { session.close(CloseReason(CloseReason.Codes.NORMAL, "")) } ?: session.cancel()
        } catch (e: Exception) { /* gone */ }
    }
}
