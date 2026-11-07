package com.gametimer.child

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * The spike's one screen: what is granted, which apps to block and when, strict mode, and the
 * report (measured block delays, service restarts, the event log) to copy and send back.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not while strict mode guards: its dialog shows our name and would be closed at once
        if (Build.VERSION.SDK_INT >= 33 && !SpikePrefs(this).guarding() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        WatchService.start(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    private data class App(val pkg: String, val label: String, val system: Boolean)

    // Launchable apps, without the ones that must never be blocked: this app, Settings (the way back),
    // the home screen and Play Store
    private fun launchableApps(): List<App> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = packageManager.queryIntentActivities(intent, 0)
        @Suppress("DEPRECATION")
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        val dialer = runCatching { getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull()
        val sms = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val never = setOf(packageName, "com.android.settings", "com.android.vending", home, dialer, sms)
        return list.map {
            val info = it.activityInfo.applicationInfo
            App(it.activityInfo.packageName, it.loadLabel(packageManager).toString(),
                info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
        }.filter { it.pkg !in never }.distinctBy { it.pkg }.sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
    }

    @Composable
    private fun Screen() {
        val prefs = remember { SpikePrefs(this) }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(1000); refresh++ } }
        val grants = remember(refresh) { Grants.of(this) }
        var games by remember { mutableStateOf(prefs.games) }
        var mode by remember { mutableStateOf(prefs.mode) }
        var budget by remember { mutableIntStateOf(prefs.budgetMinutes) }
        val apps = remember { launchableApps() }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GameTimer Child — spike C0", style = MaterialTheme.typography.titleLarge)
            Text("A test tool for Phase C. Not the real agent.", style = MaterialTheme.typography.bodySmall)

            Section("1. Permissions")
            Text("On Android 13+ an app installed from a file first needs ⋮ → \"Allow restricted settings\" on its App info page.",
                style = MaterialTheme.typography.bodySmall)
            GrantRow("Usage access", grants.usage) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            GrantRow("Display over other apps", grants.overlay) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            GrantRow("No battery optimisation", grants.battery) {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            OutlinedButton(onClick = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }) { Text("Open App info") }

            val locked = prefs.guarding().also { refresh }
            if (locked) Text("Strict mode is on: the settings below are locked. Pause it with the PIN in section 4.",
                color = MaterialTheme.colorScheme.error)

            Section("2. When to block")
            listOf("off" to "Off", "always" to "Block the chosen apps now", "budget" to "Block after a daily budget").forEach { (k, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == k, enabled = !locked, onClick = { mode = k; prefs.mode = k })
                    Text(label)
                }
            }
            if (mode == "budget") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !locked, onClick = { budget = (budget - 1).coerceAtLeast(1); prefs.budgetMinutes = budget }) { Text("−") }
                Text("$budget min")
                OutlinedButton(enabled = !locked, onClick = { budget += 1; prefs.budgetMinutes = budget }) { Text("+") }
                Text("used today: ${prefs.usedToday / 60}:${"%02d".format(prefs.usedToday % 60)}".also { refresh })
            }
            if (mode == "budget") OutlinedButton(enabled = !locked, onClick = { prefs.usedToday = 0 }) { Text("Reset today's use") }

            Section("3. Apps to block (${games.size})")
            apps.forEach { app ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = app.pkg in games, enabled = !locked, onCheckedChange = { on ->
                        games = if (on) games + app.pkg else games - app.pkg
                        prefs.games = games
                    })
                    Text(if (app.system) "${app.label} (system)" else app.label)
                }
            }

            Section("4. Strict mode (optional)")
            StrictMode(prefs, grants)

            Section("5. Report")
            Text(buildString {
                for (source in listOf("poll", "a11y")) {
                    val lat = prefs.latencies(source)
                    append("Block delay [$source]: ")
                    append(if (lat.isEmpty()) "no blocks yet\n" else "median ${lat.sorted()[lat.size / 2]} ms, max ${lat.max()} ms (${lat.size} blocks)\n")
                }
                append("Service starts: ${prefs.count("serviceStarts")}")
                val ago = (System.currentTimeMillis() - prefs.lastPollMs) / 1000
                append("\nLast poll: ${if (prefs.lastPollMs == 0L) "never" else "${ago}s ago"}".also { refresh })
                append("\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                append("\nGrants: $grants")
                append("\nAccessibility service running: ${GuardService.connected}; strict: ${prefs.strict}, guarding: ${prefs.guarding()}")
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val text = "Block delays ms: poll ${prefs.latencies("poll")}, a11y ${prefs.latencies("a11y")}\n" +
                        "Service starts: ${prefs.count("serviceStarts")}, strict ${prefs.strict}\n" +
                        "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\nGrants: ${Grants.of(this@MainActivity)}\n\n" +
                        SpikeLog.read(this@MainActivity)
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("spike report", text))
                }) { Text("Copy report") }
                OutlinedButton(enabled = !locked, onClick = { SpikeLog.clear(this@MainActivity) }) { Text("Clear log") }
            }
            Text(SpikeLog.read(this@MainActivity).lines().takeLast(40).joinToString("\n").also { refresh },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }

    @Composable
    private fun Section(title: String) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun GrantRow(label: String, ok: Boolean, open: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text((if (ok) "✓ " else "✗ ") + label, Modifier.weight(1f), color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (!ok) OutlinedButton(onClick = open) { Text("Grant") }
        }
    }

    // Strict mode: device admin + the accessibility service + a PIN the parent sets
    @Composable
    private fun StrictMode(prefs: SpikePrefs, grants: Grants) {
        var refresh by remember { mutableIntStateOf(0) }
        var pinText by remember { mutableStateOf("") }
        var pinAgain by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        val guarding = remember(refresh, grants) { prefs.guarding() }
        Text("The accessibility service sees a game the moment it opens. Strict mode also sends the child home from any " +
            "system screen that shows this app's name. It can't stop: safe mode, a factory reset, a computer with adb, " +
            "the clock, or a force-stop that gets through.", style = MaterialTheme.typography.bodySmall)
        GrantRow("Device admin (uninstall needs it switched off first)", grants.admin) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Keeps the screen-time limits from being removed without a parent."))
        }
        GrantRow("Accessibility service (${if (GuardService.connected) "running" else "not running"})", grants.accessibility) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val settingPin = prefs.pin == null
        OutlinedTextField(value = pinText, onValueChange = { v -> pinText = v.filter { it.isDigit() }.take(8) },
            label = { Text(if (settingPin) "New parent PIN (4+ digits)" else "Parent PIN") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        if (settingPin) OutlinedTextField(value = pinAgain, onValueChange = { v -> pinAgain = v.filter { it.isDigit() }.take(8) },
            label = { Text("The same PIN again") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!prefs.strict) {
                // Only with both in place: afterwards their Settings screens are guarded too
                val ready = grants.admin && grants.accessibility
                val pinOk = if (settingPin) pinText.length >= 4 && pinText == pinAgain else pinText == prefs.pin
                Button(enabled = ready && pinOk, onClick = {
                    if (settingPin) prefs.pin = pinText
                    prefs.strict = true; prefs.unpause()
                    SpikeLog.add(this@MainActivity, "strict mode ON"); pinText = ""; pinAgain = ""; message = ""; refresh++
                }) { Text("Turn strict mode on") }
                if (!ready) Text("needs device admin and the accessibility service", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = {
                    if (pinText == prefs.pin) {
                        prefs.pause(10)
                        SpikeLog.add(this@MainActivity, "strict mode paused 10 min"); message = "Paused for 10 minutes"
                    } else { SpikeLog.add(this@MainActivity, "strict mode: wrong PIN"); message = "Wrong PIN" }
                    pinText = ""; refresh++
                }) { Text("Pause 10 min") }
                OutlinedButton(onClick = {
                    if (pinText == prefs.pin) {
                        prefs.strict = false; SpikeLog.add(this@MainActivity, "strict mode OFF"); message = ""
                    } else { SpikeLog.add(this@MainActivity, "strict mode: wrong PIN"); message = "Wrong PIN" }
                    pinText = ""; refresh++
                }) { Text("Turn off") }
            }
        }
        Text(when {
            !prefs.strict -> "Strict mode: off"
            guarding -> "Strict mode: on, guarding" + if (!GuardService.connected) " — but the accessibility service is NOT running" else ""
            else -> "Strict mode: paused"
        } + if (message.isNotEmpty()) " · $message" else "")
    }
}
