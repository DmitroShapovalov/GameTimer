using System.Text.Json.Serialization;

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
}

public class AlertPoint
{
    public int Minutes { get; set; }
    public bool Enabled { get; set; } = true;
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
