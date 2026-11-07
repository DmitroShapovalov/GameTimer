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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The watcher: polls the usage events for the app in front (about every [POLL_MS] while the screen is on);
 * while blocking is on and a chosen app is in front, it opens the block page (normal mode) or keeps the
 * chosen apps suspended (Device Owner mode). Counts the chosen apps' time in front for the daily budget.
 */
class WatchService : Service() {
    private val crashLog = CoroutineExceptionHandler { _, e -> SpikeLog.add(this, "watch loop error: $e") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashLog)
    private lateinit var prefs: SpikePrefs

    // The app in front and the time of its "resumed" event; whether this visit has a block page already
    private var front: String? = null
    private var frontSinceMs = 0L
    private var blockedVisit = false
    private var blockAskedAt = 0L          // elapsedRealtime of the last block request, for "did it appear"
    private var lastEventMs = 0L
    private var blockingSinceMs = 0L       // when blocking last became on (wall clock), 0 = off
    private var lastGrants: Grants? = null
    private var carryMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = SpikePrefs(this)
        val n = prefs.bump("serviceStarts")
        val gapS = if (prefs.lastPollMs > 0) (System.currentTimeMillis() - prefs.lastPollMs) / 1000 else -1
        SpikeLog.add(this, "service start #$n (last poll ${if (gapS < 0) "never" else "${gapS}s ago"}), battery ${battery()}%")
        startForegroundCompat()
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
        val power = getSystemService(PowerManager::class.java)
        // A game that was already open when the service (re)started is picked up from the recent past
        val startMs = System.currentTimeMillis()
        lastEventMs = startMs - 3 * 3600_000L
        readEvents(usm, startMs)
        if (prefs.isBlockedNow()) blockingSinceMs = startMs
        if (front != null) SpikeLog.add(this, "in front at start: $front")

        var lastTick = SystemClock.elapsedRealtime()
        var lastGrantCheck = 0L
        var lastSave = 0L
        var lastBattery = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            val tick = SystemClock.elapsedRealtime()
            if (!power.isInteractive) {
                // Screen off: nothing is played; look again less often
                front = null
                lastTick = tick
                delay(5_000)
                continue
            }
            val nowMs = System.currentTimeMillis()
            if (lastEventMs > nowMs) {
                SpikeLog.add(this, "clock went back ${(lastEventMs - nowMs) / 1000}s: event window reset")
                lastEventMs = nowMs - 5_000
            }
            readEvents(usm, nowMs)

            // Budget: time the chosen apps spent in front since the last poll (capped: a stall is not play)
            val games = prefs.games
            if (front != null && front in games) {
                carryMs += (tick - lastTick).coerceIn(0, 5_000)
                if (carryMs >= 1000) { prefs.usedToday = prefs.usedToday + (carryMs / 1000).toInt(); carryMs %= 1000 }
            }
            lastTick = tick

            val blocked = prefs.isBlockedNow()
            if (blocked && blockingSinceMs == 0L) blockingSinceMs = nowMs
            if (!blocked) blockingSinceMs = 0L
            enforce(games, blocked, tick)

            if (tick - lastSave > 10_000) { lastSave = tick; prefs.lastPollMs = nowMs }
            if (tick - lastGrantCheck > 30_000) { lastGrantCheck = tick; checkGrants() }
            if (tick - lastBattery > 30 * 60_000L) { lastBattery = tick; SpikeLog.add(this, "battery ${battery()}%") }
            delay(POLL_MS)
        }
    }

    // Usage events since the last read: the newest "resumed" activity is the app in front; the screen going
    // off or the lock screen means nothing is
    private fun readEvents(usm: UsageStatsManager, nowMs: Long) {
        val events = usm.queryEvents(lastEventMs, nowMs)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp >= lastEventMs) lastEventMs = e.timeStamp + 1
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (e.packageName != front) {
                    front = e.packageName
                    frontSinceMs = e.timeStamp
                    blockedVisit = false
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> front = null
            }
        }
    }

    private fun enforce(games: Set<String>, blocked: Boolean, tick: Long) {
        if (isDeviceOwner(this)) {
            // Device Owner: the system keeps the chosen apps from opening at all
            val want = if (blocked) games else emptySet()
            val had = prefs.suspended
            if (want != had) {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                val admin = adminComponent(this)
                runCatching {
                    val lift = had - want
                    if (lift.isNotEmpty()) dpm.setPackagesSuspended(admin, lift.toTypedArray(), false)
                    val failed = if (want.isNotEmpty()) dpm.setPackagesSuspended(admin, want.toTypedArray(), true).toList() else emptyList()
                    prefs.suspended = want - failed.toSet()
                    SpikeLog.add(this, "suspended ${want.size - failed.size} app(s)${if (failed.isNotEmpty()) ", refused by the system: $failed" else ""}")
                }.onFailure { SpikeLog.add(this, "suspend failed: $it") }
            }
            return
        }
        // The block page was asked for but didn't show (no overlay grant, the vendor's pop-up switch…): try again
        if (blockedVisit && front != packageName && prefs.pendingBlock() != null && tick - blockAskedAt > 2_000) {
            SpikeLog.add(this, "block page did not appear over $front, retrying")
            prefs.clearPendingBlock()
            blockedVisit = false
        }
        val pkg = front ?: return
        if (!blocked || pkg !in games || pkg == packageName || blockedVisit) return
        blockedVisit = true
        blockAskedAt = tick
        // The delay counts from the game coming to the front, or from blocking turning on in the middle of play
        prefs.setPendingBlock(pkg, maxOf(frontSinceMs, blockingSinceMs))
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

    private fun battery(): Int = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

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
