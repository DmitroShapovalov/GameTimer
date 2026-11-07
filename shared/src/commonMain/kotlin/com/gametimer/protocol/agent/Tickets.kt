package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base64Url
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.selects.select

/**
 * One WebSocket connection as the host sees it (§3): one binary message = one Noise message.
 * Mirrors the C# `IFrameTransport`.
 */
public interface FrameTransport {
    /**
     * The next binary message, or null once the peer closed the connection. A text message, or a message
     * longer than 65535 bytes (refuse it before buffering it whole), throws [ProtocolViolation]. Cancelling a
     * pending receive (the host does, for its timeouts) must leave the connection usable for sending; a message
     * lost to that cancellation doesn't matter (the host then only sends, or closes).
     */
    public suspend fun receive(): ByteArray?

    /** Sends one binary message. Cancelling it may abort the connection. */
    public suspend fun send(frame: ByteArray)

    /**
     * Closes the connection; idempotent, callable alongside a pending send or receive, and must make them
     * return or throw promptly (close with a short timeout, then abort).
     */
    public suspend fun close()
}

/** A reserved handshake slot (§3, §10.4). [release] gives the slot back; safe to call twice. */
public class HandshakeTicket internal constructor(
    private val host: AgentHost,
    /** The remote IP, or `relay:<hub id>` for a relayed stream (its failure-counter key). */
    public val remoteIp: String,
    /** For a relayed stream: the hub whose route the relay opened it with; its key must match (§10.4). */
    public val relayHubId: String? = null,
) {
    private val gate = newHostLock()
    private var released = false
    private var proven = false

    /** Completes (cancelled) when a newer relayed stream took this slot: the handshake is abandoned. */
    internal val evicted: CompletableJob = Job()

    /** True once a newer relayed stream took this slot. */
    public val isEvicted: Boolean get() = evicted.isCancelled

    internal fun evict() {
        evicted.cancel()
    }

    internal fun markProven() {
        gate.withLock { proven = true }
    }

    /** The proven-but-unconfirmed count of its hub goes down once (confirmed or ended). */
    internal fun unprove() {
        val was = gate.withLock { proven.also { proven = false } }
        if (was) host.unprove(relayHubId!!)
    }

    /** Gives the slot back. Call it if the connection is not handed to [AgentHost.run]. */
    public fun release() {
        val first = gate.withLock { (!released).also { released = true } }
        if (first) host.release(this)
    }

    /** Called once the handshake is over either way. */
    internal fun finish() {
        release()
        unprove()
        evicted.cancel() // no-op if evicted already; detaches nothing else
    }
}

/** An open pairing window (§8.2): the one-time secret and what goes into the QR. */
public class PairingOffer internal constructor(
    internal val secret: ByteArray,
    /** Epoch milliseconds by the host's clock. */
    public val expiresAt: Long,
    agentPublicKey: ByteArray,
    /** The device name shown to the parent (`n` in the QR). */
    public val deviceName: String,
) {
    private val key = agentPublicKey.copyOf()

    public val agentPublicKey: ByteArray get() = key.copyOf()

    /** The hub key that first reached pair.hello with this secret (base64url), if any. Under the host lock. */
    internal var usedBy: String? = null
    internal var attempts: Int = 0
    /** The attempt currently waiting for the owner's code (one per phone key). Under the host lock. */
    internal var activeAttempt: CompletableJob? = null

    /** Cancelled when the offer is replaced, closed or aborted by the two-phones rule. */
    internal val aborted: CompletableJob = Job()

    /** URL fragment for the QR: `v=2&k=…&s=…&n=…` (without the leading `#`). */
    public val qrFragment: String
        get() = "v=2&k=${Base64Url.encode(key)}&s=${Base64Url.encode(secret)}&n=${percentEncode(deviceName)}"

    internal fun abort() {
        aborted.cancel()
    }

    private companion object {
        /** Like .NET's Uri.EscapeDataString: everything but RFC 3986 unreserved characters, as UTF-8 %XX. */
        fun percentEncode(s: String): String {
            val sb = StringBuilder()
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') sb.append(ch)
                else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
            return sb.toString()
        }

        const val HEX = "0123456789ABCDEF"
    }
}

/**
 * Identifies one hub request while it runs (§5). The backend passes it to [AgentHost.publish] for events its
 * request caused, so the requester (which gets the result instead) is left out. Explicit on purpose: nothing
 * flows implicitly into timers or coroutines the backend starts.
 */
public class RequestOrigin internal constructor(internal val session: Session) {
    private val gate = newHostLock()
    private var closed = false
    private var byeAfter: String? = null

    /** The requester itself must be closed after its reply (self-revoke). False once closed. */
    internal fun trySetByeAfter(code: String): Boolean = gate.withLock {
        if (closed) return false
        byeAfter = code
        true
    }

    /** Ends the request: returns a bye the requester must get after its reply, if any. */
    internal fun close(): String? = gate.withLock {
        closed = true
        byeAfter
    }
}

/** One relay route of a hub (§10.1): the claim to register, its public route (base32), the hub. */
public class RelayRoute(public val claim: ByteArray, public val route: String, public val hubId: String)

/**
 * Runs [block] (in [scope]) until it finishes or one of [signals] is cancelled; then returns null and cancels
 * [block]. With [awaitCancelled] the cancelled work is waited for, so none of its side effects (a counted
 * attempt, a proven relay stream) can happen after this returns — for transport and crypto work, which
 * honours cancellation. Without it the caller is not held up (a dialog that ignores cancellation). The caller's
 * own cancellation cancels [block] too. Failures of [block] are rethrown.
 */
internal suspend fun <T> untilSignalled(
    scope: CoroutineScope, signals: List<Job>, awaitCancelled: Boolean = true, block: suspend () -> T,
): T? {
    // A failure is carried as a value: it must reach the caller, not cancel [scope]
    val work = scope.async {
        try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    try {
        return select {
            work.onAwait { it.getOrThrow() }
            for (s in signals) s.onJoin { null }
        }
    } finally {
        if (!work.isCompleted) {
            work.cancel()
            // bounded: a transport that ignores cancellation (against its contract) must not hold the slot forever
            if (awaitCancelled) withContext(NonCancellable) { withTimeoutOrNull(2.seconds) { work.join() } }
        }
    }
}
