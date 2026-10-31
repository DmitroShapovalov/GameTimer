package com.gametimer.protocol.hub

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.identity.DiscoveryTag
import com.gametimer.protocol.identity.RouteToken
import com.gametimer.protocol.noise.KeyPair
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** The connection to one agent as the UI sees it. */
public sealed interface LinkStatus {
    public data object Connecting : LinkStatus

    /** Connected; [viaRelay] when the session goes through the relay (§10), not the home network. */
    public data class Online(val hello: JsonObject, val viaRelay: Boolean = false) : LinkStatus

    /** Not reachable right now; retrying with back-off. */
    public data class Offline(val reason: OfflineReason) : LinkStatus

    /** This phone was removed on the PC or by an admin (bye revoked): pair again. */
    public data object Revoked : LinkStatus

    public data object Stopped : LinkStatus
}

public enum class OfflineReason {
    /** No connection (PC off, asleep, other network). */
    Unreachable,

    /** The PC answered but closed at once: this phone isn't (or is no longer) paired there. */
    Refused,

    /** Something answered with a different key or id: not the paired PC (§4.1). Never trusted. */
    WrongDevice,
}

/**
 * The agent's §7.1 state with the moment it arrived on this device's boot clock. The countdown runs
 * from [receivedAtNanos] (§7.1: the PC's clock may be wrong), never from `targetTime`.
 */
public class AgentState(public val json: JsonObject, public val receivedAtNanos: Long) {
    public val timer: JsonObject? get() = json["timer"] as? JsonObject
    public val isRunning: Boolean get() = timer?.string("state") == "running"

    /** Seconds left now, counted down locally; 0 when no timer runs. */
    public fun remainingSecondsNow(): Int {
        if (!isRunning) return 0
        val atReceive = (timer?.get("remainingSeconds") as? JsonPrimitive)?.intOrNull ?: return 0
        val elapsed = (elapsedRealtimeNanos() - receivedAtNanos).nanoseconds.inWholeSeconds.toInt()
        return (atReceive - elapsed).coerceAtLeast(0)
    }
}

/** A request's outcome for the UI. */
public sealed interface RequestOutcome {
    public data class Ok(val reply: JsonObject) : RequestOutcome

    /** The agent refused it: an error code from §7.2 (e.g. not_running, forbidden); [reply] is the whole error. */
    public data class Refused(val code: String, val reply: JsonObject? = null) : RequestOutcome {
        // Equal by code: callers (and tests) compare outcomes; the reply is extra detail (e.g. conflict's value)
        override fun equals(other: Any?): Boolean = other is Refused && other.code == code
        override fun hashCode(): Int = code.hashCode()
    }

    /** No answer within the resend window (§5: the hub stops after 5 minutes and asks the user). */
    public data object GaveUp : RequestOutcome

    /** This phone was revoked: nothing will be delivered. */
    public data object Revoked : RequestOutcome

    /** The link was stopped while the request waited. */
    public data object Stopped : RequestOutcome
}

/**
 * How a hub reaches its PCs away from home (§10.3): the relay's base URL (`wss://host`), a connector for
 * it, and whether the phone is on Wi-Fi / Ethernet right now (off it, the relay is dialled at once).
 */
public class RelayPath(
    public val url: String,
    public val connector: RelayConnector,
    public val onLocalNetwork: () -> Boolean = { true },
) {
    public companion object {
        public const val DEFAULT_URL: String = "wss://gametimer-relay.fly.dev"
    }
}

/**
 * Keeps one agent connected: reconnects with back-off, mirrors the latest state, and resends unanswered
 * state-changing requests with the same `rid` after a reconnect (§5, §12). Runs in [scope]; cancelling
 * it, or [stop], closes the session.
 *
 * With a [locator], a failed connect also searches the LAN for the agent's discovery tag (§9, at most
 * every [LOCATE_INTERVAL]). Found addresses are only candidates, tried one after another: one is
 * adopted — [current] changes and [onMoved] is called so the app can store it — only after the
 * handshake with the pinned key succeeded there. A fake announcement can't impersonate the PC; at
 * most it costs a failed attempt (or, as a TCP relay to the real PC, it can carry the encrypted
 * traffic — which anyone in the path of a LAN can do anyway).
 */
public class AgentLink(
    agent: PairedAgent,
    private val hubKey: KeyPair,
    private val connector: Connector,
    private val appName: String,
    private val scope: CoroutineScope,
    private val locator: Locator? = null,
    /**
     * With a relay (§10.3): the stored LAN address is tried first; if no session is open within
     * [RELAY_AFTER] (or the phone isn't on Wi-Fi / Ethernet), the relay is dialled too, and the first
     * path whose handshake with the pinned key completes gets `ready`; the other is closed. Only while
     * the PC said `remoteAccess: true` ([PairedAgent.remoteAccess]).
     */
    private val relay: RelayPath? = null,
    /** The PC's "Control from anywhere" changed (hello, ev.settings, bye remote_off): the app stores it. */
    private val onRemoteAccess: (Boolean) -> Unit = {},
    private val onMoved: (PairedAgent) -> Unit = {},
) {
    @Volatile private var relayRetryAtNanos: Long = 0
    @Volatile private var relayBackoff: Duration = RELAY_BACKOFF
    @Volatile private var agent: PairedAgent = agent
    private var lastLocateNanos: Long? = null
    private var locateInterval: Duration = LOCATE_INTERVAL

    /** The agent with its current address (changes when discovery found it elsewhere). */
    public val current: PairedAgent get() = agent

    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Stopped)
    private val _state = MutableStateFlow<AgentState?>(null)
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    @Volatile private var job: Job? = null
    @Volatile private var session: HubSession? = null

    public val status: StateFlow<LinkStatus> = _status.asStateFlow()
    public val state: StateFlow<AgentState?> = _state.asStateFlow()

    /** Agent events (ev.*) for notices; state changes are already reflected in [state]. */
    public val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    public fun start() {
        if (job?.isActive == true) return
        _status.value = LinkStatus.Connecting
        job = scope.launch { runLoop() }
    }

    public suspend fun stop() {
        val running = job
        job = null // first: a start() racing with this stop() then keeps its own new loop
        running?.cancelAndJoin() // so the old loop can't write a status after Stopped
        if (job != null) return // start() ran meanwhile: its new loop owns the session and the status
        session?.close()
        session = null
        _status.value = LinkStatus.Stopped
    }

    /**
     * Sends a request. State-changing requests ([changesState]) get one rid and are resent with it after
     * reconnects until a reply arrives or [resendWindow] (5 min, §5) is over; `internal` errors are
     * retried the same way (§7.2). Read requests are sent once.
     */
    public suspend fun send(type: String, fields: JsonObject? = null, changesState: Boolean = true,
                            resendWindow: Duration = 5.minutes): RequestOutcome {
        val rid = if (changesState) newRid() else null
        val started = elapsedRealtimeNanos()
        fun left() = resendWindow - (elapsedRealtimeNanos() - started).nanoseconds
        while (left() > Duration.ZERO) {
            val ready = withTimeoutOrNull(left()) {
                status.first { it is LinkStatus.Online || it is LinkStatus.Revoked || it is LinkStatus.Stopped }
            } ?: break
            when (ready) {
                LinkStatus.Revoked -> return RequestOutcome.Revoked
                LinkStatus.Stopped -> return RequestOutcome.Stopped
                else -> Unit
            }
            val current = session
            if (current == null || current.closed.isCompleted) {
                waitForChange(ready, left()) // the session just ended; the status will move on
                continue
            }
            val reply = try {
                current.request(type, fields, rid, timeout = minOf(left(), 30.seconds))
            } catch (e: SessionClosedException) {
                if (!changesState) return RequestOutcome.GaveUp
                waitForChange(ready, left()); continue // reconnect, then resend with the same rid
            } catch (e: RequestTimeoutException) {
                if (!changesState) return RequestOutcome.GaveUp
                waitForChange(ready, left()); continue
            }
            if (reply.type() == "error") {
                val code = reply.string("code") ?: "unknown"
                if (code == "internal" && changesState) { delay(1.seconds); continue }
                return RequestOutcome.Refused(code, reply)
            }
            // The session updated its state before completing the reply; let [state] catch up, so a caller
            // reading it right after send() sees the effect of its own request
            val latest = current.state.value
            if (latest != null) withTimeoutOrNull(1.seconds) { state.first { it === latest || it?.receivedAtNanos ?: 0 > latest.receivedAtNanos } }
            return RequestOutcome.Ok(reply)
        }
        return RequestOutcome.GaveUp
    }

    private suspend fun locate(): List<AgentAddress> {
        val loc = locator ?: return emptyList()
        val now = elapsedRealtimeNanos()
        val last = lastLocateNanos
        if (last != null && (now - last).nanoseconds < locateInterval) return emptyList()
        if (last != null) locateInterval = (locateInterval * 2).coerceAtMost(MAX_LOCATE_INTERVAL) // PC off: search less
        lastLocateNanos = now
        val ak = Base64Url.tryDecode(agent.ak) ?: return emptyList()
        val tags = try { DiscoveryTag.candidates(ak, currentEpochMillis()) } finally { ak.fill(0) }
        val found = try {
            loc.locate(tags, LOCATE_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        // Same rule as the pairing QR: only LAN addresses (the agent accepts LAN clients only, §3) — and
        // never this phone itself: a fake announcement must not make us probe our own local ports
        return found.filter {
            PairingLink.isLanAddress(it.host) && it.port in 1..65535 && (allowLoopbackCandidates || !isLoopback(it.host))
        }
    }

    /** Tests only: the interop tests run the agent on 127.0.0.1. */
    internal var allowLoopbackCandidates: Boolean = false

    private fun isLoopback(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        return h.startsWith("127.") || h == "::1"
    }

    public companion object {
        /**
         * How often a failing link may search the LAN at first (each search browses for [LOCATE_TIMEOUT]);
         * doubles after every search up to [MAX_LOCATE_INTERVAL], back to this once a session opens.
         */
        public val LOCATE_INTERVAL: Duration = 20.seconds
        public val MAX_LOCATE_INTERVAL: Duration = 5.minutes
        public val LOCATE_TIMEOUT: Duration = 5.seconds
        private const val MAX_CANDIDATES = 8

        /** §10.3: the relay is dialled when the LAN gave no session within this time. */
        public val RELAY_AFTER: Duration = 2.seconds
        /** After the relay answered 404 for today's, tomorrow's and yesterday's route: wait, doubling. */
        public val RELAY_BACKOFF: Duration = 20.seconds
        public val MAX_RELAY_BACKOFF: Duration = 5.minutes
    }

    // ---------------- the relay (§10.3) ----------------

    private fun relayAllowed(): Boolean =
        relay != null && agent.remoteAccess && elapsedRealtimeNanos() >= relayRetryAtNanos

    private fun remoteAccessFrom(o: JsonObject?, missingIsOff: Boolean = false) {
        val v = (o?.get("remoteAccess") as? JsonPrimitive)?.booleanOrNull ?: if (missingIsOff) false else return
        setRemoteAccess(v)
    }

    private fun setRemoteAccess(value: Boolean) {
        if (agent.remoteAccess == value) return
        agent = agent.copy(remoteAccess = value)
        if (value) relayRetryAtNanos = 0
        try { onRemoteAccess(value) } catch (e: Exception) { /* storing is the app's business */ }
    }

    private class Attempt(val viaRelay: Boolean, val pending: HubSession.PendingSession?, val error: Exception?)

    /**
     * §10.3: LAN first, the relay after [RELAY_AFTER] (at once off Wi-Fi, or as soon as the LAN failed);
     * the first handshake that completes gets `ready`, the other is closed without it.
     */
    private suspend fun openRacing(target: PairedAgent, parent: Job?): Pair<HubSession, Boolean> {
        val path = relay!!
        val won: Attempt = coroutineScope {
            val results = Channel<Attempt>(Channel.UNLIMITED)
            fun attempt(viaRelay: Boolean) = launch {
                val a = try {
                    val p = if (viaRelay) relayHandshake(path, target)
                        else HubSession.handshake(connector.connect(target.host, target.port, "/v2"), target, hubKey)
                    Attempt(viaRelay, p, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Attempt(viaRelay, null, e)
                }
                if (results.trySend(a).isFailure) a.pending?.abandon()
            }
            val lan = attempt(viaRelay = false)
            var relayJob: Job? = if (!path.onLocalNetwork()) attempt(viaRelay = true) else null
            var running = if (relayJob != null) 2 else 1
            var winner: Attempt? = null
            var lanError: Exception? = null
            var relayError: Exception? = null
            while (running > 0 && winner == null) {
                val next = if (relayJob == null) withTimeoutOrNull(RELAY_AFTER) { results.receive() } else results.receive()
                if (next == null) { relayJob = attempt(viaRelay = true); running++; continue }
                running--
                if (next.pending != null) { winner = next; break }
                if (next.viaRelay) relayError = next.error
                else {
                    lanError = next.error
                    if (relayJob == null) { relayJob = attempt(viaRelay = true); running++ } // no need to wait any longer
                }
            }
            lan.cancel(); relayJob?.cancel()
            lan.join(); relayJob?.join()
            results.close()
            while (true) {
                val late = results.tryReceive().getOrNull() ?: break
                if (late !== winner) late.pending?.abandon()
            }
            winner ?: throw pickError(lanError, relayError)
        }
        return won.pending!!.confirm(appName, parent) to won.viaRelay
    }

    /** What the status should say when both paths failed: the PC's own answer beats "unreachable". */
    private fun pickError(lan: Exception?, relay: Exception?): Exception {
        val all = listOfNotNull(lan, relay)
        return all.firstOrNull { it is HubProtocolException }
            ?: all.firstOrNull { it is SessionClosedException }
            ?: lan ?: relay ?: IllegalStateException("no path")
    }

    /**
     * Dials today's route, then tomorrow's and yesterday's (a PC clock about a day off, §10.3) while the relay
     * answers 404, and runs the handshake on the first that joins. When all are refused, the relay waits
     * ([RELAY_BACKOFF], doubling); a joined route resets that.
     */
    private suspend fun relayHandshake(path: RelayPath, target: PairedAgent): HubSession.PendingSession {
        val ak = Base64Url.tryDecode(target.ak) ?: throw IllegalStateException("no ak")
        val routes = try { RouteToken.forNow(ak, currentEpochMillis()) } finally { ak.fill(0) }
        var last: Exception? = null
        for (route in routes) {
            val socket = try {
                path.connector.connect(path.url.trimEnd('/') + "/v2/hub/" + route)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                continue
            }
            relayBackoff = RELAY_BACKOFF
            relayRetryAtNanos = 0
            return HubSession.handshake(socket, target, hubKey)
        }
        relayRetryAtNanos = elapsedRealtimeNanos() + relayBackoff.inWholeNanoseconds
        relayBackoff = (relayBackoff * 2).coerceAtMost(MAX_RELAY_BACKOFF)
        throw last ?: IllegalStateException("relay unreachable")
    }

    private suspend fun waitForChange(from: LinkStatus, timeout: Duration) {
        if (timeout > Duration.ZERO) withTimeoutOrNull(timeout) { status.first { it !== from } }
    }

    private suspend fun runLoop() {
        try {
            loop()
        } finally {
            // Cancelled (stop() or the scope): never leave a stale Online behind
            if (_status.value !is LinkStatus.Revoked) _status.value = LinkStatus.Stopped
        }
    }

    private suspend fun loop() {
        val myJob = kotlin.coroutines.coroutineContext[Job]
        var backoff = 1.seconds
        val candidates = ArrayDeque<AgentAddress>()
        var reason = OfflineReason.Unreachable // of the stored address: a failed candidate doesn't change it
        while (scope.isActive) {
            val trying = candidates.removeFirstOrNull()
            if (trying == null) _status.value = LinkStatus.Connecting
            var bye: String? = null
            var uptime = Duration.ZERO
            var opened = false
            val target = trying?.let { agent.copy(host = it.host, port = it.port) } ?: agent
            try {
                val useRelay = trying == null && relayAllowed()
                val (s, viaRelay) = if (useRelay) openRacing(target, myJob)
                    else HubSession.open(connector, target, hubKey, appName, parent = myJob) to false
                opened = true
                locateInterval = LOCATE_INTERVAL
                val openedAt = elapsedRealtimeNanos()
                if (target !== agent) {
                    candidates.clear()
                    agent = target // the pinned key answered there: adopt the new address
                    try { onMoved(target) } catch (e: Exception) { /* storing is the app's business */ }
                }
                session = s
                try {
                    coroutineScope {
                        // State and events follow the session's own ordered stream
                        launch { s.state.collect { st -> if (st != null) _state.value = st } }
                        launch {
                            for (ev in s.events) {
                                if (ev.string("t") == "ev.settings") remoteAccessFrom(ev["settings"] as? JsonObject)
                                _events.emit(ev)
                            }
                        }
                        remoteAccessFrom(s.hello, missingIsOff = true)
                        _status.value = LinkStatus.Online(s.hello, viaRelay)
                        bye = s.closed.await()
                        coroutineContext[Job]!!.cancelChildren()
                    }
                } finally {
                    uptime = (elapsedRealtimeNanos() - openedAt).nanoseconds
                    session = null
                    withContext(NonCancellable) { s.close() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionClosedException) {
                bye = e.byeCode ?: "dropped"
            } catch (e: HubProtocolException) {
                bye = "wrong_device"
            } catch (e: Exception) {
                bye = "unreachable"
            }

            if (bye == "revoked") { _status.value = LinkStatus.Revoked; return }
            if (bye == "remote_off") setRemoteAccess(false) // §10.2: no more relay dialling until a hello says on
            if (trying == null || opened) {
                reason = when (bye) {
                    "not_accepted" -> OfflineReason.Refused
                    "wrong_device" -> OfflineReason.WrongDevice
                    else -> OfflineReason.Unreachable
                }
            }
            _status.value = LinkStatus.Offline(reason)
            if (!opened) {
                // Nobody (or someone else: another PC refuses our msg 1, §4.1) is at the address: maybe the PC
                // got a new one. A session that opened and then ended means the PC is there: no search.
                if (candidates.isNotEmpty()) continue // the next address found by the last search
                candidates += locate().filter { it.host != agent.host || it.port != agent.port }.distinct().take(MAX_CANDIDATES)
                if (candidates.isNotEmpty()) continue // try them right away
            }
            // Back-off grows unless the session had been up for a while (busy / protocol_error loops don't spin)
            if (uptime >= 30.seconds) backoff = 1.seconds
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30.seconds)
        }
    }
}

private fun Job.cancelChildren() = children.forEach { it.cancel() }
