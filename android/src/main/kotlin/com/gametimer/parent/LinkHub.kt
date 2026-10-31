package com.gametimer.parent

import android.content.Context
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.KtorConnector
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.hub.RelayPath
import com.gametimer.protocol.noise.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Another parent app was added on a PC (§12: a clear notice, it may be a child's phone). */
data class PeerAdded(val pcName: String, val phoneName: String, val iAmAdmin: Boolean)

/**
 * The live links to the paired PCs, for the whole app process: the screens use them while visible, and
 * [MonitorService] keeps them connected in the background (for notifications). One [AgentLink] per PC,
 * connected while the UI is visible or the service runs. Main thread only.
 */
class LinkHub(private val context: Context, private val store: HubStore) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val connector = KtorConnector()
    private val locator = NsdLocator(context)
    val appName = "GameTimer Parent ${BuildConfig.VERSION_NAME} (android)"
    private val links = HashMap<String, AgentLink>()
    private val watchers = HashMap<String, Job>()
    private val notifier = Notifier(context)

    // ---------------- the hub key ----------------

    private val keyLock = Mutex()
    @Volatile var hubKey: KeyPair? = null
        private set

    /** Loads the key once (Keystore + file, off the main thread); throws if it can't be read. */
    suspend fun loadKey(): KeyPair = keyLock.withLock {
        hubKey ?: withContext(Dispatchers.IO) { store.loadHubKey() }.also {
            hubKey = it
            if (store.identityWasReset) { // a new identity: every PC must be paired again, forget what was seen
                seenPeers.edit().clear().apply()
                notifier.forgetAll()
            }
        }
    }

    // ---------------- connected while visible or monitored ----------------

    private var uiVisible = false
    private var monitoring = false
    @Volatile private var onLan = true
    @Volatile private var uiVisibleNow = false
    private var active = false
    private val activeChanges = Channel<Boolean>(Channel.CONFLATED) // latest wins (rotation = stop + start)

    init {
        scope.launch { for (want in activeChanges) applyActive(want) }
        watchNetwork()
    }

    /** The PC whose screen is open (its events need no notification: the screen shows them). */
    var shownAgent: String? = null

    private fun shouldNotify(agentId: String) = !uiVisible || shownAgent != agentId

    fun setUiVisible(value: Boolean) {
        uiVisible = value
        uiVisibleNow = value
        updateActive()
        // In the background only the home network is used (notifications away from home come with B5):
        // a session that runs through the relay ends when the app leaves the screen
        if (!value) links.values.filter { (it.status.value as? LinkStatus.Online)?.viaRelay == true }.forEach { l ->
            scope.launch { l.stop(); if (active) l.start() }
        }
    }

    /** Store writes one at a time, in order (an old value must never overwrite a newer one). */
    private val storeWriter = Dispatchers.IO.limitedParallelism(1)

    fun setMonitoring(value: Boolean) {
        monitoring = value
        updateActive()
    }

    // In the background the links only run on Wi-Fi / Ethernet: the PCs are on the home network, so on
    // mobile data every attempt would just wake the radio (and the screens connect anyway when opened)
    private fun updateActive() {
        activeChanges.trySend(uiVisible || (monitoring && onLan))
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        fun lan(caps: android.net.NetworkCapabilities?) = caps != null &&
            (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
        onLan = lan(cm.getNetworkCapabilities(cm.activeNetwork))
        // The default network only (a home Wi-Fi without internet loses that role to mobile data: then the
        // background watching pauses too — accepted)
        var current: android.net.Network? = cm.activeNetwork
        try {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    val now = lan(caps)
                    scope.launch { current = network; if (now != onLan) { onLan = now; updateActive() } }
                }
                override fun onLost(network: android.net.Network) {
                    // A late "lost" of the previous network must not switch off the new one
                    scope.launch { if (network == current && onLan) { onLan = false; updateActive() } }
                }
            })
        } catch (e: RuntimeException) { /* no callback: always try */ }
    }

    private suspend fun applyActive(value: Boolean) {
        if (value == active) return
        active = value
        if (value) store.agents.value.forEach { link(it.agentId)?.start() } else links.values.toList().forEach { it.stop() }
    }

    /** The live link to a paired PC; null until the hub key is loaded or for a PC that isn't in the list. */
    fun link(agentId: String): AgentLink? {
        links[agentId]?.let { return it }
        val agent = store.agents.value.firstOrNull { it.agentId == agentId } ?: return null
        val key = hubKey ?: return null
        val onMoved: (PairedAgent) -> Unit = { moved ->
            // The PC answered with its pinned key at a new address (§9): remember it, unless it was removed meanwhile
            scope.launch(storeWriter) { store.updateAddress(moved.agentId, moved.host, moved.port) }
        }
        // Away from home (protocol §10): LAN first, the relay after ~2 s or at once off Wi-Fi, while the PC
        // has "Control from anywhere" on (learned from its hello and stored with the PC)
        val relay = RelayPath(RelayPath.DEFAULT_URL, connector, onLocalNetwork = { onLan }, allowed = { uiVisibleNow })
        val onRemoteAccess: (Boolean) -> Unit = { on ->
            scope.launch(storeWriter) { store.updateRemoteAccess(agentId, on) }
        }
        return AgentLink(agent, key, connector, appName, scope, locator, relay, onRemoteAccess, onMoved).also {
            links[agentId] = it
            watch(agent, it)
            if (active) it.start()
        }
    }

    /** Re-paired or removed: drop the link (and its watcher); a later [link] makes a new one. */
    suspend fun forget(agentId: String, removed: Boolean) {
        watchers.remove(agentId)?.cancel()
        links.remove(agentId)?.stop()
        if (removed) {
            seenPeers.edit().remove(agentId).apply()
            notifier.forget(agentId)
        }
    }

    // ---------------- "parent added" and notifications ----------------

    // A queue: notices about several PCs are shown one after another, none is lost
    private val _peerAdded = MutableStateFlow<List<PeerAdded>>(emptyList())
    val peerAdded: StateFlow<List<PeerAdded>> = _peerAdded.asStateFlow()

    fun peerAddedShown() {
        _peerAdded.value = _peerAdded.value.drop(1)
    }

    private val seenPeers = context.getSharedPreferences("peers_seen", Context.MODE_PRIVATE)

    /**
     * Per PC: the parent list is compared with the last one this phone saw (on every connect and every
     * ev.peers), so a phone added while this app was closed is reported too — the first list is only
     * remembered, this phone itself never reported. Events go to [Notifier] (history caught up on connect).
     */
    private fun watch(agent: PairedAgent, link: AgentLink) {
        watchers.remove(agent.agentId)?.cancel()
        watchers[agent.agentId] = scope.launch {
            val api = AgentApi(link)
            val check = Mutex()
            var me = Me(null, null)
            suspend fun compare() = check.withLock {
                val list = (api.peers() as? ApiResult.Ok)?.value ?: return@withLock
                me = me.copy(name = list.firstOrNull { it.current }?.name)
                val known = seenPeers.getStringSet(agent.agentId, null)
                if (known != null) {
                    val added = list.filter { !it.current && it.id !in known }.map { it.name }
                    if (added.isNotEmpty()) {
                        val iAmAdmin = list.firstOrNull { it.current }?.role == "admin"
                        val notice = PeerAdded(agent.name, added.joinToString(", "), iAmAdmin)
                        _peerAdded.value += notice
                        if (!uiVisible) notifier.peerAdded(agent, notice) // otherwise the dialog shows it
                    }
                }
                seenPeers.edit().putStringSet(agent.agentId, list.map { it.id }.toSet()).apply()
            }
            launch {
                link.status.collect {
                    if (it is LinkStatus.Online) {
                        // This phone's hub id on that PC: known at once, so even an early event isn't "news"
                        me = me.copy(hubId = ((it.hello["you"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content)
                        compare()
                        notifier.catchUp(agent, api, me, link.state.value?.json?.str("now"), notify = shouldNotify(agent.agentId))
                    }
                }
            }
            link.events.collect { ev ->
                when (ev.str("t")) {
                    "ev.peers" -> compare()
                    "ev.history" -> notifier.liveEvent(agent, ev["event"] as? JsonObject, me, notify = shouldNotify(agent.agentId))
                    "ev.alert" -> if (shouldNotify(agent.agentId)) notifier.alert(agent, (ev["minutes"] as? JsonPrimitive)?.content?.toIntOrNull())
                }
            }
        }
    }
}

private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
