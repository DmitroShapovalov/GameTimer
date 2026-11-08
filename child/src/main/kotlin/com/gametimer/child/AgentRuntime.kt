package com.gametimer.child

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.gametimer.play.AskResult
import com.gametimer.play.BlockReason
import com.gametimer.play.PlayBackend
import com.gametimer.protocol.agent.AgentHost
import com.gametimer.protocol.agent.AgentInfo
import com.gametimer.protocol.agent.PairingAbortReason
import com.gametimer.protocol.agent.PairingUi
import com.gametimer.protocol.agent.Peer
import com.gametimer.protocol.agent.RelayClient
import com.gametimer.protocol.agent.RelayStatus
import com.gametimer.protocol.noise.Sodium
import java.net.Inet4Address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The agent as it runs on this phone: the [AgentHost] with its store and backend, the relay client (always on
 * for a phone — owner's decision for C2 — as soon as a parent is paired) and the LAN server while on Wi-Fi.
 * [AgentService] starts it and keeps the process alive; the UI watches [state] and drives pairing.
 */
object AgentRuntime {
    enum class Phase { Stopped, Starting, Running, StoreUnreadable, Failed }

    /** What the screen shows. [lanAddress] is the phone's Wi-Fi IPv4 while the LAN server listens. */
    data class State(
        val phase: Phase = Phase.Stopped,
        val peers: List<Peer> = emptyList(),
        val relay: RelayStatus = RelayStatus.Off,
        val lanAddress: String? = null,
        val pairing: Pairing? = null,
        val ask: Ask? = null,
        val message: Message? = null,
    )

    /** The QR is on screen: [link] is its text, [expiresAt] epoch ms. */
    data class Pairing(val link: String, val expiresAt: Long)

    /** A parent's phone reached pair.hello: the owner types the code it shows. */
    class Ask(val hubName: String, internal val answer: CompletableDeferred<String?>)

    enum class Message { Paired, TwoPhones, NoWifi }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lifecycle = Mutex()
    private val peersLock = Mutex()
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var host: AgentHost? = null
    @Volatile private var relay: RelayClient? = null
    @Volatile private var lan: LanServer? = null
    @Volatile private var network: ConnectivityManager.NetworkCallback? = null
    private lateinit var app: Context

    private val pairingUi = object : PairingUi {
        override suspend fun askForCode(hubName: String): String? {
            val ask = Ask(hubName, CompletableDeferred())
            _state.update { it.copy(ask = ask) }
            try {
                return ask.answer.await()
            } finally {
                _state.update { if (it.ask === ask) it.copy(ask = null) else it }
            }
        }

        override fun pairingAborted(reason: PairingAbortReason) {
            _state.update { it.copy(pairing = null, ask = null, message = Message.TwoPhones) }
        }
    }

    /** Idempotent; after a failed start (e.g. an unreadable store before the first unlock) it tries again. */
    suspend fun start(context: Context) = lifecycle.withLock {
        if (scope != null) return@withLock
        app = context.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        _state.update { it.copy(phase = Phase.Starting) }
        try {
            Sodium.initialize()
            val play = PlayBackend(AgentInfo(deviceName(), "android", appVersion()), AndroidPlayPlatform(app))
            val h = AgentHost(AgentFileStore(app), play, pairingUi, scope = s)
            play.host = h
            play.onChanged = { refreshPlay() }
            backend = play
            h.remoteAccess = true // a phone is mostly away from home: control from anywhere is always on
            // A parent's phone's clock (its ready): checks this phone's clock when the relay can't be reached (C4)
            h.onHubTime = { t ->
                val at = android.os.SystemClock.elapsedRealtime()
                s.launch { play.trustedTime(t, at, fromParent = true) }
            }
            h.onPeersChanged = { s.launch { onPeersChanged() } }
            h.onPaired = { _state.update { st -> st.copy(pairing = null, message = Message.Paired) } }
            host = h
            relay = RelayClient(h, scope = s).also { r ->
                // The relay's time checks the phone's clock (C4)
                r.onServerTime = { t ->
                    val at = android.os.SystemClock.elapsedRealtime()
                    s.launch { play.trustedTime(t, at) } // off the relay's connect loop (it saves and publishes)
                }
                s.launch { r.status.collect { st -> _state.update { it.copy(relay = st) } } }
            }
            reportForceStop(play)
            lan = LanServer(h)
            _state.update { it.copy(phase = Phase.Running) }
            onPeersChanged()
            watchNetwork()
            val w = Watcher(app, play)
            watcher = w
            s.launch { w.run() }
            refreshPlay()
        } catch (e: StoreUnreadableException) {
            failed(s, Phase.StoreUnreadable)
        } catch (e: Exception) {
            failed(s, Phase.Failed)
        }
    }

    private fun failed(s: CoroutineScope, phase: Phase) {
        host?.close()
        host = null; relay = null; lan = null; backend = null; watcher = null
        s.cancel()
        scope = null // the next start() tries again
        _state.update { State(phase = phase) }
    }

    /** Says bye to the parents and stops everything; always completes the teardown, each step bounded. */
    suspend fun stop() = lifecycle.withLock {
        val s = scope ?: return@withLock
        val h = host; val r = relay; val l = lan; val cb = network; val b = backend
        // Out first: nothing new (network events, peer changes) reaches the old instances
        scope = null; host = null; relay = null; lan = null; network = null; backend = null; watcher = null
        withContext(NonCancellable) {
            try {
                cb?.let { runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
                runCatching { b?.saveNow() } // the game time counted since the last save
                h?.cancelPairing()
                withTimeoutOrNull(2_500) { h?.shutdown() }
                withTimeoutOrNull(2_000) { r?.stop() }
                l?.stop()
            } finally {
                h?.close()
                s.cancel()
                _state.value = State()
            }
        }
    }

    /** Starts over after an unreadable store (behind the parent PIN): a new identity, parents pair again. */
    suspend fun wipeAndRestart(context: Context) {
        stop()
        AgentFileStore(context.applicationContext).wipe()
        start(context)
    }

    /** One at a time, reading the current list: events handled out of order can't leave the relay wrong. */
    private suspend fun onPeersChanged() = peersLock.withLock {
        val h = host ?: return@withLock
        val peers = h.peerList
        _state.update { it.copy(peers = peers) }
        refreshPlay()
        // Relay routes exist only for paired parents: with none there is nothing to register
        if (peers.isEmpty()) relay?.stop() else relay?.start()
    }

    // ---------------- pairing (§8.2), from the screen behind the parent PIN ----------------

    /** Opens a pairing window and shows the QR; false when not on Wi-Fi (pairing is LAN-only, §8.2). */
    fun startPairing(): Boolean {
        val h = host ?: return false
        val ip = _state.value.lanAddress ?: run { _state.update { it.copy(message = Message.NoWifi) }; return false }
        val offer = h.startPairing(deviceName())
        val link = "http://$ip:${LanServer.PORT}/pair#${offer.qrFragment}"
        _state.update { it.copy(pairing = Pairing(link, offer.expiresAt)) }
        return true
    }

    fun cancelPairing() {
        host?.cancelPairing()
        _state.value.ask?.answer?.complete(null)
        _state.update { it.copy(pairing = null, ask = null) }
    }

    /** The owner typed [code] (null = cancel) for the waiting parent's phone. */
    fun answer(code: String?) {
        _state.value.ask?.answer?.complete(code)
    }

    fun revoke(hubId: String) {
        host?.revokeByOwner(hubId)
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---------------- play (C3): the screen's and the block page's view ----------------

    /** Game time left today (null = no limit), whether the child asked, how many parents and games there are. */
    data class PlayUi(val minutesLeft: Int? = null, val asked: Boolean = false, val parents: Int = 0, val tick: Long = 0)

    @Volatile var backend: PlayBackend? = null
        private set
    @Volatile var watcher: Watcher? = null
        private set
    private val _play = MutableStateFlow(PlayUi())
    val play: StateFlow<PlayUi> = _play.asStateFlow()

    fun refreshPlay() {
        val b = backend
        _play.update {
            PlayUi(b?.minutesLeft(), b?.asked == true, _state.value.peers.size, it.tick + 1)
        }
    }

    fun blockReason(pkg: String?): BlockReason? = backend?.blockReason(pkg)

    /**
     * A force stop since the last start (Android 11+ keeps the reason; before that it goes unseen) is shown to
     * the parents like the PC's "closed" (C4). The first start only notes where the list stands.
     */
    private fun reportForceStop(play: PlayBackend) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val prefs = app.getSharedPreferences("tamper", Context.MODE_PRIVATE)
            val exits = app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 16)
            val seen = prefs.getLong("exitSeen", -1L)
            val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
            // An update also stops the app (on Android 11–12 recorded as the user's): not a force stop
            val updated = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            if (seen >= 0) {
                exits.filter {
                    it.timestamp > seen && it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                        kotlin.math.abs(it.timestamp - updated) > 5 * 60_000L
                }.maxByOrNull { it.timestamp }?.let { play.reportClosed(it.timestamp) }
            }
            prefs.edit().putLong("exitSeen", maxOf(newest, seen, 0L)).apply()
        } catch (e: Exception) {
            // best effort
        }
    }

    fun askForMoreTime(): AskResult = backend?.askForMoreTime() ?: AskResult.NotBlocked

    // ---------------- the home network ----------------

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshLan()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refreshLan()
            override fun onLost(network: Network) = refreshLan()
        }
        cm.registerDefaultNetworkCallback(cb)
        network = cb
        refreshLan()
    }

    @Volatile private var lanJob: Job? = null
    private val lanLock = Mutex()

    /** The LAN server listens only on Wi-Fi / Ethernet, on the Wi-Fi address; the QR carries it. */
    private fun refreshLan() {
        val s = scope ?: return
        val server = lan ?: return
        lanJob?.cancel()
        lanJob = s.launch { lanLock.withLock {
            val ip = homeAddress()
            val listening = ip != null && server.start(ip)
            // Stopped meanwhile (stop() ran during the bind): don't leave the port held by a closed host
            if (!listening || lan !== server || !isActive) server.stop()
            if (lan !== server) return@withLock
            _state.update {
                val addr = if (listening) ip else null
                // The QR names the old address once the network changed: close it
                if (addr != it.lanAddress && it.pairing != null) host?.cancelPairing()
                it.copy(lanAddress = addr, pairing = if (addr != it.lanAddress) null else it.pairing)
            }
        } }
    }

    private fun homeAddress(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && LanServer.isLanAddress(it.hostAddress ?: "") }
            ?.hostAddress
    }

    private fun deviceName(): String =
        (Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL).trim().take(64).ifEmpty { "Android" }

    private fun appVersion(): String =
        try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
}
