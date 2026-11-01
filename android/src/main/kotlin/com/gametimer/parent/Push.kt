package com.gametimer.parent

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Push wake-ups away from home (protocol §10.6). The PC asks the relay to wake this phone through Firebase
 * when something happened; the message carries nothing but "wake". The phone then connects (through the
 * relay) and catches up the PC's history like after any reconnect, so the notifications are the usual ones.
 * Firebase is set up by hand from BuildConfig (no google-services.json in the repository); a build without
 * those values simply has no push.
 */
object Push {
    val configured: Boolean get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    fun init(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .build(),
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** This phone's current push token, or null (no push in this build, or not fetched yet). */
    fun token(context: Context): String? = if (configured) prefs(context).getString("token", null) else null

    fun save(context: Context, token: String) = prefs(context).edit().putString("token", token).apply()

    /** The token a PC confirmed with push.set (null: none confirmed, or this phone asked for none). */
    fun acked(context: Context, agentId: String): String? = prefs(context).getString("acked/$agentId", null)

    fun setAcked(context: Context, agentId: String, token: String?) =
        prefs(context).edit().apply { if (token != null) putString("acked/$agentId", token) else remove("acked/$agentId") }.apply()

    /** Push wake-ups cover this PC: it has remote access on and confirmed this phone's current token. */
    fun covers(context: Context, agent: com.gametimer.protocol.hub.PairedAgent): Boolean {
        val token = token(context) ?: return false
        return agent.remoteAccess && acked(context, agent.agentId) == token
    }

    /** Asks Firebase for the token (on app start); a new one reaches the PCs on their next session. */
    fun refresh(context: Context, onToken: () -> Unit) {
        if (!configured) return
        try {
            // Deprecated in firebase-messaging 25 (a successor API is on its way); still the documented way to
            // get the registration token the relay needs
            @Suppress("DEPRECATION")
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                if (t != null && t != token(context)) { save(context, t); onToken() }
            }
        } catch (e: RuntimeException) { /* no Google Play services: no push */ }
    }
}

/** Receives the wake-ups and new tokens. Runs on a Firebase worker thread. */
class PushService : FirebaseMessagingService() {
    private val app get() = application as GameTimerApp

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        Push.save(this, token)
        app.hub.scope.launch { app.hub.pushTokenChanged() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] != "wake") return
        // A high-priority message may start a foreground service: it keeps the process and the network
        // for the ~15 s of connect + catch up, beyond the short window of the message itself
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, WakeService::class.java))
        } catch (e: RuntimeException) {
            runBlocking { app.hub.wake() } // not allowed (e.g. a deprioritised message): do what fits in the window
        }
    }
}

/** Runs one wake-up (connect, catch up, notify) as a short, silent foreground service, then stops. */
class WakeService : android.app.Service() {
    private val app get() = application as GameTimerApp

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLocale.wrap(newBase))
    private var running = 0
    private var lastStartId = 0

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val notification = androidx.core.app.NotificationCompat.Builder(this, Notifier.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.wake_title))
            .setSilent(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            androidx.core.app.ServiceCompat.startForeground(this, WAKE_ID, notification, type)
        } catch (e: RuntimeException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running++
        lastStartId = startId
        app.hub.scope.launch {
            // stopSelfResult(the latest start): a start that arrived meanwhile keeps the service (and its
            // foreground promise) alive
            try { app.hub.wake() } finally { if (--running == 0) stopSelfResult(lastStartId) }
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val WAKE_ID = 2
    }
}
