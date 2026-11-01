package com.gametimer.parent

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Keeps the links to the paired PCs open while the app is closed, so [Notifier] can tell the parent
 * when the time is up, a warning point is reached, something suspicious or someone else's action
 * happened. Android requires a (quiet) ongoing notification for that. Only needed for PCs that push
 * wake-ups don't cover (no "Control from anywhere", or no push in this build): see [wanted]. It only
 * works on the home network; away from home those links stay offline.
 */
class MonitorService : Service() {
    private val app get() = application as GameTimerApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(getString(R.string.monitor_text))
            .setContentIntent(Notifier.openApp(this))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, notification, type)
        } catch (e: RuntimeException) {
            stopSelf() // not allowed right now (e.g. started from the background on a newer Android)
            return START_NOT_STICKY
        }
        // A sticky restart (e.g. after notifications were switched off, which kills the process) re-checks the conditions
        if (!wanted(this)) { stopSelf(); return START_NOT_STICKY }
        val hub = app.hub
        hub.scope.launch {
            try { hub.loadKey() } catch (e: Exception) { stopSelf(); return@launch } // no identity: nothing to watch
            if (app.store.agents.value.isEmpty()) { stopSelf(); return@launch } // e.g. the identity was just reset
            app.store.agents.value.forEach { hub.link(it.agentId) }
            hub.setMonitoring(true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        app.hub.setMonitoring(false)
        super.onDestroy()
    }

    companion object {
        private const val ONGOING_ID = 1

        /**
         * Enabled, a PC paired, allowed to notify (watching without notifying would only cost battery) — and
         * some PC isn't covered by push wake-ups (protocol §10.6: it has "Control from anywhere" on and has
         * confirmed this phone's current push token). When push covers every PC, nothing stays open in the background: no
         * ongoing notification, the PCs wake the phone when there is news.
         */
        fun wanted(context: Context): Boolean {
            val app = context.applicationContext as GameTimerApp
            val agents = app.store.agents.value
            return NotifySettings(context).background && agents.isNotEmpty() &&
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                !agents.all { Push.covers(context, it) }
        }

        /** Starts or stops watching in the background, per [wanted]. */
        fun update(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (wanted(context)) {
                try { ContextCompat.startForegroundService(context, intent) } catch (e: RuntimeException) { /* retried on the next app start */ }
            } else {
                context.stopService(intent)
            }
        }
    }
}

/** After a reboot or an app update: watch again (if enabled). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MonitorService.update(context)
        }
    }
}
