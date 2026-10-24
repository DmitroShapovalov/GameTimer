package com.gametimer.protocol.hub

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
import kotlinx.serialization.json.intOrNull

/** The connection to one agent as the UI sees it. */
public sealed interface LinkStatus {
    public data object Connecting : LinkStatus

    public data class Online(val hello: JsonObject) : LinkStatus

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
 * Keeps one agent connected: reconnects with back-off, mirrors the latest state, and resends unanswered
 * state-changing requests with the same `rid` after a reconnect (§5, §12). Runs in [scope]; cancelling
 * it, or [stop], closes the session.
 */
public class AgentLink(
    private val agent: PairedAgent,
    private val hubKey: KeyPair,
    private val connector: Connector,
    private val appName: String,
    private val scope: CoroutineScope,
) {
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
        while (scope.isActive) {
            _status.value = LinkStatus.Connecting
            var bye: String? = null
            var uptime = Duration.ZERO
            try {
                val s = HubSession.open(connector, agent, hubKey, appName, parent = myJob)
                val openedAt = elapsedRealtimeNanos()
                session = s
                try {
                    coroutineScope {
                        // State and events follow the session's own ordered stream
                        launch { s.state.collect { st -> if (st != null) _state.value = st } }
                        launch { for (ev in s.events) _events.emit(ev) }
                        _status.value = LinkStatus.Online(s.hello)
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
            _status.value = LinkStatus.Offline(
                when (bye) {
                    "not_accepted" -> OfflineReason.Refused
                    "wrong_device" -> OfflineReason.WrongDevice
                    else -> OfflineReason.Unreachable
                },
            )
            // Back-off grows unless the session had been up for a while (busy / protocol_error loops don't spin)
            if (uptime >= 30.seconds) backoff = 1.seconds
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30.seconds)
        }
    }
}

private fun Job.cancelChildren() = children.forEach { it.cancel() }
