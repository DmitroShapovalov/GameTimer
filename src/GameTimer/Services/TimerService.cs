using System.Windows.Threading;
using GameTimer.Models;

namespace GameTimer.Services;

/// <summary>
/// Low-level countdown. Remaining time is measured with a monotonic clock
/// (<see cref="Environment.TickCount64"/>), so changing the system clock does not affect it.
/// GetTickCount64 keeps counting while the PC sleeps, so time spent asleep still counts down.
/// Must be used from the UI thread (it is driven by a <see cref="DispatcherTimer"/>).
/// </summary>
public class TimerService
{
    private readonly DispatcherTimer _timer;
    private long _deadlineMs;
    private readonly HashSet<int> _firedAlerts = new();

    public event Action<TimeSpan>? Tick;
    public event Action<int>? AlertTriggered; // minutes remaining
    public event Action? TimerExpired;

    public bool IsRunning => _timer.IsEnabled;

    public TimeSpan Remaining
    {
        get
        {
            if (!IsRunning) return TimeSpan.Zero;
            var ms = _deadlineMs - Environment.TickCount64;
            return ms > 0 ? TimeSpan.FromMilliseconds(ms) : TimeSpan.Zero;
        }
    }

    /// <summary>Absolute monotonic deadline (<see cref="Environment.TickCount64"/>), for persistence.</summary>
    public long DeadlineTick => _deadlineMs;

    /// <summary>Alert points already fired, so a resumed timer does not replay them.</summary>
    public IReadOnlyCollection<int> FiredAlerts => _firedAlerts;

    public AppSettings Settings { get; set; }

    public TimerService(AppSettings settings)
    {
        Settings = settings;
        _timer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(1)
        };
        _timer.Tick += (_, _) => Evaluate();
    }

    public void Start(TimeSpan duration, IEnumerable<int>? firedAlerts = null)
    {
        _deadlineMs = Environment.TickCount64 + (long)duration.TotalMilliseconds;
        _firedAlerts.Clear();
        if (firedAlerts != null)
            foreach (var m in firedAlerts) _firedAlerts.Add(m);
        _timer.Start();
    }

    /// <summary>
    /// Adds (or, if negative, removes) time on a running timer. Never goes below zero.
    /// Call <see cref="EvaluateNow"/> afterwards to update listeners (and expire at zero).
    /// </summary>
    public void Extend(TimeSpan delta)
    {
        if (!IsRunning) return;

        var newRemaining = Remaining + delta;
        if (newRemaining < TimeSpan.Zero) newRemaining = TimeSpan.Zero;
        _deadlineMs = Environment.TickCount64 + (long)newRemaining.TotalMilliseconds;

        // Alerts that are now ahead of us again can fire again
        var minutesLeft = (int)Math.Ceiling(newRemaining.TotalMinutes);
        _firedAlerts.RemoveWhere(m => m < minutesLeft);

        // Restart the interval so the next tick is a full second away
        _timer.Stop();
        _timer.Start();
    }

    /// <summary>Raises Tick / alerts / expiry for the current remaining time without waiting for the next tick.</summary>
    public void EvaluateNow()
    {
        if (IsRunning) Evaluate();
    }

    public void Stop()
    {
        _timer.Stop();
        _firedAlerts.Clear();
    }

    private void Evaluate()
    {
        var remaining = Remaining;

        if (remaining <= TimeSpan.Zero)
        {
            _timer.Stop();
            Tick?.Invoke(TimeSpan.Zero);
            TimerExpired?.Invoke();
            return;
        }

        Tick?.Invoke(remaining);

        // Check alert points
        var totalMinutes = (int)Math.Ceiling(remaining.TotalMinutes);
        foreach (var alert in Settings.AlertPoints)
        {
            if (alert.Enabled && totalMinutes == alert.Minutes && !_firedAlerts.Contains(alert.Minutes))
            {
                _firedAlerts.Add(alert.Minutes);
                AlertTriggered?.Invoke(alert.Minutes);
            }
        }
    }
}
