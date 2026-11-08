package com.gametimer.play

import com.gametimer.protocol.agent.AgentBackend
import com.gametimer.protocol.agent.AgentHost
import com.gametimer.protocol.agent.AgentInfo
import com.gametimer.protocol.agent.Reply
import com.gametimer.protocol.agent.RequestContext
import com.gametimer.protocol.agent.RequestOrigin
import com.gametimer.protocol.agent.newHostLock
import com.gametimer.protocol.agent.withLock
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** What the phone platform gives the play logic. */
public interface PlayPlatform {
    /** The local time now (the phone's zone). */
    public fun now(): LocalNow

    /** A monotonic clock in milliseconds (keeps counting in sleep): game time is measured on it, never the wall clock. */
    public fun elapsedMillis(): Long

    /** The apps a parent can limit (launchable; not this app, a home screen or the phone app), sorted by name. */
    public fun installedApps(): List<AppEntry>

    public fun load(): PlayData?

    public fun save(data: PlayData)

    /** The phone's boot count (the same within one boot), or -1 when unknown. */
    public fun bootCount(): Int = -1
}

/** Why a limited game is blocked right now. */
public enum class BlockReason {
    /** The timer ran out (or today's allowance is used up): more time needs a parent. */
    TimeUp,
    /** Outside today's allowed hours, or not a game day. */
    NotNow,
}

/** The child pressed "Ask for more time" on the block page. */
public enum class AskResult { Sent, AlreadyAsked, TooSoon, NotBlocked }

/**
 * The phone's side of protocol §7 for the parent app (capabilities `timer`, `schedule`, `history`, `apps`):
 * the same message shapes as the Windows agent, with the phone's rules (§7.4, owner's decisions for C3):
 * - time counts only while a **limited game** is in front (`timer.state` `running`), otherwise it is
 *   `paused` (protocol minor 4); only the limited games are blocked;
 * - with the schedule on, each day's allowance (game minutes) becomes the timer by itself and every day starts
 *   afresh at local midnight; outside the allowed hours the games are blocked;
 * - a parent's + and "yes" are granted even past the schedule: they add to today's limit (and, when given while
 *   games aren't allowed, let the child play now).
 * The app calls [tick] about every second with the app in front, and asks [blockReason] whether to cover it.
 * Thread-safe: changes run one at a time; their events go out in the same order, outside the state lock.
 */
public class PlayBackend(
    override val info: AgentInfo,
    private val platform: PlayPlatform,
) : AgentBackend {
    public companion object {
        public const val MAX_HISTORY: Int = 500
        internal const val TICK_EVENT_MS = 30_000L
        internal const val MAX_STEP_MS = 5_000L // a stall between two ticks isn't play
        internal const val SAVE_EVERY_MS = 10_000L
        internal const val REQUEST_REPLACEABLE_MS = 15 * 60_000L
        internal const val REQUEST_PAUSE_AFTER_NO_MS = 5 * 60_000L
        internal const val MAX_LIMITED = 500
        internal const val DAYS_KEPT = 8
        private val json = Json { encodeDefaults = false; explicitNulls = false }
    }

    override val capabilities: Collection<String> = listOf("timer", "schedule", "history", "apps")

    /** Set by the app before the host accepts connections. */
    public lateinit var host: AgentHost

    /** The app's own observer (the child's screen, the block page): called after every change, outside the lock. */
    public var onChanged: (() -> Unit)? = null

    /** Changes one at a time, with their saving and publishing: hubs see events in the order the state changed. */
    private val emitLock = newHostLock()
    /** The state itself (never held while saving or publishing). */
    private val lock = newHostLock()
    private var data: PlayData = platform.load() ?: PlayData()
    private var lastTick: Long = platform.elapsedMillis()
    private var lastSave: Long = lastTick
    private var lastTickEvent: Long = lastTick
    private var dirty = false
    private var foreground: String? = null
    private var running = false
    private var lastStatus: ScheduleStatus? = null
    private var request: Pending? = null
    private var declinedAt: Long? = null
    /** On the monotonic clock: when the counted day ends (its local midnight), or null before the first tick. */
    private var midnightAt: Long? = null

    init {
        // The app restarted within the same boot: the monotonic clock still counts from the same point
        val boot = platform.bootCount()
        if (boot >= 0 && data.anchorBoot == boot && data.today.isNotEmpty()) midnightAt = data.anchorMidnight
    }

    private class Pending(val id: String, val iso: String, val at: Long)

    /** What to save and publish once the state lock is released. */
    private class Out {
        val events = mutableListOf<Pair<String, JsonObject>>()
        var origin: RequestOrigin? = null
        var save = false
        var changed = false
    }

    private inline fun <T> change(origin: RequestOrigin?, block: Out.() -> T): T = emitLock.withLock {
        val out = Out().also { it.origin = origin }
        val result = lock.withLock { out.block() }
        flush(out)
        result
    }

    private fun flush(out: Out) {
        if (out.save) {
            val snapshot = lock.withLock { dirty = false; lastSave = platform.elapsedMillis(); data }
            try { platform.save(snapshot) } catch (e: Exception) { lock.withLock { dirty = true } /* saved with the next change */ }
        }
        if (::host.isInitialized) {
            for ((type, body) in out.events) {
                // History and ticks reach every hub; a state / schedule / apps change caused by a request skips
                // its requester, who gets the same in its result (§5)
                val origin = if (type == "ev.history" || type == "ev.tick") null else out.origin
                try { host.publish(type, body, origin) } catch (e: Exception) { /* a closed host */ }
            }
        }
        if (out.changed || out.events.isNotEmpty()) try { onChanged?.invoke() } catch (e: Exception) { }
    }

    /** Saves now (the app stops). */
    public fun saveNow(): Unit = change(null) { save = true }

    // ---------------- the phone's side: ticks, blocking, the child's request ----------------

    /**
     * Called about every second (and when the app in front changes) with the package in front, or null when
     * the screen is off or locked. Counts game time, rolls the day over, runs the timer out, publishes changes.
     */
    public fun tick(front: String?): Unit = change(null) {
        val nowMs = platform.elapsedMillis()
        val step = (nowMs - lastTick).coerceIn(0, MAX_STEP_MS)
        lastTick = nowMs
        val now = platform.now()
        rollOver(now)
        val wasGame = foreground?.let { it in data.limited } == true
        foreground = front
        // The step belongs to what was in front during it
        if (wasGame && step > 0 && !blockedLocked(now)) {
            setDay { it.copy(usedMs = it.usedMs + step) }
            data.timer?.let { t ->
                val left = t.remainingMs - step
                data = data.copy(timer = t.copy(remainingMs = left))
                if (left <= 0) expireLocked(now)
            }
            dirty = true
        }
        autoStartLocked(now) // e.g. the allowed hours just began
        // A request that no longer blocks anything (time added, the hours began) goes away
        if (request != null && !blockedLocked(now)) { request = null; stateEvent(now) }
        val isRunning = front != null && front in data.limited && data.timer != null && !blockedLocked(now)
        val status = scheduleLocked(now).status
        if (isRunning != running || status != lastStatus) {
            running = isRunning
            lastStatus = status
            stateEvent(now)
        }
        if (running && nowMs - lastTickEvent >= TICK_EVENT_MS) {
            lastTickEvent = nowMs
            events += "ev.tick" to buildJsonObject { put("state", stateLocked(now)) }
        }
        if (dirty && nowMs - lastSave >= SAVE_EVERY_MS) save = true
    }

    /** [pkg] is one of the limited games. */
    public fun isLimited(pkg: String?): Boolean = pkg != null && lock.withLock { pkg in data.limited }

    /** Whether [pkg] must be covered by the block page now (a limited game while blocked), and why. */
    public fun blockReason(pkg: String?): BlockReason? = lock.withLock {
        if (pkg == null || pkg !in data.limited) return null
        val now = platform.now()
        if (!blockedLocked(now)) return null
        val s = scheduleLocked(now).status
        val t = data.timer
        val timeLeft = t != null && t.remainingMs > 0
        // Time left (or no timer at all) but outside the allowed hours / not a game day: "not now"; otherwise time's up
        if ((timeLeft || (t == null && !data.expired)) && (s == ScheduleStatus.OutsideWindow || s == ScheduleStatus.DayDisabled)) BlockReason.NotNow
        else BlockReason.TimeUp
    }

    /** The child asks for more time on the block page (protocol minor 3, as on the PC). */
    public fun askForMoreTime(): AskResult = change(null) {
        val now = platform.now()
        val t = platform.elapsedMillis()
        if (!blockedLocked(now)) return@change AskResult.NotBlocked
        val r = request
        if (r != null && t - r.at < REQUEST_REPLACEABLE_MS) return@change AskResult.AlreadyAsked
        val no = declinedAt
        if (no != null && t - no < REQUEST_PAUSE_AFTER_NO_MS) return@change AskResult.TooSoon
        request = Pending(randomId(), now.iso, t)
        history(now, "timeRequested", source = "pc")
        stateEvent(now)
        AskResult.Sent
    }

    /** A request is waiting for a parent's answer (and can't be replaced yet: 15 minutes). */
    public val asked: Boolean
        get() = lock.withLock { request?.let { platform.elapsedMillis() - it.at < REQUEST_REPLACEABLE_MS } == true }

    /** Minutes of game time left today, or null without a limit (the child's screen). */
    public fun minutesLeft(): Int? = lock.withLock {
        val now = platform.now()
        val t = data.timer
        when {
            t != null -> (t.remainingMs.coerceAtLeast(0) / 60_000).toInt()
            data.expired -> 0
            else -> scheduleLocked(now).availableMs.takeIf { it != Long.MAX_VALUE }?.let { (it / 60_000).toInt() }
        }
    }

    // ---------------- the rules ----------------

    /** The day being counted ([PlayData.today]: the clock's date once a midnight really passed). */
    private fun today(): DayUse = data.days[data.today] ?: DayUse()

    private inline fun setDay(change: (DayUse) -> DayUse) {
        data = data.copy(days = data.days + (data.today to change(today())))
    }

    /** Today's schedule decision; a parent's extra time today raises the limit. */
    private fun scheduleLocked(now: LocalNow): ScheduleDecision {
        val d = today()
        return ScheduleRules.evaluate(trusted(now), data.schedule, d.usedMs - d.bonusMs, d.skipped) // below 0: extra time left over
    }

    /**
     * The weekday and the time of day the schedule goes by: the counted day's, and the time from the monotonic
     * clock (its midnight less what is left of it) — moving the phone's date or time doesn't pick another
     * weekday or move into the allowed hours. The clock's own until the first tick.
     */
    private fun trusted(now: LocalNow): LocalNow {
        val end = midnightAt ?: return now
        if (data.today.isEmpty()) return now
        val left = end - platform.elapsedMillis()
        val second = (86_400L - (left + 999) / 1000).coerceIn(0, 86_399).toInt()
        return now.copy(date = data.today, secondOfDay = second, weekday = weekdayOf(data.today) ?: now.weekday)
    }

    private fun anchor(at: Long) {
        midnightAt = at
        data = data.copy(anchorBoot = platform.bootCount(), anchorMidnight = at)
        dirty = true
    }

    /** Limited games are blocked now. */
    private fun blockedLocked(now: LocalNow): Boolean {
        val s = scheduleLocked(now)
        val t = data.timer
        if (t != null) return t.remainingMs <= 0 || (s.status.blocks && !t.lift)
        if (data.expired) return true
        return s.status.blocks
    }

    /**
     * Another local day: with the schedule on it starts afresh (no timer, no time's up; today's allowance starts
     * below). Within one boot the days go by the monotonic clock alone: a new day starts when the counted day's
     * midnight is reached, the next one 24 hours later, whatever the phone's date and time say — moving them gains
     * nothing (daylight saving time and travelling shift the hours by as much until the next reboot). Without that
     * anchor (the first start, a reboot) the clock's date and time are taken as they are: a gap for C4 to report.
     */
    private fun Out.rollOver(now: LocalNow) {
        val t = platform.elapsedMillis()
        val end = midnightAt
        if (end != null && data.today.isNotEmpty()) {
            if (t < end) return
            var day = data.today
            var next = end
            while (t >= next) { day = nextDate(day) ?: now.date; next += 86_400_000L }
            anchor(next)
            newDay(now, day)
            return
        }
        anchor(t + (86_400L - now.secondOfDay) * 1000)
        save = true
        if (data.today != now.date) newDay(now, now.date)
    }

    private fun Out.newDay(now: LocalNow, date: String) {
        val first = data.today.isEmpty()
        data = data.copy(today = date)
        if (data.schedule.enabled) data = data.copy(timer = null, expired = false)
        // Keep the last few days only (the newest added)
        if (data.days.size > DAYS_KEPT) data = data.copy(days = data.days.entries.toList().takeLast(DAYS_KEPT).associate { it.key to it.value })
        request = null
        if (!first) stateEvent(now)
        save = true
    }

    /** With the schedule on and nothing running, what is left of today's allowance becomes the timer (the PC's auto start). */
    private fun Out.autoStartLocked(now: LocalNow) {
        if (!data.schedule.enabled) return
        val s = scheduleLocked(now)
        val t = data.timer
        // A parent's grant while games weren't allowed yet: once they are, today's allowance (which the grant
        // raised by as much) takes it over, so the grant doesn't use the day up
        if (t != null && t.lift && !t.auto && !today().skipped && s.status == ScheduleStatus.Allowed) {
            data = data.copy(timer = if (s.budgetLeftMs == Long.MAX_VALUE) t.copy(lift = false) else PlayTimer(maxOf(s.budgetLeftMs, t.remainingMs), auto = true))
            stateEvent(now)
            save = true
            return
        }
        if (t != null || data.expired) return
        if (s.status == ScheduleStatus.Allowed && s.budgetLeftMs != Long.MAX_VALUE) {
            data = data.copy(timer = PlayTimer(s.budgetLeftMs, auto = true))
            stateEvent(now)
            save = true
        }
    }

    /**
     * A parent's extra time ([ms] > 0: +, "yes", a later end): the timer gets it, and with the schedule on it raises
     * today's limit too. Given while games aren't allowed at this hour, it lets the child play now.
     */
    private fun Out.grantLocked(now: LocalNow, ms: Long) {
        val s = scheduleLocked(now)
        val notNow = s.status == ScheduleStatus.OutsideWindow || s.status == ScheduleStatus.DayDisabled
        val t = data.timer
        // While games aren't allowed now, only the granted minutes can be played now (not what is left of
        // today's allowance: that comes back when the allowed hours begin, see autoStartLocked)
        data = if (t != null && t.remainingMs > 0 && (t.lift || !notNow)) data.copy(timer = t.copy(remainingMs = t.remainingMs + ms))
        else data.copy(timer = PlayTimer(ms, lift = notNow), expired = false)
        if (s.status.restricts) setDay { it.copy(bonusMs = it.bonusMs + ms) }
        request = null
        save = true
    }

    private fun Out.expireLocked(now: LocalNow) {
        val t = data.timer ?: return
        // A timer started during a lift ends the lift with it (the PC's EndLiftOnExpiry)
        val endLift = t.lift && today().skipped
        data = data.copy(timer = null, expired = true)
        if (endLift) setDay { it.copy(skipped = false) }
        history(now, "expired", source = "system")
        if (endLift) history(now, "scheduleRestored", source = "system")
        request = null
        stateEvent(now)
        save = true
    }

    private fun Out.history(now: LocalNow, type: String, source: String, ctx: RequestContext? = null, minutes: Int? = null, mode: String? = null) {
        val e = PlayEvent(now.iso, type, source, device = ctx?.hubName, hub = ctx?.hubId, mode = mode, minutes = minutes)
        data = data.copy(history = (data.history + e).takeLast(MAX_HISTORY))
        events += "ev.history" to buildJsonObject { put("event", json.encodeToJsonElement(e)) }
        save = true
        changed = true
    }

    private fun Out.stateEvent(now: LocalNow) {
        events.removeAll { it.first == "ev.state" }
        events += "ev.state" to buildJsonObject { put("state", stateLocked(now)) }
        changed = true
    }

    /** §7.1 state. */
    private fun stateLocked(now: LocalNow): JsonObject {
        val s = scheduleLocked(now)
        val t = data.timer
        val timerState = when {
            t != null && t.remainingMs > 0 -> if (running) "running" else "paused"
            t != null || data.expired -> "expired"
            else -> "stopped"
        }
        return buildJsonObject {
            put("now", now.iso)
            put("timer", buildJsonObject {
                put("state", timerState)
                put("mode", "interval")
                put("remainingSeconds", if (t != null) (t.remainingMs.coerceAtLeast(0) + 999) / 1000 else 0)
            })
            put("schedule", buildJsonObject {
                put("status", s.status.wire)
                put("availableSeconds", if (s.availableMs == Long.MAX_VALUE) 0 else s.availableMs / 1000)
                put("usedSecondsToday", today().usedMs / 1000)
            })
            put("interruption", JsonNull)
            put("request", request?.let { r -> buildJsonObject { put("id", r.id); put("time", r.iso) } } ?: JsonNull)
        }
    }

    // ---------------- AgentBackend ----------------

    override suspend fun state(): JsonObject = lock.withLock { stateLocked(platform.now()) }

    override suspend fun handle(type: String, message: JsonObject, context: RequestContext): Reply = when (type) {
        "state.get", "interruption.ack" -> lock.withLock { Reply.ok(buildJsonObject { put("state", stateLocked(platform.now())) }) }
        "timer.start" -> start(message, context)
        "timer.stop" -> stop(message, context)
        "timer.extend" -> extend(message, context)
        "timer.end" -> end(context)
        "timer.setEnd" -> setEnd(message, context)
        "timer.answerRequest" -> answer(message, context)
        "schedule.get" -> lock.withLock { Reply.ok(buildJsonObject { put("schedule", scheduleJson()) }) }
        "schedule.put" -> putSchedule(message, context)
        "history.get" -> historyPage(message)
        "apps.get" -> Reply.ok(buildJsonObject { put("apps", appsJson()) })
        "apps.put" -> putApps(message, context)
        else -> Reply.error("unsupported") // settings: nothing on a phone to set yet
    }

    private fun okState(now: LocalNow): Reply = Reply.ok(buildJsonObject { put("state", stateLocked(now)) })

    private fun start(m: JsonObject, ctx: RequestContext): Reply = change(ctx.origin) {
        val now = platform.now()
        rollOver(now)
        if (m.str("mode") != "interval") return@change Reply.error("invalid_time") // a time of day means nothing for game time
        val hours = m.int("hours") ?: return@change Reply.error("bad_request")
        val mins = m.int("minutes") ?: return@change Reply.error("bad_request")
        if (hours !in 0..24 || mins !in 0..1440) return@change Reply.error("invalid_time")
        val minutes = hours * 60 + mins
        if (minutes <= 0 || minutes > 1440) return@change Reply.error("invalid_time")
        val t = data.timer
        if (t != null && t.remainingMs > 0 && !t.auto) return@change Reply.error("already_running")
        val s = scheduleLocked(now)
        var ms = minutes * 60_000L
        if (s.status.restricts) {
            if (s.status.blocks) return@change Reply.error("schedule_limit")
            ms = minOf(ms, s.budgetLeftMs)
        }
        // During a lift the new timer is the parent's limit, not capped (the PC's rule)
        data = data.copy(timer = PlayTimer(ms, lift = s.status == ScheduleStatus.SkippedToday), expired = false)
        request = null
        history(now, "started", "phone", ctx, minutes = ((ms + 59_999) / 60_000).toInt(), mode = "interval")
        stateEvent(now)
        okState(now)
    }

    private fun stop(m: JsonObject, ctx: RequestContext): Reply = change(ctx.origin) {
        val now = platform.now()
        rollOver(now)
        val skip = m["skipScheduleToday"].isTrue()
        val s = scheduleLocked(now)
        val active = data.timer != null || data.expired
        if (!active && !(skip && s.status.restricts)) return@change Reply.error("not_running")
        if (active) {
            data = data.copy(timer = null, expired = false)
            history(now, "stopped", "phone", ctx)
        }
        if (skip && s.status.restricts) {
            setDay { it.copy(skipped = true) }
            history(now, "scheduleSkipped", "phone", ctx)
        }
        request = null
        // With the schedule still on, "stop" means: no timer of the parent's, the schedule's allowance applies
        if (!skip) autoStartLocked(now)
        stateEvent(now)
        okState(now)
    }

    private fun extend(m: JsonObject, ctx: RequestContext): Reply = change(ctx.origin) {
        val now = platform.now()
        rollOver(now)
        val minutes = m.int("minutes") ?: return@change Reply.error("invalid_minutes")
        if (minutes == 0 || minutes !in -1440..1440) return@change Reply.error("invalid_minutes")
        val t = data.timer
        if (minutes > 0) {
            // + gives game time even when the time is up or today's limit is used (a parent's own decision)
            if (t == null && !blockedLocked(now)) return@change Reply.error("not_running")
            grantLocked(now, minutes * 60_000L)
        } else {
            if (t == null || t.remainingMs <= 0) return@change Reply.error("not_running")
            data = data.copy(timer = t.copy(remainingMs = t.remainingMs + minutes * 60_000L))
        }
        history(now, "extended", "phone", ctx, minutes = minutes)
        if (data.timer!!.remainingMs <= 0) expireLocked(now)
        stateEvent(now)
        okState(now)
    }

    private fun end(ctx: RequestContext): Reply = change(ctx.origin) {
        val now = platform.now()
        val t = data.timer ?: return@change Reply.error("not_running")
        history(now, "extended", "phone", ctx, minutes = -((t.remainingMs.coerceAtLeast(0) + 59_999) / 60_000).toInt().coerceAtLeast(1))
        data = data.copy(timer = t.copy(remainingMs = 0))
        expireLocked(now)
        okState(now)
    }

    /** Only `inSeconds` (minor 2): that much game time left; more than now counts as the parent's extra time. */
    private fun setEnd(m: JsonObject, ctx: RequestContext): Reply = change(ctx.origin) {
        val now = platform.now()
        val t = data.timer ?: return@change Reply.error("not_running")
        if (t.remainingMs <= 0) return@change Reply.error("not_running")
        val secs = m.int("inSeconds") ?: return@change Reply.error("invalid_time")
        if (secs !in 1..86400) return@change Reply.error("invalid_time")
        val delta = secs * 1000L - t.remainingMs
        if (delta > 0) grantLocked(now, delta) else data = data.copy(timer = t.copy(remainingMs = secs * 1000L))
        history(now, "extended", "phone", ctx, minutes = kotlin.math.round(delta / 60_000.0).toInt())
        stateEvent(now)
        okState(now)
    }

    private fun answer(m: JsonObject, ctx: RequestContext): Reply = change(ctx.origin) {
        val now = platform.now()
        val id = m.str("request") ?: return@change Reply.error("bad_request")
        val minutes = m.int("minutes") ?: return@change Reply.error("bad_request")
        if (minutes !in 0..1440) return@change Reply.error("invalid_minutes")
        if (request?.id != id) return@change Reply.error("no_request")
        request = null
        if (minutes == 0) {
            declinedAt = platform.elapsedMillis()
            history(now, "timeRequestDeclined", "phone", ctx)
        } else {
            // A parent's "yes": granted even past today's schedule (owner's decision for C3)
            grantLocked(now, minutes * 60_000L)
            history(now, "extended", "phone", ctx, minutes = minutes)
        }
        stateEvent(now)
        okState(now)
    }

    // ---------------- schedule ----------------

    private fun scheduleJson(): JsonObject = buildJsonObject {
        put("enabled", data.schedule.enabled)
        put("week", buildJsonArray {
            for (d in data.schedule.week) add(buildJsonObject {
                put("allowed", d.allowed); put("limitMinutes", d.limitMinutes)
                put("windowStartMinutes", d.windowStartMinutes); put("windowEndMinutes", d.windowEndMinutes)
            })
        })
        put("rev", data.schedule.rev)
    }

    private fun putSchedule(m: JsonObject, ctx: RequestContext): Reply = change(ctx.origin) {
        val now = platform.now()
        rollOver(now)
        val ifRev = (m["ifRev"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (ifRev != null && ifRev != data.schedule.rev) {
            return@change Reply.error("conflict", buildJsonObject { put("value", scheduleJson()) })
        }
        val week = m["week"] as? JsonArray ?: return@change Reply.error("invalid_schedule")
        if (week.size != 7) return@change Reply.error("invalid_schedule")
        val days = week.map { el ->
            val d = el as? JsonObject ?: return@change Reply.error("invalid_schedule")
            val rule = DayRule(
                allowed = d["allowed"].boolOrNull() ?: return@change Reply.error("invalid_schedule"),
                limitMinutes = d.int("limitMinutes") ?: return@change Reply.error("invalid_schedule"),
                windowStartMinutes = d.int("windowStartMinutes") ?: return@change Reply.error("invalid_schedule"),
                windowEndMinutes = d.int("windowEndMinutes") ?: return@change Reply.error("invalid_schedule"),
            )
            if (rule.limitMinutes !in 0..1440 || rule.windowStartMinutes !in 0..1440 || rule.windowEndMinutes !in 0..1440) {
                return@change Reply.error("invalid_schedule")
            }
            rule
        }
        val enabled = m["enabled"].boolOrNull() ?: return@change Reply.error("invalid_schedule")
        data = data.copy(schedule = WeekSchedule(enabled, days, data.schedule.rev + 1))
        // Today's allowance follows the new schedule; a parent's own timer stays as it is
        if (data.timer?.auto == true) data = data.copy(timer = null)
        if (data.expired && enabled && data.timer == null) data = data.copy(expired = false) // the schedule decides from now on
        autoStartLocked(now)
        request = request?.takeIf { blockedLocked(now) }
        save = true
        events += "ev.schedule" to buildJsonObject { put("schedule", scheduleJson()) }
        stateEvent(now)
        Reply.ok(buildJsonObject { put("schedule", scheduleJson()) })
    }

    // ---------------- history ----------------

    private fun historyPage(m: JsonObject): Reply = lock.withLock {
        val limit = (m.int("limit") ?: 50).coerceIn(1, 100)
        val newestFirst = data.history.asReversed()
        val start = m["before"]?.let { b ->
            (b as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it in 0..newestFirst.size } ?: return Reply.error("bad_request")
        } ?: 0
        val page = newestFirst.drop(start).take(limit)
        val more = start + page.size < newestFirst.size
        Reply.ok(buildJsonObject {
            put("events", JsonArray(page.map { json.encodeToJsonElement(it) }))
            put("more", more)
            if (more) put("next", (start + page.size).toString())
        })
    }

    // ---------------- apps (the limited games) ----------------

    private fun installed(): List<AppEntry> = try { platform.installedApps() } catch (e: Exception) { emptyList() }

    private fun appsJson(installed: List<AppEntry> = installed()): JsonObject {
        val (limited, rev) = lock.withLock { data.limited to data.appsRev }
        return buildJsonObject {
            put("installed", buildJsonArray {
                for (a in installed) add(buildJsonObject { put("id", a.id); put("name", a.name); put("limited", a.id in limited) })
            })
            put("rev", rev)
        }
    }

    private fun putApps(m: JsonObject, ctx: RequestContext): Reply {
        val list = m["limited"] as? JsonArray ?: return Reply.error("bad_request")
        if (list.size > MAX_LIMITED) return Reply.error("bad_request")
        val ids = list.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return Reply.error("bad_request") }
        if (ids.any { it.isEmpty() || it.length > 256 }) return Reply.error("bad_request")
        // Only apps a parent can limit: not this app, a home screen or the phone app (they keep the phone usable)
        val installed = installed()
        if (installed.isEmpty()) return Reply.error("internal") // the list couldn't be read: don't wipe the limits
        val allowed = installed.map { it.id }.toSet()
        return change(ctx.origin) {
            val ifRev = (m["ifRev"] as? JsonPrimitive)?.content?.toLongOrNull()
            if (ifRev != null && ifRev != data.appsRev) {
                return@change Reply.error("conflict", buildJsonObject { put("value", appsJson(installed)) })
            }
            data = data.copy(limited = ids.filter { it in allowed }.toSet(), appsRev = data.appsRev + 1)
            save = true
            val apps = appsJson(installed)
            events += "ev.apps" to buildJsonObject { put("apps", apps) }
            stateEvent(platform.now())
            Reply.ok(buildJsonObject { put("apps", apps) })
        }
    }

    private fun randomId(): String = LibsodiumRandom.buf(8).asByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** The weekday (0 = Sunday) of a `YYYY-MM-DD` date, or null. */
internal fun weekdayOf(date: String): Int? = epochDay(date)?.let { ((it + 4).mod(7L)).toInt() } // 1970-01-01: a Thursday

/** The date after a `YYYY-MM-DD` date, or null. */
internal fun nextDate(date: String): String? = epochDay(date)?.let { civil(it + 1) }

/** Days from 1970-01-01 (Howard Hinnant's days_from_civil), or null. */
private fun epochDay(date: String): Long? {
    val parts = date.split('-')
    if (parts.size != 3) return null
    var y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val d = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    if (m <= 2) y -= 1
    val era = y.floorDiv(400)
    val yoe = y - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097 + doe - 719_468
}

/** `YYYY-MM-DD` of a day from 1970-01-01 (civil_from_days). */
private fun civil(epochDay: Long): String {
    val z = epochDay + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** An integer member that fits an Int, else null (a string or a fraction isn't one). */
private fun JsonObject.int(name: String): Int? {
    val p = this[name] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.content.toIntOrNull()
}

/** A JSON boolean (not the string "true"). */
private fun kotlinx.serialization.json.JsonElement?.boolOrNull(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.content.toBooleanStrictOrNull()
}

private fun kotlinx.serialization.json.JsonElement?.isTrue(): Boolean = boolOrNull() == true
