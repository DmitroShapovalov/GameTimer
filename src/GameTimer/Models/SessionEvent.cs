namespace GameTimer.Models;

public enum SessionEventType
{
    Started,
    Stopped,
    Reset,
    Extended,
    Expired,
    Resumed,
    Closed,
    Tampered,
    Updated,
    ScheduleSkipped,  // a parent lifted the schedule for today
    ScheduleRestored  // the lift ended (a timer started during it ran out)
}

/// <summary>Who caused an event, for the history view.</summary>
public enum ActionSource
{
    Pc,
    Phone,
    System
}

/// <summary>
/// One line in the game-session history (stored as JSONL next to the exe). Null fields are omitted,
/// so each event only carries what it needs (minutes for Extended, action for Expired, etc.).
/// </summary>
public sealed class SessionEvent
{
    public DateTimeOffset Time { get; set; }

    public SessionEventType Type { get; set; }

    public ActionSource Source { get; set; }

    /// <summary>Phone name when Source is Phone; null otherwise.</summary>
    public string? Device { get; set; }

    public TimerMode? Mode { get; set; }

    /// <summary>Planned length for Started, or the signed change for Extended.</summary>
    public int? Minutes { get; set; }

    public TimerAction? Action { get; set; }
}
