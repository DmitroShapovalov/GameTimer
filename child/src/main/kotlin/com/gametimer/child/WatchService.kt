package com.gametimer.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The watcher: polls the usage events for the app in front (about every [POLL_MS]); while blocking is on
 * and a chosen app comes to the front, it opens the block page (normal mode) or keeps the chosen apps
 * suspended (Device Owner mode). Counts the chosen apps' time in front for the daily budget.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: SpikePrefs

    // The app in front, the wall-clock time of its "resumed" event, and whether this visit was blocked already
    private var front: String? = null
    private var frontSinceMs = 0L
    private var blockedVisit = false
    private var lastEventMs = 0L
    private var suspended: Set<String> = emptySet()
    private var lastGrants: Grants? = null
    private var carryMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = SpikePrefs(this)
        val n = prefs.bump("serviceStarts")
        val gapS = if (prefs.lastPollMs > 0) (System.currentTimeMillis() - prefs.lastPollMs) / 1000 else -1
        SpikeLog.add(this, "service start #$n (last poll ${if (gapS < 0) "never" else "${gapS}s ago"})")
        startForegroundCompat()
        lastEventMs = System.currentTimeMillis() - 5_000
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        SpikeLog.add(this, "service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        SpikeLog.add(this, "task removed (swiped from recents)")
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun loop() {
        val usm = getSystemService(UsageStatsManager::class.java)
        var lastTick = SystemClock.elapsedRealtime()
        var lastGrantCheck = 0L
        while (scope.isActive) {
            val nowMs = System.currentTimeMillis()
            val tick = SystemClock.elapsedRealtime()
            readEvents(usm, nowMs)

            // Budget: time the chosen apps spent in front since the last poll (capped: sleep is not play)
            val games = prefs.games
            if (front != null && front in games) {
                carryMs += (tick - lastTick).coerceIn(0, 5_000)
                if (carryMs >= 1000) { prefs.usedToday = prefs.usedToday + (carryMs / 1000).toInt(); carryMs %= 1000 }
            }
            lastTick = tick

            enforce(games, nowMs)
            prefs.lastPollMs = nowMs

            if (tick - lastGrantCheck > 30_000) { lastGrantCheck = tick; checkGrants() }
            delay(POLL_MS)
        }
    }

    // Usage events since the last read: the newest "resumed" activity is the app in front
    private fun readEvents(usm: UsageStatsManager, nowMs: Long) {
        val events = usm.queryEvents(lastEventMs, nowMs)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp >= lastEventMs) lastEventMs = e.timeStamp + 1
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != front) {
                front = e.packageName
                frontSinceMs = e.timeStamp
                blockedVisit = false
            }
        }
    }

    private fun enforce(games: Set<String>, nowMs: Long) {
        val blocked = prefs.isBlockedNow()
        if (isDeviceOwner(this)) {
            // Device Owner: the system keeps the chosen apps from opening at all
            val want = if (blocked) games else emptySet()
            if (want != suspended) {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                val admin = adminComponent(this)
                val lift = suspended - want
                if (lift.isNotEmpty()) dpm.setPackagesSuspended(admin, lift.toTypedArray(), false)
                val failed = if (want.isNotEmpty()) dpm.setPackagesSuspended(admin, want.toTypedArray(), true).toList() else emptyList()
                SpikeLog.add(this, "suspended ${want.size} app(s)${if (failed.isNotEmpty()) ", failed: $failed" else ""}")
                suspended = want
            }
            return
        }
        val pkg = front ?: return
        if (!blocked || pkg !in games || pkg == packageName || blockedVisit) return
        blockedVisit = true
        val latency = nowMs - frontSinceMs
        prefs.addLatency(latency)
        SpikeLog.add(this, "block $pkg after ${latency} ms")
        startActivity(Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BlockActivity.EXTRA_PACKAGE, pkg))
    }

    // What a child could switch off: logged the moment it changes
    private fun checkGrants() {
        val g = Grants.of(this)
        val prev = lastGrants
        if (prev != null && prev != g) SpikeLog.add(this, "grants changed: $prev → $g")
        lastGrants = g
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watching", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("GameTimer Child (spike)")
            .setContentText("Watching the chosen apps")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
    }

    companion object {
        private const val POLL_MS = 700L
        private const val CHANNEL = "watch"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchService::class.java))
        }
    }
}
