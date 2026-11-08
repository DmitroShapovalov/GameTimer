package com.gametimer.child

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.gametimer.play.PlayBackend
import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * Which app is in front (usage events, polled about every [POLL_MS] while the screen is on — the C0 spike's
 * method), fed to [PlayBackend.tick]; a limited game that is blocked gets the block page. With strict mode the
 * accessibility service reports the front app at once ([onFront]); polling stays as the backstop (a budget
 * running out in the middle of a game, a missed event).
 */
class Watcher(context: Context, private val backend: PlayBackend) {
    private val app = context.applicationContext
    /**
     * The activities resumed now (`package/class`; two apps in split screen), newest last. By activity, not by
     * package: a game going from one screen to another reports the old one stopped after the new one resumed.
     * The accessibility service's reports have no class (`package/`). Touched by the watcher and the a11y thread.
     */
    private val resumed = LinkedHashSet<String>()
    private var lastEventMs = System.currentTimeMillis() - 3 * 3600_000L // a game open at start is picked up
    private var askedFor: String? = null
    private var askedAt = 0L

    suspend fun run() {
        val usm = app.getSystemService(UsageStatsManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        while (currentCoroutineContext().isActive) {
            if (!power.isInteractive) {
                // Screen off: nothing is played; look again less often
                synchronized(resumed) { resumed.clear() }
                backend.tick(null)
                delay(5_000)
                continue
            }
            try {
                readEvents(usm)
                step()
            } catch (e: Exception) {
                // one bad pass must not stop the watcher
            }
            delay(POLL_MS)
        }
    }

    /** The accessibility service saw [pkg] come to the front: count and block at once. */
    fun onFront(pkg: String) {
        val key = "$pkg/"
        synchronized(resumed) { resumed.remove(key); resumed.add(key) }
        step()
    }

    /**
     * Counts with the app in front — in split screen a limited game on either side counts — and covers a blocked
     * game. One at a time (the watcher's loop and the accessibility thread).
     */
    @Synchronized
    private fun step() {
        val shown = synchronized(resumed) { resumed.map { it.substringBefore('/') } }
        val game = shown.lastOrNull { backend.isLimited(it) }
        backend.tick(game ?: shown.lastOrNull())
        if (game != null) enforce(game) else askedFor = null
    }

    // Resumed activities are on screen (two in split screen) until paused; the screen going off or the lock
    // screen means none
    private fun readEvents(usm: UsageStatsManager) {
        val now = System.currentTimeMillis()
        if (lastEventMs > now) lastEventMs = now - 5_000 // the clock went back
        val events = usm.queryEvents(lastEventMs, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp >= lastEventMs) lastEventMs = e.timeStamp + 1
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val key = "${e.packageName}/${e.className}"
                    synchronized(resumed) { resumed.remove(key); resumed.add(key) }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    synchronized(resumed) { resumed.remove("${e.packageName}/${e.className}"); resumed.remove("${e.packageName}/") }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> synchronized(resumed) { resumed.clear() }
            }
        }
    }

    private fun enforce(pkg: String) {
        val reason = backend.blockReason(pkg) ?: run { askedFor = null; return }
        val now = SystemClock.elapsedRealtime()
        // Once per visit, again after 2 s if the page didn't come up (no overlay grant, a vendor's pop-up switch)
        if (BlockActivity.showing) {
            // The page is up but the game is still on screen (split screen: the page in the other half): home
            if (now - BlockActivity.shownAt > 3_000) {
                app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                BlockActivity.shownAt = now
            }
            return
        }
        if (askedFor == pkg && now - askedAt < 2_000) return
        askedFor = pkg
        askedAt = now
        app.startActivity(
            Intent(app, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockActivity.EXTRA_REASON, reason.name),
        )
    }

    private companion object {
        const val POLL_MS = 700L
    }
}
