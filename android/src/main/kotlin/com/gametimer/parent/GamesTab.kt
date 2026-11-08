package com.gametimer.parent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.AgentApps
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.PairedAgent
import kotlinx.coroutines.launch

/**
 * The games of a child's phone (capability `apps`, protocol minor 4): the apps installed there, the limited
 * ones ticked. The timer and the schedule count and block only these. A search field narrows the list; the
 * selection is saved with the phone's `rev`, so another parent's change meanwhile is reported, not overwritten.
 */
@Composable
fun GamesTab(vm: MainViewModel, agent: PairedAgent, link: AgentLink) {
    val api = remember(agent.agentId) { AgentApi(link) }
    val scope = rememberCoroutineScope()
    val online = link.status.collectStateValue() is LinkStatus.Online
    var apps by remember { mutableStateOf<AgentApps?>(null) }
    /** What the edit started from: saved with its rev, so another parent's change meanwhile is a conflict. */
    var base by remember { mutableStateOf<AgentApps?>(null) }
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val conflictText = stringResource(R.string.conflict)
    val savedText = stringResource(R.string.games_saved)

    fun limitedOf(a: AgentApps?) = a?.installed?.filter { it.limited }?.map { it.id }?.toSet()
    fun show(a: AgentApps) {
        apps = a
        base = a
        picked = a.installed.filter { it.limited }.map { it.id }.toSet()
    }
    // Another parent's change reloads the list, unless this parent is in the middle of editing it
    ReloadOn(link, "ev.apps") {
        val a = (api.apps() as? ApiResult.Ok)?.value ?: return@ReloadOn
        if (base == null || picked == limitedOf(base)) show(a) else apps = a // the list refreshes; the edit keeps its rev
    }

    val current = apps
    val from = base
    val selection = picked
    if (current == null || from == null || selection == null) {
        Text(stringResource(R.string.loading), Modifier.padding(24.dp))
        return
    }
    val original = limitedOf(from)!!
    val shown = current.installed.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.games_hint), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.games_count, selection.size), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text(stringResource(R.string.games_search)) }, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { app ->
                val on = app.id in selection
                Row(Modifier.fillMaxWidth().clickable { picked = if (on) selection - app.id else selection + app.id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = { picked = if (it) selection + app.id else selection - app.id })
                    Text(app.name, Modifier.padding(start = 8.dp))
                }
            }
        }
        Button(
            onClick = {
                saving = true
                scope.launch {
                    when (val r = api.saveApps(selection, from.rev)) {
                        is ApiResult.Ok -> { show(r.value); vm.say(savedText) }
                        is ApiResult.Conflict -> { show(r.current); vm.say(conflictText) }
                        is ApiResult.Failed -> vm.say(vm.outcomeText(r.outcome))
                    }
                    saving = false
                }
            },
            enabled = online && !saving && selection != original,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) { Text(stringResource(R.string.save)) }
    }
}
