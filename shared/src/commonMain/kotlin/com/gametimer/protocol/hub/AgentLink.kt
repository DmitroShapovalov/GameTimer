package com.gametimer.protocol.hub

import com.gametimer.protocol.noise.KeyPair
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The connection to one agent as the UI sees it. */
public sealed interface LinkStatus {
    public data object Connecting : LinkStatus

    public data class Online(val hello: JsonObject) : LinkStatus

    /** Not reachable right now; retrying with back-off. [refused]: the PC answered but closed (not accepted). */
    public data class Offline(val refused: Boolean) : LinkStatus

    /** This phone was removed on the PC or by an admin (bye revoked): pair again. */
    public data object Revoked : LinkStatus

    public data object Stopped : LinkStatus
}

/**
 * The agent's §7.1 state with the moment it arrived. The countdown runs on this device's monotonic
 * clock from [receivedAt] (§7.1: the PC's clock may be wrong), never on `targetTime`.
 */
public class AgentState(public val json: JsonObject, public val receivedAt: TimeMark) {
    public val timer: JsonObject? get() = json["timer"] as? JsonObject
    public val isRunning: Boolean get() = timer?.string("state") == "running"

    /** Seconds left now, counted down locally; 0 when no timer runs. */
    public fun remainingSecondsNow(): Int {
        if (!isRunning) return 0
        val atReceive = (timer?.get("remainingSeconds") as? JsonPrimitive)?.intOrNull ?: return 0
        return (atReceive - receivedAt.elapsedNow().inWholeSeconds.toInt()).coerceAtLeast(0)
    }
}

/** A request's outcome for the UI. */
public sealed interface RequestOutcome {
    public data class Ok(val reply: JsonObject) : RequestOutcome

    /** The agent refused it: an error code from §7.2 (e.g. not_running, forbidden). */
    public data class Refused(val code: String) : RequestOutcome

    /** No answer within the resend window (§5: the hub stops after 5 minutes and asks the user). */
    public data object GaveUp : RequestOutcome
}

/**
 * Keeps one agent connected: reconnects with back-off, tracks the latest state, and resends unanswered
 * state-changing requests with the same `rid` after a reconnect (§5, §12).
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
    private var job: Job? = null
    private var session: HubSession? = null

    public val status: StateFlow<LinkStatus> = _status.asStateFlow()
    public val state: StateFlow<AgentState?> = _state.asStateFlow()
    public val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    public fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    public suspend fun stop() {
        job?.cancel()
        job = null
        session?.close()
        session = null
        _status.value = LinkStatus.Stopped
    }

    /**
     * Sends a request. State-changing requests ([changesState]) get one rid and are resent with it
     * after reconnects until a reply arrives or [resendWindow] (5 min, §5) is over.
     */
    public suspend fun send(type: String, fields: JsonObject? = null, changesState: Boolean = true,
                            resendWindow: Duration = 5.minutes): RequestOutcome {
        val rid = if (changesState) newRid() else null
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < resendWindow) {
            val online = withTimeoutOrNull(resendWindow - started.elapsedNow()) {
                status.filterIsInstance<LinkStatus.Online>().first()
            } ?: break
            val current = session ?: continue
            val reply = try { current.request(type, fields, rid) } catch (e: SessionClosedException) {
                if (!changesState) return RequestOutcome.GaveUp
                continue // reconnect, then resend with the same rid
            }
            check(online.hello.isNotEmpty())
            return if (reply.type() == "error") {
                RequestOutcome.Refused(reply.string("code") ?: "unknown")
            } else {
                (reply["state"] as? JsonObject)?.let { _state.value = AgentState(it, TimeSource.Monotonic.markNow()) }
                RequestOutcome.Ok(reply)
            }
        }
        return RequestOutcome.GaveUp
    }

    private suspend fun runLoop() {
        var backoff = 1.seconds
        while (scope.isActive) {
            _status.value = LinkStatus.Connecting
            val s = try {
                HubSession.open(connector, agent, hubKey, appName)
            } catch (e: SessionClosedException) {
                if (e.byeCode == "revoked") { _status.value = LinkStatus.Revoked; return }
                _status.value = LinkStatus.Offline(refused = e.byeCode == "not_accepted")
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = LinkStatus.Offline(refused = false)
                null
            }

            if (s != null) {
                backoff = 1.seconds
                session = s
                (s.hello["state"] as? JsonObject)?.let { _state.value = AgentState(it, TimeSource.Monotonic.markNow()) }
                _status.value = LinkStatus.Online(s.hello)
                val collector = scope.launch {
                    s.events.collect { ev ->
                        if (ev.type() == "ev.state" || ev.type() == "ev.tick") {
                            (ev["state"] as? JsonObject)?.let { _state.value = AgentState(it, TimeSource.Monotonic.markNow()) }
                        }
                        _events.emit(ev)
                    }
                }
                val bye = s.closed.await()
                collector.cancel()
                session = null
                when (bye) {
                    "revoked" -> { _status.value = LinkStatus.Revoked; return }
                    "expired", "busy", "shutdown" -> { _status.value = LinkStatus.Offline(refused = false); delay(1.seconds); continue }
                    else -> _status.value = LinkStatus.Offline(refused = false)
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30.seconds)
        }
    }
}
