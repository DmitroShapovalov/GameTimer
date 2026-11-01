package com.gametimer.parent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.HistoryEvent
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.hub.RequestOutcome
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// The PC's history as a report: days under their own headers, every kind of event with its own icon
// and colour (the same state colours as the rest of the app), and a filter for what matters now.

private enum class HistoryFilter(val label: Int) { All(R.string.hist_all), Timer(R.string.hist_timer), Alerts(R.string.hist_alerts) }

private val TIMER_TYPES = setOf("started", "stopped", "reset", "extended", "expired", "resumed", "scheduleSkipped", "scheduleRestored")
private val ALERT_TYPES = setOf("closed", "tampered")

private fun HistoryFilter.accepts(e: HistoryEvent) = when (this) {
    HistoryFilter.All -> true
    HistoryFilter.Timer -> e.type in TIMER_TYPES
    HistoryFilter.Alerts -> e.type in ALERT_TYPES
}

private fun localTime(e: HistoryEvent): ZonedDateTime? =
    runCatching { OffsetDateTime.parse(e.time).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()

/** An event with a key of its own (events have no id; two may be alike) and its local time, read once. */
private class Shown(val key: Long, val event: HistoryEvent) {
    val time: ZonedDateTime? = localTime(event)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val scope = rememberCoroutineScope()
    val events = remember { mutableStateListOf<Shown>() }
    var keys by remember { mutableStateOf(0L) }
    fun shown(list: List<HistoryEvent>) = list.map { Shown(keys++, it) }
    var next by remember { mutableStateOf<String?>(null) }
    var loadedOnce by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) } // a reload makes older "load more" results stale
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.All) }

    suspend fun reload() {
        val gen = ++generation
        val r = api.history()
        if (r is ApiResult.Ok && gen == generation) {
            events.clear(); events.addAll(shown(r.value.events)); next = r.value.next.takeIf { r.value.more }; loadedOnce = true
        }
    }
    val online = link.status.collectStateValue() is LinkStatus.Online
    LaunchedEffect(online) { if (online) reload() }
    // A new event is put on top; the pages below stay as they are (their cursors remain valid)
    LaunchedEffect(link) {
        link.events.filter { it.type() == "ev.history" }.collect { ev ->
            val e = (ev["event"] as? JsonObject)?.let { runCatching { HistoryJson.decodeFromJsonElement(HistoryEvent.serializer(), it) }.getOrNull() }
            // the same event may already be there when a reload raced with it
            if (e != null && loadedOnce) { if (events.take(10).none { it.event == e }) events.add(0, Shown(keys++, e)) } else reload()
        }
    }
    if (!loadedOnce) return Loading()

    // "Today" moves on at midnight even while the screen stays open
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val untilMidnight = java.time.Duration.between(java.time.LocalDateTime.now(), today.plusDays(1).atStartOfDay()).toMillis()
            kotlinx.coroutines.delay(untilMidnight.coerceAtLeast(1_000) + 500)
            today = LocalDate.now()
        }
    }
    // Newest first, as the PC sends them. One group per day, in the order the days first appear (a PC
    // clock set back can't split a day in two); an event whose time can't be read joins the day above it
    val groups by remember {
        derivedStateOf {
            val byDay = LinkedHashMap<LocalDate?, MutableList<Shown>>()
            var last: LocalDate? = null
            events.forEach { e ->
                if (!filter.accepts(e.event)) return@forEach
                val day = e.time?.toLocalDate() ?: last
                byDay.getOrPut(day) { mutableListOf() }.add(e)
                last = day
            }
            byDay.toList()
        }
    }
    val todayText = stringResource(R.string.hist_today)
    val yesterdayText = stringResource(R.string.hist_yesterday)
    val dayFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
        item(key = "filters") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) })
                }
            }
        }
        if (groups.isEmpty()) item(key = "empty") {
            // With a filter on, older pages may still hold such events: say so, the button below loads them
            Text(stringResource(if (filter != HistoryFilter.All && next != null) R.string.hist_none_loaded else R.string.history_empty),
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        groups.forEach { (day, list) ->
            stickyHeader(key = "day-$day") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (day) {
                            null -> "—"
                            today -> todayText
                            today.minusDays(1) -> yesterdayText
                            else -> day.format(dayFormat)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(list, key = { it.key }) { e -> EventRow(e.event, e.time?.format(timeFormat) ?: "") }
        }
        next?.let { cursor ->
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = {
                        if (loadingMore) return@OutlinedButton
                        loadingMore = true
                        val gen = generation
                        scope.launch {
                            when (val r = api.history(before = cursor)) {
                                is ApiResult.Ok -> if (gen == generation && next == cursor) {
                                    events.addAll(shown(r.value.events)); next = r.value.next.takeIf { r.value.more }
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
    }
}

@Composable
private fun EventRow(e: HistoryEvent, time: String) {
    val (icon, color) = look(e)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(eventText(e), style = MaterialTheme.typography.bodyLarge)
            Text(listOf(time, who(e)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The icon and the state colour of an event: started green, changed blue, time up orange, alerts red. */
@Composable
private fun look(e: HistoryEvent): Pair<ImageVector, Color> {
    val c = StateColors.current()
    return when (e.type) {
        "started" -> Icons.Filled.PlayArrow to c.good
        "resumed" -> Icons.Filled.Refresh to c.good
        "extended" -> (if ((e.minutes ?: 0) >= 0) Icons.Filled.Add else Icons.Filled.Edit) to c.changed
        "scheduleSkipped", "scheduleRestored" -> Icons.Filled.DateRange to c.changed
        "stopped", "reset" -> Icons.Filled.Close to c.neutral
        "updated" -> Icons.Filled.Settings to c.neutral
        "expired" -> Icons.Filled.Lock to c.timeUp
        "closed", "tampered" -> Icons.Filled.Warning to c.alert
        else -> Icons.Filled.Edit to c.neutral
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
    "scheduleSkipped" -> stringResource(R.string.ev_sched_skipped)
    "scheduleRestored" -> stringResource(R.string.ev_sched_restored)
    else -> e.type
}
