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

    private readonly TimerService _timer;
    private readonly AppSettings _settings;
    private readonly Dispatcher _dispatcher;

    /// <summary>Raised after every state transition.</summary>
    public event Action<TimerState>? StateChanged;

    /// <summary>Raised every second while running, and right after start / extend.</summary>
    public event Action<TimeSpan>? Tick;

    /// <summary>Raised when an alert point is reached (minutes remaining).</summary>
    public event Action<int>? AlertTriggered;

    public TimerState State { get; private set; } = TimerState.NotStarted;
    public TimerMode? Mode { get; private set; }
    public bool IsRunning => State == TimerState.Running;
    public TimeSpan Remaining => _timer.Remaining;

    /// <summary>Wall-clock moment the timer ends (derived from the monotonic remaining time).</summary>
    public DateTime? TargetTime => IsRunning ? DateTime.Now + Remaining : null;

    public TimerController(AppSettings settings)
    {
        _settings = settings;
        _dispatcher = Dispatcher.CurrentDispatcher;
        _timer = new TimerService(settings);
        _timer.Tick += remaining => Tick?.Invoke(remaining);
        _timer.AlertTriggered += minutes => AlertTriggered?.Invoke(minutes);
        _timer.TimerExpired += OnExpired;
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
        ShutdownService.CancelShutdown();
        SetState(TimerState.Stopped);
        return true;
    }

    /// <summary>Stops the timer (if any), cancels any pending shutdown and returns to "not started".</summary>
    public void Reset()
    {
        _dispatcher.VerifyAccess();
        _timer.Stop();
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
        return true;
    }

    private void Begin(TimerMode mode, TimeSpan duration)
    {
        Mode = mode;
        _timer.Start(duration);
        SetState(TimerState.Running);
        Tick?.Invoke(Remaining);
    }

    private void OnExpired()
    {
        SetState(TimerState.Expired);
        ShutdownService.Execute(_settings.Action);
    }

    private void SetState(TimerState state)
    {
        State = state;
        StateChanged?.Invoke(state);
    }
}
