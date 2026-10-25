using System.IO;
using System.Text.Json;

namespace GameTimer.Services;

/// <summary>
/// Tracks how many seconds of game time have been used today (local date), for the daily budget.
/// Persisted to usage.json next to the exe so it survives restarts; resets at local midnight.
/// Call on the UI thread.
/// </summary>
public sealed class UsageTracker
{
    private static readonly string UsagePath = Path.Combine(AppContext.BaseDirectory, "usage.json");
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private sealed class Record
    {
        public string Date { get; set; } = "";
        public int UsedSeconds { get; set; }
        /// <summary>The parent lifted the schedule for this date (rolls over with it at midnight).</summary>
        public bool ScheduleSkipped { get; set; }
    }

    private Record _record;

    public UsageTracker()
    {
        _record = Load() ?? new Record { Date = Today(), UsedSeconds = 0 };
        RollOver();
    }

    private static string Today() => DateTime.Now.ToString("yyyy-MM-dd");

    /// <summary>Seconds of game time used so far today.</summary>
    public int UsedSecondsToday
    {
        get { RollOver(); return _record.UsedSeconds; }
    }

    /// <summary>True while the parent has lifted the schedule for today.</summary>
    public bool ScheduleSkippedToday
    {
        get { RollOver(); return _record.ScheduleSkipped; }
    }

    /// <summary>Lifts (or restores) the schedule for the rest of today and persists.</summary>
    public void SetScheduleSkippedToday(bool skipped)
    {
        RollOver();
        if (_record.ScheduleSkipped == skipped) return;
        _record.ScheduleSkipped = skipped;
        Save();
    }

    /// <summary>Adds elapsed game time to today's total and persists.</summary>
    public void Add(int seconds)
    {
        if (seconds <= 0) return;
        RollOver();
        _record.UsedSeconds += seconds;
        Save();
    }

    private void RollOver()
    {
        var today = Today();
        if (_record.Date != today)
        {
            _record = new Record { Date = today, UsedSeconds = 0 };
            Save();
        }
    }

    private Record? Load()
    {
        try
        {
            var json = ProtectedStore.Read(UsagePath);
            if (json != null)
                return JsonSerializer.Deserialize<Record>(json, JsonOptions);
        }
        catch { /* treat as no record */ }
        return null;
    }

    private void Save()
    {
        try
        {
            ProtectedStore.Write(UsagePath, JsonSerializer.Serialize(_record, JsonOptions));
        }
        catch { /* non-critical */ }
    }
}
