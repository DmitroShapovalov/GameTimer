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

    /**
     * Strict mode: the accessibility service also keeps the child out of the settings screens that would
     * switch this app off. Turning it off needs the parent's PIN (in the real agent: the parent app).
     */
    var strict: Boolean
        get() = p.getBoolean("strict", false)
        set(v) = p.edit().putBoolean("strict", v).apply()
    var pin: String?
        get() = p.getString("pin", null)
        set(v) = p.edit().putString("pin", v).apply()
    /** Strict mode paused until this wall-clock time (the parent entered the PIN). */
    var pausedUntil: Long
        get() = p.getLong("pausedUntil", 0)
        set(v) = p.edit().putLong("pausedUntil", v).apply()
    fun guarding(): Boolean = strict && System.currentTimeMillis() >= pausedUntil

    /** The service's own counters, to see what the vendor's battery manager does. */
    fun bump(key: String): Int { val n = p.getInt(key, 0) + 1; p.edit().putInt(key, n).apply(); return n }
    fun count(key: String): Int = p.getInt(key, 0)
    var lastPollMs: Long
        get() = p.getLong("lastPoll", 0)
        set(v) = p.edit().putLong("lastPoll", v).apply()

    /**
     * A block page asked for: the app, the moment the delay is measured from (the later of "the game came to
     * the front" and "blocking became on"), whether it is a fair case, and who saw it (poll | a11y). The page
     * itself records the delay when it shows.
     */
    fun setPendingBlock(pkg: String, fromMs: Long, measured: Boolean, source: String) = p.edit()
        .putString("pendingPkg", pkg).putLong("pendingFrom", fromMs).putBoolean("pendingMeasured", measured)
        .putString("pendingSource", source).apply()
    data class Pending(val pkg: String, val fromMs: Long, val measured: Boolean, val source: String)
    fun pendingBlock(): Pending? = p.getString("pendingPkg", null)?.let {
        Pending(it, p.getLong("pendingFrom", 0), p.getBoolean("pendingMeasured", true), p.getString("pendingSource", "poll")!!)
    }
    fun clearPendingBlock() = p.edit().remove("pendingPkg").remove("pendingFrom").remove("pendingMeasured").remove("pendingSource").apply()

    /** Block delays measured (ms) per source, the newest last. */
    fun addLatency(source: String, ms: Long) {
        val list = latencies(source).takeLast(49) + ms
        p.edit().putString("lat_$source", list.joinToString(",")).apply()
    }
    fun latencies(source: String): List<Long> = (p.getString("lat_$source", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
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

/** What is granted, as the phone reports it now (what a child could switch off). */
data class Grants(val usage: Boolean, val overlay: Boolean, val battery: Boolean, val admin: Boolean, val accessibility: Boolean) {
    companion object {
        fun of(context: Context): Grants {
            val ops = context.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION") // deprecated in the newest SDK; fine for the spike
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
