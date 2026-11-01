package com.gametimer.parent

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.DaySchedule
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.PairedAgent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// The weekly plan as Microsoft Family Safety and Family Link show it: the week at a glance (a bar for
// each day's allowed hours, its limit on the right), one day edited at a time in a bottom sheet, then
// copied to other days. Edits stay on the phone until Save (the PC checks the revision, §7.2).

private val DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0) // Mon..Sun, index = weekday with 0 = Sunday
private val WEEKDAYS = listOf(1, 2, 3, 4, 5)
private val WEEKEND = listOf(6, 0)

private fun dayName(wd: Int, style: TextStyle) =
    DayOfWeek.of(if (wd == 0) 7 else wd).getDisplayName(style, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** "1:30", "2 h", "45 min" for a limit in minutes. */
private fun duration(minutes: Int, h: String, min: String): String = when {
    minutes < 60 -> "$minutes $min"
    minutes % 60 == 0 -> "${minutes / 60} $h"
    else -> "%d:%02d".format(minutes / 60, minutes % 60)
}

/** A day as numbers; text that can't be read (an old half-typed value) falls back to a whole free day. */
private data class Day(val allowed: Boolean, val limit: Int, val from: Int, val to: Int) {
    fun edit() = PcEditors.DayEdit(allowed, limit.toString(), PcEditors.clock(from), PcEditors.clock(to))

    companion object {
        fun of(d: PcEditors.DayEdit) = Day(d.allowed, d.limit.toIntOrNull()?.coerceIn(0, 1440) ?: 0,
            parseClock(d.from) ?: 0, parseClock(d.to) ?: 1440)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val ed = remember(agent.agentId) { vm.editors(agent.agentId) }
    val online = link.status.collectStateValue() is LinkStatus.Online
    val conflictText = stringResource(R.string.conflict)
    val savedText = stringResource(R.string.saved)
    val invalidText = stringResource(R.string.err_schedule)
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }

    ReloadOn(link, "ev.schedule") {
        val r = api.schedule()
        if (r is ApiResult.Ok && !ed.scheduleDirty && !ed.scheduleSaving) ed.fillSchedule(r.value)
    }
    val loaded = ed.scheduleLoaded
    if (loaded == null || ed.days.size != 7) return Loading()
    val days = ed.days
    val todayWd = LocalDate.now().dayOfWeek.value % 7
    val h = stringResource(R.string.hours_short)
    val min = stringResource(R.string.min_short)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!online) Text(stringResource(R.string.offline_hint), color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sched_enable), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(ed.scheduleEnabled, { ed.scheduleEnabled = it; ed.scheduleDirty = true })
        }
        Text(stringResource(R.string.sched_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 0 / 12 / 24 above the bars
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Spacer(Modifier.width(52.dp))
            Row(Modifier.weight(1f)) {
                Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("12", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("24", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(72.dp))
        }
        val dim = !ed.scheduleEnabled
        DAY_ORDER.forEach { wd ->
            val d = Day.of(days[wd])
            val today = wd == todayWd
            Row(
                Modifier.fillMaxWidth()
                    .background(if (today) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .clickable { editing = wd }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dayName(wd, TextStyle.SHORT), Modifier.width(52.dp).padding(start = 8.dp),
                    fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal)
                WindowBar(d, dim, Modifier.weight(1f).height(12.dp))
                Text(
                    when {
                        !d.allowed -> stringResource(R.string.sched_off)
                        d.limit == 0 -> stringResource(R.string.sched_no_limit)
                        else -> duration(d.limit, h, min)
                    },
                    Modifier.width(72.dp).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (d.allowed && !dim) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
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
        }, enabled = online && ed.scheduleDirty && !ed.scheduleSaving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    }

    editing?.let { wd ->
        DayEditor(wd, Day.of(days[wd]), onChange = { days[wd] = it.edit(); ed.scheduleDirty = true },
            onCopy = { targets ->
                val src = days[wd]
                targets.filter { it != wd }.forEach { days[it] = src }
                ed.scheduleDirty = true
            },
            onDone = { editing = null })
    }
}

/** The day as a bar over 0–24 h: the allowed hours filled, the rest a quiet track; a day without gaming has no fill. */
@Composable
private fun WindowBar(d: Day, dim: Boolean, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = if (dim) 0.3f else 0.75f)
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (d.allowed && d.to > d.from) {
            val x0 = size.width * d.from / 1440f
            val x1 = size.width * d.to / 1440f
            drawRoundRect(fill, topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = r)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DayEditor(wd: Int, day: Day, onChange: (Day) -> Unit, onCopy: (List<Int>) -> Unit, onDone: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = from, false = to
    val h = stringResource(R.string.hours_short)
    val min = stringResource(R.string.min_short)
    val copied = stringResource(R.string.sched_copied)
    var copiedTo by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayName(wd, TextStyle.FULL), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text(stringResource(R.string.sched_done)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sched_allowed), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(day.allowed, { onChange(day.copy(allowed = it)) })
            }
            if (day.allowed) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.sched_window_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { picking = true }) { Text(PcEditors.clock(day.from)) }
                        Text("–")
                        OutlinedButton(onClick = { picking = false }) { Text(PcEditors.clock(day.to)) }
                    }
                }
                Stepper(stringResource(R.string.sched_daily_limit), day.limit, 0..1440,
                    text = { if (it == 0) "—" else duration(it, h, min) }, stepOf = { 15 }) { onChange(day.copy(limit = it)) }
                Text(stringResource(if (day.limit == 0) R.string.sched_no_limit_hint else R.string.sched_limit_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sched_copy_to), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        stringResource(R.string.sched_weekdays) to WEEKDAYS,
                        stringResource(R.string.sched_weekend) to WEEKEND,
                        stringResource(R.string.sched_every_day) to DAY_ORDER,
                    ).forEach { (label, targets) ->
                        AssistChip(onClick = { onCopy(targets); copiedTo = label }, label = { Text(label) })
                    }
                }
                copiedTo?.let { Text("$copied: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    picking?.let { from ->
        // The end of the day is 24:00; the clock picker can't show it, so 0:00 as an end means 24:00
        val minutes = if (from) day.from else day.to
        TimeDialog(minutes % 1440, onDismiss = { picking = null }) { picked ->
            picking = null
            if (from) onChange(day.copy(from = picked)) else onChange(day.copy(to = if (picked == 0) 1440 else picked))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(minutes / 60, minutes % 60, DateFormat.is24HourFormat(context))
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}
