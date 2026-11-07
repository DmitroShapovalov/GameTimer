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
import android.os.UserManager
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
 * The spike's one screen: what is granted, which apps to block and when, Device Owner extras, and the
 * report (measured block delays, service restarts, the event log) to copy and send back.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        WatchService.start(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    private data class App(val pkg: String, val label: String, val system: Boolean)

    // Every launchable app, for lifting suspensions (lifting is always harmless)
    private fun allLaunchable(): List<String> {
        @Suppress("DEPRECATION")
        val list = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return list.map { it.activityInfo.packageName }.distinct()
    }

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

            Section("2. When to block")
            listOf("off" to "Off", "always" to "Block the chosen apps now", "budget" to "Block after a daily budget").forEach { (k, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == k, onClick = { mode = k; prefs.mode = k })
                    Text(label)
                }
            }
            if (mode == "budget") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { budget = (budget - 1).coerceAtLeast(1); prefs.budgetMinutes = budget }) { Text("−") }
                Text("$budget min")
                OutlinedButton(onClick = { budget += 1; prefs.budgetMinutes = budget }) { Text("+") }
                Text("used today: ${prefs.usedToday / 60}:${"%02d".format(prefs.usedToday % 60)}".also { refresh })
            }
            if (mode == "budget") OutlinedButton(onClick = { prefs.usedToday = 0 }) { Text("Reset today's use") }

            Section("3. Apps to block (${games.size})")
            apps.forEach { app ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = app.pkg in games, onCheckedChange = { on ->
                        games = if (on) games + app.pkg else games - app.pkg
                        prefs.games = games
                    })
                    Text(if (app.system) "${app.label} (system)" else app.label)
                }
            }

            Section("4. Device Owner (optional)")
            DeviceOwner(grants.deviceOwner, allLaunchable())

            Section("5. Report")
            val lat = prefs.latencies()
            Text(buildString {
                append("Block delay: ")
                append(if (lat.isEmpty()) "no blocks yet" else "median ${lat.sorted()[lat.size / 2]} ms, max ${lat.max()} ms (${lat.size} blocks)")
                append("\nService starts: ${prefs.count("serviceStarts")}")
                val ago = (System.currentTimeMillis() - prefs.lastPollMs) / 1000
                append("\nLast poll: ${if (prefs.lastPollMs == 0L) "never" else "${ago}s ago"}".also { refresh })
                append("\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                append("\nGrants: $grants")
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val text = "Block delays ms: ${prefs.latencies()}\nService starts: ${prefs.count("serviceStarts")}\n" +
                        "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\nGrants: ${Grants.of(this@MainActivity)}\n\n" +
                        SpikeLog.read(this@MainActivity)
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("spike report", text))
                }) { Text("Copy report") }
                OutlinedButton(onClick = { SpikeLog.clear(this@MainActivity) }) { Text("Clear log") }
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

    // Device Owner extras: set up from a computer, and always removable from here (never throws)
    @Composable
    private fun DeviceOwner(ownerNow: Boolean, allApps: List<String>) {
        var removed by remember { mutableStateOf(false) }
        if (!ownerNow || removed) {
            Text("Not Device Owner. To try it on a test phone just reset, with no accounts added (Google, Samsung, Xiaomi…) " +
                "and USB debugging on:", style = MaterialTheme.typography.bodySmall)
            Text("adb shell dpm set-device-owner com.gametimer.child/.AdminReceiver", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            return
        }
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = adminComponent(this)
        var refresh by remember { mutableIntStateOf(0) }
        val uninstallBlocked = remember(refresh) { runCatching { dpm.isUninstallBlocked(admin, packageName) }.getOrDefault(false) }
        val restrictions = remember(refresh) { runCatching { dpm.getUserRestrictions(admin) }.getOrNull() }
        Text("Device Owner: yes. Chosen apps are suspended by the system while blocking is on.")
        ToggleRow("Block uninstalling this app", uninstallBlocked) { on ->
            runCatching { dpm.setUninstallBlocked(admin, packageName, on) }.onFailure { SpikeLog.add(this, "uninstall block: $it") }
            refresh++
        }
        val labels = mapOf(UserManager.DISALLOW_SAFE_BOOT to "Block safe mode", UserManager.DISALLOW_FACTORY_RESET to "Block factory reset",
            UserManager.DISALLOW_ADD_USER to "Block adding users")
        RESTRICTIONS.forEach { key ->
            ToggleRow(labels.getValue(key), restrictions?.getBoolean(key) == true) { on ->
                runCatching { if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key) }
                    .onFailure { SpikeLog.add(this, "restriction $key: $it") }
                refresh++
            }
        }
        Text("Developer options are never blocked here, so adb keeps working (with the device-owner build: dpm remove-active-admin).",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { releaseDeviceOwner(this, allApps); removed = !isDeviceOwner(this) }) { Text("Remove Device Owner") }
    }

    @Composable
    private fun ToggleRow(label: String, on: Boolean, set: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = on, onCheckedChange = set)
            Text(label)
        }
    }
}
