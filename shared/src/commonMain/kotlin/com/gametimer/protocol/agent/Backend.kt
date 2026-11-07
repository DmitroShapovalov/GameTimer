package com.gametimer.protocol.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What the agent says about itself in `hello.agent` and `pair.result.agent` (§6). */
public data class AgentInfo(val name: String, val platform: String, val version: String)

/**
 * Who sent a request: the authenticated hub and its role (§8.1), and [origin] to pass to [AgentHost.publish]
 * for events this request causes (§5 ordering).
 */
public class RequestContext(public val hubId: String, public val hubName: String, public val role: PeerRole, public val origin: RequestOrigin)

/** A request's outcome: a `result` with fields, or an `error` with a code (§5). */
public class Reply private constructor(public val isError: Boolean, public val code: String?, public val fields: JsonObject) {
    public companion object {
        /** `{ "t": "result", "re": id, ...fields }` */
        public fun ok(fields: JsonObject = JsonObject(emptyMap())): Reply = Reply(false, null, fields)

        /** `{ "t": "error", "re": id, "code": code, ...extra }` (e.g. `value` for `conflict`) */
        public fun error(code: String, extra: JsonObject? = null): Reply = Reply(true, code, extra ?: JsonObject(emptyMap()))
    }
}

/**
 * The platform side of an agent (the phone: timer, schedule, settings, history, apps). The host owns sessions,
 * pairing, peers and idempotency; the backend only executes requests of its own capabilities and publishes
 * events through [AgentHost.publish] — with [RequestContext.origin] for an event that is the direct effect of a
 * request, without one for anything else (ticks, expiry, changes made on the device).
 * [info] and [capabilities] must not change. [handle] must not throw after it changed state: a thrown request is
 * answered `internal`, which is not cached, so the hub's retry would apply the change twice. A timeout inside [handle] (a
 * `withTimeout` that fires) counts as throwing: let it fire only before changing state.
 */
public interface AgentBackend {
    public val info: AgentInfo

    /** Capabilities this backend implements (§6), e.g. timer, schedule, settings, history. The host adds `peers` and `push`. */
    public val capabilities: Collection<String>

    /** The current §7.1 state object, for `hello`. */
    public suspend fun state(): JsonObject

    /**
     * Executes one request of a capability this backend announced. [message] is the whole request object (with
     * `t`, `id`, `rid`). Validation errors are replies, not exceptions.
     */
    public suspend fun handle(type: String, message: JsonObject, context: RequestContext): Reply
}

/** The device owner's side of pairing (§8.2): asks for the code shown on the parent's phone. */
public interface PairingUi {
    /**
     * Shows "'[hubName]' wants to connect. Enter the code shown on that phone" and returns the code typed, or
     * null if cancelled. The call is cancelled on the 60-second timeout or when the attempt is aborted.
     */
    public suspend fun askForCode(hubName: String): String?

    /** The pairing was aborted without the owner's doing (two phones used one QR). */
    public fun pairingAborted(reason: PairingAbortReason)
}

public enum class PairingAbortReason {
    /** Two different phones reached pair.hello with the same secret: the QR may have been photographed. */
    TwoPhones,
}

/** Role of a hub on this agent (§8.1). On the wire and on disk: "admin" / "parent". */
@Serializable
public enum class PeerRole {
    @SerialName("admin") Admin,
    @SerialName("parent") Parent,
}

/** A paired hub (parent app) as the agent stores it (§8). Times are epoch milliseconds. */
@Serializable
public data class Peer(
    val id: String,
    /** The hub's static X25519 public key, base64url. */
    val publicKey: String,
    val name: String,
    val role: PeerRole,
    val pairedAt: Long,
    val lastSeen: Long? = null,
    /** This hub's own secret `ak` (§4.4), base64url. */
    val ak: String,
    /** The hub's push token (§10.6), set by `push.set`; used only to wake it through the relay. */
    val pushToken: String? = null,
    /** What may wake this hub (§10.6): history types and `peerAdded`; null = all of them. */
    val pushEvents: List<String>? = null,
) {
    /** Never prints [ak] or [pushToken] (they would end up in logs). */
    override fun toString(): String = "Peer(id=$id, name=$name, role=$role)"
}

/** One idempotency entry (§5): rid → (body hash, stored reply without `re`). */
@Serializable
public data class RidEntry(val rid: String, val bodyHash: String, val reply: String, val at: Long)

/** Everything the agent persists for protocol v2. The host saves it through [AgentStore]. */
@Serializable
public data class AgentStateData(
    /** The agent's static X25519 private key, base64url. Never leaves the device. */
    val privateKey: String,
    val peers: List<Peer> = emptyList(),
    /** Idempotency cache per hub id. */
    val rids: Map<String, List<RidEntry>> = emptyMap(),
    /** Networks a hub had a session on (opaque ids the app chooses), oldest first. */
    val knownNetworks: List<String> = emptyList(),
)

/** Persistence for [AgentStateData] (Android: encrypted with a Keystore key). Called outside the host's lock. */
public interface AgentStore {
    /**
     * The stored state, or null if nothing was stored yet (then the host creates a new identity). Throw if the
     * state exists but can't be read right now: the host must not start then (it would replace every pairing).
     */
    public fun load(): AgentStateData?
    public fun save(state: AgentStateData)
}

/** In-memory store for tests and tools. */
public class MemoryAgentStore : AgentStore {
    public var state: AgentStateData? = null
    public var saves: Int = 0
        private set
    override fun load(): AgentStateData? = state
    override fun save(state: AgentStateData) { this.state = state; saves++ }
}

/** A clock for the host (tests can move it): epoch milliseconds. */
public fun interface AgentClock {
    public fun nowMillis(): Long
}
