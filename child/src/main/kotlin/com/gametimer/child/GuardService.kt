package com.gametimer.child

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Strict mode's accessibility service. It sees every window change at once, so:
 * - a chosen app is covered by the block page as soon as it opens (no polling delay);
 * - while strict mode is on, any settings, installer or permission screen that shows this app's name (its
 *   App info, the accessibility and device-admin pages, the uninstall dialog, the permission lists) is closed
 *   at once, so the child can't switch the app off, stop it or uninstall it from there.
 * What it can't stop: safe mode (third-party apps don't run there), a factory reset, a computer with adb.
 */
class GuardService : AccessibilityService() {
    private lateinit var prefs: SpikePrefs
    private var front: String? = null
    private var lastScan = 0L
    private var lastGuardLog = 0L

    override fun onServiceConnected() {
        prefs = SpikePrefs(this)
        connected = true
        SpikeLog.add(this, "accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        SpikeLog.add(this, "accessibility service UNBOUND")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::prefs.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != front) { front = pkg; onFront(pkg) }
                guard(pkg, force = true)
            }
            // A list scrolled our name into view, or a page filled in after opening
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> guard(pkg, force = false)
        }
    }

    private fun onFront(pkg: String) {
        if (pkg == packageName || pkg !in prefs.games || !prefs.isBlockedNow()) return
        prefs.setPendingBlock(pkg, System.currentTimeMillis(), measured = true, source = "a11y")
        startActivity(Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BlockActivity.EXTRA_PACKAGE, pkg))
    }

    private fun guard(pkg: String, force: Boolean) {
        if (!prefs.guarding() || !isGuardedPackage(pkg)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastScan < 300) return // content changes come in bursts
        lastScan = now
        val root = rootInActiveWindow ?: return
        val hits = try { root.findAccessibilityNodeInfosByText(NAME) } catch (e: RuntimeException) { return }
        if (hits.isEmpty()) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (now - lastGuardLog > 2_000) {
            lastGuardLog = now
            SpikeLog.add(this, "guarded: closed $pkg (${root.className ?: "?"})")
        }
    }

    companion object {
        /** Part of the app's label as Settings shows it. */
        private const val NAME = "GameTimer Child"

        @Volatile var connected = false

        // Settings, the installer and the permission screens, also the vendors' own (Samsung, Xiaomi, Huawei…)
        fun isGuardedPackage(pkg: String): Boolean =
            pkg == "com.android.settings" || "packageinstaller" in pkg || "permissioncontroller" in pkg ||
                "securitycenter" in pkg || pkg.endsWith(".settings") || "settings.intelligence" in pkg
    }
}
