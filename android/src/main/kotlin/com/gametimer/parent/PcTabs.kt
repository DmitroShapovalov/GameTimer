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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
private fun ReloadOn(link: AgentLink, vararg events: String, load: suspend () -> Unit) {
    val online = link.status.collectStateValue() is LinkStatus.Online
    LaunchedEffect(online) { if (online) load() }
    LaunchedEffect(link) { link.events.filter { it.type() in events }.collect { load() } }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectStateValue(): T {
    val v by this.collectAsState()
    return v
}

private fun JsonObject.type(): String? = (this["t"] as? JsonPrimitive)?.content

private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun shortDateTime(iso: String?): String =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()) }.getOrNull() }
        ?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) ?: ""

private fun shortTime(iso: String?): String =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()) }.getOrNull() }
        ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: ""

@Composable
private fun actionText(action: String?): String = stringResource(
    when (action) {
        "shutdown" -> R.string.act_shutdown
        "logoff" -> R.string.act_logoff
        else -> R.string.act_lock
    },
)

private fun parseColor(hex: String?): Color? =
    hex?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }?.let { Color(android.graphics.Color.parseColor(it)) }


/** "HH:MM" (or "H") → minutes 0..1440, else null. */
private fun parseClock(text: String): Int? {
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

// ---------------------------------------------------------------- timer

private const val PREFS = "timer-form"

@Composable
fun TimerTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val status = link.status.collectStateValue()
    val state = link.state.collectStateValue()
    val seconds = rememberCountdown(state)
    val online = status is LinkStatus.Online
    var settings by remember { mutableStateOf<AgentSettings?>(null) }
    ReloadOn(link, "ev.settings") { (api.settings() as? ApiResult.Ok)?.let { settings = it.value } }

    var confirm by remember { mutableStateOf<Pair<String, suspend () -> RequestOutcome>?>(null) }
    fun run(block: suspend () -> RequestOutcome) = scope.launch {
        val r = block()
        if (r !is RequestOutcome.Ok && r !is RequestOutcome.Stopped) vm.say(vm.outcomeText(r))
    }

    val timer = state?.json?.get("timer") as? JsonObject
    val schedule = state?.json?.get("schedule") as? JsonObject
    val interruption = state?.json?.str("interruption")
    val running = state?.isRunning == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (interruption != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.interruption, shortDateTime(interruption)))
                    TextButton(onClick = { run { api.acknowledgeInterruption() } }, enabled = online) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }
        scheduleChip(schedule)?.let { AssistChip(onClick = {}, label = { Text(it) }) }

        if (running) {
            val color = when {
                settings == null -> MaterialTheme.colorScheme.onSurface
                seconds <= settings!!.redThreshold * 60 -> parseColor(settings!!.colorRed)
                seconds <= settings!!.yellowThreshold * 60 -> parseColor(settings!!.colorYellow)
                else -> parseColor(settings!!.colorGreen)
            } ?: MaterialTheme.colorScheme.onSurface
            Text(formatSeconds(seconds), fontSize = 64.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = color)
            timer?.str("targetTime")?.let { Text(stringResource(R.string.ends_at, shortTime(it), actionText(timer.str("action")))) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val endText = actionText(timer?.str("action"))
                val minusMessage = stringResource(R.string.confirm_end, endText)
                OutlinedButton(onClick = {
                    if (seconds <= 15 * 60) confirm = minusMessage to { api.extend(-15) } else run { api.extend(-15) }
                }, enabled = online) { Text(stringResource(R.string.minus15)) }
                OutlinedButton(onClick = { run { api.extend(15) } }, enabled = online) { Text(stringResource(R.string.plus15)) }
            }
            val stopMessage = stringResource(R.string.confirm_stop)
            Button(onClick = { confirm = stopMessage to { api.stop() } }, enabled = online) { Text(stringResource(R.string.stop)) }
        } else {
            if (timer?.str("state") == "expired") {
                Text("00:00", fontSize = 64.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    color = parseColor(settings?.colorRed) ?: MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.state_expired), style = MaterialTheme.typography.titleMedium)
            } else {
                Text(stringResource(R.string.timer_idle), style = MaterialTheme.typography.titleMedium)
            }
            StartForm(context, online) { interval, h, m -> run { if (interval) api.startInterval(h, m) else api.startUntil(h, m) } }
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; run(action) }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun scheduleChip(schedule: JsonObject?): String? = when (schedule?.str("status")) {
    "allowed" -> stringResource(R.string.sched_today, (schedule.int("availableSeconds") ?: 0) / 60)
    "outsideWindow" -> stringResource(R.string.sched_window)
    "dayDisabled" -> stringResource(R.string.sched_day)
    "limitReached" -> stringResource(R.string.sched_limit)
    else -> null
}

/** Mode (for / until), hours and minutes, presets; the last values per mode are remembered on this phone. */
@Composable
private fun StartForm(context: Context, online: Boolean, onStart: (Boolean, Int, Int) -> Unit) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var interval by rememberSaveable { mutableStateOf(prefs.getBoolean("interval", true)) }
    fun last(isInterval: Boolean) = if (isInterval) prefs.getInt("ih", 1) to prefs.getInt("im", 0) else prefs.getInt("ah", 20) to prefs.getInt("am", 0)
    var h by rememberSaveable { mutableStateOf(last(interval).first.toString()) }
    var m by rememberSaveable { mutableStateOf("%02d".format(last(interval).second)) }

    SingleChoiceSegmentedButtonRow {
        listOf(true to R.string.mode_interval, false to R.string.mode_until).forEachIndexed { i, (isInterval, label) ->
            SegmentedButton(selected = interval == isInterval, shape = SegmentedButtonDefaults.itemShape(i, 2), onClick = {
                interval = isInterval
                val (lh, lm) = last(isInterval)
                h = lh.toString(); m = "%02d".format(lm)
            }) { Text(stringResource(label)) }
        }
    }
    if (interval) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to 30, 1 to 0, 1 to 30, 2 to 0).forEach { (ph, pm) ->
                FilterChip(selected = h == ph.toString() && m == "%02d".format(pm), onClick = { h = ph.toString(); m = "%02d".format(pm) },
                    label = { Text("$ph:%02d".format(pm)) })
            }
        }
    }
    if (!interval) Text(stringResource(R.string.until_hint), style = MaterialTheme.typography.bodySmall)
    val hv = h.toIntOrNull()
    val mv = m.toIntOrNull()
    val valid = hv != null && mv != null && hv in 0..23 && mv in 0..59 && !(interval && hv == 0 && mv == 0)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(h, { v -> h = v.filter { it.isDigit() }.take(2) }, label = { Text(stringResource(if (interval) R.string.hours else R.string.hour)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp))
        Text(":")
        OutlinedTextField(m, { v -> m = v.filter { it.isDigit() }.take(2) }, label = { Text(stringResource(R.string.minutes)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp),
            isError = !valid)
    }
    Button(onClick = {
        prefs.edit().putBoolean("interval", interval)
            .putInt(if (interval) "ih" else "ah", hv!!).putInt(if (interval) "im" else "am", mv!!).apply()
        onStart(interval, hv, mv)
    }, enabled = online && valid) { Text(stringResource(R.string.start)) }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val ed = remember(agent.agentId) { vm.editors(agent.agentId) }
    val online = link.status.collectStateValue() is LinkStatus.Online
    var newAlert by remember { mutableStateOf("") }
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
        listOf("lock", "logoff", "shutdown").forEach { a ->
            Row(Modifier.fillMaxWidth().selectable(selected = e.action == a, onClick = { change(e.copy(action = a)) }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = e.action == a, onClick = { change(e.copy(action = a)) })
                Text(actionText(a))
            }
        }
        SectionLabel(stringResource(R.string.set_thresholds))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberText(stringResource(R.string.set_yellow), ed.yellowText, Modifier.weight(1f)) { ed.yellowText = it; ed.settingsDirty = true }
            NumberText(stringResource(R.string.set_red), ed.redText, Modifier.weight(1f)) { ed.redText = it; ed.settingsDirty = true }
        }
        SectionLabel(stringResource(R.string.set_colors))
        ColorField(stringResource(R.string.color_green), e.colorGreen) { change(e.copy(colorGreen = it)) }
        ColorField(stringResource(R.string.color_yellow), e.colorYellow) { change(e.copy(colorYellow = it)) }
        ColorField(stringResource(R.string.color_red), e.colorRed) { change(e.copy(colorRed = it)) }
        SectionLabel(stringResource(R.string.set_alerts))
        if (e.alertPoints.isEmpty()) Text(stringResource(R.string.no_alerts))
        e.alertPoints.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(a.enabled, { on -> change(e.copy(alertPoints = e.alertPoints.map { if (it == a) it.copy(enabled = on) else it })) })
                Text(stringResource(R.string.alert_item, a.minutes), Modifier.weight(1f))
                TextButton(onClick = { change(e.copy(alertPoints = e.alertPoints - a)) }) { Text(stringResource(R.string.remove)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newAlert, { v -> newAlert = v.filter { it.isDigit() }.take(4) }, label = { Text(stringResource(R.string.minutes)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(140.dp))
            OutlinedButton(onClick = {
                val m = newAlert.toIntOrNull()
                if (m == null || m !in 1..1440 || e.alertPoints.any { it.minutes == m } || e.alertPoints.size >= 20) vm.say(invalidText)
                else { change(e.copy(alertPoints = (e.alertPoints + AlertPoint(m, true)).sortedByDescending { it.minutes })); newAlert = "" }
            }) { Text(stringResource(R.string.alert_add)) }
        }
        SectionLabel(stringResource(R.string.set_language))
        listOf("en" to "English", "ru" to "Русский", "nl" to "Nederlands", "uk" to "Українська").forEach { (code, name) ->
            Row(Modifier.fillMaxWidth().selectable(selected = e.language == code, onClick = { change(e.copy(language = code)) }),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = e.language == code, onClick = { change(e.copy(language = code)) })
                Text(name)
            }
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
}

private fun settingsValid(s: AgentSettings): Boolean =
    s.yellowThreshold in 1..1440 && s.redThreshold in 1..1440 &&
        listOf(s.colorGreen, s.colorYellow, s.colorRed).all { parseColor(it) != null } && s.alertPoints.size <= 20 &&
        s.alertPoints.all { it.minutes in 1..1440 } && s.alertPoints.map { it.minutes }.toSet().size == s.alertPoints.size

/** A number field over the text the user typed (validated on save, marked while out of range). */
@Composable
private fun NumberText(label: String, text: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(text, { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = text.toIntOrNull()?.let { it !in 1..1440 } ?: true, modifier = modifier)
}

@Composable
private fun ColorField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).background(parseColor(value) ?: Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)))
        OutlinedTextField(value, { v ->
            val hex = v.trim().uppercase().filter { it == '#' || it.isDigit() || it in 'A'..'F' }
            onChange(("#" + hex.removePrefix("#").replace("#", "")).take(7))
        }, label = { Text(label) }, singleLine = true, isError = parseColor(value) == null, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- schedule

private val DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0) // Mon..Sun, index = weekday with 0 = Sunday

@Composable
fun ScheduleTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val ed = remember(agent.agentId) { vm.editors(agent.agentId) }
    val online = link.status.collectStateValue() is LinkStatus.Online
    val conflictText = stringResource(R.string.conflict)
    val savedText = stringResource(R.string.saved)
    val invalidText = stringResource(R.string.err_schedule)

    ReloadOn(link, "ev.schedule") {
        val r = api.schedule()
        if (r is ApiResult.Ok && !ed.scheduleDirty && !ed.scheduleSaving) ed.fillSchedule(r.value)
    }
    val loaded = ed.scheduleLoaded
    if (loaded == null || ed.days.size != 7) return Loading()
    val days = ed.days

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!online) Text(stringResource(R.string.offline_hint), color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sched_enable), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(ed.scheduleEnabled, { ed.scheduleEnabled = it; ed.scheduleDirty = true })
        }
        Text(stringResource(R.string.sched_hint), style = MaterialTheme.typography.bodySmall)
        DAY_ORDER.forEach { wd ->
            val d = days[wd]
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(java.time.DayOfWeek.of(if (wd == 0) 7 else wd).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                    Modifier.width(44.dp))
                Checkbox(d.allowed, { days[wd] = d.copy(allowed = it); ed.scheduleDirty = true })
                SmallField(stringResource(R.string.sched_limit_label), d.limit, KeyboardType.Number, Modifier.weight(1f),
                    error = d.limit.toIntOrNull()?.let { it !in 0..1440 } ?: true) { days[wd] = d.copy(limit = it.filter(Char::isDigit).take(4)); ed.scheduleDirty = true }
                SmallField(stringResource(R.string.sched_from), d.from, KeyboardType.Text, Modifier.weight(1.2f),
                    error = parseClock(d.from) == null) { days[wd] = d.copy(from = it.take(5)); ed.scheduleDirty = true }
                SmallField(stringResource(R.string.sched_to), d.to, KeyboardType.Text, Modifier.weight(1.2f),
                    error = parseClock(d.to) == null) { days[wd] = d.copy(to = it.take(5)); ed.scheduleDirty = true }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val week = days.map { d ->
                val limit = d.limit.toIntOrNull()?.takeIf { it in 0..1440 }
                val from = parseClock(d.from)
                val to = parseClock(d.to)
                if (limit == null || from == null || to == null) null else DaySchedule(d.allowed, limit, from, to)
            }
            if (week.any { it == null }) { vm.say(invalidText); return@Button }
            val toSave = loaded.copy(enabled = ed.scheduleEnabled, week = week.map { it!! })
            ed.scheduleSaving = true
            vm.launchSave {
                try { when (val r = api.saveSchedule(toSave)) {
                    is ApiResult.Ok -> { ed.fillSchedule(r.value); vm.say(savedText) }
                    is ApiResult.Conflict -> { ed.fillSchedule(r.current); vm.say(conflictText) }
                    is ApiResult.Failed -> vm.say(vm.outcomeText(r.outcome))
                } } finally { ed.scheduleSaving = false }
            }
        }, enabled = online && ed.scheduleDirty && !ed.scheduleSaving) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun SmallField(label: String, value: String, type: KeyboardType, modifier: Modifier, error: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 11.sp) }, singleLine = true, isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = modifier)
}

// ---------------------------------------------------------------- history

@Composable
fun HistoryTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val scope = rememberCoroutineScope()
    val events = remember { mutableStateListOf<HistoryEvent>() }
    var next by remember { mutableStateOf<String?>(null) }
    var loadedOnce by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) } // a reload makes older "load more" results stale

    suspend fun reload() {
        val gen = ++generation
        val r = api.history()
        if (r is ApiResult.Ok && gen == generation) {
            events.clear(); events.addAll(r.value.events); next = r.value.next.takeIf { r.value.more }; loadedOnce = true
        }
    }
    val online = link.status.collectStateValue() is LinkStatus.Online
    LaunchedEffect(online) { if (online) reload() }
    // A new event is put on top; the pages below stay as they are (their cursors remain valid)
    LaunchedEffect(link) {
        link.events.filter { it.type() == "ev.history" }.collect { ev ->
            val e = (ev["event"] as? JsonObject)?.let { runCatching { HistoryJson.decodeFromJsonElement(HistoryEvent.serializer(), it) }.getOrNull() }
            if (e != null && loadedOnce) events.add(0, e) else reload()
        }
    }
    if (!loadedOnce) return Loading()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (events.isEmpty()) Text(stringResource(R.string.history_empty))
        events.forEach { e ->
            Column {
                Row {
                    Text(shortDateTime(e.time), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(who(e), style = MaterialTheme.typography.bodySmall)
                }
                Text(eventText(e))
            }
            HorizontalDivider()
        }
        next?.let { cursor ->
            OutlinedButton(onClick = {
                if (loadingMore) return@OutlinedButton
                loadingMore = true
                val gen = generation
                scope.launch {
                    when (val r = api.history(before = cursor)) {
                        is ApiResult.Ok -> if (gen == generation && next == cursor) {
                            events.addAll(r.value.events); next = r.value.next.takeIf { r.value.more }
                        }
                        is ApiResult.Failed -> {
                            val refused = r.outcome as? RequestOutcome.Refused
                            if (refused?.code == "bad_request") reload() // the cursor was trimmed from the log: start over
                            else vm.say(vm.outcomeText(r.outcome))
                        }
                        else -> Unit
                    }
                    loadingMore = false
                }
            }, enabled = !loadingMore) { Text(stringResource(R.string.load_more)) }
        }
    }
}

private val HistoryJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@Composable
private fun who(e: HistoryEvent): String = when (e.source) {
    "pc" -> stringResource(R.string.who_pc)
    "phone" -> e.device.orEmpty()
    else -> stringResource(R.string.who_system)
}

@Composable
private fun eventText(e: HistoryEvent): String = when (e.type) {
    "started" -> {
        val m = e.minutes ?: 0
        val dur = if (m >= 60) "%d:%02d".format(m / 60, m % 60) else "$m ${stringResource(R.string.min_short)}"
        stringResource(R.string.ev_started, dur, stringResource(if (e.mode == "absolute") R.string.ev_mode_until else R.string.ev_mode_interval))
    }
    "stopped" -> stringResource(R.string.ev_stopped)
    "reset" -> stringResource(R.string.ev_reset)
    "extended" -> if ((e.minutes ?: 0) >= 0) stringResource(R.string.ev_plus, e.minutes ?: 0) else stringResource(R.string.ev_minus, -(e.minutes ?: 0))
    "expired" -> stringResource(R.string.ev_expired, actionText(e.action))
    "resumed" -> stringResource(R.string.ev_resumed)
    "closed" -> stringResource(R.string.ev_closed)
    "tampered" -> stringResource(R.string.ev_tampered)
    "updated" -> stringResource(R.string.ev_updated)
    else -> e.type
}

// ---------------------------------------------------------------- parents

@Composable
fun ParentsTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink, onSelfRemoved: () -> Unit) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val scope = rememberCoroutineScope()
    val online = link.status.collectStateValue() is LinkStatus.Online
    var peers by remember { mutableStateOf<List<PeerInfo>?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var onlyAdmin by remember { mutableStateOf(false) }

    ReloadOn(link, "ev.peers") { (api.peers() as? ApiResult.Ok)?.let { peers = it.value } }
    val list = peers ?: return Loading()
    val me = list.firstOrNull { it.current }
    val iAmAdmin = me?.role == "admin"

    fun apply(r: ApiResult<List<PeerInfo>>) = when (r) {
        is ApiResult.Ok -> peers = r.value
        is ApiResult.Failed -> vm.say(vm.outcomeText(r.outcome))
        is ApiResult.Conflict -> Unit
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            TextButton(onClick = { scope.launch { apply(api.setRole(p.id, admin = p.role != "admin")) } }, enabled = online) {
                                Text(stringResource(if (p.role == "admin") R.string.make_parent else R.string.make_admin))
                            }
                            TextButton(onClick = { confirm = removeText to { scope.launch { apply(api.revoke(p.id)) } } }, enabled = online) {
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
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
