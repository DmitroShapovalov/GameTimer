using System.Windows.Threading;
using GameTimer.Models;

namespace GameTimer.Services;

public enum TimerState
{
    NotStarted,
    Running,
    Stopped,
    Expired
}

/// <summary>
/// Single source of truth for the timer. The main window, and later the phone API,
/// are clients: they call these methods and react to the events.
/// Authorization is the caller's job. All members must be called on the UI thread;
/// background callers (API handlers) marshal with Dispatcher.InvokeAsync.
/// </summary>
public sealed class TimerController
{
    private const int MaxExtendMinutes = 24 * 60;
    // How "same boot" is judged, and how often the heartbeat refreshes the saved "last seen" time
    private static readonly TimeSpan BootMatchTolerance = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(15);

    private readonly TimerService _timer;
    private readonly AppSettings _settings;
    private readonly TimerStateStore _store;
    private readonly Dispatcher _dispatcher;
    private readonly DispatcherTimer _heartbeat;

    /// <summary>Raised after every state transition.</summary>
    public event Action<TimerState>? StateChanged;

    /// <summary>Raised every second while running, and right after start / extend.</summary>
    public event Action<TimeSpan>? Tick;

    /// <summary>Raised when an alert point is reached (minutes remaining).</summary>
    public event Action<int>? AlertTriggered;

    /// <summary>Raised after Extend changed a still-running timer (minutes added, negative = removed).</summary>
    public event Action<int>? Extended;

    /// <summary>Raised when a resume finds the app was closed while a timer ran (tamper signal), and when that note is cleared.</summary>
    public event Action? InterruptionChanged;

    /// <summary>
    /// When a resume detected the app had been closed/killed while a timer was running, the time it was
    /// last seen; null otherwise. Surfaced to the parent (PC note and phone) and cleared on acknowledge.
    /// </summary>
    public DateTimeOffset? LastInterruption { get; private set; }

    public TimerState State { get; private set; } = TimerState.NotStarted;
    public TimerMode? Mode { get; private set; }
    public bool IsRunning => State == TimerState.Running;
    public TimeSpan Remaining => _timer.Remaining;

    /// <summary>Wall-clock moment the timer ends (derived from the monotonic remaining time).</summary>
    public DateTime? TargetTime => IsRunning ? DateTime.Now + Remaining : null;

    public TimerController(AppSettings settings, TimerStateStore store)
    {
        _settings = settings;
        _store = store;
        _dispatcher = Dispatcher.CurrentDispatcher;
        _timer = new TimerService(settings);
        _timer.Tick += remaining => Tick?.Invoke(remaining);
        _timer.AlertTriggered += minutes => AlertTriggered?.Invoke(minutes);
        _timer.TimerExpired += OnExpired;

        // Refreshes the saved "last seen" time so a later tamper report shows when the app was closed
        _heartbeat = new DispatcherTimer { Interval = HeartbeatInterval };
        _heartbeat.Tick += (_, _) => { if (IsRunning) Persist(); };
    }

    /// <summary>
    /// Resumes a timer that was running when the app last stopped (close, kill, reboot, logoff).
    /// Call once at startup, on the UI thread, after listeners are subscribed. If the time already ran
    /// out while the app was gone, the expiry action runs now.
    /// </summary>
    public void TryResume()
    {
        _dispatcher.VerifyAccess();
        var saved = _store.Load();
        if (saved == null)
        {
            DiagnosticLog.Write("resume: no saved timer");
            return;
        }

        // A leftover running state means the app did not exit cleanly through the tray (which stops the
        // timer after Windows Hello). That is the tamper signal shown to the parent.
        LastInterruption = saved.LastSeenUtc;
        InterruptionChanged?.Invoke();

        // Same boot → trust the monotonic deadline (clock-change proof). Rebooted → fall back to wall clock.
        var bootNow = DateTimeOffset.UtcNow - TimeSpan.FromMilliseconds(Environment.TickCount64);
        bool sameBoot = (bootNow - saved.BootTimeUtc).Duration() < BootMatchTolerance;
        var remaining = sameBoot
            ? TimeSpan.FromMilliseconds(saved.DeadlineTick - Environment.TickCount64)
            : saved.TargetUtc - DateTimeOffset.UtcNow;

        Mode = saved.Mode;
        DiagnosticLog.Write($"resume: sameBoot={sameBoot} remaining={remaining.TotalSeconds:F0}s action={_settings.Action}");
        if (remaining <= TimeSpan.Zero)
        {
            // Time ran out while the app was not running — enforce the action now
            _store.Clear();
            SetState(TimerState.Expired);
            try { ShutdownService.Execute(_settings.Action); }
            catch (Exception ex) { DiagnosticLog.Write("resume: action failed: " + ex); }
            return;
        }

        _timer.Start(remaining, saved.FiredAlerts);
        _heartbeat.Start();
        SetState(TimerState.Running);
        Tick?.Invoke(Remaining);
        Persist();
    }

    /// <summary>Clears the "app was closed while running" note once the parent has seen it.</summary>
    public void AcknowledgeInterruption()
    {
        _dispatcher.VerifyAccess();
        if (LastInterruption == null) return;
        LastInterruption = null;
        InterruptionChanged?.Invoke();
    }

    /// <summary>Starts a countdown of the given length. Returns false if running or input is invalid.</summary>
    public bool StartInterval(int hours, int minutes)
    {
        _dispatcher.VerifyAccess();
        if (IsRunning) return false;
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return false;
        if (hours == 0 && minutes == 0) return false;

        Begin(TimerMode.Interval, new TimeSpan(hours, minutes, 0));
        return true;
    }

    /// <summary>
    /// Starts a countdown to the given time of day (tomorrow if it has already passed today).
    /// The wall clock is read once here; from then on only the monotonic clock counts.
    /// </summary>
    public bool StartAbsolute(int hour, int minute)
    {
        _dispatcher.VerifyAccess();
        if (IsRunning) return false;
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return false;

        var now = DateTime.Now;
        var target = new DateTime(now.Year, now.Month, now.Day, hour, minute, 0);
        if (target <= now)
            target = target.AddDays(1);

        Begin(TimerMode.Absolute, target - now);
        return true;
    }

    /// <summary>Stops a running timer and cancels any pending shutdown.</summary>
    public bool Stop()
    {
        _dispatcher.VerifyAccess();
        if (!IsRunning) return false;

        _timer.Stop();
        _heartbeat.Stop();
        _store.Clear();
        ShutdownService.CancelShutdown();
        SetState(TimerState.Stopped);
        return true;
    }

    /// <summary>Stops the timer (if any), cancels any pending shutdown and returns to "not started".</summary>
    public void Reset()
    {
        _dispatcher.VerifyAccess();
        _timer.Stop();
        _heartbeat.Stop();
        _store.Clear();
        ShutdownService.CancelShutdown();
        Mode = null;
        SetState(TimerState.NotStarted);
    }

    /// <summary>
    /// Adds (positive) or removes (negative) minutes on a running timer. Never below zero:
    /// removing more than is left expires the timer. In time-of-day mode this shifts the target.
    /// </summary>
    public bool Extend(int minutes)
    {
        _dispatcher.VerifyAccess();
        if (!IsRunning || minutes == 0 || Math.Abs(minutes) > MaxExtendMinutes) return false;

        _timer.Extend(TimeSpan.FromMinutes(minutes));

        if (_timer.Remaining > TimeSpan.Zero)
            Persist(); // save the new deadline before any expiry clears it

        // Clients learn the new time first, then any alert it reached (or the expiry at zero)
        if (_timer.Remaining > TimeSpan.Zero)
            Extended?.Invoke(minutes);
        _timer.EvaluateNow();
        return true;
    }

    private void Begin(TimerMode mode, TimeSpan duration)
    {
        Mode = mode;
        _timer.Start(duration);
        _heartbeat.Start();
        Persist();
        SetState(TimerState.Running);
        Tick?.Invoke(Remaining);
    }

    private void OnExpired()
    {
        _heartbeat.Stop();
        _store.Clear();
        SetState(TimerState.Expired);
        ShutdownService.Execute(_settings.Action);
    }

    private void Persist()
    {
        if (Mode is not TimerMode mode) return;
        _store.Save(new PersistedTimer
        {
            Mode = mode,
            DeadlineTick = _timer.DeadlineTick,
            TargetUtc = DateTimeOffset.UtcNow + Remaining,
            BootTimeUtc = DateTimeOffset.UtcNow - TimeSpan.FromMilliseconds(Environment.TickCount64),
            LastSeenUtc = DateTimeOffset.UtcNow,
            FiredAlerts = _timer.FiredAlerts.ToArray()
        });
    }

    private void SetState(TimerState state)
    {
        State = state;
        StateChanged?.Invoke(state);
    }
}
