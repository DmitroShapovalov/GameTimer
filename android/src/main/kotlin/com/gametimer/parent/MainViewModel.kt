package com.gametimer.parent

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.KtorConnector
import com.gametimer.protocol.hub.PairOutcome
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.hub.PairingLink
import com.gametimer.protocol.hub.RequestOutcome
import com.gametimer.protocol.hub.pair
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the pairing flow is (§8.2). */
sealed interface PairState {
    data class Connecting(val pcName: String) : PairState
    data class ShowCode(val code: String) : PairState
    data class Done(val pcName: String) : PairState
    data class Failed(val outcome: PairOutcome) : PairState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as GameTimerApp).store
    private val connector = KtorConnector()
    private val appName = "GameTimer Parent ${BuildConfigInfo.VERSION} (android)"
    private val links = HashMap<String, AgentLink>()

    val agents: StateFlow<List<PairedAgent>> = store.agents

    private val _pair = MutableStateFlow<PairState?>(null)
    val pair: StateFlow<PairState?> = _pair.asStateFlow()
    private var pairJob: Job? = null

    private val _message = MutableStateFlow<String?>(null)
    /** A one-off notice for a snackbar (refused / no answer). */
    val message: StateFlow<String?> = _message.asStateFlow()

    val defaultPhoneName: String = Build.MODEL ?: "Phone"

    /** The live link to a PC; created on demand, connected while the app is in the foreground. */
    fun link(agent: PairedAgent): AgentLink =
        links.getOrPut(agent.agentId) {
            AgentLink(agent, store.hubKey, connector, appName, viewModelScope).also { if (foreground) it.start() }
        }

    private var foreground = false

    fun setForeground(value: Boolean) {
        foreground = value
        viewModelScope.launch {
            if (value) agents.value.forEach { link(it).start() } else links.values.forEach { it.stop() }
        }
    }

    fun startPairing(link: PairingLink, phoneName: String) {
        pairJob?.cancel()
        _pair.value = PairState.Connecting(link.pcName)
        pairJob = viewModelScope.launch {
            val outcome = pair(link, store.hubKey, phoneName.ifBlank { defaultPhoneName }, appName, connector) { code ->
                _pair.value = PairState.ShowCode(code)
            }
            _pair.value = when (outcome) {
                is PairOutcome.Paired -> {
                    store.save(outcome.agent)
                    links.remove(outcome.agent.agentId)?.stop() // re-paired: reconnect with the new ak
                    link(outcome.agent).start()
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
            links.remove(agent.agentId)?.stop()
            store.remove(agent.agentId)
        }
    }

    fun startTimer(agent: PairedAgent, minutes: Int) =
        command(agent, "timer.start", buildJsonObject { put("mode", "interval"); put("hours", minutes / 60); put("minutes", minutes % 60) })

    fun stopTimer(agent: PairedAgent) = command(agent, "timer.stop", JsonObject(emptyMap()))

    fun extend(agent: PairedAgent, minutes: Int) = command(agent, "timer.extend", buildJsonObject { put("minutes", minutes) })

    private fun command(agent: PairedAgent, type: String, fields: JsonObject) {
        viewModelScope.launch {
            when (val r = link(agent).send(type, fields)) {
                is RequestOutcome.Refused -> _message.value = getApplication<Application>().getString(R.string.request_refused, r.code)
                RequestOutcome.GaveUp -> _message.value = getApplication<Application>().getString(R.string.request_gave_up)
                else -> Unit
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }
}

internal object BuildConfigInfo {
    const val VERSION = "0.1.0"
}
