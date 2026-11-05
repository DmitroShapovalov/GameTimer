using System.Text.Json;
using GameTimer.Models;

namespace GameTimer.Services;

/// <summary>
/// Timer, settings, schedule and history changes, turned into protocol v2 events (RemoteServer.V2.cs)
/// while the server runs.
/// </summary>
public sealed partial class RemoteServer
{
    private string? _lastSettingsJson;
    private string? _lastScheduleJson;

    #region Event sources (UI thread)

    private void SubscribeEvents()
    {
        _timer.StateChanged += OnTimerStateChanged;
        _timer.Tick += OnTimerTick;
        _timer.AlertTriggered += OnAlert;
        _timer.Extended += OnExtended;
        _settingsService.SettingsChanged += OnSettingsChanged;
        _timer.InterruptionChanged += OnInterruptionChanged;
        _sessionLog.Added += V2History;
        _timer.ScheduleStateChanged += OnScheduleChanged;

        // Changes made while the server was off still bump the v2 revs (a stale ifRev must not win)
        var settings = JsonSerializer.Serialize(BuildSettings(), Json);
        var schedule = JsonSerializer.Serialize(BuildSchedule(), Json);
        if (_lastSettingsJson != null && settings != _lastSettingsJson) _v2Backend!.SettingsRev++;
        if (_lastScheduleJson != null && schedule != _lastScheduleJson) _v2Backend!.ScheduleRev++;
        _lastSettingsJson = settings;
        _lastScheduleJson = schedule;
    }

    private void UnsubscribeEvents()
    {
        _timer.StateChanged -= OnTimerStateChanged;
        _timer.Tick -= OnTimerTick;
        _timer.AlertTriggered -= OnAlert;
        _timer.Extended -= OnExtended;
        _settingsService.SettingsChanged -= OnSettingsChanged;
        _timer.InterruptionChanged -= OnInterruptionChanged;
        _sessionLog.Added -= V2History;
        _timer.ScheduleStateChanged -= OnScheduleChanged;
    }

    private void OnTimerStateChanged(TimerState state)
    {
        _lastV2TickMs = Environment.TickCount64; // next tick a full interval after start
        V2State();
    }

    private void OnTimerTick(TimeSpan remaining) => V2Tick(); // its own 30 s interval

    private void OnAlert(int minutes) => V2Alert(minutes);

    private void OnExtended(int minutes) => V2State();

    // SettingsChanged also fires for desktop-only fields (last typed times etc.): send only real changes
    private void OnSettingsChanged(object source)
    {
        var schedule = JsonSerializer.Serialize(BuildSchedule(), Json);
        bool scheduleChanged = schedule != _lastScheduleJson;
        _lastScheduleJson = schedule;

        var json = JsonSerializer.Serialize(BuildSettings(), Json);
        bool settingsChanged = json != _lastSettingsJson;
        _lastSettingsJson = json;
        V2SettingsChanged(settingsChanged, scheduleChanged);
    }

    // The "app was closed while a timer ran" note appeared or was dismissed
    private void OnInterruptionChanged() => V2State();

    // The schedule allowed/blocked state or the day's remaining allowance changed
    private void OnScheduleChanged() => V2ScheduleState();

    #endregion
}
