using System.Text.Json.Serialization;
using GameTimer.Localization;

namespace GameTimer.Models;

public class AppSettings
{
    // Overlay position (percentage of screen)
    public double OverlayX { get; set; } = 0.95;
    public double OverlayY { get; set; } = 0.02;

    // Overlay appearance
    public int FontSize { get; set; } = 24;
    public int Opacity { get; set; } = 70; // 20-100
    public bool ShowBackground { get; set; } = true;

    // Color zones (hex)
    public string ColorGreen { get; set; } = "#4CAF50";
    public string ColorYellow { get; set; } = "#FFC107";
    public string ColorRed { get; set; } = "#F44336";

    // Thresholds (minutes)
    public int YellowThreshold { get; set; } = 30;
    public int RedThreshold { get; set; } = 10;

    // Timer action
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public TimerAction Action { get; set; } = TimerAction.Shutdown;

    // Last used timer mode
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public TimerMode LastMode { get; set; } = TimerMode.Interval;

    // Last used time values
    public int LastAbsoluteHour { get; set; } = 20;
    public int LastAbsoluteMinute { get; set; } = 0;
    public int LastIntervalHours { get; set; } = 1;
    public int LastIntervalMinutes { get; set; } = 0;

    // Sound notifications
    public List<AlertPoint> AlertPoints { get; set; } = new()
    {
        new AlertPoint { Minutes = 10, Enabled = true },
        new AlertPoint { Minutes = 2, Enabled = true },
        new AlertPoint { Minutes = 1, Enabled = true }
    };

    // Custom sound file path (null = built-in)
    public string? CustomSoundPath { get; set; }

    // UI language (en, ru)
    public string Language { get; set; } = "en";

    // Phone remote over home Wi-Fi (embedded server). Off by default.
    public bool RemoteEnabled { get; set; } = false;
    public int RemotePort { get; set; } = 5123;

    // Paired phones (token hashes only)
    public List<PairedDevice> Devices { get; set; } = new();

    /// <summary>Color zone for the given remaining time. Shared by the overlay and the phone API.</summary>
    public TimerZone GetZone(TimeSpan remaining)
    {
        if (remaining.TotalMinutes <= RedThreshold) return TimerZone.Red;
        if (remaining.TotalMinutes <= YellowThreshold) return TimerZone.Yellow;
        return TimerZone.Green;
    }

    public string GetZoneColor(TimerZone zone) => zone switch
    {
        TimerZone.Red => ColorRed,
        TimerZone.Yellow => ColorYellow,
        _ => ColorGreen
    };
}

public class AlertPoint
{
    public int Minutes { get; set; }
    public bool Enabled { get; set; } = true;

    [JsonIgnore]
    public string DisplayText => $"{Minutes} {Loc.Get("MinBeforeTimer")}";
}

public enum TimerAction
{
    Shutdown,
    Lock,
    Logoff
}

public enum TimerMode
{
    Absolute,
    Interval
}

public enum TimerZone
{
    Green,
    Yellow,
    Red
}
