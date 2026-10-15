namespace GameTimer.Models;

/// <summary>
/// Rules for one day of the week. Times are minutes from local midnight (0..1440).
/// Defaults are permissive (allowed all day, no budget) so an enabled-but-unconfigured schedule
/// never locks anyone out by surprise.
/// </summary>
public sealed class DaySchedule
{
    /// <summary>Is gaming allowed on this day at all.</summary>
    public bool Allowed { get; set; } = true;

    /// <summary>Daily budget in minutes; 0 means no budget (only the window limits).</summary>
    public int LimitMinutes { get; set; } = 0;

    /// <summary>Allowed window start, minutes from midnight (0..1440).</summary>
    public int WindowStartMinutes { get; set; } = 0;

    /// <summary>Allowed window end, minutes from midnight (0..1440). Start 0 &amp; end 1440 = all day.</summary>
    public int WindowEndMinutes { get; set; } = 1440;
}
