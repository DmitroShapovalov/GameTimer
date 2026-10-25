package com.gametimer.parent

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.KtorConnector
import com.gametimer.protocol.hub.PairOutcome
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.hub.PairingLink
import com.gametimer.protocol.hub.RequestOutcome
import com.gametimer.protocol.hub.pair
import com.gametimer.protocol.noise.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where the pairing flow is (§8.2). */
sealed interface PairState {
    data class Connecting(val pcName: String) : PairState
    data class ShowCode(val code: String) : PairState
    data class Done(val pcName: String) : PairState
    data class Failed(val outcome: PairOutcome) : PairState
}

/** The hub key, loaded off the main thread at start. */
sealed interface Identity {
    data object Loading : Identity
    data class Ready(val key: KeyPair, val wasReset: Boolean) : Identity
    data object Unavailable : Identity
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as GameTimerApp).store
    private val connector = KtorConnector()
    private val locator = NsdLocator(app)
    private val appName = "GameTimer Parent ${BuildConfig.VERSION_NAME} (android)"
    private val links = HashMap<String, AgentLink>() // main thread only

    val agents: StateFlow<List<PairedAgent>> = store.agents

    private val _identity = MutableStateFlow<Identity>(Identity.Loading)
    val identity: StateFlow<Identity> = _identity.asStateFlow()

    private val _pair = MutableStateFlow<PairState?>(null)
    val pair: StateFlow<PairState?> = _pair.asStateFlow()
    private var pairJob: Job? = null

    private val _message = MutableStateFlow<String?>(null)
    /** A one-off notice for a snackbar (refused / no answer). */
    val message: StateFlow<String?> = _message.asStateFlow()

    val defaultPhoneName: String = Build.MODEL ?: "Phone"

    // Foreground changes are applied one at a time, latest wins (rotation = stop + start in a burst)
    private val wantForeground = Channel<Boolean>(Channel.CONFLATED)
    private var foreground = false
    private val retry = Channel<Unit>(Channel.CONFLATED) // declared before init: the init coroutine uses it

    init {
        viewModelScope.launch {
            while (!loadIdentity()) retry.receive() // "Try again" on the Unavailable screen
            for (want in wantForeground) applyForeground(want)
        }
    }

    fun retryIdentity() {
        retry.trySend(Unit)
    }

    private suspend fun loadIdentity(): Boolean {
        _identity.value = Identity.Loading
        _identity.value = try {
            val key = withContext(Dispatchers.IO) { store.loadHubKey() }
            Identity.Ready(key, store.identityWasReset)
        } catch (e: Exception) {
            Identity.Unavailable
        }
        return _identity.value is Identity.Ready
    }

    private fun hubKey(): KeyPair? = (_identity.value as? Identity.Ready)?.key

    /** The live link to a paired PC; null until the hub key is loaded or for a PC that isn't in the list. */
    fun link(agentId: String): AgentLink? {
        links[agentId]?.let { return it }
        val agent = agents.value.firstOrNull { it.agentId == agentId } ?: return null
        val key = hubKey() ?: return null
        val onMoved: (PairedAgent) -> Unit = { moved ->
            // The PC answered with its pinned key at a new address (§9): remember it, unless it was removed meanwhile
            viewModelScope.launch(Dispatchers.IO) { store.updateAddress(moved.agentId, moved.host, moved.port) }
        }
        return AgentLink(agent, key, connector, appName, viewModelScope, locator, onMoved).also {
            links[agentId] = it
            watchPeers(agent, it)
            if (foreground) it.start()
        }
    }

    private val editors = HashMap<String, PcEditors>()

    /** Unsaved settings / schedule edits of a PC, surviving tab switches (main thread). */
    fun editors(agentId: String): PcEditors = editors.getOrPut(agentId) { PcEditors() }

    /** Runs a save in the view model, so leaving the screen doesn't cancel it. */
    fun launchSave(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Shows a one-off notice (empty texts, e.g. for a request cut by stopping the link, are skipped). */
    fun say(text: String) {
        if (text.isNotBlank()) _message.value = text
    }

    /** Text for a failed request, translated where the code is known. */
    fun outcomeText(outcome: RequestOutcome): String {
        val res = getApplication<Application>().resources
        return when (outcome) {
            is RequestOutcome.Refused -> res.getString(R.string.request_refused, errorText(outcome.code))
            RequestOutcome.GaveUp -> res.getString(R.string.request_gave_up)
            RequestOutcome.Revoked -> res.getString(R.string.status_revoked)
            RequestOutcome.Stopped, is RequestOutcome.Ok -> ""
        }
    }

    /** Links stay connected only while the app is visible (no background service in this step). */
    fun setForeground(value: Boolean) {
        if (value) {
            stopSkipped?.cancel()
            stopSkipped = null
            if (reauth.needsUnlock()) _locked.value = true // §12: not unlocked for 14 days
        } else if (Build.VERSION.SDK_INT < 30 && reauth.inProgress) {
            // Android 9-10 show the PIN as a screen of their own: the app "stops" meanwhile, but the
            // links must not. If the app doesn't come back (Home pressed there), stop them after all.
            stopSkipped?.cancel()
            stopSkipped = viewModelScope.launch {
                while (reauth.inProgress) delay(500)
                delay(1000) // the app's own onStart follows the PIN screen
                wantForeground.trySend(false)
            }
            return
        }
        wantForeground.trySend(value)
    }

    private var stopSkipped: Job? = null // main thread only

    /** Re-authentication (§12): the app lock after 14 days, and the check before admin actions. */
    val reauth = ReAuth(app)

    private val _locked = MutableStateFlow(false)
    /** The app was not unlocked for too long: the UI shows only the unlock screen. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun unlocked() {
        _locked.value = false
    }

    /** Another parent app was added on a PC (§12: a clear notice, it may be a child's phone). */
    data class PeerAdded(val pcName: String, val phoneName: String, val iAmAdmin: Boolean)

    // A queue: notices about several PCs are shown one after another, none is lost
    private val _peerAdded = MutableStateFlow<List<PeerAdded>>(emptyList())
    val peerAdded: StateFlow<List<PeerAdded>> = _peerAdded.asStateFlow()

    fun peerAddedShown() {
        _peerAdded.value = _peerAdded.value.drop(1)
    }

    private val peerJobs = HashMap<String, Job>() // main thread only
    private val seenPeers = app.getSharedPreferences("peers_seen", android.content.Context.MODE_PRIVATE)

    /**
     * The parent apps of a PC are compared with the last list this phone saw, on every connect and every
     * ev.peers: so a phone added while this app was closed is reported too. The first list of a PC is
     * only remembered; this phone itself is never reported.
     */
    private fun watchPeers(agent: PairedAgent, link: AgentLink) {
        peerJobs.remove(agent.agentId)?.cancel()
        peerJobs[agent.agentId] = viewModelScope.launch {
            val api = AgentApi(link)
            val check = kotlinx.coroutines.sync.Mutex()
            suspend fun compare() = check.withLock {
                val list = (api.peers() as? ApiResult.Ok)?.value ?: return@withLock
                val known = seenPeers.getStringSet(agent.agentId, null)
                if (known != null) {
                    val added = list.filter { !it.current && it.id !in known }.map { it.name }
                    if (added.isNotEmpty()) {
                        val iAmAdmin = list.firstOrNull { it.current }?.role == "admin"
                        _peerAdded.value += PeerAdded(agent.name, added.joinToString(", "), iAmAdmin)
                    }
                }
                seenPeers.edit().putStringSet(agent.agentId, list.map { it.id }.toSet()).apply()
            }
            launch { link.status.collect { if (it is LinkStatus.Online) compare() } }
            link.events.collect { if (it.string("t") == "ev.peers") compare() }
        }
    }

    private suspend fun forgetLink(agentId: String) {
        peerJobs.remove(agentId)?.cancel()
        links.remove(agentId)?.stop()
    }

    private suspend fun applyForeground(value: Boolean) {
        if (value == foreground) return
        foreground = value
        if (value) agents.value.forEach { link(it.agentId)?.start() } else links.values.toList().forEach { it.stop() }
    }

    fun startPairing(link: PairingLink, phoneName: String) {
        val key = hubKey() ?: return
        pairJob?.cancel()
        _pair.value = PairState.Connecting(link.pcName)
        pairJob = viewModelScope.launch {
            val outcome = pair(link, key, phoneName.ifBlank { defaultPhoneName }, appName, connector) { code ->
                _pair.value = PairState.ShowCode(code)
            }
            _pair.value = when (outcome) {
                is PairOutcome.Paired -> {
                    withContext(Dispatchers.IO) { store.save(outcome.agent) }
                    forgetLink(outcome.agent.agentId) // re-paired: reconnect with the new ak
                    link(outcome.agent.agentId) // started if the app is visible
                    PairState.Done(outcome.agent.name)
                }
                else -> PairState.Failed(outcome)
            }
        }
    }

    fun clearPairing() {
        pairJob?.cancel()
        _pair.value = null
    }

    fun remove(agent: PairedAgent) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(agent.agentId) } // first: no screen recreates its link
            editors.remove(agent.agentId)
            forgetLink(agent.agentId)
            seenPeers.edit().remove(agent.agentId).apply()
        }
    }

    /** §7.2 error codes the user may see, translated; others shown as the code. */
    private fun errorText(code: String): String {
        val id = when (code) {
            "not_running" -> R.string.err_not_running
            "already_running" -> R.string.err_already_running
            "invalid_time" -> R.string.err_invalid_time
            "schedule_limit" -> R.string.err_schedule_limit
            "invalid_minutes" -> R.string.err_invalid_minutes
            "forbidden" -> R.string.err_forbidden
            "busy" -> R.string.err_busy
            "last_admin" -> R.string.err_last_admin
            "conflict" -> R.string.err_conflict
            "invalid_threshold", "invalid_alerts", "invalid_color", "invalid_language" -> R.string.err_settings
            "invalid_schedule" -> R.string.err_schedule
            else -> return code
        }
        return getApplication<Application>().getString(id)
    }

    fun messageShown() {
        _message.value = null
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
