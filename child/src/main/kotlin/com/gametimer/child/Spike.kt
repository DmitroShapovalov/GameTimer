package com.gametimer.child

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.time.LocalDate

import java.time.format.DateTimeFormatter

/** What the spike blocks, and when. Plain SharedPreferences: this is a test tool, not the agent. */
class SpikePrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("spike", Context.MODE_PRIVATE)

    /** Chosen apps ("games"). */
    var games: Set<String>
        get() = p.getStringSet("games", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("games", v).apply()

    /** off | always | budget */
    var mode: String
        get() = p.getString("mode", "off") ?: "off"
        set(v) = p.edit().putString("mode", v).apply()

    var budgetMinutes: Int
        get() = p.getInt("budget", 5)
        set(v) = p.edit().putInt("budget", v).apply()

    /** Seconds the chosen apps were in front today (reset at local midnight). */
    var usedToday: Int
        get() = if (p.getString("usedDay", null) == LocalDate.now().toString()) p.getInt("used", 0) else 0
        set(v) = p.edit().putString("usedDay", LocalDate.now().toString()).putInt("used", v).apply()

    fun isBlockedNow(): Boolean = when (mode) {
        "always" -> true
        "budget" -> usedToday >= budgetMinutes * 60
        else -> false
    }

    /** The service's own counters, to see what the vendor's battery manager does. */
    fun bump(key: String): Int { val n = p.getInt(key, 0) + 1; p.edit().putInt(key, n).apply(); return n }
    fun count(key: String): Int = p.getInt(key, 0)
    var lastPollMs: Long
        get() = p.getLong("lastPoll", 0)
        set(v) = p.edit().putLong("lastPoll", v).apply()

    /** Apps this app suspended (Device Owner), kept so a restart or "Remove" can always lift them. */
    var suspended: Set<String>
        get() = p.getStringSet("suspended", emptySet()) ?: emptySet()
        set(v) { p.edit().putStringSet("suspended", v).commit() }

    /**
     * A block page the service asked for: the app, and the moment the delay is measured from (the later of
     * "the game came to the front" and "blocking became on"). The page itself records the delay when it shows.
     */
    fun setPendingBlock(pkg: String, fromMs: Long) = p.edit().putString("pendingPkg", pkg).putLong("pendingFrom", fromMs).apply()
    fun pendingBlock(): Pair<String, Long>? = p.getString("pendingPkg", null)?.let { it to p.getLong("pendingFrom", 0) }
    fun clearPendingBlock() = p.edit().remove("pendingPkg").remove("pendingFrom").apply()

    /** Block delays measured (ms), the newest last. */
    fun addLatency(ms: Long) {
        val list = latencies().takeLast(49) + ms
        p.edit().putString("lat", list.joinToString(",")).apply()
    }
    fun latencies(): List<Long> = p.getString("lat", "")!!.split(',').mapNotNull { it.toLongOrNull() }
}

/** A short event log for the report the owner sends back (newest last, 300 lines). */
object SpikeLog {
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    @Synchronized
    fun add(context: Context, line: String) {
        val p = context.applicationContext.getSharedPreferences("spike_log", Context.MODE_PRIVATE)
        val lines = (p.getString("log", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(299) +
            "${java.time.LocalDateTime.now().format(time)} $line"
        p.edit().putString("log", lines.joinToString("\n")).commit() // written at once: the next moment may be a crash
    }
    fun read(context: Context): String =
        context.applicationContext.getSharedPreferences("spike_log", Context.MODE_PRIVATE).getString("log", "") ?: ""
    fun clear(context: Context) =
        context.applicationContext.getSharedPreferences("spike_log", Context.MODE_PRIVATE).edit().clear().apply()
}

/** The permissions the normal (non-Device-Owner) mode needs, as the phone reports them now. */
data class Grants(val usage: Boolean, val overlay: Boolean, val battery: Boolean, val deviceOwner: Boolean) {
    companion object {
        fun of(context: Context): Grants {
            val ops = context.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION") // deprecated in the newest SDK; fine for the spike
            val usage = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
            val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            return Grants(usage, Settings.canDrawOverlays(context), battery, isDeviceOwner(context))
        }
    }
}

fun isDeviceOwner(context: Context): Boolean =
    context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

/**
 * Lifts every suspension this app may have made and its restrictions, then gives up Device Owner. Never
 * throws: it is the way back on the test phone.
 */
fun releaseDeviceOwner(context: Context, alsoLift: Collection<String>) {
    val dpm = context.getSystemService(DevicePolicyManager::class.java)
    val admin = adminComponent(context)
    val prefs = SpikePrefs(context)
    val lift = (prefs.suspended + prefs.games + alsoLift).toTypedArray()
    runCatching { if (lift.isNotEmpty()) dpm.setPackagesSuspended(admin, lift, false) }
        .onFailure { SpikeLog.add(context, "lift failed: $it") }
    prefs.suspended = emptySet()
    runCatching { dpm.setUninstallBlocked(admin, context.packageName, false) }
    for (r in RESTRICTIONS) runCatching { dpm.clearUserRestriction(admin, r) }
    runCatching {
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(context.packageName)
    }.onFailure { SpikeLog.add(context, "clear Device Owner failed: $it") }
    SpikeLog.add(context, "Device Owner removed (lifted ${lift.size} app(s))")
}

/** The restrictions the spike can set (developer options are left alone: they are the way back). */
val RESTRICTIONS = listOf(android.os.UserManager.DISALLOW_SAFE_BOOT, android.os.UserManager.DISALLOW_FACTORY_RESET,
    android.os.UserManager.DISALLOW_ADD_USER)

