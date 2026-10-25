package com.gametimer.parent

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gametimer.protocol.hub.AgentLink
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
    private val hub = (app as GameTimerApp).hub

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

    private val retry = Channel<Unit>(Channel.CONFLATED) // declared before init: the init coroutine uses it
    private var visible = false // declared before init: the init coroutine may run at once

    private val _openAgent = MutableStateFlow<String?>(null)
    /** A notification asked to open this PC's screen. */
    val openAgent: StateFlow<String?> = _openAgent.asStateFlow()

    fun open(agentId: String?) { _openAgent.value = agentId }

    fun opened() { _openAgent.value = null }

    fun setShownAgent(agentId: String?) { hub.shownAgent = agentId }

    init {
        viewModelScope.launch {
            while (!loadIdentity()) retry.receive() // "Try again" on the Unavailable screen
            hub.setUiVisible(visible) // links made from now on start if the UI is visible
            agents.value.forEach { hub.link(it.agentId) }
            MonitorService.update(getApplication())
        }
    }

    override fun onCleared() {
        hub.setUiVisible(false) // e.g. cleared while the Android 9-10 PIN workaround still waited
        super.onCleared()
    }

    fun retryIdentity() {
        retry.trySend(Unit)
    }

    private suspend fun loadIdentity(): Boolean {
        _identity.value = Identity.Loading
        _identity.value = try {
            val key = hub.loadKey()
            Identity.Ready(key, store.identityWasReset)
        } catch (e: Exception) {
            Identity.Unavailable
        }
        return _identity.value is Identity.Ready
    }

    private fun hubKey(): KeyPair? = (_identity.value as? Identity.Ready)?.key

    /** The live link to a paired PC; null until the hub key is loaded or for a PC that isn't in the list. */
    fun link(agentId: String): AgentLink? = if (hubKey() == null) null else hub.link(agentId)

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

    /** The UI is visible (links connected; with [MonitorService] they also stay connected in the background). */
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
                visible = false
                hub.setUiVisible(false)
            }
            return
        }
        visible = value
        if (hubKey() != null) hub.setUiVisible(value)
        if (value && hubKey() != null) MonitorService.update(getApplication()) // (re)start watching while the app is in front
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

    /** Another parent app was added on a PC (§12): a queue shown one by one. */
    val peerAdded: StateFlow<List<PeerAdded>> = hub.peerAdded

    fun peerAddedShown() = hub.peerAddedShown()

    fun startPairing(link: PairingLink, phoneName: String) {
        val key = hubKey() ?: return
        pairJob?.cancel()
        _pair.value = PairState.Connecting(link.pcName)
        pairJob = viewModelScope.launch {
            val outcome = pair(link, key, phoneName.ifBlank { defaultPhoneName }, hub.appName, hub.connector) { code ->
                _pair.value = PairState.ShowCode(code)
            }
            _pair.value = when (outcome) {
                is PairOutcome.Paired -> {
                    withContext(Dispatchers.IO) { store.save(outcome.agent) }
                    hub.forget(outcome.agent.agentId, removed = false) // re-paired: reconnect with the new ak
                    link(outcome.agent.agentId) // started if the app is visible
                    MonitorService.update(getApplication()) // the first PC: start watching
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
            hub.forget(agent.agentId, removed = true)
            MonitorService.update(getApplication()) // the last PC gone: stop watching
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
