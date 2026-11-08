package com.gametimer.child

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.gametimer.play.AppEntry
import com.gametimer.play.LocalNow
import com.gametimer.play.PlayData
import com.gametimer.play.PlayPlatform
import com.gametimer.play.Protection
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

/**
 * The play logic's view of this phone: the local time, the monotonic clock, the launchable apps, and the
 * play state in app-private storage (noBackupFilesDir; backups are off, and without root the child can't
 * reach it).
 */
class AndroidPlayPlatform(context: Context) : PlayPlatform {
    private val app = context.applicationContext
    private val file = File(app.noBackupFilesDir, "play.json")
    private val json = Json { ignoreUnknownKeys = true }

    override fun now(): LocalNow = local(ZonedDateTime.now())

    private fun local(z: ZonedDateTime): LocalNow {
        return LocalNow(
            epochMillis = z.toInstant().toEpochMilli(),
            date = z.toLocalDate().toString(),
            secondOfDay = z.toLocalTime().toSecondOfDay(),
            weekday = z.dayOfWeek.value % 7, // java: Monday = 1 … Sunday = 7; the protocol: Sunday = 0
            iso = z.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    override fun at(epochMillis: Long): LocalNow = local(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault()))

    override fun zoneId(): String = java.time.ZoneId.systemDefault().id

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun installedApps(): List<AppEntry> {
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        // Not this app, a home screen or the phone app: limiting them would lock the child out of the phone
        @Suppress("DEPRECATION")
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val dialer = runCatching { app.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        return launchable.map { it.activityInfo.packageName }.distinct()
            .filter { it != app.packageName && it !in homes && it != dialer }
            .map { pkg -> AppEntry(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) }
            .sortedBy { it.name.lowercase() }
    }

    private val backup = File(app.noBackupFilesDir, "play.json.bak")

    /** The saved state, or the one before it if the last save was cut off. */
    @Synchronized
    override fun load(): PlayData? = read(file) ?: read(backup)

    private fun read(f: File): PlayData? =
        try { if (f.exists()) json.decodeFromString(PlayData.serializer(), f.readText()) else null } catch (e: Exception) { null }

    override fun bootCount(): Int = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)

    @Synchronized
    override fun save(data: PlayData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(PlayData.serializer(), data).encodeToByteArray())
            out.fd.sync()
        }
        if (file.exists()) file.renameTo(backup) // replaces the older backup
        if (!tmp.renameTo(file)) throw IOException("cannot replace ${file.name}")
    }
}

/** What is granted now (what enforcement needs, and what a child could switch off). */
data class Grants(val usage: Boolean, val overlay: Boolean, val battery: Boolean, val admin: Boolean, val accessibility: Boolean) {
    /** Blocking works (about a second after a game opens). */
    val canBlock: Boolean get() = usage && overlay

    /** As the play logic names them ([Protection]), for C4's reports. */
    val present: Set<String>
        get() = buildSet {
            if (usage) add(Protection.USAGE)
            if (overlay) add(Protection.OVERLAY)
            if (accessibility) add(Protection.ACCESSIBILITY)
            if (admin) add(Protection.ADMIN)
        }

    companion object {
        fun of(context: Context): Grants {
            val ops = context.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            val usage = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
            val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            val admin = context.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(context))
            return Grants(usage, Settings.canDrawOverlays(context), battery, admin, accessibilityOn(context))
        }
    }
}

fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

/** Our accessibility service is switched on in Settings (it may still be starting). */
fun accessibilityOn(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(context, GuardService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

/**
 * Strict mode (C0's design): with device admin and the accessibility service in place, the parent turns it on
 * behind the parent PIN; settings screens that would switch the app off are closed. Pausing (10 minutes, PIN)
 * is counted on this boot's monotonic clock: a changed date can't stretch it, a reboot ends it.
 */
class StrictMode(context: Context) {
    private val app = context.applicationContext
    private val p = app.getSharedPreferences("strict", Context.MODE_PRIVATE)

    var on: Boolean
        get() = p.getBoolean("on", false)
        set(v) { p.edit().putBoolean("on", v).commit() }

    fun pause(minutes: Int) {
        p.edit().putLong("pausedUntil", SystemClock.elapsedRealtime() + minutes * 60_000L).putInt("pausedBoot", bootCount()).commit()
    }

    fun unpause() { p.edit().remove("pausedUntil").commit() }

    val paused: Boolean
        get() = p.getInt("pausedBoot", -1) == bootCount() && SystemClock.elapsedRealtime() < p.getLong("pausedUntil", 0)

    /** The accessibility service guards the settings screens now. */
    val guarding: Boolean get() = on && !paused

    private fun bootCount(): Int = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)
}
