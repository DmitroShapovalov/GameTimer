package com.gametimer.parent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** This phone's own settings: which notifications to show, and keeping the app awake enough for them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { NotifySettings(context) }
    var background by remember { mutableStateOf(settings.background) }
    var values by remember { mutableStateOf(NotifySettings.Category.entries.associateWith { settings.isOn(it) }) }
    // Allowed by the runtime permission (Android 13+) and not switched off for the app in Android's settings
    fun granted() = (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun unrestricted() = (context.getSystemService(PowerManager::class.java))?.isIgnoringBatteryOptimizations(context.packageName) == true
    var permission by remember { mutableStateOf(granted()) }
    var battery by remember { mutableStateOf(unrestricted()) }
    LifecycleResumeEffect(Unit) {
        val was = permission
        permission = granted(); battery = unrestricted()
        if (permission != was) MonitorService.update(context) // changed in Android's settings meanwhile
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = granted()
        MonitorService.update(context) // allowed now: start watching (or stop when refused)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_settings)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ntf_section), style = MaterialTheme.typography.titleMedium)
            SwitchRow(stringResource(R.string.ntf_background), stringResource(R.string.ntf_background_hint), background) {
                background = it
                settings.background = it
                MonitorService.update(context)
                if (it && !permission && Build.VERSION.SDK_INT >= 33 && !askedBefore(context)) {
                    markAsked(context)
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            listOf(
                NotifySettings.Category.TimeUp to (R.string.ntf_cat_time_up to R.string.ntf_cat_time_up_hint),
                NotifySettings.Category.Soon to (R.string.ntf_cat_soon to R.string.ntf_cat_soon_hint),
                NotifySettings.Category.Suspicious to (R.string.ntf_cat_suspicious to R.string.ntf_cat_suspicious_hint),
                NotifySettings.Category.Others to (R.string.ntf_cat_others to R.string.ntf_cat_others_hint),
            ).forEach { (category, texts) ->
                SwitchRow(stringResource(texts.first), stringResource(texts.second), values.getValue(category), enabled = background) {
                    settings.set(category, it)
                    values = values + (category to it)
                }
            }
            if (background && !permission) {
                Text(stringResource(R.string.ntf_no_permission), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    // Ask while Android still offers the prompt; once refused for good, only its settings can allow it
                    val activity = context.findActivity()
                    val canAsk = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                        (activity == null || !askedBefore(context) || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                    if (canAsk) {
                        markAsked(context)
                        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }) { Text(stringResource(R.string.ntf_allow)) }
            }
            if (background && !battery) {
                Text(stringResource(R.string.ntf_battery_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
                    } catch (e: RuntimeException) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text(stringResource(R.string.ntf_battery)) }
            }
            Text(stringResource(R.string.ntf_home_only), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun askedBefore(context: android.content.Context) =
    context.getSharedPreferences("notify", android.content.Context.MODE_PRIVATE).getBoolean("asked", false)

private fun markAsked(context: android.content.Context) =
    context.getSharedPreferences("notify", android.content.Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
