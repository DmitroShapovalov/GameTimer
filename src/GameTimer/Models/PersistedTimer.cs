using System.Text.Json.Serialization;

namespace GameTimer.Models;

/// <summary>
/// A running timer saved to disk so it survives the app being closed, the PC rebooting or the
/// user logging off (parental-control resilience: the countdown must not disappear when the app
/// stops). Written while a timer runs and removed on a clean stop / reset / expiry.
/// </summary>
public sealed class PersistedTimer
{
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public TimerMode Mode { get; set; }

    /// <summary>Monotonic deadline (<see cref="System.Environment.TickCount64"/>); used when the PC has not rebooted — clock-change proof.</summary>
    public long DeadlineTick { get; set; }

    /// <summary>Wall-clock deadline (UTC); used to resume after a reboot, when the monotonic clock has reset.</summary>
    public DateTimeOffset TargetUtc { get; set; }

    /// <summary>Approximate boot time (UTC) at save, to tell "same boot" from "rebooted".</summary>
    public DateTimeOffset BootTimeUtc { get; set; }

    /// <summary>Last moment the app was known to be running, for the "closed at HH:MM" message.</summary>
    public DateTimeOffset LastSeenUtc { get; set; }

    /// <summary>Alert points already played, so they are not repeated after a resume.</summary>
    public int[] FiredAlerts { get; set; } = Array.Empty<int>();

    /// <summary>
    /// Saved by a parent-confirmed exit for an update / uninstall: the next start resumes the timer
    /// without reporting an unclean close.
    /// </summary>
    public bool ClosedForUpdate { get; set; }

    /// <summary>Version that wrote this state. An update exit counts as clean only if the next start is a different version.</summary>
    public string? AppVersion { get; set; }

    /// <summary>A "closed at HH:MM" notice the parent hasn't acknowledged yet, carried across restarts.</summary>
    public DateTimeOffset? PendingInterruption { get; set; }
}
