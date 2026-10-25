package com.gametimer.parent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gametimer.protocol.hub.AgentApi
import com.gametimer.protocol.hub.ApiResult
import com.gametimer.protocol.hub.HistoryEvent
import com.gametimer.protocol.hub.PairedAgent
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Which notifications the parent wants (app settings screen). Shared preferences, this phone only. */
class NotifySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notify", Context.MODE_PRIVATE)

    /** Keep watching the PCs in the background (a quiet ongoing notification) to notify at all. */
    var background: Boolean
        get() = prefs.getBoolean("background", true)
        set(v) = prefs.edit().putBoolean("background", v).apply()

    fun isOn(category: Category): Boolean = prefs.getBoolean(category.key, true)

    fun set(category: Category, on: Boolean) = prefs.edit().putBoolean(category.key, on).apply()

    enum class Category(val key: String) {
        TimeUp("timeUp"),          // the time ran out (or was ended): the PC locked / logged off / shut down
        Soon("soon"),              // a warning point on the PC ("5 min left")
        Suspicious("suspicious"),  // the app on the PC was closed while a timer ran; files tampered; a parent app added
        Others("others"),          // someone else started / stopped / changed the timer or lifted the schedule
    }
}

/**
 * Turns agent events into system notifications. History events are caught up on every connect (the
 * phone may have slept or been away), from the last one seen per PC, so nothing is shown twice and
 * nothing that happened meanwhile is missed — the first connect only remembers where history stands.
 */
class Notifier(private val context: Context) {
    private val settings = NotifySettings(context)
    private val seen = context.getSharedPreferences("notify_seen", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        createChannels(context)
    }

    fun forget(agentId: String) = seen.edit().remove(agentId).apply()

    /** On connect: history newer than the last event seen, oldest first. */
    suspend fun catchUp(agent: PairedAgent, api: AgentApi, myName: String?, notify: Boolean) {
        val page = (api.history(limit = 30) as? ApiResult.Ok)?.value ?: return
        val last = seen.getString(agent.agentId, null)?.let(::parseTime)
        val newest = page.events.firstOrNull()?.let { parseTime(it.time) }
        if (last == null) { // first connect: remember where history stands, no flood of old events
            if (newest != null) seen.edit().putString(agent.agentId, page.events.first().time).apply()
            return
        }
        val fresh = page.events.filter { e -> parseTime(e.time)?.isAfter(last) == true }.reversed()
        if (fresh.isEmpty()) return
        seen.edit().putString(agent.agentId, fresh.last().time).apply()
        if (notify) fresh.forEach { show(agent, it, myName) }
    }

    fun liveEvent(agent: PairedAgent, event: JsonObject?, myName: String?, notify: Boolean) {
        val e = event?.let { runCatching { json.decodeFromJsonElement(HistoryEvent.serializer(), it) }.getOrNull() } ?: return
        val time = parseTime(e.time)
        val last = seen.getString(agent.agentId, null)?.let(::parseTime)
        if (time != null && (last == null || time.isAfter(last))) seen.edit().putString(agent.agentId, e.time).apply()
        else if (time != null) return // already shown by a catch-up
        if (notify) show(agent, e, myName)
    }

    fun alert(agent: PairedAgent, minutes: Int?) {
        if (minutes == null || !settings.isOn(NotifySettings.Category.Soon)) return
        post(agent, NotifySettings.Category.Soon, context.getString(R.string.ntf_soon, minutes), tag = "soon")
    }

    fun peerAdded(agent: PairedAgent, notice: PeerAdded) {
        if (!settings.isOn(NotifySettings.Category.Suspicious)) return
        post(agent, NotifySettings.Category.Suspicious, context.getString(R.string.ntf_peer_added, notice.phoneName), tag = "peer")
    }

    private fun show(agent: PairedAgent, e: HistoryEvent, myName: String?) {
        // What this phone did itself is no news
        if (e.source == "phone" && myName != null && e.device == myName) return
        val (category, text) = when (e.type) {
            "expired" -> NotifySettings.Category.TimeUp to context.getString(R.string.ntf_time_up, actionText(e.action))
            "closed" -> NotifySettings.Category.Suspicious to context.getString(R.string.ntf_closed)
            "tampered" -> NotifySettings.Category.Suspicious to context.getString(R.string.ntf_tampered)
            else -> {
                if (e.source == "system") return // automatic starts, resumes, restores: not someone's action
                val who = if (e.source == "pc") context.getString(R.string.ntf_who_pc) else (e.device ?: "?")
                val what = when (e.type) {
                    "started" -> context.getString(R.string.ntf_started, e.minutes ?: 0)
                    "stopped", "reset" -> context.getString(R.string.ntf_stopped)
                    "extended" -> if ((e.minutes ?: 0) >= 0) context.getString(R.string.ntf_plus, e.minutes ?: 0)
                                  else context.getString(R.string.ntf_minus, -(e.minutes ?: 0))
                    "scheduleSkipped" -> context.getString(R.string.ntf_lifted)
                    else -> return
                }
                NotifySettings.Category.Others to context.getString(R.string.ntf_by, what, who)
            }
        }
        if (!settings.isOn(category)) return
        // Something from a while ago (the phone was asleep or away): say when it happened
        val at = parseTime(e.time)
        val late = at != null && Duration.between(at, OffsetDateTime.now()) > Duration.ofMinutes(2)
        val body = if (late) context.getString(R.string.ntf_at, text, at.atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) else text
        post(agent, category, body, tag = e.type)
    }

    private fun actionText(action: String?): String = context.getString(
        when (action) {
            "shutdown" -> R.string.act_shutdown
            "logoff" -> R.string.act_logoff
            else -> R.string.act_lock
        },
    )

    private fun post(agent: PairedAgent, category: NotifySettings.Category, text: String, tag: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val channel = when (category) {
            NotifySettings.Category.TimeUp, NotifySettings.Category.Soon -> CHANNEL_TIMER
            NotifySettings.Category.Suspicious -> CHANNEL_SECURITY
            NotifySettings.Category.Others -> CHANNEL_ACTIVITY
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(agent.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(if (category == NotifySettings.Category.Suspicious) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .build()
        // One per PC and kind: a newer "time's up" replaces the older one instead of piling up
        val id = (agent.agentId + "/" + tag).hashCode()
        try { NotificationManagerCompat.from(context).notify(id, notification) } catch (e: SecurityException) { /* permission revoked */ }
    }

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_TIMER = "timer"
        const val CHANNEL_SECURITY = "security"
        const val CHANNEL_ACTIVITY = "activity"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannels(listOf(
                NotificationChannel(CHANNEL_MONITOR, context.getString(R.string.ch_monitor), NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_TIMER, context.getString(R.string.ch_timer), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_SECURITY, context.getString(R.string.ch_security), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_ACTIVITY, context.getString(R.string.ch_activity), NotificationManager.IMPORTANCE_DEFAULT),
            ))
        }

        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

private fun parseTime(s: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(s) }.getOrNull()
