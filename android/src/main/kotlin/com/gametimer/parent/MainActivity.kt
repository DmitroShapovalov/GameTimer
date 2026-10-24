package com.gametimer.parent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
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

// A FragmentActivity: the system's fingerprint / PIN prompt (androidx.biometric) needs one
class MainActivity : FragmentActivity() {
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
        if (!isChangingConfigurations) vm.setForeground(false) // a rotation isn't leaving the app
        super.onStop()
    }
}

@Composable
private fun App(vm: MainViewModel) {
    // "home", "scan" or "pc:<agent id>"; saveable, so a rotation keeps the screen. A pairing in progress
    // (vm.pair) always shows the pairing screen, so its code survives a rotation too.
    var route by rememberSaveable { mutableStateOf("home") }
    val agents by vm.agents.collectAsState()
    val pairState by vm.pair.collectAsState()
    val identity by vm.identity.collectAsState()
    val locked by vm.locked.collectAsState()
    val peerAdded by vm.peerAdded.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }
    BackHandler(route != "home" || pairState != null) {
        if (pairState != null) vm.clearPairing()
        route = "home"
    }

    when (val id = identity) {
        Identity.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Identity.Unavailable -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.identity_unavailable), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.retryIdentity() }) { Text(stringResource(R.string.retry)) }
        }
        is Identity.Ready -> if (locked) {
            LockedScreen(vm)
        } else {
            peerAdded.firstOrNull()?.let { n ->
                AlertDialog(onDismissRequest = { vm.peerAddedShown() },
                    title = { Text(stringResource(R.string.peer_added_title)) },
                    text = { Text(stringResource(if (n.iAmAdmin) R.string.peer_added_text else R.string.peer_added_text_parent, n.phoneName, n.pcName)) },
                    confirmButton = { TextButton(onClick = { vm.peerAddedShown() }) { Text(stringResource(R.string.ok)) } })
            }
            var resetShown by rememberSaveable { mutableStateOf(false) }
            if (id.wasReset && !resetShown) {
                AlertDialog(onDismissRequest = { resetShown = true }, text = { Text(stringResource(R.string.identity_reset)) },
                    confirmButton = { TextButton(onClick = { resetShown = true }) { Text(stringResource(R.string.ok)) } })
            }
            when {
                pairState != null -> PairScreen(vm, onDone = { vm.clearPairing(); route = "home" })
                route == "scan" -> ScanScreen(vm, onBack = { route = "home" }, onLink = { link, name -> vm.startPairing(link, name) })
                route.startsWith("pc:") -> {
                    val agent = agents.firstOrNull { it.agentId == route.removePrefix("pc:") }
                    if (agent == null) LaunchedEffect(route) { route = "home" }
                    else PcScreen(vm, agent, snackbar, onBack = { route = "home" })
                }
                else -> HomeScreen(vm, agents, snackbar, onAdd = { route = "scan" }, onOpen = { route = "pc:" + it.agentId })
            }
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
                items(agents, key = { it.agentId }) { agent ->
                    val link = remember(agent.agentId) { vm.link(agent.agentId) }
                    if (link != null) PcCard(link, agent) { onOpen(agent) }
                }
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
internal fun rememberCountdown(state: com.gametimer.protocol.hub.AgentState?): Int {
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

internal fun formatSeconds(total: Int): String {
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
                QrScanner(onText = { tryText(it) }, onNoCamera = { paste = true }, modifier = Modifier.fillMaxWidth().weight(1f))
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
            null -> Unit // the caller stops showing this screen
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
                        is PairOutcome.Failed -> stringResource(R.string.pair_failed, o.reason) // technical detail, kept as is
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
    val link = remember(agent.agentId) { vm.link(agent.agentId) } ?: return
    val status by link.status.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(agent.name)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(status)
                            Text(statusText(status), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.remove)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val tabs = listOf(R.string.tab_timer, R.string.tab_schedule, R.string.tab_settings, R.string.tab_history, R.string.tab_parents)
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) }) }
            }
            when (tab) {
                0 -> TimerTab(vm, agent, link)
                1 -> ScheduleTab(vm, agent, link)
                2 -> SettingsTab(vm, agent, link)
                3 -> HistoryTab(vm, agent, link)
                // Removing the PC from the list is enough: the screen for a PC that isn't listed goes home by itself
                else -> ParentsTab(vm, agent, link, onSelfRemoved = { vm.remove(agent) })
            }
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

/** §12: the app wasn't unlocked for 14 days. Asks at once; the button asks again after a cancel. */
@Composable
private fun LockedScreen(vm: MainViewModel) {
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.unlock_title)
    val subtitle = stringResource(R.string.unlock_subtitle)
    val ask: () -> Unit = {
        scope.launch { if (activity != null && vm.reauth.authenticate(activity, title, subtitle)) vm.unlocked() }
    }
    LaunchedEffect(Unit) { ask() }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.locked_text), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = ask) { Text(stringResource(R.string.unlock_button)) }
    }
}

/**
 * §12: before an admin action (roles, removing another parent) the phone's own lock is asked, unless it
 * was just asked. Returns a function that runs the check; false = cancelled.
 */
@Composable
fun rememberAdminCheck(vm: MainViewModel): suspend () -> Boolean {
    val activity = LocalContext.current.findActivity()
    val title = stringResource(R.string.admin_auth_title)
    val subtitle = stringResource(R.string.admin_auth_subtitle)
    return remember(activity, title, subtitle) {
        { vm.reauth.recentlyAuthenticated() || (activity != null && vm.reauth.authenticate(activity, title, subtitle)) }
    }
}

private tailrec fun android.content.Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
