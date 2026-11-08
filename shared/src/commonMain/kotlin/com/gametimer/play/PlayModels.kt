package com.gametimer.play

import kotlinx.serialization.Serializable

/**
 * The child's phone (Phase C, C3): what it enforces, in the shapes of protocol §7 so the parent app shows a
 * phone like the PC. Platform-free (Android today, iOS later); the app feeds it the time and the app in front.
 */

/** One weekday: [limitMinutes] of game time (0 = no limit) inside the allowed hours (minutes from midnight). */
@Serializable
public data class DayRule(
    val allowed: Boolean = true,
    val limitMinutes: Int = 0,
    val windowStartMinutes: Int = 0,
    val windowEndMinutes: Int = 1440,
)

/** §7.2 `schedule`: [week] indexed by weekday, 0 = Sunday. */
@Serializable
public data class WeekSchedule(val enabled: Boolean = false, val week: List<DayRule> = List(7) { DayRule() }, val rev: Long = 1)

/**
 * The countdown of game time: it runs only while a limited game is in front (owner's decision for C3).
 * [lift]: the schedule's allowed hours don't block it (a parent's grant while games weren't allowed, or a timer
 * started during a lift). [auto]: today's allowance, started by the schedule itself. With the schedule on, any
 * timer ends at local midnight: each day starts afresh.
 */
@Serializable
public data class PlayTimer(val remainingMs: Long, val lift: Boolean = false, val auto: Boolean = false)

/** One local day's play: game time used, extra time parents granted (it adds to the schedule's limit), a lift. */
@Serializable
public data class DayUse(val usedMs: Long = 0, val bonusMs: Long = 0, val skipped: Boolean = false)

/** One history event in the shape of §7.3 `ev.history` (the PC's SessionEvent). */
@Serializable
public data class PlayEvent(
    val time: String,
    val type: String,
    val source: String,
    val device: String? = null,
    val hub: String? = null,
    val mode: String? = null,
    val minutes: Int? = null,
    val action: String? = null,
)

/** An app the parent can limit: its package and its label. */
@Serializable
public data class AppEntry(val id: String, val name: String)

/** Everything the phone keeps about play (persisted by the app). */
@Serializable
public data class PlayData(
    val timer: PlayTimer? = null,
    /** Time's up and no parent has acted yet: limited games stay blocked. */
    val expired: Boolean = false,
    /** The local day (`YYYY-MM-DD`) the phone last saw. */
    val today: String = "",
    /**
     * Play per local day, the last few days: going back to a date already seen (a changed date or time zone)
     * finds its time used, never a fresh day.
     */
    val days: Map<String, DayUse> = emptyMap(),
    val schedule: WeekSchedule = WeekSchedule(),
    /** Packages of the limited apps ("games"), chosen by a parent. */
    val limited: Set<String> = emptySet(),
    val appsRev: Long = 1,
    /** Newest last, at most [PlayBackend.MAX_HISTORY]. */
    val history: List<PlayEvent> = emptyList(),
    /** The boot ([PlayPlatform.bootCount]) and the monotonic time of the counted day's midnight in it. */
    val anchorBoot: Int = -1,
    val anchorMidnight: Long = 0,
)

/** The local time as the phone sees it (its zone): the date, the second of the day, the weekday. */
public data class LocalNow(
    val epochMillis: Long,
    /** `YYYY-MM-DD` */
    val date: String,
    val secondOfDay: Int,
    /** 0 = Sunday … 6 = Saturday */
    val weekday: Int,
    /** ISO 8601 with the offset, for `now` and history times. */
    val iso: String,
)

/** §7.1 `schedule.status`, as the PC's ScheduleEvaluator. */
public enum class ScheduleStatus(public val wire: String) {
    Off("off"), Allowed("allowed"), OutsideWindow("outsideWindow"), DayDisabled("dayDisabled"),
    LimitReached("limitReached"), SkippedToday("skippedToday");

    public val blocks: Boolean get() = this == OutsideWindow || this == DayDisabled || this == LimitReached
    public val restricts: Boolean get() = this != Off && this != SkippedToday
}

public data class ScheduleDecision(
    val status: ScheduleStatus,
    /** Game time left today (budget, and the time to the end of the allowed hours); [Long.MAX_VALUE] = no limit. */
    val availableMs: Long,
    /** Budget left alone (no window): what a timer is capped to. [Long.MAX_VALUE] = no limit. */
    val budgetLeftMs: Long,
)

/** The PC's ScheduleEvaluator (src/GameTimer/Services/ScheduleEvaluator.cs), with game time instead of timer time. */
public object ScheduleRules {
    /** [usedMs]: game time used today less a parent's extra time (below 0 when extra time is left over). */
    public fun evaluate(now: LocalNow, schedule: WeekSchedule, usedMs: Long, skippedToday: Boolean): ScheduleDecision {
        if (!schedule.enabled || schedule.week.size < 7) return ScheduleDecision(ScheduleStatus.Off, Long.MAX_VALUE, Long.MAX_VALUE)
        if (skippedToday) return ScheduleDecision(ScheduleStatus.SkippedToday, Long.MAX_VALUE, Long.MAX_VALUE)
        val day = schedule.week[now.weekday]
        if (!day.allowed) return ScheduleDecision(ScheduleStatus.DayDisabled, 0, 0)
        var start = day.windowStartMinutes.coerceIn(0, 1440)
        var end = day.windowEndMinutes.coerceIn(0, 1440)
        if (end <= start) { start = 0; end = 1440 } // a reversed or empty window counts as all day (permissive)
        val nowS = now.secondOfDay
        if (nowS < start * 60 || nowS >= end * 60) return ScheduleDecision(ScheduleStatus.OutsideWindow, 0, 0)
        val budgetLeft = if (day.limitMinutes > 0) day.limitMinutes * 60_000L - usedMs else Long.MAX_VALUE
        if (budgetLeft <= 0) return ScheduleDecision(ScheduleStatus.LimitReached, 0, 0)
        val untilEnd = (end * 60 - nowS) * 1000L
        return ScheduleDecision(ScheduleStatus.Allowed, minOf(budgetLeft, untilEnd), budgetLeft)
    }
}
