package com.gametimer.child

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service. It sees every window change at once, so:
 * - a chosen app is covered by the block page as soon as it opens (WatchService's polling stays as a backstop
 *   for a budget that runs out in the middle of a game);
 * - while strict mode guards, any screen of a system app (Settings, the installer, the permission screens, the
 *   vendors' security and battery apps…) that shows this app's name is sent to the home screen at once, so the
 *   child can't switch the app off, stop it or uninstall it from there.
 * What it can't stop: safe mode (third-party apps don't run there), a factory reset, a computer with adb, the
 * clock, and anything that force-stops the app (which also switches this service off).
 */
class GuardService : AccessibilityService() {
    private lateinit var prefs: SpikePrefs
    private val main = Handler(Looper.getMainLooper())
    private var front: String? = null
    private var lastScan = 0L
    private var rescanPending = false
    private var lastGuardKey: String? = null
    private var lastGuardLog = 0L
    private val systemApp = HashMap<String, Boolean>()

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
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::prefs.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != front) {
                    front = pkg
                    // When the window really changed, not when the event reached us
                    val atMs = System.currentTimeMillis() - (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0)
                    onFront(pkg, atMs)
                }
                guard(pkg, event.className?.toString(), force = true)
            }
            // A list scrolled our name into view, or a page filled in after opening
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> guard(pkg, event.className?.toString(), force = false)
        }
    }

    private fun onFront(pkg: String, atMs: Long) {
        if (pkg == packageName || pkg !in prefs.games || !prefs.isBlockedNow()) return
        prefs.setPendingBlock(pkg, atMs, measured = true, source = "a11y")
        startActivity(Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BlockActivity.EXTRA_PACKAGE, pkg))
    }

    private fun guard(pkg: String, screen: String?, force: Boolean) {
        if (!prefs.guarding() || !isGuarded(pkg)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastScan < 300) {
            // Content changes come in bursts: look once more when this one is over (the page may load late)
            if (!rescanPending) { rescanPending = true; main.postDelayed({ rescanPending = false; guard(pkg, screen, force = true) }, 350) }
            return
        }
        lastScan = now
        val root = rootInActiveWindow ?: return
        // Only the window the event came from (not our own app, or the other half of a split screen), and only a
        // full-screen app window (not the notification shade, an edge panel or the lock screen)
        if (root.packageName?.toString() != pkg) return
        if (root.window?.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) return
        val hits = try { root.findAccessibilityNodeInfosByText(NAME) } catch (e: RuntimeException) { return }
        if (hits.isEmpty()) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val matched = hits.firstOrNull()?.let { it.text ?: it.contentDescription }?.toString()?.take(60)
        val key = "$pkg/$screen"
        if (key != lastGuardKey || now - lastGuardLog > 10_000) {
            lastGuardKey = key; lastGuardLog = now
            SpikeLog.add(this, "guarded: sent home from $pkg ${screen ?: "?"} (\"$matched\")")
        }
    }

    // The screens that can switch this app off: Settings, the installer and permission screens, the vendors'
    // security and battery apps, and other system apps without an icon of their own. Not a browser or mail app
    // (preinstalled, so "system" too), not any home screen, the system bars or the keyboard.
    private fun isGuarded(pkg: String): Boolean {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg in homes || pkg == keyboard) return false
        return systemApp.getOrPut(pkg) {
            pkg == "com.android.settings" || pkg in VENDOR || "packageinstaller" in pkg || "permissioncontroller" in pkg ||
                "securitycenter" in pkg || pkg.endsWith(".settings") ||
                runCatching {
                    packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                        packageManager.getLaunchIntentForPackage(pkg) == null
                }.getOrDefault(false)
        }
    }

    // Every app that can be the home screen (with no default set, "the" home app is the system's chooser)
    private val homes: Set<String> by lazy {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }
    private val keyboard: String? by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    }

    companion object {
        /** Part of the app's label as Settings shows it. */
        private const val NAME = "GameTimer Child"

        @Volatile var connected = false

        /** Vendor screens that can switch an app off (accessibility, battery limits, permissions). */
        private val VENDOR = setOf(
            "com.samsung.accessibility", "com.samsung.android.lool", "com.samsung.android.sm",
            "com.lbe.security.miui", "com.miui.powerkeeper", "com.huawei.systemmanager",
            "com.coloros.safecenter", "com.oplus.battery", "com.iqoo.secure", "com.vivo.permissionmanager",
        )
    }
}
