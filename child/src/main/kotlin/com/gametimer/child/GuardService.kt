package com.gametimer.child

import android.accessibilityservice.AccessibilityService
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

/**
 * The accessibility service (strict mode, from the C0 spike). It sees every window change at once, so:
 * - a blocked game is covered as soon as it opens (the watcher's polling stays as the backstop);
 * - while strict mode guards, a full-screen page of Settings, the installer, the permission screens or a
 *   vendor's security / battery app that shows this app's name is sent to the home screen, so the child
 *   can't switch the app off, stop it or uninstall it from there.
 * What it can't stop: safe mode, a factory reset, a computer with adb, the clock, a force stop (which also
 * switches this service off). Those are for the parents to see (C4).
 */
class GuardService : AccessibilityService() {
    private lateinit var strict: StrictMode
    private val main = Handler(Looper.getMainLooper())
    /** The watcher's counting and the block page run here, not on the main thread (saving, locks). */
    private val worker = Executors.newSingleThreadExecutor()
    private var front: String? = null
    private var lastScan = 0L
    private var rescanPending = false
    private val systemApp = HashMap<String, Boolean>()
    private lateinit var name: String

    override fun onServiceConnected() {
        strict = StrictMode(this)
        name = getString(R.string.app_name)
        connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::strict.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // An app window came up (not the keyboard, a pop-up or the system bars): the watcher counts it and
                // covers a blocked game at once
                if (pkg != front && isAppWindow(event) && !isGuarded(pkg) && pkg != "com.android.systemui" && pkg != keyboard) {
                    front = pkg
                    worker.execute { try { AgentRuntime.watcher?.onFront(pkg) } catch (e: Exception) { } }
                }
                guard(pkg, force = true)
            }
            // A list scrolled our name into view, or a page filled in after opening
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> guard(pkg, force = false)
        }
    }

    private fun guard(pkg: String, force: Boolean) {
        if (!strict.guarding || !isGuarded(pkg)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastScan < 300) {
            // Content changes come in bursts: look once more when this one is over (the page may load late)
            if (!rescanPending) { rescanPending = true; main.postDelayed({ rescanPending = false; guard(pkg, force = true) }, 350) }
            return
        }
        lastScan = now
        val root = rootInActiveWindow ?: return
        // Only the window the event came from, and only a full-screen app window (not the shade or the lock screen)
        if (root.packageName?.toString() != pkg) return
        if (root.window?.type != AccessibilityWindowInfo.TYPE_APPLICATION) return
        val hits = try { root.findAccessibilityNodeInfosByText(name) } catch (e: RuntimeException) { return }
        if (hits.isEmpty()) return
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun isAppWindow(event: AccessibilityEvent): Boolean {
        val source = event.source ?: return false
        return try { source.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION } catch (e: RuntimeException) { false }
    }

    // The screens that can switch this app off: Settings, the installer and permission screens, the vendors'
    // security and battery apps, and other system apps without an icon. Not a browser or mail app, not any home
    // screen, the system bars or the keyboard.
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

    private val homes: Set<String> by lazy {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }
    private val keyboard: String? by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    }

    companion object {
        @Volatile var connected = false

        /** Vendor screens that can switch an app off (accessibility, battery limits, permissions). */
        private val VENDOR = setOf(
            "com.samsung.accessibility", "com.samsung.android.lool", "com.samsung.android.sm",
            "com.lbe.security.miui", "com.miui.powerkeeper", "com.huawei.systemmanager",
            "com.coloros.safecenter", "com.oplus.battery", "com.iqoo.secure", "com.vivo.permissionmanager",
        )
    }
}

/** Device admin (not Device Owner): while active, the app can't be uninstalled before it is switched off in Settings. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}
