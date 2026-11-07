package com.gametimer.child

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Device Owner / device admin entry point (needed even when it does nothing itself). */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = SpikeLog.add(context, "device admin enabled")
    override fun onDisabled(context: Context, intent: Intent) = SpikeLog.add(context, "device admin disabled")
}

/** Starts the watcher after a reboot or an update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SpikeLog.add(context, "boot/update: ${intent.action?.substringAfterLast('.')}")
        WatchService.start(context)
    }
}
