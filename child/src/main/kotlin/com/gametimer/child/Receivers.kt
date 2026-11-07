package com.gametimer.child

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin (not Device Owner): while it is active, the app can't be uninstalled without first
 * switching the admin off in Settings — a screen strict mode keeps the child out of.
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = SpikeLog.add(context, "device admin enabled")
    override fun onDisabled(context: Context, intent: Intent) = SpikeLog.add(context, "device admin DISABLED")
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        SpikeLog.add(context, "device admin: disable requested")
        return "This switches off the screen-time limits your parent set."
    }
}

/** Starts the watcher after a reboot or an update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SpikeLog.add(context, "boot/update: ${intent.action?.substringAfterLast('.')}")
        WatchService.start(context)
    }
}
