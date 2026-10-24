package com.gametimer.parent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.core.content.ContextCompat
import com.gametimer.protocol.hub.AgentLink
import com.gametimer.protocol.hub.LinkStatus
import com.gametimer.protocol.hub.OfflineReason
import com.gametimer.protocol.hub.PairOutcome
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.hub.PairingLink
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF4CA3E8)) else lightColorScheme(primary = Color(0xFF0078D4))
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }

    // Links stay connected only while the app is visible (no background service in this step)
    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
    }

    override fun onStop() {
        vm.setForeground(false)
        super.onStop()
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Scan : Screen
    data object Pair : Screen
    data class Pc(val agentId: String) : Screen
}

@Composable
private fun App(vm: MainViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val agents by vm.agents.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }
    BackHandler(screen != Screen.Home) {
        if (screen == Screen.Pair) vm.clearPairing()
        screen = Screen.Home
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(vm, agents, snackbar, onAdd = { screen = Screen.Scan }, onOpen = { screen = Screen.Pc(it.agentId) })
        Screen.Scan -> ScanScreen(vm, onBack = { screen = Screen.Home }, onLink = { link, name -> vm.startPairing(link, name); screen = Screen.Pair })
        Screen.Pair -> PairScreen(vm, onDone = { vm.clearPairing(); screen = Screen.Home })
        is Screen.Pc -> {
            val agent = agents.firstOrNull { it.agentId == s.agentId }
            if (agent == null) screen = Screen.Home else PcScreen(vm, agent, snackbar, onBack = { screen = Screen.Home })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: MainViewModel, agents: List<PairedAgent>, snackbar: SnackbarHostState,
                       onAdd: () -> Unit, onOpen: (PairedAgent) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_pc)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (agents.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(agents, key = { it.agentId }) { agent -> PcCard(vm.link(agent), agent) { onOpen(agent) } }
            }
        }
    }
}

@Composable
private fun PcCard(link: AgentLink, agent: PairedAgent, onClick: () -> Unit) {
    val status by link.status.collectAsState()
    val state by link.state.collectAsState()
    val seconds = rememberCountdown(state)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(agent.name, style = MaterialTheme.typography.titleMedium)
                Text(statusText(status), style = MaterialTheme.typography.bodySmall)
            }
            if (state?.isRunning == true) Text(formatSeconds(seconds), fontFamily = FontFamily.Monospace, fontSize = 22.sp)
        }
    }
}

@Composable
private fun StatusDot(status: LinkStatus) {
    val color = when (status) {
        is LinkStatus.Online -> Color(0xFF4CAF50)
        LinkStatus.Connecting -> Color(0xFFFFC107)
        else -> Color(0xFF9E9E9E)
    }
    Box(Modifier.size(12.dp).background(color, CircleShape))
}

@Composable
private fun statusText(status: LinkStatus): String = stringResource(
    when (status) {
        is LinkStatus.Online -> R.string.status_online
        LinkStatus.Connecting -> R.string.status_connecting
        is LinkStatus.Offline -> when (status.reason) {
            OfflineReason.Refused -> R.string.status_refused
            OfflineReason.WrongDevice -> R.string.status_wrong_device
            OfflineReason.Unreachable -> R.string.status_offline
        }
        LinkStatus.Revoked -> R.string.status_revoked
        LinkStatus.Stopped -> R.string.status_offline
    },
)

/** Seconds left, recomputed every second from the local clock (protocol §7.1). */
@Composable
private fun rememberCountdown(state: com.gametimer.protocol.hub.AgentState?): Int {
    var now by remember { mutableIntStateOf(state?.remainingSecondsNow() ?: 0) }
    LaunchedEffect(state) {
        while (true) {
            now = state?.remainingSecondsNow() ?: 0
            if (state?.isRunning != true) break
            delay(1000)
        }
    }
    return now
}

private fun formatSeconds(total: Int): String {
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScreen(vm: MainViewModel, onBack: () -> Unit, onLink: (PairingLink, String) -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    var phoneName by rememberSaveable { mutableStateOf(vm.defaultPhoneName) }
    var paste by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var notLink by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf(false) }

    fun tryText(text: String) {
        if (handled) return
        val link = PairingLink.parse(text)
        if (link == null) { notLink = true; return }
        handled = true
        onLink(link, phoneName)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.scan_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(phoneName, { phoneName = it.take(64) }, label = { Text(stringResource(R.string.phone_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (paste) {
                OutlinedTextField(pasted, { pasted = it; notLink = false }, label = { Text(stringResource(R.string.paste_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Button(onClick = { tryText(pasted) }, enabled = pasted.isNotBlank()) { Text(stringResource(R.string.connect)) }
            } else if (hasCamera) {
                Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyMedium)
                QrScanner(onText = { tryText(it) }, modifier = Modifier.fillMaxWidth().weight(1f))
            } else {
                Text(stringResource(R.string.camera_denied))
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.grant)) }
            }
            if (notLink) Text(stringResource(R.string.not_link), color = MaterialTheme.colorScheme.error)
            if (!paste) TextButton(onClick = { paste = true }) { Text(stringResource(R.string.scan_paste)) }
        }
    }
}

@Composable
private fun PairScreen(vm: MainViewModel, onDone: () -> Unit) {
    val state by vm.pair.collectAsState()
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        when (val s = state) {
            null -> onDone()
            is PairState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.pair_connecting, s.pcName))
            }
            is PairState.ShowCode -> {
                Text(stringResource(R.string.pair_code_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(24.dp))
                Text(s.code.chunked(3).joinToString(" "), fontSize = 48.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.pair_code_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }
            is PairState.Done -> {
                Text(stringResource(R.string.pair_done, s.pcName), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone) { Text(stringResource(R.string.ok)) }
            }
            is PairState.Failed -> {
                Text(
                    when (val o = s.outcome) {
                        PairOutcome.Denied -> stringResource(R.string.pair_denied)
                        PairOutcome.CodeUsed -> stringResource(R.string.pair_code_used)
                        PairOutcome.Rejected -> stringResource(R.string.pair_rejected)
                        is PairOutcome.Failed -> stringResource(R.string.pair_failed, o.reason)
                        is PairOutcome.Paired -> ""
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PcScreen(vm: MainViewModel, agent: PairedAgent, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val link = vm.link(agent)
    val status by link.status.collectAsState()
    val state by link.state.collectAsState()
    val seconds = rememberCountdown(state)
    var minutes by rememberSaveable { mutableStateOf("60") }
    var confirmRemove by remember { mutableStateOf(false) }
    val online = status is LinkStatus.Online

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(agent.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(24.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status)
                Text(statusText(status), Modifier.padding(start = 8.dp))
            }
            if (state?.isRunning == true) {
                Text(formatSeconds(seconds), fontSize = 64.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.timer_left))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.extend(agent, -15) }, enabled = online) { Text(stringResource(R.string.minus15)) }
                    OutlinedButton(onClick = { vm.extend(agent, 15) }, enabled = online) { Text(stringResource(R.string.plus15)) }
                }
                Button(onClick = { vm.stopTimer(agent) }, enabled = online) { Text(stringResource(R.string.stop)) }
            } else {
                Text(stringResource(R.string.timer_idle), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(minutes, { v -> minutes = v.filter { it.isDigit() }.take(4) }, label = { Text(stringResource(R.string.minutes)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(onClick = { minutes.toIntOrNull()?.takeIf { it in 1..1440 }?.let { vm.startTimer(agent, it) } }, enabled = online) {
                    Text(stringResource(R.string.start))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.remove)) }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text(stringResource(R.string.remove_confirm, agent.name)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.remove(agent); onBack() }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
