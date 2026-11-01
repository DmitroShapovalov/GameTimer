package com.gametimer.parent

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.AgentSettings
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.hub.RequestOutcome
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// The PC's state first, as the big parental apps open: a ring with the time left and when it ends, a
// state chip, then the everyday actions (− / +, lift for today). Ending a timer now or at a set time
// is in the "more" menu, still behind a confirmation. With no timer, the start form takes the ring's place.

private const val PREFS = "timer-form"

/** Steps for − / + on a running timer, in minutes. */
private val STEPS = listOf(1, 5, 10, 15, 30, 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
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
    val expired = !running && timer?.str("state") == "expired"
    // Lift / end now / end at: agents with protocol minor ≥ 1 (v1.7.1); older ones keep Stop and ±
    val controls = (status as? LinkStatus.Online)?.let { AgentApi.supportsTimerControls(it.hello) } == true
    val scheduleStatus = schedule?.str("status")
    val scheduleRestricts = scheduleStatus in setOf("allowed", "outsideWindow", "dayDisabled", "limitReached")
    var liftAsk by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // The timer ended meanwhile: nothing left to move, end or confirm (a lift dialog opened with no timer stays)
    var wasRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (!running) { pickEnd = false; menu = false }
        if (wasRunning && !running) { confirm = null; liftAsk = false }
        wasRunning = running
    }
    val confirmLiftText = stringResource(R.string.confirm_lift)
    // No schedule today: lifting is just stopping the timer; otherwise ask about today's schedule
    val askLift: () -> Unit = {
        if (scheduleRestricts) liftAsk = true
        else confirm = confirmLiftText to { api.lift(skipScheduleToday = false) }
    }
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var step by rememberSaveable { mutableIntStateOf(prefs.getInt("step", 15).takeIf { it in STEPS } ?: 15) }

    // The ring is the time left against the timer's whole length. The state doesn't carry the length,
    // so it is read from the history: the last "started" plus the − / + after it. Read again whenever
    // the end moves; without history (or before it arrives) the longest time left seen here stands in.
    var total by remember(agent.agentId) { mutableIntStateOf(0) }
    var longest by remember(agent.agentId) { mutableIntStateOf(0) }
    val targetTime = timer?.str("targetTime")
    LaunchedEffect(running, targetTime) {
        if (!running) { total = 0; longest = 0; return@LaunchedEffect }
        total = (api.history(limit = 30) as? ApiResult.Ok)?.value?.events?.let { timerLength(it) } ?: 0
    }
    androidx.compose.runtime.SideEffect { if (running && seconds > longest) longest = seconds }

    val colors = StateColors.current()
    val zoneColor = when {
        !running -> colors.timeUp
        settings == null -> colors.good
        seconds <= settings!!.redThreshold * 60 -> parseColor(settings!!.colorRed)
        seconds <= settings!!.yellowThreshold * 60 -> parseColor(settings!!.colorYellow)
        else -> parseColor(settings!!.colorGreen)
    } ?: colors.good

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

        if (running || expired) {
            val fraction = if (running) seconds.toFloat() / maxOf(if (total > 0) total else longest, seconds, 1) else 0f
            TimeRing(fraction, zoneColor, Modifier.size(220.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (running) formatSeconds(seconds) else "00:00", fontSize = 44.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = if (running) MaterialTheme.colorScheme.onSurface else colors.timeUp)
                    if (running) timer?.str("targetTime")?.let {
                        Text(stringResource(R.string.ends_short, shortTime(it)), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        StateChip(
            when {
                running -> stringResource(R.string.st_running)
                expired -> stringResource(R.string.state_expired)
                else -> stringResource(R.string.timer_idle)
            },
            when { running -> colors.good; expired -> colors.timeUp; else -> colors.neutral },
        )
        if (running) timer?.str("action")?.let {
            Text(stringResource(R.string.then_action, actionText(it)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        scheduleLine(schedule)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        if (running) {
            val endMessage = stringResource(R.string.confirm_end, actionText(timer?.str("action")))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    if (seconds <= step * 60) confirm = endMessage to { api.extend(-step) } else run { api.extend(-step) }
                }, enabled = online, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.minus_n, step)) }
                Button(onClick = { run { api.extend(step) } }, enabled = online, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.plus_n, step)) }
            }
            // The step for − / +, remembered on this phone
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                STEPS.forEach { n ->
                    FilterChip(selected = step == n, onClick = { step = n; prefs.edit().putInt("step", n).apply() },
                        label = { Text(n.toString()) })
                }
            }
            if (controls) OutlinedButton(onClick = askLift, enabled = online, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lift)) }
            // End now / end at (and Stop on older PCs): rarer and final, so in a menu, still confirmed
            val stopMessage = stringResource(R.string.confirm_stop)
            Box {
                TextButton(onClick = { menu = true }, enabled = online) {
                    Icon(Icons.Filled.MoreVert, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.more_actions), Modifier.padding(start = 6.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (controls) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.end_at_button)) }, enabled = online,
                            onClick = { menu = false; pickEnd = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.end_now), color = MaterialTheme.colorScheme.error) }, enabled = online,
                            onClick = { menu = false; confirm = endMessage to { api.endNow() } })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.stop), color = MaterialTheme.colorScheme.error) }, enabled = online,
                            onClick = { menu = false; confirm = stopMessage to { api.stop() } })
                    }
                }
            }
        } else {
            StartForm(context, online) { interval, h, m -> run { if (interval) api.startInterval(h, m) else api.startUntil(h, m) } }
            // No timer, but the schedule limits today (or has locked the PC): set it aside for today
            if (controls && scheduleRestricts) {
                OutlinedButton(onClick = askLift, enabled = online, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lift_today)) }
            }
        }
    }

    if (liftAsk) {
        val available = (schedule?.int("availableSeconds") ?: 0) / 60
        AlertDialog(onDismissRequest = { liftAsk = false },
            title = { Text(stringResource(R.string.lift_title)) },
            text = { Text(if (scheduleStatus == "allowed") stringResource(R.string.lift_allowed, available) else stringResource(R.string.lift_blocked)) },
            confirmButton = { TextButton(onClick = { liftAsk = false; run { api.lift(skipScheduleToday = true) } }) { Text(stringResource(R.string.lift_skip_today)) } },
            dismissButton = {
                Row {
                    if (running) TextButton(onClick = { liftAsk = false; run { api.lift(skipScheduleToday = false) } }) { Text(stringResource(R.string.lift_only_timer)) }
                    TextButton(onClick = { liftAsk = false }) { Text(stringResource(R.string.cancel)) }
                }
            })
    }

    if (pickEnd) {
        val now = remember { java.time.LocalTime.now() }
        val target = timer?.str("targetTime")?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()) }.getOrNull() }
        TimeDialog((target?.hour ?: now.hour) * 60 + (target?.minute ?: now.minute), onDismiss = { pickEnd = false }) { picked ->
            pickEnd = false
            val endIn = (status as? LinkStatus.Online)?.let { AgentApi.supportsEndIn(it.hello) } == true
            // The time is picked on the phone's clock, in the phone's time zone; newer PCs get it as a duration
            // from now, so the PC's own time zone and clock don't matter (older ones get the clock time)
            if (endIn) run { api.setEndIn(secondsUntil(picked)) } else run { api.setEnd(picked / 60, picked % 60) }
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; run(action) }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

/**
 * The running timer's length in seconds from the newest history (newest first): the last "started" plus
 * every − / + recorded after it; null when the timer's start isn't among these events.
 */
private fun timerLength(events: List<com.gametimer.protocol.hub.HistoryEvent>): Int? {
    var changes = 0
    for (e in events) {
        when (e.type) {
            "extended" -> changes += e.minutes ?: 0
            "started" -> return ((e.minutes ?: return null) + changes).takeIf { it > 0 }?.times(60)
            "stopped", "reset", "expired" -> return null // a newer end: the current timer started after these events
        }
    }
    return null
}

/** Seconds from now until the next time the phone's clock shows [minutes] past midnight (tomorrow if passed). */
private fun secondsUntil(minutes: Int): Int {
    val now = java.time.ZonedDateTime.now()
    var target = now.with(java.time.LocalTime.of(minutes / 60, minutes % 60))
    if (!target.isAfter(now)) target = target.plusDays(1)
    return java.time.Duration.between(now, target).seconds.toInt().coerceIn(1, 86400)
}

/** A ring over a track: [fraction] (0..1) of it drawn in [color], clockwise from the top. */
@Composable
private fun TimeRing(fraction: Float, color: Color, modifier: Modifier, content: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            if (fraction > 0f) drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
private fun StateChip(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
}

@Composable
private fun scheduleLine(schedule: JsonObject?): String? = when (schedule?.str("status")) {
    "allowed" -> stringResource(R.string.sched_today, (schedule.int("availableSeconds") ?: 0) / 60)
    "outsideWindow" -> stringResource(R.string.sched_window)
    "dayDisabled" -> stringResource(R.string.sched_day)
    "limitReached" -> stringResource(R.string.sched_limit)
    "skippedToday" -> stringResource(R.string.sched_skipped)
    else -> null
}

/** For / until, presets and a − / + stepper (no typing); the last values per mode are remembered on this phone. */
@Composable
private fun StartForm(context: Context, online: Boolean, onStart: (Boolean, Int, Int) -> Unit) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var interval by rememberSaveable { mutableStateOf(prefs.getBoolean("interval", true)) }
    // Interval: minutes 1..23:59; until: minutes since midnight on the PC
    fun last(isInterval: Boolean) = if (isInterval) (prefs.getInt("ih", 1) * 60 + prefs.getInt("im", 0)).coerceIn(1, 1439)
        else (prefs.getInt("ah", 20) * 60 + prefs.getInt("am", 0)).coerceIn(0, 1439)
    var value by rememberSaveable { mutableIntStateOf(last(interval)) }
    var picking by remember { mutableStateOf(false) }
    val h = stringResource(R.string.hours_short)
    val min = stringResource(R.string.min_short)

    SingleChoiceSegmentedButtonRow {
        listOf(true to R.string.mode_interval, false to R.string.mode_until).forEachIndexed { i, (isInterval, label) ->
            SegmentedButton(selected = interval == isInterval, shape = SegmentedButtonDefaults.itemShape(i, 2), onClick = {
                interval = isInterval
                value = last(isInterval)
            }) { Text(stringResource(label)) }
        }
    }
    if (interval) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 90, 120).forEach { p ->
                FilterChip(selected = value == p, onClick = { value = p }, label = { Text("%d:%02d".format(p / 60, p % 60)) })
            }
        }
        Stepper(stringResource(R.string.duration), value, 1..1439,
            text = { if (it < 60) "$it $min" else if (it % 60 == 0) "${it / 60} $h" else "%d:%02d".format(it / 60, it % 60) },
            stepOf = { if (it < 60) 5 else 15 }) { value = it }
    } else {
        Text(stringResource(R.string.until_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { picking = true }) {
            Text(PcEditors.clock(value), style = MaterialTheme.typography.titleLarge)
        }
        if (picking) TimeDialog(value, onDismiss = { picking = false }) { value = it; picking = false }
    }
    Button(onClick = {
        val hv = value / 60
        val mv = value % 60
        prefs.edit().putBoolean("interval", interval)
            .putInt(if (interval) "ih" else "ah", hv).putInt(if (interval) "im" else "am", mv).apply()
        onStart(interval, hv, mv)
    }, enabled = online, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start)) }
}
