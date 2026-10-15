using GameTimer.Models;

namespace GameTimer.Services;

public enum ScheduleStatus
{
    Off,           // schedule disabled — no restriction
    Allowed,       // within an allowed window with budget left
    OutsideWindow, // before / after today's allowed hours
    DayDisabled,   // gaming not allowed on this day at all
    LimitReached   // today's minute budget is used up
}

/// <summary>Result of evaluating the schedule for a given moment.</summary>
public readonly record struct ScheduleDecision(ScheduleStatus Status, int AvailableSeconds, int WindowEndMinutes)
{
    public bool IsBlocked => Status is ScheduleStatus.OutsideWindow or ScheduleStatus.DayDisabled or ScheduleStatus.LimitReached;
}

/// <summary>
/// Pure decision logic for the daily schedule (per-weekday allowed window + minute budget).
/// No state, no UI, no clock of its own — the caller passes <c>now</c>, so it is fully testable.
/// </summary>
public static class ScheduleEvaluator
{
    public static ScheduleDecision Evaluate(DateTime now, bool enabled, IReadOnlyList<DaySchedule> week, int usedSecondsToday)
    {
        if (!enabled || week == null || week.Count < 7)
            return new ScheduleDecision(ScheduleStatus.Off, 0, 1440);

        var day = week[(int)now.DayOfWeek];
        if (!day.Allowed)
            return new ScheduleDecision(ScheduleStatus.DayDisabled, 0, day.WindowEndMinutes);

        // Normalise the window; a reversed or empty window is treated as all-day (permissive).
        int start = Math.Clamp(day.WindowStartMinutes, 0, 1440);
        int end = Math.Clamp(day.WindowEndMinutes, 0, 1440);
        if (end <= start) { start = 0; end = 1440; }

        int nowSeconds = now.Hour * 3600 + now.Minute * 60 + now.Second;
        int startSeconds = start * 60;
        int endSeconds = end * 60;

        if (nowSeconds < startSeconds || nowSeconds >= endSeconds)
            return new ScheduleDecision(ScheduleStatus.OutsideWindow, 0, end);

        // Budget (0 = unlimited within the window)
        long budgetLeft = day.LimitMinutes > 0
            ? (long)day.LimitMinutes * 60 - Math.Max(0, usedSecondsToday)
            : long.MaxValue;
        if (budgetLeft <= 0)
            return new ScheduleDecision(ScheduleStatus.LimitReached, 0, end);

        int untilWindowEnd = endSeconds - nowSeconds;
        int available = (int)Math.Min(budgetLeft, untilWindowEnd);
        if (available <= 0) // window closes this second
            return new ScheduleDecision(ScheduleStatus.OutsideWindow, 0, end);

        return new ScheduleDecision(ScheduleStatus.Allowed, available, end);
    }
}
