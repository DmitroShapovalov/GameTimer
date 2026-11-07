package com.gametimer.protocol.agent

import com.gametimer.protocol.hub.encodeJson
import com.gametimer.protocol.noise.CipherState
import com.gametimer.protocol.noise.HandshakeState
import com.gametimer.protocol.noise.NoiseException
import com.gametimer.protocol.noise.NoisePattern
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Runs one accepted connection to its end: `/v2` ([pairing] false) or `/v2/pair`. The endpoint has checked
 * the path (and, on the LAN, the missing Origin header) and reserved [ticket]. Never throws for anything the
 * remote side does; the connection is always closed. Cancelling the caller ends a session with `bye shutdown`.
 */
public suspend fun AgentHost.run(transport: FrameTransport, ticket: HandshakeTicket, pairing: Boolean) {
    try {
        if (pairing) runPairing(transport, ticket) else runSession(transport, ticket)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // the caller's cancellation goes on; a stray one (closed host) ends here
    } catch (e: Exception) {
        // Remote misbehaviour or a dropped socket: nothing to report (§3: no oracle before the handshake)
    } finally {
        ticket.finish()
        withContext(NonCancellable) { closeQuietly(transport) }
    }
}

internal suspend fun closeQuietly(transport: FrameTransport) {
    try { transport.close() } catch (e: Exception) { /* already gone */ }
}

// ---------------- /v2: IK, ready rule, session ----------------

/** A result that may itself be null, told apart from a timeout's null. */
internal class Step<T>(val value: T)

private class Handshaken(val send: CipherState, val recv: CipherState, val peer: Peer, val hubMinor: Int)

private suspend fun AgentHost.runSession(transport: FrameTransport, ticket: HandshakeTicket) {
    val ip = ticket.remoteIp
    val relayed = ticket.relayHubId != null

    // null: timed out or evicted; Step(null): refused (already counted)
    val step: Step<Handshaken?>? = try {
        untilSignalled(scope, listOf(ticket.evicted)) {
            withTimeoutOrNull(AgentHost.HANDSHAKE_TIMEOUT) { Step(sessionHandshake(transport, ticket, relayed)) }
        }
    } catch (e: ProtocolViolation) {
        recordFailure(ip)
        return
    }
    if (step == null) {
        recordFailure(ip) // handshake timeout, or a newer relayed stream took the slot
        return
    }
    val done = step.value ?: return

    // Confirmed. From here on failures are answered with bye (§3).
    ticket.release()
    ticket.unprove()
    val session = Session(this, transport, done.send, done.peer, relayed)
    val refused = register(session)
    if (refused != null) {
        session.bye(refused)
        session.completion.join()
        return
    }

    try {
        persist() // lastSeen, set by register
        // Events published from now on wait in the session until hello is out (§6: hello comes first)
        val hello = try {
            hello(session, done.hubMinor)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            session.bye("internal") // the backend's own timeout
            return
        } catch (e: Exception) {
            session.bye("internal") // the backend failed to produce the state
            return
        }
        session.sendHello(hello)
        if (!relayed) raiseSessionStarted(ip) // a relayed session says nothing about this network (§9)
        receiveLoop(session, transport, done.recv)
    } catch (e: CancellationException) {
        session.bye("shutdown") // the endpoint is stopping (the only cancellation that gets here)
        throw e
    } finally {
        unregister(session)
        session.complete()
        withContext(NonCancellable) {
            withTimeoutOrNull(2.seconds) { session.completion.join() }
            if (session.completion.isCompleted) session.disposeTimers()
        }
    }
}

/** msg 1 → msg 2 → ready, or null for anything that is not a live, paired hub (each counted as a failure). */
private suspend fun AgentHost.sessionHandshake(transport: FrameTransport, ticket: HandshakeTicket, relayed: Boolean): Handshaken? {
    val ip = ticket.remoteIp
    // §10.4: a relayed stream brings msg 1 at once, or gives its slot back early
    val msg1 = withTimeoutOrNull(if (relayed) AgentHost.RELAY_FIRST_MESSAGE_TIMEOUT else AgentHost.HANDSHAKE_TIMEOUT) {
        Step(transport.receive())
    }
    if (msg1 == null) { recordFailure(ip); return null } // timed out
    val m1 = msg1.value ?: return null // closed

    val hs = HandshakeState(NoisePattern.IK, false, AgentHost.PROLOGUE, key)
    val payload = try { hs.readMessage(m1) } catch (e: NoiseException) { recordFailure(ip); return null }

    // §4.1: only a paired hub key may continue; §4.2: msg 1 carries { "t": "connect" } only.
    val known = findPeerByKey(hs.remoteStaticPublicKey!!)
    if (known == null || !isBareMessage(payload, "connect")) { recordFailure(ip); return null }
    // §10.4: a relayed stream is only for the hub whose route it was opened with
    if (relayed && known.id != ticket.relayHubId) { recordFailure(ip); return null }
    // Out of the evictable pool; proven-but-unconfirmed streams are capped per hub (msg 1 can be replayed)
    if (relayed && !tryProve(ticket)) { recordFailure(ip); return null }

    transport.send(hs.writeMessage(ByteArray(0)))
    val (send, recv) = hs.split()

    // §4.3: nothing happens until the first transport message proves a live hub
    val first = transport.receive() ?: run { recordFailure(ip); return null }
    val hubMinor = readReady(recv, first) ?: run { recordFailure(ip); return null }
    return Handshaken(send, recv, known, hubMinor)
}

/** The hub's minor from a valid `ready`, or null. */
private fun readReady(recv: CipherState, frame: ByteArray): Int? {
    val plain = try { recv.decrypt(frame) } catch (e: NoiseException) { return null }
    val r = StrictJson.parse(plain)
    if (r.str("t") != "ready") return null
    if (r.int("protocol") != AgentHost.PROTOCOL_MAJOR) return null
    val minor = r.int("minor") ?: return null
    return if (minor < 0) null else minor
}

/** An integer member that fits an Int (like .NET's TryGetInt32), else null. */
internal fun JsonObject.int(name: String): Int? {
    val p = this[name] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.content.toIntOrNull()
}

private suspend fun AgentHost.hello(session: Session, hubMinor: Int): JsonObject {
    // revoked meanwhile: the bye is queued already
    val ak = lock.withLock { peers.firstOrNull { it.id == session.hubId }?.ak ?: "" }
    val state = backendRef.state()
    return buildJsonObject {
        put("t", "hello")
        put("protocol", AgentHost.PROTOCOL_MAJOR)
        put("minor", min(hubMinor, AgentHost.PROTOCOL_MINOR))
        put("agent", agentJson())
        put("you", buildJsonObject { put("id", session.hubId); put("role", AgentHost.roleName(session.role)) })
        put("capabilities", buildJsonArray { capabilities.forEach { add(JsonPrimitive(it)) } })
        put("remoteAccess", remoteAccess) // §6 / §10.2: the hub dials the relay only while this is on
        put("ak", ak)
        put("state", state)
    }
}

/**
 * §3 session limits: 2 per hub (a new one closes that hub's oldest), 16 in total. Re-checks under the lock
 * that the hub is still paired. Returns null when registered, else the bye code to close with.
 */
private fun AgentHost.register(session: Session): String? {
    var evict: Session? = null
    lock.withLock {
        if (closed) return "shutdown"
        if (session.relayed && !remoteAccess) return "remote_off" // turned off during this handshake
        if (peers.none { it.id == session.hubId }) return "revoked"
        val mine = sessions.filter { it.hubId == session.hubId }.sortedBy { it.seq }
        if (mine.size >= AgentHost.MAX_SESSIONS_PER_HUB) evict = mine[0]
        else if (sessions.size >= AgentHost.MAX_SESSIONS) return "busy"
        evict?.let { sessions.remove(it) }
        val peer = updatePeerLocked(session.hubId) { it.copy(lastSeen = nowMillis()) }!! // only an accepted session counts
        session.role = peer.role
        sessions.add(session)
    }
    evict?.bye("busy")
    return null
}

private fun AgentHost.unregister(session: Session) {
    lock.withLock { sessions.remove(session) }
}

private suspend fun AgentHost.receiveLoop(session: Session, transport: FrameTransport, recv: CipherState) {
    val started = timeSource.markNow()
    while (!session.isClosing) {
        val lifetimeLeft = AgentHost.SESSION_LIFETIME - started.elapsedNow()
        if (lifetimeLeft <= Duration.ZERO) { session.bye("expired"); return }
        val wait = minOf(AgentHost.IDLE_TIMEOUT, lifetimeLeft)
        val frame: ByteArray? = try {
            withTimeout(wait) { transport.receive() }
        } catch (e: TimeoutCancellationException) {
            if (started.elapsedNow() >= AgentHost.SESSION_LIFETIME) session.bye("expired")
            else session.abort() // 90 s of silence
            return
        } catch (e: ProtocolViolation) {
            session.bye("protocol_error") // e.g. a text message
            return
        }
        if (frame == null) { session.abort(); return }
        if (session.isClosing) return // revoked or evicted meanwhile: nothing more is executed

        try {
            val plain = try { recv.decrypt(frame) } catch (e: NoiseException) { throw ProtocolViolation("transport message did not decrypt") }
            handleMessage(session, plain)
        } catch (e: ProtocolViolation) {
            session.bye("protocol_error")
            return
        }
    }
}

/** True if the plaintext is exactly an I-JSON object whose `t` is [type]. */
internal fun isBareMessage(payload: ByteArray, type: String): Boolean =
    try { StrictJson.parse(payload).str("t") == type } catch (e: ProtocolViolation) { false }

/**
 * A confirmed session. Everything it sends goes through one outbox and one send loop, so the send
 * CipherState (not thread-safe) is only ever used by that loop, in order. Until [sendHello] runs, messages are
 * held back so that hello is always first (§6).
 */
internal class Session(
    host: AgentHost,
    private val transport: FrameTransport,
    private val send: CipherState,
    peer: Peer,
    /** The session came through the relay (§10): ended when "Control from anywhere" is turned off. */
    val relayed: Boolean,
) {
    private companion object {
        const val OUTBOX_CAPACITY = 257 // hello + up to 256 held-back messages
        const val HELD_BACK_LIMIT = OUTBOX_CAPACITY - 1
        /** A peer that doesn't read its bye in this time is cut off (it would otherwise hold a slot). */
        val BYE_GRACE = 2.seconds
        private var counter = 0L
        private val counterLock = newHostLock()
        fun nextSeq(): Long = counterLock.withLock { ++counter }
    }

    private val scope = host.scope
    private val outbox = Channel<JsonObject>(OUTBOX_CAPACITY)
    private val gate = newHostLock()
    private var beforeHello: MutableList<JsonObject>? = mutableListOf()
    private var byeCode: String? = null
    @Volatile private var closing = false
    private var byeTimer: Job? = null

    val hubId: String = peer.id
    val hubName: String = peer.name
    @Volatile var role: PeerRole = peer.role
    /** Start order (oldest first), for the per-hub limit. */
    val seq: Long = nextSeq()
    var lastId: Long = 0
    val isClosing: Boolean get() = closing

    /** The send loop: completes once the bye (if any) is out and the transport is closed. */
    val completion: Job = scope.launch { sendLoop() }

    /** Queues a message; dropped once the session is closing. A full outbox (dead reader) aborts. */
    fun enqueue(message: JsonObject) {
        gate.withLock {
            if (closing) return
            val held = beforeHello
            if (held != null) {
                if (held.size < HELD_BACK_LIMIT) { held.add(message); return }
            } else if (outbox.trySend(message).isSuccess) return
        }
        abort() // a reader that can't keep up
    }

    /** Sends hello, then whatever was published while it was being built. */
    fun sendHello(hello: JsonObject) {
        gate.withLock {
            val held = beforeHello ?: mutableListOf()
            beforeHello = null
            if (closing) return
            // Capacity is hello + the held-back limit, so these can't fail
            outbox.trySend(hello)
            held.forEach { outbox.trySend(it) }
        }
    }

    /** Sends `bye` after what is already queued (never dropped, even with a full outbox), then closes (§3). */
    fun bye(code: String) {
        gate.withLock {
            if (closing) return
            closing = true
            byeCode = code
            beforeHello = null // hello never went out: just the bye
            outbox.close()
            byeTimer = scope.launch { delay(BYE_GRACE); abort() }
        }
    }

    /** Closes without a goodbye (dead connection, idle timeout, bye not read); unblocks a stuck send. */
    fun abort() {
        gate.withLock {
            closing = true
            byeCode = null
            beforeHello = null
            outbox.close()
        }
        // Outside the lock: cancellation and the transport's close run arbitrary code
        completion.cancel()
        scope.launch { closeQuietly(transport) }
    }

    /** The receive side ended: let the send loop drain and stop. */
    fun complete() {
        gate.withLock {
            closing = true
            outbox.close()
        }
    }

    private suspend fun sendLoop() {
        try {
            for (message in outbox) sendNow(message)
            val bye = gate.withLock { byeCode }
            if (bye != null) sendNow(buildJsonObject { put("t", "bye"); put("code", bye) })
        } catch (e: CancellationException) {
            // aborted
        } catch (e: Exception) {
            // socket gone; the receive side notices too
        } finally {
            withContext(NonCancellable) { closeQuietly(transport) }
        }
    }

    private suspend fun sendNow(message: JsonObject) {
        transport.send(send.encrypt(encodeJson(message)))
    }

    /** After both loops have ended. */
    fun disposeTimers() {
        gate.withLock { byeTimer?.cancel() }
    }
}
