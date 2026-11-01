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

    /**
     * [wakeEvents]: what may wake this phone for the category away from home (protocol §10.6, push.set):
     * the history types [Notifier.show] turns into it, and `peerAdded`. Keep the two in step.
     */
    enum class Category(val key: String, val wakeEvents: List<String>) {
        TimeUp("timeUp", listOf("expired")),         // the time ran out (or was ended): the PC locked / logged off / shut down
        Soon("soon", listOf("alert")),               // a warning point on the PC ("5 min left"): live only; woken → from the state
        Suspicious("suspicious", listOf("closed", "tampered", "peerAdded")), // closed while a timer ran; tampered; a parent app added
        Others("others", listOf("started", "stopped", "reset", "extended", "scheduleSkipped")), // someone else's action
    }
}

/** Who this phone is on a PC: its hub id (from hello) and its name there (from the parent list). */
data class Me(val hubId: String?, val name: String?)

/**
 * Turns agent events into system notifications. History events are caught up on every connect (the
 * phone may have slept or been away): the PC's history is read newest first until an event this phone
 * has already seen — by the event itself, not its time, since the PC's clock may be wrong or set back
 * and "app closed" is recorded with an earlier time. The first connect only remembers where history stands.
 */
class Notifier(private val context: Context) {
    private val settings = NotifySettings(context)
    private val seen = context.getSharedPreferences("notify_seen2", Context.MODE_PRIVATE) // event keys (v2 of this file)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        createChannels(context)
    }

    fun forget(agentId: String) = seen.edit().remove(agentId).remove("soon/$agentId").apply()

    /** Forget everything (a new hub identity: the PCs must be paired again). */
    fun forgetAll() = seen.edit().clear().apply()

    private fun key(e: HistoryEvent) =
        listOf(e.type, e.time, e.source, e.device, e.hub, e.mode, e.minutes, e.action).joinToString("|").replace('\n', ' ')

    private fun known(agentId: String): MutableList<String>? =
        seen.getString(agentId, null)?.split('\n')?.filter { it.isNotEmpty() }?.toMutableList()

    private fun remember(agentId: String, keys: List<String>) {
        seen.edit().putString(agentId, keys.takeLast(MAX_KEYS).joinToString("\n")).apply()
    }

    /**
     * On connect: the events this phone hasn't seen yet, oldest first. [agentNow] is the PC's clock in its
     * last state, so "a while ago" is measured on the PC's own clock.
     */
    suspend fun catchUp(agent: PairedAgent, api: AgentApi, me: Me, agentNow: String?, notify: Boolean) {
        val known = known(agent.agentId)
        val fresh = mutableListOf<HistoryEvent>()
        var before: String? = null
        var reachedKnown = false
        for (pageNo in 0 until MAX_PAGES) {
            val page = (api.history(limit = PAGE, before = before) as? ApiResult.Ok)?.value ?: return
            for (e in page.events) {
                if (known != null && key(e) in known) { reachedKnown = true; break }
                fresh += e
            }
            if (known == null || reachedKnown || !page.more || page.next == null) break
            before = page.next
        }
        if (known == null) { // first connect: remember where history stands, no flood of old events
            val current = known(agent.agentId) ?: mutableListOf()
            remember(agent.agentId, current + fresh.reversed().map(::key).filter { it !in current })
            return
        }
        if (fresh.isEmpty()) return
        // Live events may have arrived (and been shown) while the pages loaded: read the list again
        val current = known(agent.agentId) ?: mutableListOf()
        val ordered = fresh.reversed().filter { key(it) !in current }
        if (ordered.isEmpty()) return
        remember(agent.agentId, current + ordered.map(::key))
        val pcNow = agentNow?.let(::parseTime) ?: OffsetDateTime.now()
        if (notify) ordered.takeLast(MAX_SHOWN).forEach { show(agent, it, me, pcNow) }
    }

    fun liveEvent(agent: PairedAgent, event: JsonObject?, me: Me, notify: Boolean) {
        val e = event?.let { runCatching { json.decodeFromJsonElement(HistoryEvent.serializer(), it) }.getOrNull() } ?: return
        val known = known(agent.agentId) ?: mutableListOf()
        val k = key(e)
        if (k in known) return // already shown by a catch-up
        remember(agent.agentId, known + k)
        if (notify) show(agent, e, me, pcNow = null)
    }

    /**
     * After a push wake-up (protocol §10.6): a warning point isn't in the history, so it is told from the timer
     * state — "about N min left" — once per timer run (by its target time), and only near the end.
     */
    fun soonFromState(agent: PairedAgent, state: com.gametimer.protocol.hub.AgentState?) {
        if (state == null || !state.isRunning || !settings.isOn(NotifySettings.Category.Soon)) return
        val left = state.remainingSecondsNow()
        if (left <= 0 || left > SOON_WITHIN) return
        val run = (state.timer?.get("targetTime") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        val minutes = (left + 59) / 60
        if (!newSoon(agent.agentId, run, minutes)) return
        post(agent, NotifySettings.Category.Soon, context.getString(R.string.ntf_soon, minutes), tag = "soon")
    }

    /**
     * Per PC and timer run: a "minutes left" notice only when it is meaningfully less than the last one told
     * (each warning point once, whether it came live or after a wake-up).
     */
    private fun newSoon(agentId: String, run: String?, minutes: Int): Boolean {
        val key = "soon/$agentId"
        val last = seen.getString(key, null)?.split('|')
        if (last != null && last.size == 2 && last[0] == (run ?: "") && minutes >= (last[1].toIntOrNull() ?: Int.MAX_VALUE) - 1) return false
        seen.edit().putString(key, (run ?: "") + "|" + minutes).apply()
        return true
    }

    fun alert(agent: PairedAgent, minutes: Int?, run: String? = null) {
        if (minutes == null || !settings.isOn(NotifySettings.Category.Soon)) return
        if (!newSoon(agent.agentId, run, minutes)) return
        post(agent, NotifySettings.Category.Soon, context.getString(R.string.ntf_soon, minutes), tag = "soon")
    }

    fun peerAdded(agent: PairedAgent, notice: PeerAdded) {
        if (!settings.isOn(NotifySettings.Category.Suspicious)) return
        post(agent, NotifySettings.Category.Suspicious, context.getString(R.string.ntf_peer_added, notice.phoneName), tag = "peer")
    }

    private fun show(agent: PairedAgent, e: HistoryEvent, me: Me, pcNow: OffsetDateTime?) {
        // What this phone did itself is no news (by hub id; agents before v1.7.2 only give the name)
        val mine = if (e.hub != null) e.hub == me.hubId else e.source == "phone" && me.name != null && e.device == me.name
        if (mine) return
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
        // Caught up from a while ago (the phone was asleep or away): say when it happened, by the PC's clock
        val at = parseTime(e.time)
        val late = at != null && pcNow != null && Duration.between(at, pcNow) > Duration.ofMinutes(2)
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
            .setContentIntent(openApp(context, agent.agentId))
            .setAutoCancel(true)
            // On the lock screen only "GameTimer" shows; names and actions after unlocking
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.ntf_public)).build())
            .setCategory(if (category == NotifySettings.Category.Suspicious) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .build()
        // One per PC and kind: a newer "time's up" replaces the older one instead of piling up
        val id = (agent.agentId + "/" + tag).hashCode()
        try { NotificationManagerCompat.from(context).notify(id, notification) } catch (e: SecurityException) { /* permission revoked */ }
    }

    companion object {
        /** A woken phone tells "time almost up" only within this many seconds of the end. */
        private const val SOON_WITHIN = 15 * 60

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

        const val EXTRA_AGENT = "agent"
        private const val MAX_KEYS = 400  // events remembered per PC (more than MAX_PAGES × PAGE)
        private const val PAGE = 50
        private const val MAX_PAGES = 4
        private const val MAX_SHOWN = 20  // after a long absence: only the newest ones

        /** Opens the app, on that PC's screen when [agentId] is given. */
        fun openApp(context: Context, agentId: String? = null): PendingIntent = PendingIntent.getActivity(context,
            agentId?.hashCode() ?: 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (agentId != null) putExtra(EXTRA_AGENT, agentId) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

private fun parseTime(s: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(s) }.getOrNull()
