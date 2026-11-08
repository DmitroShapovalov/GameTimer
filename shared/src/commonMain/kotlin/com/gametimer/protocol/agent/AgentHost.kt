package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base32
import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.hub.currentEpochMillis
import com.gametimer.protocol.identity.DiscoveryTag
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.RouteToken
import com.gametimer.protocol.noise.KeyPair
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The agent side of protocol v2 (docs/protocol.md), independent of the web server, the relay client and the
 * UI: handshakes and the `ready` rule, sessions and their limits, pairing, peers and roles, the idempotency
 * cache, and event fan-out. A Kotlin port of the C# `AgentHost` (src/GameTimer.Protocol/Agent), same
 * behaviour and limits; the phone agent feeds it connections and implements [AgentBackend] and [PairingUi].
 *
 * Thread-safe: shared state is guarded by one lock; sends go through per-session outboxes. [Sodium] must be
 * initialised before the host is created.
 *
 * @param scope runs the per-session send loops and timers; cancelled by [close] only if the host created it.
 * @param clock wall-clock time (pairing window, rid lifetime, `lastSeen`, UTC dates).
 * @param timeSource monotonic time for the session lifetime.
 */
public class AgentHost(
    private val store: AgentStore,
    private val backend: AgentBackend,
    private val pairingUi: PairingUi,
    private val clock: AgentClock = AgentClock { currentEpochMillis() },
    scope: CoroutineScope? = null,
    internal val timeSource: TimeSource = TimeSource.Monotonic,
) {
    public companion object {
        public const val PROTOCOL_MAJOR: Int = 2
        /**
         * 1: timer.end, timer.setEnd, …; 2: timer.setEnd inSeconds; 3: state.request, timer.answerRequest (as the C#
         * agent); 4: timer state `paused` and the `apps` capability (the phone agent, §6 / §7); 5: `state.device` and
         * the phone's tamper events (§7.4).
         */
        public const val PROTOCOL_MINOR: Int = 5

        // §3 limits
        public val HANDSHAKE_TIMEOUT: Duration = 5.seconds
        public val IDLE_TIMEOUT: Duration = 90.seconds
        public val SESSION_LIFETIME: Duration = 24.hours
        public const val MAX_PENDING_HANDSHAKES: Int = 8
        public const val MAX_PENDING_PER_IP: Int = 2
        public const val MAX_SESSIONS_PER_HUB: Int = 2
        public const val MAX_SESSIONS: Int = 16
        public const val MAX_FAILED_HANDSHAKES_PER_MINUTE: Int = 10

        // §8.2 pairing
        public val PAIRING_WINDOW: Duration = 2.minutes
        public val PAIRING_CONFIRM_TIMEOUT: Duration = 60.seconds
        public const val MAX_PAIRING_ATTEMPTS: Int = 3
        public const val MAX_HUB_NAME_LENGTH: Int = 64

        // §5 idempotency
        public val RID_LIFETIME: Duration = 10.minutes
        public const val MAX_RIDS_PER_HUB: Int = 256

        /** At most this many route claims on one relay socket (§10.4): 21 hubs × 3 days. */
        public const val MAX_ROUTE_TOKENS: Int = 63

        /** Relay streams waiting for a handshake: their own pool, never LAN slots (§10.4). */
        public const val MAX_RELAY_PENDING: Int = 4
        public const val MAX_RELAY_PENDING_PER_HUB: Int = 2

        /** A relayed stream must bring msg 1 this soon after `open` (§10.4). */
        public val RELAY_FIRST_MESSAGE_TIMEOUT: Duration = 2.seconds

        public const val MAX_KNOWN_NETWORKS: Int = 16
        public const val MAX_PUSH_TOKEN: Int = 4096

        internal const val MAX_TRACKED_IPS = 1024
        internal const val MINUTE_MILLIS = 60_000L
        internal const val DAY_MILLIS = 86_400_000L

        internal val PROLOGUE: ByteArray = "GameTimer/2".encodeToByteArray()

        /** A push token as §10.6 allows it: 1..4096 printable ASCII characters. */
        public fun isPushToken(token: String?): Boolean =
            token != null && token.length in 1..MAX_PUSH_TOKEN && token.all { it in '!'..'~' }

        /** History types that wake even when caused automatically (source system); the rest only when someone acted. */
        private val WAKE_ALWAYS = setOf("expired", "closed", "tampered")

        internal fun roleName(role: PeerRole): String = if (role == PeerRole.Admin) "admin" else "parent"

        internal fun randomBytes(n: Int): ByteArray = LibsodiumRandom.buf(n).asByteArray()
    }

    internal val lock = newHostLock()
    private val ownsScope = scope == null
    internal val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- state under [lock] ----
    private val privateKeyText: String
    internal val peers: MutableList<Peer>
    internal val rids: MutableMap<String, MutableList<RidEntry>>
    private val knownNetworks: MutableList<String>
    internal val sessions: MutableList<Session> = mutableListOf()
    private val pendingPerIp = mutableMapOf<String, Int>()
    private var pendingTotal = 0
    private val failures = mutableMapOf<String, ArrayDeque<Long>>()
    internal var pairing: PairingOffer? = null
    internal var closed: Boolean = false
    private val relayPending = mutableListOf<HandshakeTicket>()
    /** Relayed streams whose msg 1 named the right key but which aren't confirmed yet, per hub (≤ 2). */
    private val relayProven = mutableMapOf<String, Int>()
    /** Serialises rid-carrying requests per hub, so two sessions of one hub can't run one rid twice. */
    internal val ridLocks = mutableMapOf<String, Mutex>()

    internal val key: KeyPair

    /** One fan-out at a time, so every session sees events in the same order (enqueue never blocks). */
    internal val publishLock = newHostLock()

    /**
     * A new identity was created but could not be saved: it lives only until the app exits, and the next
     * start creates another one. Null when the identity is in the store.
     */
    public val initialSaveError: Exception?

    init {
        // A load that throws propagates: the store is unavailable (e.g. before the first unlock), and creating a
        // new identity would overwrite every pairing. Null means "nothing stored yet".
        val loaded = store.load()
        val priv = loaded?.let { Base64Url.tryDecode(it.privateKey) }
        if (loaded != null && priv != null && priv.size == 32) {
            key = KeyPair.fromPrivateKey(priv)
            privateKeyText = loaded.privateKey
            peers = loaded.peers.toMutableList()
            rids = loaded.rids.mapValues { it.value.toMutableList() }.toMutableMap()
            knownNetworks = loaded.knownNetworks.toMutableList()
            initialSaveError = null
        } else {
            // First run (or an unreadable store): a new identity. Existing hubs must pair again.
            key = KeyPair.generate()
            privateKeyText = Base64Url.encode(key.privateKey)
            peers = mutableListOf()
            rids = mutableMapOf()
            knownNetworks = mutableListOf()
            initialSaveError = try { store.save(snapshotLocked()); null } catch (e: Exception) { e }
        }
        priv?.fill(0)
    }

    /** The agent's static public key (goes into the pairing QR as `k`). */
    public val agentPublicKey: ByteArray get() = key.publicKey

    public val agentId: String = Ids.agentId(key.publicKey)

    internal val capabilities: List<String>
        get() = backend.capabilities.filter { it != "peers" && it != "push" } + listOf("peers", "push")

    // ---------------- app-side API ----------------

    /** Peers were added, revoked or changed role (any thread). */
    public var onPeersChanged: (() -> Unit)? = null

    /** A hub completed pairing (any thread), also when a known hub paired again. */
    public var onPaired: ((Peer) -> Unit)? = null

    /** Persisting the state failed. The change stays in memory; the app should log it. */
    public var onStoreError: ((Exception) -> Unit)? = null

    /** A hub's session started (hello sent), with its remote address. The app may remember the network. */
    public var onSessionStarted: ((String) -> Unit)? = null

    /**
     * Something a parent may want to hear about happened (§10.6): the push tokens of the hubs that want that
     * kind of event and have no live session right now, while "Control from anywhere" is on.
     */
    public var onWakeNeeded: ((List<String>) -> Unit)? = null

    /** The relay client's own hooks (next to the app's), set while it runs. */
    @Volatile internal var relayPeersChanged: (() -> Unit)? = null
    @Volatile internal var relayWake: ((List<String>) -> Unit)? = null

    /**
     * A paired hub's clock from its `ready` (minor 5, epoch ms), at each session: the phone agent checks its own
     * clock against it (§7.4). Called on the session's coroutine; must not block.
     */
    @Volatile public var onHubTime: ((Long) -> Unit)? = null

    /** The paired hubs (without their push tokens, which never leave the host). */
    public val peerList: List<Peer> get() = lock.withLock { peers.map { it.forApp() } }

    /**
     * "Control from anywhere" (§10.2) is on: reported to hubs in `hello`. Set by the app; the relay connection
     * itself is the app's business.
     */
    @Volatile public var remoteAccess: Boolean = false

    /** Now by this host's clock, epoch milliseconds. */
    public val now: Long get() = clock.nowMillis()

    /** Today's UTC date by this host's clock, `YYYY-MM-DD`. */
    public val utcToday: String get() = DiscoveryTag.utcDate(clock.nowMillis())

    /**
     * The discovery tags (§9) for [utcDate] (`YYYY-MM-DD`): one per paired hub, in pairing order.
     * They change when peers change and at the UTC date change.
     */
    public fun discoveryTags(utcDate: String): List<String> = lock.withLock {
        peers.mapNotNull { p ->
            val ak = Base64Url.tryDecode(p.ak)?.takeIf { it.size == 32 } ?: return@mapNotNull null
            DiscoveryTag.derive(ak, utcDate).also { ak.fill(0) }
        }
    }

    /** The relay route claims (§10.1) around [utcToday]; see [relayRoutes]. */
    public fun routeClaims(utcToday: String): List<ByteArray> = relayRoutes(utcToday).map { it.claim }

    /**
     * The relay routes to register around [utcToday] (`YYYY-MM-DD`): yesterday's, today's and tomorrow's for
     * each paired hub. Hubs used most recently come first (last seen, else paired); beyond [MAX_ROUTE_TOKENS]
     * the least recently used hubs are left out, whole. The order of the result is shuffled (§10.5).
     */
    public fun relayRoutes(utcToday: String): List<RelayRoute> {
        val todayMillis = parseUtcDate(utcToday)
        return lock.withLock {
            val routes = mutableListOf<RelayRoute>()
            val byUse = peers.withIndex()
                .sortedWith(compareByDescending<IndexedValue<Peer>> { it.value.lastSeen ?: it.value.pairedAt }.thenByDescending { it.index })
                .map { it.value }
            for (p in byUse) {
                if (routes.size + 3 > MAX_ROUTE_TOKENS) break
                val ak = Base64Url.tryDecode(p.ak)?.takeIf { it.size == 32 } ?: continue
                for (d in -1..1) {
                    val claim = RouteToken.claim(ak, DiscoveryTag.utcDate(todayMillis + d * DAY_MILLIS))
                    routes.add(RelayRoute(claim, Base32.encode(RouteToken.route(claim)), p.id))
                }
                ak.fill(0)
            }
            secureShuffle(routes)
            routes
        }
    }

    /**
     * Ends every session that came through the relay with `bye` [code] (§10.2: remote_off) and waits (at most
     * 2 s) until they are out, so the bye isn't lost when the relay connection stops next.
     */
    public suspend fun endRelaySessions(code: String = "remote_off") {
        val relayed = liveSessions().filter { it.relayed }
        relayed.forEach { it.bye(code) }
        withTimeoutOrNull(2.seconds) { relayed.map { it.completion }.joinAll() }
    }

    internal fun raiseSessionStarted(remoteIp: String) {
        try { onSessionStarted?.invoke(remoteIp) } catch (e: Exception) { /* an app handler must not break a session */ }
    }

    /** How many networks are known ([rememberNetwork]). */
    public val knownNetworkCount: Int get() = lock.withLock { knownNetworks.size }

    /** True if a hub had a session on network [id] (an id the app chose). */
    public fun isKnownNetwork(id: String): Boolean = lock.withLock { id in knownNetworks }

    /**
     * Remembers network [id] (most recent last; the oldest is dropped beyond [MAX_KNOWN_NETWORKS]). Returns
     * true if it was new. Persists only when something changed.
     */
    public fun rememberNetwork(id: String): Boolean {
        if (id.isEmpty() || id.length > 128) return false
        val added: Boolean
        lock.withLock {
            val at = knownNetworks.indexOf(id)
            if (at >= 0 && at == knownNetworks.size - 1) return false // already the most recent
            added = at < 0
            if (!added) knownNetworks.removeAt(at)
            knownNetworks.add(id)
            while (knownNetworks.size > MAX_KNOWN_NETWORKS) knownNetworks.removeAt(0)
        }
        persist()
        return added
    }

    /**
     * The device owner revokes a hub (behind the device's own check in the app). Allowed even for the last
     * admin (§8.1: e.g. a lost phone); the next hub paired then becomes admin.
     */
    public fun revokeByOwner(hubId: String): Boolean = revoke(hubId, origin = null, protectLastAdmin = false) == PeerChange.Done

    /** The device owner changes a hub's role (no last-admin protection on the device, §8.1). */
    public fun setRoleByOwner(hubId: String, role: PeerRole): Boolean =
        setRole(hubId, role, origin = null, protectLastAdmin = false) == PeerChange.Done

    /**
     * Opens a pairing window (§8.2): a fresh one-time secret, valid 2 minutes. Replaces any previous offer and
     * aborts attempts still in progress for it. The QR fragment is [PairingOffer.qrFragment].
     */
    public fun startPairing(deviceName: String): PairingOffer {
        val offer = PairingOffer(randomBytes(32), clock.nowMillis() + PAIRING_WINDOW.inWholeMilliseconds, key.publicKey, deviceName)
        val old = lock.withLock { pairing.also { pairing = offer } }
        old?.abort()
        return offer
    }

    /** Closes the pairing window (dialog closed); attempts in progress get `bye denied`. */
    public fun cancelPairing() {
        val old = lock.withLock { pairing.also { pairing = null } }
        old?.abort()
    }

    /**
     * Publishes an event (§7.3) to every live session, at once and in call order; any thread. Pass [causedBy]
     * ([RequestContext.origin]) when the event is the direct effect of a hub request: that requester gets its
     * `result` instead (§5). Events from timers, the device or anything later pass null and reach everyone.
     */
    public fun publish(type: String, body: JsonObject, causedBy: RequestOrigin? = null) {
        require(type.startsWith("ev.")) { "events start with ev." }
        if (type == "ev.history") raiseWake(body["event"] as? JsonObject)
        else if (type == "ev.alert") raiseWake("alert", null, onlyIfAsked = true)
        val message = JsonObject(buildMap { put("t", JsonPrimitive(type)); putAll(body.filterKeys { it != "t" }) })
        publishLock.withLock {
            for (s in liveSessions()) if (s !== causedBy?.session) s.enqueue(message)
        }
    }

    /** A history event (§7.3): wakes the hubs that asked for its type — never the hub that caused it. */
    private fun raiseWake(historyEvent: JsonObject?) {
        val type = historyEvent?.str("type") ?: return
        val source = historyEvent.str("source")
        if (source == "system" && type !in WAKE_ALWAYS) return // automatic starts, resumes, restores
        raiseWake(type, historyEvent.str("hub"))
    }

    private fun raiseWake(kind: String, causedBy: String?, onlyIfAsked: Boolean = false) {
        val handlers = listOfNotNull(onWakeNeeded, relayWake)
        if (!remoteAccess || handlers.isEmpty()) return
        val tokens = pushTokensToWake(kind, causedBy, onlyIfAsked)
        if (tokens.isEmpty()) return
        for (h in handlers) try { h(tokens) } catch (e: Exception) { /* the relay client's / app's business */ }
    }

    private fun pushTokensToWake(kind: String?, causedBy: String?, onlyIfAsked: Boolean = false): List<String> = lock.withLock {
        val live = sessions.map { it.hubId }.toSet()
        peers.filter { p ->
            p.pushToken != null && p.id !in live && p.id != causedBy &&
                (kind == null || (p.pushEvents?.contains(kind) ?: !onlyIfAsked))
        }.map { it.pushToken!! }
    }

    /**
     * "Control from anywhere" was just turned off (§10.6): every hub with a push token is woken once more, so a
     * phone that relied on wake-ups connects and learns it must watch on its own again.
     */
    public fun pushTokensForFarewell(): List<String> = lock.withLock {
        val live = sessions.map { it.hubId }.toSet()
        peers.filter { it.pushToken != null && it.id !in live }.map { it.pushToken!! }
    }

    /**
     * For a wake-up that was held back: is this token still one to wake — its hub has no live session and none
     * started since [lastEventAt] (epoch ms)? A hub that connected after the event has caught it up (§10.6).
     */
    public fun stillToWake(pushToken: String, lastEventAt: Long): Boolean {
        if (!remoteAccess || pushToken !in pushTokensToWake(null, null)) return false
        return lock.withLock {
            val peer = peers.firstOrNull { it.pushToken == pushToken } ?: return false
            peer.lastSeen == null || peer.lastSeen < lastEventAt
        }
    }

    /** Says `bye shutdown` to every session (app exit or update) and waits up to 2 s for them to go out. */
    public suspend fun shutdown() {
        liveSessions().forEach { it.bye("shutdown") }
        withTimeoutOrNull(2.seconds) { liveSessions().map { it.completion }.joinAll() }
    }

    /** Stops everything: no new connections, open sessions are cut. The host can't be used afterwards. */
    public fun close() {
        val first = lock.withLock { (!closed).also { closed = true } }
        if (!first) return
        cancelPairing()
        liveSessions().forEach { it.abort() }
        if (ownsScope) scope.cancel()
    }

    // ---------------- admission (§3), called by the endpoint before accepting the connection ----------------

    /**
     * Reserves one of the handshake slots (8 total, 2 per remote IP). Null → refuse the connection (HTTP 503).
     * The ticket is passed to [run], which releases it when the handshake ends.
     */
    public fun tryAdmit(remoteIp: String): HandshakeTicket? = lock.withLock {
        if (closed) return null
        val n = pendingPerIp[remoteIp] ?: 0
        if (pendingTotal >= MAX_PENDING_HANDSHAKES || n >= MAX_PENDING_PER_IP) return null
        pendingPerIp[remoteIp] = n + 1
        pendingTotal++
        HandshakeTicket(this, remoteIp)
    }

    /**
     * Reserves a handshake slot for a stream the relay opened with one of [hubId]'s routes (§10.4): at most 2
     * per hub and 4 in total, apart from the LAN slots. A full pool evicts instead of refusing: the hub's own
     * oldest waiting stream if it has one, else the oldest of the hub with the most waiting. A stream leaves
     * the pool once its msg 1 proved the hub's key. Null when "Control from anywhere" is off or the host closed.
     */
    public fun tryAdmitRelay(hubId: String): HandshakeTicket? {
        var evict: HandshakeTicket? = null
        val ticket: HandshakeTicket
        lock.withLock {
            if (closed || !remoteAccess) return null
            val mine = relayPending.filter { it.relayHubId == hubId }
            if (mine.isNotEmpty() && (mine.size >= MAX_RELAY_PENDING_PER_HUB || relayPending.size >= MAX_RELAY_PENDING)) {
                evict = mine[0]
            } else if (relayPending.size >= MAX_RELAY_PENDING) {
                evict = relayPending.groupBy { it.relayHubId }.values.maxBy { it.size }.first()
            }
            evict?.let { relayPending.remove(it) }
            ticket = HandshakeTicket(this, "relay:$hubId", hubId)
            relayPending.add(ticket)
        }
        evict?.evict()
        return ticket
    }

    internal val relayPendingCount: Int get() = lock.withLock { relayPending.size }

    internal val liveSessionCount: Int get() = lock.withLock { sessions.size }

    /**
     * A relayed stream's msg 1 proved the hub's key: it leaves the evictable pool. A msg 1 can be replayed, so
     * at most [MAX_RELAY_PENDING_PER_HUB] such streams per hub wait for `ready` at a time.
     */
    internal fun tryProve(ticket: HandshakeTicket): Boolean {
        lock.withLock {
            // Evicted or released meanwhile: its handshake is over, it can't prove anything any more
            if (relayPending.none { it === ticket } || ticket.isEvicted) return false
            val hub = ticket.relayHubId!!
            val n = relayProven[hub] ?: 0
            if (n >= MAX_RELAY_PENDING_PER_HUB) return false
            relayProven[hub] = n + 1
            ticket.markProven()
        }
        ticket.release() // the pool slot
        return true
    }

    internal fun unprove(hub: String) {
        lock.withLock {
            val n = relayProven[hub] ?: return
            if (n <= 1) relayProven.remove(hub) else relayProven[hub] = n - 1
        }
    }

    internal fun release(ticket: HandshakeTicket) {
        lock.withLock {
            if (ticket.relayHubId != null) {
                relayPending.remove(ticket)
                return
            }
            pendingTotal--
            val n = (pendingPerIp[ticket.remoteIp] ?: 1) - 1
            if (n <= 0) pendingPerIp.remove(ticket.remoteIp) else pendingPerIp[ticket.remoteIp] = n
        }
    }

    internal fun recordFailure(ip: String) {
        lock.withLock {
            val q = failures[ip] ?: run {
                if (failures.size >= MAX_TRACKED_IPS) sweepFailures()
                ArrayDeque<Long>().also { failures[ip] = it }
            }
            q.addLast(clock.nowMillis())
            trim(q)
        }
    }

    internal fun overFailureLimit(ip: String): Boolean = lock.withLock {
        val q = failures[ip] ?: return false
        trim(q)
        if (q.isEmpty()) failures.remove(ip)
        q.size >= MAX_FAILED_HANDSHAKES_PER_MINUTE
    }

    /** Bounds the per-IP counters (an IPv6 LAN offers many source addresses). Under the lock. */
    private fun sweepFailures() {
        for ((ip, q) in failures.entries.toList()) {
            trim(q)
            if (q.isEmpty()) failures.remove(ip)
        }
        // Still full of active addresses: forget the ones that failed longest ago
        failures.entries.sortedBy { it.value.last() }.take(failures.size - MAX_TRACKED_IPS / 2).map { it.key }
            .forEach { failures.remove(it) }
    }

    private fun trim(q: ArrayDeque<Long>) {
        val cutoff = clock.nowMillis() - MINUTE_MILLIS
        while (q.isNotEmpty() && q.first() < cutoff) q.removeFirst()
    }

    // ---------------- peers (§8.1) ----------------

    internal fun findPeerByKey(publicKey: ByteArray): Peer? {
        val encoded = Base64Url.encode(publicKey)
        return lock.withLock { peers.firstOrNull { it.publicKey == encoded } }
    }

    internal fun findPeer(id: String): Peer? = lock.withLock { peers.firstOrNull { it.id == id } }

    internal enum class PeerChange { Done, NotFound, LastAdmin }

    /**
     * Removes a hub: closes its sessions (bye revoked), drops its ak and cached replies, tells the others. The
     * last-admin check and the removal happen under one lock, so two admins revoking each other can't leave none.
     */
    internal fun revoke(hubId: String, origin: RequestOrigin?, protectLastAdmin: Boolean): PeerChange {
        val closing: List<Session>
        lock.withLock {
            val peer = peers.firstOrNull { it.id == hubId } ?: return PeerChange.NotFound
            if (protectLastAdmin && isLastAdminLocked(peer)) return PeerChange.LastAdmin
            peers.remove(peer)
            rids.remove(hubId)
            ridLocks.remove(hubId)
            closing = sessions.filter { it.hubId == hubId }
        }
        persist()
        for (s in closing) {
            // A hub that revokes itself gets its result first, then the bye (§8.1)
            if (origin?.session === s && origin.trySetByeAfter("revoked")) continue
            s.bye("revoked")
        }
        publishPeers("revoked", hubId, origin)
        firePeersChanged()
        return PeerChange.Done
    }

    internal fun setRole(hubId: String, role: PeerRole, origin: RequestOrigin?, protectLastAdmin: Boolean): PeerChange {
        lock.withLock {
            val i = peers.indexOfFirst { it.id == hubId }
            if (i < 0) return PeerChange.NotFound
            val peer = peers[i]
            if (peer.role == role) return PeerChange.Done
            if (protectLastAdmin && role != PeerRole.Admin && isLastAdminLocked(peer)) return PeerChange.LastAdmin
            peers[i] = peer.copy(role = role)
            sessions.filter { it.hubId == hubId }.forEach { it.role = role }
        }
        persist()
        publishPeers("role", hubId, origin)
        firePeersChanged()
        return PeerChange.Done
    }

    private fun isLastAdminLocked(peer: Peer): Boolean =
        peer.role == PeerRole.Admin && peers.count { it.role == PeerRole.Admin } == 1

    /** Replaces a peer under the lock (peers are immutable values). */
    internal fun updatePeerLocked(id: String, change: (Peer) -> Peer): Peer? {
        val i = peers.indexOfFirst { it.id == id }
        if (i < 0) return null
        return change(peers[i]).also { peers[i] = it }
    }

    internal fun firePeersChanged() {
        try { relayPeersChanged?.invoke() } catch (e: Exception) { /* the relay client */ }
        try { onPeersChanged?.invoke() } catch (e: Exception) { /* the app's handler */ }
    }

    internal fun firePaired(peer: Peer) {
        try { onPaired?.invoke(peer) } catch (e: Exception) { /* e.g. a closed dialog */ }
    }

    // ---------------- persistence: snapshot under the lock, write outside it, newest wins ----------------

    private val saveLock = newHostLock()
    private var saveSeq = 0L
    private var savedSeq = 0L

    private fun snapshotLocked(): AgentStateData = AgentStateData(
        privateKey = privateKeyText,
        peers = peers.toList(),
        rids = rids.mapValues { it.value.toList() },
        knownNetworks = knownNetworks.toList(),
    )

    /** Saves the current state. Call outside [lock]: the store may write to disk. */
    internal fun persist() {
        val snapshot: AgentStateData
        val seq: Long
        lock.withLock {
            snapshot = snapshotLocked()
            seq = ++saveSeq
        }
        var error: Exception? = null
        saveLock.withLock {
            if (seq <= savedSeq) return // a newer snapshot is saved already
            try { store.save(snapshot) } catch (e: Exception) { error = e }
            savedSeq = seq
        }
        error?.let { e -> try { onStoreError?.invoke(e) } catch (x: Exception) { /* the app's logger failed */ } }
    }

    /** `peers[]` as seen by one hub (`current` marks that hub). */
    internal fun peersJson(forHubId: String): JsonArray = lock.withLock {
        buildJsonArray {
            for (p in peers) add(buildJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("role", roleName(p.role))
                put("pairedAt", isoUtc(p.pairedAt))
                put("lastSeen", p.lastSeen?.let { JsonPrimitive(isoUtc(it)) } ?: JsonNull)
                put("current", p.id == forHubId)
            })
        }
    }

    /** ev.peers is per session (each hub sees its own `current`), so it bypasses [publish]. */
    internal fun publishPeers(change: String, hubId: String, origin: RequestOrigin?) {
        if (change == "added") raiseWake("peerAdded", hubId) // §10.6: a new parent app is news for the others
        publishLock.withLock {
            for (s in liveSessions()) {
                if (origin?.session === s) continue
                s.enqueue(buildJsonObject {
                    put("t", "ev.peers"); put("peers", peersJson(s.hubId)); put("change", change); put("peer", hubId)
                })
            }
        }
    }

    internal fun liveSessions(): List<Session> = lock.withLock { sessions.toList() }

    internal fun agentJson(): JsonObject = buildJsonObject {
        put("id", agentId)
        put("name", backend.info.name)
        put("platform", backend.info.platform)
        put("version", backend.info.version)
    }

    internal val backendRef: AgentBackend get() = backend
    internal val pairingUiRef: PairingUi get() = pairingUi
    internal fun nowMillis(): Long = clock.nowMillis()
}

// ---------------- small helpers ----------------

/** A peer as the app sees it: the push token stays inside the host. */
internal fun Peer.forApp(): Peer = copy(pushToken = null, pushEvents = null)

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Epoch ms of `YYYY-MM-DD` (UTC midnight). */
internal fun parseUtcDate(date: String): Long {
    require(Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) { "date must be YYYY-MM-DD" }
    val y = date.substring(0, 4).toLong()
    val m = date.substring(5, 7).toLong()
    val d = date.substring(8, 10).toLong()
    // days from civil (H. Hinnant)
    val yy = if (m <= 2) y - 1 else y
    val era = yy.floorDiv(400L)
    val yoe = yy - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return (era * 146_097 + doe - 719_468) * AgentHost.DAY_MILLIS
}

/** `YYYY-MM-DDTHH:MM:SS.mmmZ` (ISO 8601, UTC) of an epoch-millisecond instant. */
internal fun isoUtc(epochMillis: Long): String {
    val ms = epochMillis.mod(AgentHost.DAY_MILLIS)
    fun two(v: Long) = v.toString().padStart(2, '0')
    return DiscoveryTag.utcDate(epochMillis) + "T" + two(ms / 3_600_000) + ":" + two(ms / 60_000 % 60) + ":" +
        two(ms / 1000 % 60) + "." + (ms % 1000).toString().padStart(3, '0') + "Z"
}

/** Fisher–Yates with libsodium's CSPRNG (the route order must not tell which claims belong together). */
internal fun <T> secureShuffle(list: MutableList<T>) {
    for (i in list.size - 1 downTo 1) {
        val j = com.ionspin.kotlin.crypto.util.LibsodiumRandom.uniform((i + 1).toUInt()).toInt()
        val t = list[i]; list[i] = list[j]; list[j] = t
    }
}
