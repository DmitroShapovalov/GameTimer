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
    private static readonly TimeSpan ScheduleCheckInterval = TimeSpan.FromSeconds(30);

    private readonly TimerService _timer;
    private readonly AppSettings _settings;
    private readonly TimerStateStore _store;
    private readonly SessionLog _log;
    private readonly UsageTracker _usage;
    private readonly Dispatcher _dispatcher;
    private readonly DispatcherTimer _heartbeat;
    private readonly DispatcherTimer _scheduleCheck;
    private long _lastRemainingMs;   // to accrue used time from the countdown's progress
    private bool _lastBlocked;       // debounce the enforcement action

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

    /// <summary>Raised after the schedule state (allowed / blocked / budget) may have changed.</summary>
    public event Action? ScheduleStateChanged;

    /// <summary>Seconds of game time used so far today (for the daily budget).</summary>
    public int UsedSecondsToday => _usage.UsedSecondsToday;

    /// <summary>The schedule decision for right now (Off when the schedule is disabled).</summary>
    public ScheduleDecision CurrentSchedule() =>
        ScheduleEvaluator.Evaluate(DateTime.Now, _settings.ScheduleEnabled, _settings.Week, _usage.UsedSecondsToday);

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

    public TimerController(AppSettings settings, TimerStateStore store, SessionLog log, UsageTracker usage)
    {
        _settings = settings;
        _store = store;
        _log = log;
        _usage = usage;
        _dispatcher = Dispatcher.CurrentDispatcher;
        _timer = new TimerService(settings);
        _timer.Tick += OnTick;
        _timer.AlertTriggered += minutes => AlertTriggered?.Invoke(minutes);
        _timer.TimerExpired += OnExpired;

        // Enforces the daily schedule (auto-stop + lock when blocked, clamp when the window/budget shrinks)
        _scheduleCheck = new DispatcherTimer { Interval = ScheduleCheckInterval };
        _scheduleCheck.Tick += (_, _) => ScheduleCheck();
        _scheduleCheck.Start();

        // On each tick: accrue used game time, then notify listeners
        void OnTick(TimeSpan remaining)
        {
            AccrueUsage(remaining);
            Tick?.Invoke(remaining);
        }

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

        if (saved.ClosedForUpdate)
        {
            // The parent confirmed closing for an update: not a tamper signal
            DiagnosticLog.Write("resume: after a confirmed update exit");
        }
        else
        {
            // A leftover running state means the app did not exit cleanly through the tray (which stops the
            // timer after Windows Hello). That is the tamper signal shown to the parent.
            LastInterruption = saved.LastSeenUtc;
            InterruptionChanged?.Invoke();
            // Backdated: the app was closed around when it was last seen
            _log.Add(new SessionEvent { Time = saved.LastSeenUtc.ToLocalTime(), Type = SessionEventType.Closed, Source = ActionSource.System });
        }

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
            Log(SessionEventType.Expired, ActionSource.System, action: _settings.Action);
            SetState(TimerState.Expired);
            try { ShutdownService.Execute(_settings.Action); }
            catch (Exception ex) { DiagnosticLog.Write("resume: action failed: " + ex); }
            return;
        }

        _timer.Start(remaining, saved.FiredAlerts);
        _lastRemainingMs = (long)remaining.TotalMilliseconds;
        _heartbeat.Start();
        Log(SessionEventType.Resumed, ActionSource.System);
        SetState(TimerState.Running);
        Tick?.Invoke(Remaining);
        Persist();
    }

    /// <summary>
    /// Clean exit for an update or uninstall that the parent confirmed. A running timer keeps running:
    /// it is saved (flagged) so the new version resumes it — the update's downtime still counts — and
    /// the next start doesn't report it as an unclean close. Call right before shutting down.
    /// </summary>
    public void PrepareForUpdate()
    {
        _dispatcher.VerifyAccess();
        Log(SessionEventType.Updated, ActionSource.Pc);
        if (!IsRunning) return;
        _heartbeat.Stop();
        Persist(closedForUpdate: true);
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
    public bool StartInterval(int hours, int minutes, ActionSource source = ActionSource.Pc, string? device = null)
    {
        _dispatcher.VerifyAccess();
        if (IsRunning) return false;
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return false;
        if (hours == 0 && minutes == 0) return false;

        var duration = new TimeSpan(hours, minutes, 0);
        if (!CapToSchedule(ref duration)) return false; // schedule blocks or allows a shorter time
        Begin(TimerMode.Interval, duration, source, device);
        return true;
    }

    /// <summary>
    /// Starts a countdown to the given time of day (tomorrow if it has already passed today).
    /// The wall clock is read once here; from then on only the monotonic clock counts.
    /// </summary>
    public bool StartAbsolute(int hour, int minute, ActionSource source = ActionSource.Pc, string? device = null)
    {
        _dispatcher.VerifyAccess();
        if (IsRunning) return false;
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return false;

        var now = DateTime.Now;
        var target = new DateTime(now.Year, now.Month, now.Day, hour, minute, 0);
        if (target <= now)
            target = target.AddDays(1);

        var duration = target - now;
        if (!CapToSchedule(ref duration)) return false;
        Begin(TimerMode.Absolute, duration, source, device);
        return true;
    }

    /// <summary>Stops a running timer and cancels any pending shutdown.</summary>
    public bool Stop(ActionSource source = ActionSource.Pc, string? device = null)
    {
        _dispatcher.VerifyAccess();
        if (!IsRunning) return false;

        _timer.Stop();
        _heartbeat.Stop();
        _store.Clear();
        _lastRemainingMs = 0;
        ShutdownService.CancelShutdown();
        Log(SessionEventType.Stopped, source, device);
        SetState(TimerState.Stopped);
        return true;
    }

    /// <summary>Stops the timer (if any), cancels any pending shutdown and returns to "not started".</summary>
    public void Reset(ActionSource source = ActionSource.Pc, string? device = null)
    {
        _dispatcher.VerifyAccess();
        bool had = State == TimerState.Running || Mode != null;
        _timer.Stop();
        _heartbeat.Stop();
        _store.Clear();
        _lastRemainingMs = 0;
        ShutdownService.CancelShutdown();
        Mode = null;
        if (had) Log(SessionEventType.Reset, source, device);
        SetState(TimerState.NotStarted);
    }

    /// <summary>
    /// Adds (positive) or removes (negative) minutes on a running timer. Never below zero:
    /// removing more than is left expires the timer. In time-of-day mode this shifts the target.
    /// </summary>
    public bool Extend(int minutes, ActionSource source = ActionSource.Pc, string? device = null)
    {
        _dispatcher.VerifyAccess();
        if (!IsRunning || minutes == 0 || Math.Abs(minutes) > MaxExtendMinutes) return false;

        // A positive extend can't take the timer past what the schedule still allows today
        if (minutes > 0 && _settings.ScheduleEnabled)
        {
            var dec = CurrentSchedule();
            if (dec.IsBlocked) return false;
            int maxAdd = dec.AvailableSeconds - (int)_timer.Remaining.TotalSeconds;
            if (maxAdd <= 0) return false;
            minutes = Math.Min(minutes, Math.Max(1, maxAdd / 60));
        }

        _timer.Extend(TimeSpan.FromMinutes(minutes));
        _lastRemainingMs = (long)_timer.Remaining.TotalMilliseconds; // the change isn't game time
        Log(SessionEventType.Extended, source, device, minutes: minutes);

        if (_timer.Remaining > TimeSpan.Zero)
            Persist(); // save the new deadline before any expiry clears it

        // Clients learn the new time first, then any alert it reached (or the expiry at zero)
        if (_timer.Remaining > TimeSpan.Zero)
            Extended?.Invoke(minutes);
        _timer.EvaluateNow();
        return true;
    }

    private void Begin(TimerMode mode, TimeSpan duration, ActionSource source, string? device)
    {
        Mode = mode;
        _timer.Start(duration);
        _lastRemainingMs = (long)duration.TotalMilliseconds;
        _heartbeat.Start();
        Persist();
        Log(SessionEventType.Started, source, device, mode: mode, minutes: (int)Math.Round(duration.TotalMinutes));
        SetState(TimerState.Running);
        Tick?.Invoke(Remaining);
    }

    // Usage accrues from the countdown's progress (how much the remaining time dropped since last tick),
    // so a running timer always spends today's budget at real time. Clamped so a sleep/clock jump can't
    // dump a huge amount, and a positive extend (which raises remaining) is never counted.
    private void AccrueUsage(TimeSpan remaining)
    {
        var ms = remaining.TotalMilliseconds;
        if (_lastRemainingMs > 0)
        {
            var deltaMs = _lastRemainingMs - ms;
            if (deltaMs > 0) _usage.Add((int)Math.Round(Math.Min(deltaMs, 120_000) / 1000.0));
        }
        _lastRemainingMs = (long)ms;
    }

    /// <summary>
    /// Clamps a requested duration to what the schedule still allows today. Returns false if gaming is
    /// blocked right now (outside the window, day off, or budget used up).
    /// </summary>
    private bool CapToSchedule(ref TimeSpan duration)
    {
        if (!_settings.ScheduleEnabled) return true;
        var dec = CurrentSchedule();
        if (dec.IsBlocked) return false;
        if (duration.TotalSeconds > dec.AvailableSeconds)
            duration = TimeSpan.FromSeconds(dec.AvailableSeconds);
        return duration > TimeSpan.Zero;
    }

    /// <summary>
    /// At app launch and on session unlock: if the schedule allows gaming now and no timer is running,
    /// start one for the time left today. Called by the app, not the periodic check, so a parent can
    /// still stop the timer for a break without it restarting every 30 s.
    /// </summary>
    public void AutoStartIfScheduled()
    {
        _dispatcher.VerifyAccess();
        if (!_settings.ScheduleEnabled || IsRunning) return;
        var dec = CurrentSchedule();
        if (dec.Status == ScheduleStatus.Allowed && dec.AvailableSeconds > 0)
            Begin(TimerMode.Interval, TimeSpan.FromSeconds(dec.AvailableSeconds), ActionSource.System, null);
        else
            ScheduleCheck(); // blocked right now (e.g. outside hours) -> enforce
    }

    /// <summary>Re-check the schedule now (e.g. after the schedule was changed from the phone or PC).</summary>
    public void ReevaluateSchedule() => ScheduleCheck();

    // Periodic enforcement of the schedule. Does not auto-start (that is AutoStartIfScheduled).
    private void ScheduleCheck()
    {
        if (!_settings.ScheduleEnabled) { _lastBlocked = false; return; }
        var dec = CurrentSchedule();

        if (dec.IsBlocked)
        {
            if (IsRunning)
            {
                _timer.Stop();
                _heartbeat.Stop();
                _store.Clear();
                _lastRemainingMs = 0;
            }
            if (!_lastBlocked) // act once per transition into "blocked", not every 30 s
            {
                _lastBlocked = true;
                Log(SessionEventType.Expired, ActionSource.System, action: _settings.Action);
                SetState(TimerState.Expired);
                try { ShutdownService.Execute(_settings.Action); }
                catch (Exception ex) { DiagnosticLog.Write("schedule enforce failed: " + ex); }
            }
        }
        else
        {
            _lastBlocked = false;
            // Window or budget shrank below the running timer -> clamp it down
            if (IsRunning && _timer.Remaining.TotalSeconds > dec.AvailableSeconds + 2)
            {
                _timer.Extend(TimeSpan.FromSeconds(dec.AvailableSeconds - _timer.Remaining.TotalSeconds));
                _lastRemainingMs = (long)_timer.Remaining.TotalMilliseconds;
                Persist();
            }
        }
        ScheduleStateChanged?.Invoke();
    }

    private void OnExpired()
    {
        _heartbeat.Stop();
        _store.Clear();
        _lastRemainingMs = 0;
        Log(SessionEventType.Expired, ActionSource.System, action: _settings.Action);
        SetState(TimerState.Expired);
        ShutdownService.Execute(_settings.Action);
    }

    private void Log(SessionEventType type, ActionSource source, string? device = null,
        TimerMode? mode = null, int? minutes = null, TimerAction? action = null)
    {
        _log.Add(new SessionEvent
        {
            Time = DateTimeOffset.Now,
            Type = type,
            Source = source,
            Device = device,
            Mode = mode,
            Minutes = minutes,
            Action = action
        });
    }

    private void Persist(bool closedForUpdate = false)
    {
        if (Mode is not TimerMode mode) return;
        _store.Save(new PersistedTimer
        {
            ClosedForUpdate = closedForUpdate,
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
