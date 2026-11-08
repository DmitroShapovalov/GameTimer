package com.gametimer.child

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gametimer.protocol.agent.PeerRole
import com.gametimer.protocol.agent.RelayStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The child's phone, as the parent sets it up: the parent PIN, connecting a parent's phone (a QR the parent
 * app scans, then the code it shows), the connected parents, and whether the phone is reachable.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Parent mode ends when the app leaves the screen (the phone is the child's), and so does an open QR:
        // the child must not find it, or a code prompt, when reopening the app
        lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                ParentUnlock.lock()
                AgentRuntime.cancelPairing()
            }
        })
        val pin = ParentPin(this)
        if (pin.isSet) AgentService.start(this)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF66BB6A)) else lightColorScheme(primary = Color(0xFF2E7D32))
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { App(pin) }
                }
            }
        }
    }
}

@Composable
private fun App(pin: ParentPin) {
    var ready by remember { mutableStateOf(pin.isSet) }
    if (!ready) Setup(pin) { ready = true } else Home(pin)
}

// ---------------- first run: the parent PIN ----------------

@Composable
private fun Setup(pin: ParentPin, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { done() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_text))
        PinField(first, { first = it; error = null }, stringResource(R.string.pin_new))
        PinField(second, { second = it; error = null }, stringResource(R.string.pin_repeat))
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            when {
                !ParentPin.isValid(first) -> error = R.string.pin_rule
                first != second -> error = R.string.pin_mismatch
                else -> scope.launch {
                    withContext(Dispatchers.Default) { pin.set(first) }
                    AgentService.start(context)
                    if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else done()
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_start)) }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(12)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------- the home screen ----------------

@Composable
private fun Home(pin: ParentPin) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AgentRuntime.state.collectAsState()
    var guarded by remember { mutableStateOf<(() -> Unit)?>(null) } // the action waiting for the PIN
    var removing by remember { mutableStateOf<com.gametimer.protocol.agent.Peer?>(null) }
    var changingPin by remember { mutableStateOf(false) }
    var startingOver by remember { mutableStateOf(false) }

    fun withPin(action: () -> Unit) { if (ParentUnlock.isUnlocked) action() else guarded = action }
    /** Runs a confirmed protected action only while parent mode is still on (the dialog may be old). */
    fun ifUnlocked(action: () -> Unit) { if (ParentUnlock.isUnlocked) action() }
    val locks by ParentUnlock.locks.collectAsState()
    LaunchedEffect(locks) { guarded = null; removing = null; changingPin = false; startingOver = false }

    if (state.pairing != null) {
        PairingScreen(state.pairing!!, onCancel = { AgentRuntime.cancelPairing() })
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (state.phase) {
                        AgentRuntime.Phase.Running -> R.string.status_running
                        AgentRuntime.Phase.Starting, AgentRuntime.Phase.Stopped -> R.string.status_starting
                        AgentRuntime.Phase.StoreUnreadable -> R.string.status_unreadable
                        AgentRuntime.Phase.Failed -> R.string.status_failed
                    }))
                    if (state.phase == AgentRuntime.Phase.Running) {
                        Text(stringResource(when {
                            state.peers.isEmpty() -> R.string.relay_waiting
                            state.relay == RelayStatus.Connected -> R.string.relay_connected
                            else -> R.string.relay_connecting
                        }))
                        Text(state.lanAddress?.let { stringResource(R.string.lan_on, it) } ?: stringResource(R.string.lan_off))
                    }
                    if (state.phase == AgentRuntime.Phase.StoreUnreadable) {
                        OutlinedButton(onClick = { withPin { startingOver = true } }) { Text(stringResource(R.string.start_over)) }
                    }
                }
            }
            if (state.phase == AgentRuntime.Phase.Running) TodayCard()
            ProtectionCard(::withPin)
            BatteryCard()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.parents_title), style = MaterialTheme.typography.titleMedium)
                    if (state.peers.isEmpty()) Text(stringResource(R.string.parents_none))
                    for (p in state.peers) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name)
                                Text(stringResource(if (p.role == PeerRole.Admin) R.string.role_admin else R.string.role_parent),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { withPin { removing = p } }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                    Button(
                        onClick = { withPin { AgentRuntime.startPairing() } },
                        enabled = state.phase == AgentRuntime.Phase.Running,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.connect_parent)) }
                    if (state.phase == AgentRuntime.Phase.Running && state.lanAddress == null) {
                        Text(stringResource(R.string.connect_needs_wifi), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = { withPin { changingPin = true } }) { Text(stringResource(R.string.pin_change)) }
        }
    }

    state.ask?.let { ask -> CodeDialog(ask.hubName, onDone = { AgentRuntime.answer(it) }) }
    state.message?.let { m ->
        AlertDialog(
            onDismissRequest = { AgentRuntime.clearMessage() },
            confirmButton = { TextButton(onClick = { AgentRuntime.clearMessage() }) { Text(stringResource(R.string.ok)) } },
            text = { Text(stringResource(when (m) {
                AgentRuntime.Message.Paired -> R.string.msg_paired
                AgentRuntime.Message.TwoPhones -> R.string.msg_two_phones
                AgentRuntime.Message.NoWifi -> R.string.connect_needs_wifi
            })) },
        )
    }
    guarded?.let { action ->
        PinDialog(pin, onDismiss = { guarded = null }, onOk = { guarded = null; ParentUnlock.unlock(); action() })
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_title, p.name)) },
            text = { Text(stringResource(R.string.remove_text)) },
            confirmButton = { TextButton(onClick = { ifUnlocked { AgentRuntime.revoke(p.id) }; removing = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (startingOver) {
        AlertDialog(
            onDismissRequest = { startingOver = false },
            text = { Text(stringResource(R.string.start_over_text)) },
            confirmButton = { TextButton(onClick = { startingOver = false; ifUnlocked { scope.launch { AgentRuntime.wipeAndRestart(context) } } }) { Text(stringResource(R.string.start_over)) } },
            dismissButton = { TextButton(onClick = { startingOver = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (changingPin) ChangePinDialog(pin) { changingPin = false }
}

/** Today's game time as the child sees it. */
@Composable
private fun TodayCard() {
    val play by AgentRuntime.play.collectAsState()
    // Game time is counted every second without an event: read it again while the screen is open
    LaunchedEffect(Unit) { while (true) { AgentRuntime.refreshPlay(); delay(5_000) } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.today_title), style = MaterialTheme.typography.titleMedium)
            val left = play.minutesLeft
            Text(when {
                left == null -> stringResource(R.string.today_no_limit)
                left <= 0 -> stringResource(R.string.today_time_up)
                else -> stringResource(R.string.today_left, left / 60, left % 60)
            })
            if (play.asked) Text(stringResource(R.string.block_asked), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * What blocking needs (usage access, display over other apps) and strict mode (device admin + the accessibility
 * service, behind the parent PIN). Every button here is behind the PIN: the phone is the child's.
 */
@Composable
private fun ProtectionCard(withPin: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val strict = remember { StrictMode(context) }
    var grants by remember { mutableStateOf(Grants.of(context)) }
    var strictOn by remember { mutableStateOf(strict.on) }
    var paused by remember { mutableStateOf(strict.paused) }
    LaunchedEffect(Unit) { while (true) { grants = Grants.of(context); strictOn = strict.on; paused = strict.paused; delay(1_500) } }
    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val pkgUri = Uri.parse("package:${context.packageName}")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.prot_title), style = MaterialTheme.typography.titleMedium)
            if (!grants.canBlock) Text(stringResource(R.string.prot_needed), color = MaterialTheme.colorScheme.error)
            GrantRow(stringResource(R.string.grant_usage), grants.usage) {
                withPin { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            GrantRow(stringResource(R.string.grant_overlay), grants.overlay) {
                withPin { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }
            }
            if (!grants.usage || !grants.overlay || !grants.accessibility || !grants.admin) {
                Text(stringResource(R.string.prot_restricted), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { withPin { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) } }) {
                    Text(stringResource(R.string.prot_app_info))
                }
            }
            Text(stringResource(R.string.strict_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.strict_text), style = MaterialTheme.typography.bodySmall)
            val adminExplanation = stringResource(R.string.admin_explanation)
            GrantRow(stringResource(R.string.grant_admin), grants.admin) {
                withPin {
                    open(Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, adminExplanation))
                }
            }
            GrantRow(stringResource(R.string.grant_accessibility), grants.accessibility) {
                withPin { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            val ready = grants.admin && grants.accessibility
            Text(stringResource(when {
                !strictOn -> R.string.strict_off
                paused -> R.string.strict_paused
                !ready -> R.string.strict_broken
                else -> R.string.strict_on
            }))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!strictOn) {
                    Button(onClick = { withPin { strict.on = true; strict.unpause() } }, enabled = ready) { Text(stringResource(R.string.strict_turn_on)) }
                } else {
                    OutlinedButton(onClick = { withPin { if (paused) strict.unpause() else strict.pause(10) } }) {
                        Text(stringResource(if (paused) R.string.strict_resume else R.string.strict_pause))
                    }
                    TextButton(onClick = { withPin { strict.on = false } }) { Text(stringResource(R.string.strict_turn_off)) }
                }
            }
        }
    }
}

@Composable
private fun GrantRow(title: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        if (granted) Text(stringResource(R.string.granted), color = MaterialTheme.colorScheme.primary)
        else TextButton(onClick = onGrant) { Text(stringResource(R.string.grant)) }
    }
}

@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    LaunchedEffect(Unit) { while (true) { delay(2000); exempt = pm.isIgnoringBatteryOptimizations(context.packageName) } }
    if (exempt) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.battery_text))
            OutlinedButton(onClick = {
                @Suppress("BatteryLife")
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.battery_allow)) }
        }
    }
}

// ---------------- pairing ----------------

@Composable
private fun PairingScreen(pairing: AgentRuntime.Pairing, onCancel: () -> Unit) {
    val qr = remember(pairing.link) { qrBitmap(pairing.link, 720) }
    var left by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pairing) {
        while (true) {
            left = ((pairing.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            // A parent's phone already waiting for its code keeps its 60 s (§8.2): the host ends it then
            if (left == 0L && AgentRuntime.state.value.ask == null) { onCancel(); break }
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.pair_text))
        Box(Modifier.background(Color.White).padding(12.dp)) {
            Image(qr.asImageBitmap(), contentDescription = null, modifier = Modifier.size(280.dp))
        }
        Text(stringResource(R.string.pair_left, left / 60, left % 60))
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun CodeDialog(hubName: String, onDone: (String?) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.code_title, hubName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.code_text))
                OutlinedTextField(
                    value = code, onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(code) }, enabled = code.length == 6) { Text(stringResource(R.string.connect)) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

// ---------------- the parent PIN ----------------

@Composable
private fun PinDialog(pin: ParentPin, onDismiss: () -> Unit, onOk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val wrong = stringResource(R.string.pin_wrong)
    val lockedText = stringResource(R.string.pin_locked)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(value, { value = it; error = null }, stringResource(R.string.pin_label))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                checking = true
                scope.launch {
                    when (val r = withContext(Dispatchers.Default) { pin.check(value) }) {
                        ParentPin.Result.Ok -> onOk()
                        ParentPin.Result.Wrong -> { error = wrong; value = "" }
                        is ParentPin.Result.Locked -> { error = lockedText.format((r.millis + 999) / 1000); value = "" }
                    }
                    checking = false
                }
            }, enabled = value.length >= 4 && !checking) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChangePinDialog(pin: ParentPin, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.pin_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(first, { first = it; error = null }, stringResource(R.string.pin_new))
                PinField(second, { second = it; error = null }, stringResource(R.string.pin_repeat))
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    !ParentPin.isValid(first) -> error = R.string.pin_rule
                    first != second -> error = R.string.pin_mismatch
                    !ParentUnlock.isUnlocked -> onDone() // parent mode ended while the dialog was open
                    else -> scope.launch { withContext(Dispatchers.Default) { pin.set(first) }; onDone() }
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}
