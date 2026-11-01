package com.gametimer.parent

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.AgentSchedule
import com.gametimer.protocol.hub.AgentSettings
import com.gametimer.protocol.hub.AlertPoint
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.DaySchedule
import com.gametimer.protocol.hub.HistoryEvent
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.PeerInfo
import com.gametimer.protocol.hub.RequestOutcome
import com.gametimer.protocol.hub.PairedAgent
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// ---------------------------------------------------------------- helpers

/** Reloads [load] now and every time the PC sends one of [events]. */
@Composable
internal fun ReloadOn(link: AgentLink, vararg events: String, load: suspend () -> Unit) {
    val online = link.status.collectStateValue() is LinkStatus.Online
    LaunchedEffect(online) { if (online) load() }
    LaunchedEffect(link) { link.events.filter { it.type() in events }.collect { load() } }
}

@Composable
internal fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectStateValue(): T {
    val v by this.collectAsState()
    return v
}

internal fun JsonObject.type(): String? = (this["t"] as? JsonPrimitive)?.content

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun shortDateTime(iso: String?): String =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()) }.getOrNull() }
        ?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) ?: ""

internal fun shortTime(iso: String?): String =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()) }.getOrNull() }
        ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: ""

@Composable
internal fun actionText(action: String?): String = stringResource(
    when (action) {
        "shutdown" -> R.string.act_shutdown
        "logoff" -> R.string.act_logoff
        else -> R.string.act_lock
    },
)

internal fun parseColor(hex: String?): Color? =
    hex?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }?.let { Color(android.graphics.Color.parseColor(it)) }


/** "HH:MM" (or "H") → minutes 0..1440, else null. */
internal fun parseClock(text: String): Int? {
    val parts = text.trim().split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toIntOrNull() ?: return null else 0
    if (parts.size > 2 || h !in 0..24 || m !in 0..59) return null
    return (h * 60 + m).takeIf { it in 0..1440 }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

// ---------------------------------------------------------------- settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val ed = remember(agent.agentId) { vm.editors(agent.agentId) }
    val online = link.status.collectStateValue() is LinkStatus.Online
    var addingAlert by remember { mutableStateOf(false) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    val minShort = stringResource(R.string.min_short)
    val conflictText = stringResource(R.string.conflict)
    val savedText = stringResource(R.string.saved)
    val invalidText = stringResource(R.string.err_settings)

    ReloadOn(link, "ev.settings") {
        val r = api.settings()
        if (r is ApiResult.Ok && !ed.settingsDirty && !ed.settingsSaving) ed.fillSettings(r.value)
    }
    val e = ed.settingsEdit ?: return Loading()
    fun change(v: AgentSettings) { ed.settingsEdit = v; ed.settingsDirty = true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!online) Text(stringResource(R.string.offline_hint), color = MaterialTheme.colorScheme.error)
        SectionLabel(stringResource(R.string.set_action))
        Dropdown(stringResource(R.string.set_action), listOf("lock", "logoff", "shutdown").map { it to actionText(it) }, e.action) {
            change(e.copy(action = it))
        }
        SectionLabel(stringResource(R.string.set_alerts))
        if (e.alertPoints.isEmpty()) Text(stringResource(R.string.no_alerts), style = MaterialTheme.typography.bodyMedium)
        // A chip per alert: tap switches it on / off, the cross removes it
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.alertPoints.forEach { a ->
                FilterChip(
                    selected = a.enabled,
                    onClick = { change(e.copy(alertPoints = e.alertPoints.map { if (it == a) it.copy(enabled = !a.enabled) else it })) },
                    label = { Text(stringResource(R.string.alert_item, a.minutes)) },
                    trailingIcon = {
                        // Its own 28 dp target, apart from the chip's toggle
                        Box(Modifier.size(28.dp).clip(CircleShape).clickable { change(e.copy(alertPoints = e.alertPoints - a)) },
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, stringResource(R.string.remove), Modifier.size(18.dp))
                        }
                    },
                )
            }
            if (e.alertPoints.size < 20) AssistChip(onClick = { addingAlert = true }, label = { Text(stringResource(R.string.alert_add)) },
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) })
        }
        SectionLabel(stringResource(R.string.set_language))
        Dropdown(stringResource(R.string.set_language), AppLocale.names.toList(), e.language) { change(e.copy(language = it)) }
        // The countdown's look is set once and rarely changed: folded by default, but open while a value
        // there is wrong (Save would refuse it with nothing visible to fix)
        val yellowOk = ed.yellowText.toIntOrNull()?.let { it in 1..1440 } == true
        val redOk = ed.redText.toIntOrNull()?.let { it in 1..1440 } == true
        val lookOk = yellowOk && redOk && listOf(e.colorGreen, e.colorYellow, e.colorRed).all { parseColor(it) != null }
        Row(Modifier.fillMaxWidth().clickable(enabled = lookOk) { appearance = !appearance }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.set_appearance))
            Spacer(Modifier.weight(1f))
            if (lookOk) Icon(if (appearance) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (appearance || !lookOk) {
            Text(stringResource(R.string.set_thresholds), style = MaterialTheme.typography.bodyMedium)
            Stepper(stringResource(R.string.set_yellow), ed.yellowText.toIntOrNull()?.coerceIn(1, 1440) ?: 1, 1..1440,
                text = { "$it $minShort" }, stepOf = ::minuteStep, isError = !yellowOk) { ed.yellowText = it.toString(); ed.settingsDirty = true }
            Stepper(stringResource(R.string.set_red), ed.redText.toIntOrNull()?.coerceIn(1, 1440) ?: 1, 1..1440,
                text = { "$it $minShort" }, stepOf = ::minuteStep, isError = !redOk) { ed.redText = it.toString(); ed.settingsDirty = true }
            ColorSwatches(stringResource(R.string.color_green), e.colorGreen) { change(e.copy(colorGreen = it)) }
            ColorSwatches(stringResource(R.string.color_yellow), e.colorYellow) { change(e.copy(colorYellow = it)) }
            ColorSwatches(stringResource(R.string.color_red), e.colorRed) { change(e.copy(colorRed = it)) }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val toSave = e.copy(yellowThreshold = ed.yellowText.toIntOrNull() ?: -1, redThreshold = ed.redText.toIntOrNull() ?: -1)
            if (!settingsValid(toSave)) { vm.say(invalidText); return@Button }
            ed.settingsSaving = true
            vm.launchSave {
                try { when (val r = api.saveSettings(toSave)) {
                    is ApiResult.Ok -> { ed.fillSettings(r.value); vm.say(savedText) }
                    is ApiResult.Conflict -> { ed.fillSettings(r.current); vm.say(conflictText) }
                    is ApiResult.Failed -> vm.say(vm.outcomeText(r.outcome))
                } } finally { ed.settingsSaving = false }
            }
        }, enabled = online && ed.settingsDirty && !ed.settingsSaving) { Text(stringResource(R.string.save)) }
    }
    if (addingAlert) {
        // Starts at a value not in the list yet; one already there can't be confirmed
        val taken = e.alertPoints.map { it.minutes }.toSet()
        val first = listOf(5, 10, 1, 2, 3, 15, 30).firstOrNull { it !in taken } ?: ((1..1440).firstOrNull { it !in taken } ?: 1)
        NumberDialog(stringResource(R.string.set_alerts), first, 1..1440, stringResource(R.string.alert_add),
            accept = { it !in taken }, onDismiss = { addingAlert = false }) { m ->
            addingAlert = false
            if (e.alertPoints.size >= 20) vm.say(invalidText)
            else change(e.copy(alertPoints = (e.alertPoints + AlertPoint(m, true)).sortedByDescending { it.minutes }))
        }
    }
}

private fun settingsValid(s: AgentSettings): Boolean =
    s.yellowThreshold in 1..1440 && s.redThreshold in 1..1440 &&
        listOf(s.colorGreen, s.colorYellow, s.colorRed).all { parseColor(it) != null } && s.alertPoints.size <= 20 &&
        s.alertPoints.all { it.minutes in 1..1440 } && s.alertPoints.map { it.minutes }.toSet().size == s.alertPoints.size

// ---------------------------------------------------------------- parents

@Composable
fun ParentsTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink, onSelfRemoved: () -> Unit) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val scope = rememberCoroutineScope()
    val online = link.status.collectStateValue() is LinkStatus.Online
    var peers by remember { mutableStateOf<List<PeerInfo>?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var onlyAdmin by remember { mutableStateOf(false) }
    val adminCheck = rememberAdminCheck(vm)

    ReloadOn(link, "ev.peers") { (api.peers() as? ApiResult.Ok)?.let { peers = it.value } }
    val list = peers ?: return Loading()
    val me = list.firstOrNull { it.current }
    val iAmAdmin = me?.role == "admin"

    fun apply(r: ApiResult<List<PeerInfo>>) = when (r) {
        is ApiResult.Ok -> peers = r.value
        is ApiResult.Failed -> vm.say(vm.outcomeText(r.outcome))
        is ApiResult.Conflict -> Unit
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var noScreenLock by remember { mutableStateOf(!vm.reauth.hasScreenLock(context)) }
    // Checked again when the app comes back (the parent may just have set a PIN)
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        noScreenLock = !vm.reauth.hasScreenLock(context)
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // §12 relies on the phone's own lock: without one, anyone holding the phone can change the parents
        if (iAmAdmin && noScreenLock) Text(stringResource(R.string.no_screen_lock), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
        list.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (p.current) Text(stringResource(R.string.this_phone), style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                    val role = stringResource(if (p.role == "admin") R.string.role_admin else R.string.role_parent)
                    Text(if (p.lastSeen == null) stringResource(R.string.peer_never, role) else stringResource(R.string.peer_sub, role, shortDateTime(p.lastSeen)),
                        style = MaterialTheme.typography.bodySmall)
                    if (iAmAdmin && !p.current) {
                        val removeText = stringResource(R.string.confirm_revoke, p.name)
                        Row {
                            TextButton(onClick = { scope.launch { if (adminCheck()) apply(api.setRole(p.id, admin = p.role != "admin")) } }, enabled = online) {
                                Text(stringResource(if (p.role == "admin") R.string.make_parent else R.string.make_admin))
                            }
                            TextButton(onClick = { confirm = removeText to { scope.launch { if (adminCheck()) apply(api.revoke(p.id)) } } }, enabled = online) {
                                Text(stringResource(R.string.revoke_peer))
                            }
                        }
                    }
                }
            }
        }
        if (me != null) {
            val disconnectText = stringResource(R.string.confirm_disconnect)
            OutlinedButton(onClick = {
                confirm = disconnectText to {
                    vm.launchSave {
                        when (val r = api.revoke(me.id)) {
                            is ApiResult.Ok -> onSelfRemoved()
                            is ApiResult.Failed -> when (val o = r.outcome) {
                                // Already removed (the reply was lost and the resend met a revoked session)
                                RequestOutcome.Revoked -> onSelfRemoved()
                                is RequestOutcome.Refused -> if (o.code == "last_admin") onlyAdmin = true
                                    else if (o.code == "forbidden") onSelfRemoved() else vm.say(vm.outcomeText(o))
                                else -> vm.say(vm.outcomeText(o))
                            }
                            else -> Unit
                        }
                    }
                }
            }, enabled = online) { Text(stringResource(R.string.disconnect_self)) }
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (onlyAdmin) {
        AlertDialog(onDismissRequest = { onlyAdmin = false }, text = { Text(stringResource(R.string.only_admin)) },
            confirmButton = { TextButton(onClick = { onlyAdmin = false; onSelfRemoved() }) { Text(stringResource(R.string.remove_local)) } },
            dismissButton = { TextButton(onClick = { onlyAdmin = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
