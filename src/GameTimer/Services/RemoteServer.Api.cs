using System.Text.RegularExpressions;
using GameTimer.Models;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace GameTimer.Services;

/// <summary>
/// Phone API: timer control and settings (RMT-03). Every handler runs its work on the UI thread
/// and goes through <see cref="TimerController"/> / <see cref="SettingsService"/>, never the window.
/// Errors are <c>{ "error": "code" }</c> with 400 (bad input) or 409 (wrong timer state).
/// </summary>
public sealed partial class RemoteServer
{
    private const int MaxMinutes = 24 * 60;
    private const int MaxAlertPoints = 20;
    private static readonly string[] Languages = { "en", "ru", "nl", "uk" };
    private static readonly Regex HexColor = new("^#[0-9A-Fa-f]{6}$", RegexOptions.Compiled);

    #region DTOs

    public sealed record StatusDto(
        TimerState State,
        TimerMode? Mode,
        int RemainingSeconds,
        DateTimeOffset? TargetTime,
        TimerAction Action,
        TimerZone? Zone,
        DateTimeOffset? LastInterruption,
        ScheduleStatus ScheduleStatus,
        int ScheduleAvailableSeconds,
        int UsedSecondsToday);

    public sealed record DayDto(bool Allowed, int LimitMinutes, int WindowStartMinutes, int WindowEndMinutes);
    public sealed record ScheduleDto(bool Enabled, List<DayDto> Week);

    public sealed record StartRequest(TimerMode? Mode, int Hours, int Minutes);

    public sealed record ExtendRequest(int Minutes);

    public sealed record AlertPointDto(int Minutes, bool Enabled);

    public sealed record SettingsDto(
        TimerAction Action,
        int YellowThreshold,
        int RedThreshold,
        IReadOnlyList<AlertPointDto> AlertPoints,
        string ColorGreen,
        string ColorYellow,
        string ColorRed,
        string Language);

    /// <summary>PUT body: every field is optional; omitted fields keep their current value.</summary>
    public sealed record SettingsUpdate(
        TimerAction? Action,
        int? YellowThreshold,
        int? RedThreshold,
        List<AlertPointDto>? AlertPoints,
        string? ColorGreen,
        string? ColorYellow,
        string? ColorRed,
        string? Language);

    #endregion

    private void MapTimer(WebApplication app)
    {
        app.MapGet("/api/status", () => _ui.InvokeAsync(() => Results.Json(BuildStatus())));

        app.MapPost("/api/timer/start", async (StartRequest? request, HttpContext context) =>
        {
            if (request?.Mode is not TimerMode mode)
                return Error(StatusCodes.Status400BadRequest, "invalid_request");

            var name = CurrentDevice(context).Name;
            return await _ui.InvokeAsync(() =>
            {
                if (_timer.IsRunning)
                    return Error(StatusCodes.Status409Conflict, "already_running");

                bool started = mode == TimerMode.Interval
                    ? _timer.StartInterval(request.Hours, request.Minutes, ActionSource.Phone, name)
                    : _timer.StartAbsolute(request.Hours, request.Minutes, ActionSource.Phone, name);

                return started
                    ? Results.Json(BuildStatus())
                    : Error(StatusCodes.Status400BadRequest, "invalid_time");
            });
        });

        app.MapGet("/api/history", () =>
            _ui.InvokeAsync(() => Results.Json(_sessionLog.Recent())));

        // Dismiss the "app was closed while a timer ran" note (shown after a tamper-style restart)
        app.MapPost("/api/interruption/ack", () => _ui.InvokeAsync(() =>
        {
            _timer.AcknowledgeInterruption();
            return Results.Json(BuildStatus());
        }));

        // Stop also cancels a pending shutdown (TimerController.Stop)
        app.MapPost("/api/timer/stop", async (HttpContext context) =>
        {
            var name = CurrentDevice(context).Name;
            return await _ui.InvokeAsync(() =>
                _timer.Stop(ActionSource.Phone, name)
                    ? Results.Json(BuildStatus())
                    : Error(StatusCodes.Status409Conflict, "not_running"));
        });

        app.MapPost("/api/timer/extend", async (ExtendRequest? request, HttpContext context) =>
        {
            if (request == null || request.Minutes == 0 || Math.Abs(request.Minutes) > MaxMinutes)
                return Error(StatusCodes.Status400BadRequest, "invalid_minutes");

            var name = CurrentDevice(context).Name;
            return await _ui.InvokeAsync(() =>
                _timer.Extend(request.Minutes, ActionSource.Phone, name)
                    ? Results.Json(BuildStatus())
                    : Error(StatusCodes.Status409Conflict, "not_running"));
        });
    }

    private void MapSettings(WebApplication app)
    {
        app.MapGet("/api/settings", () => _ui.InvokeAsync(() => Results.Json(BuildSettings())));

        app.MapGet("/api/schedule", () => _ui.InvokeAsync(() => Results.Json(BuildSchedule())));

        app.MapPut("/api/schedule", async (ScheduleDto? update) =>
        {
            var error = ValidateSchedule(update);
            if (error != null)
                return Error(StatusCodes.Status400BadRequest, error);
            return await _ui.InvokeAsync(() =>
            {
                ApplySchedule(update!);
                return Results.Json(BuildSchedule());
            });
        });

        app.MapPut("/api/settings", async (SettingsUpdate? update) =>
        {
            if (update == null)
                return Error(StatusCodes.Status400BadRequest, "invalid_request");

            var error = Validate(update);
            if (error != null)
                return Error(StatusCodes.Status400BadRequest, error);

            return await _ui.InvokeAsync(() =>
            {
                Apply(update);
                _settingsService.Save(_settings);
                _settingsService.NotifyChanged(this); // the window refreshes its controls and the overlay
                return Results.Json(BuildSettings());
            });
        });
    }

    #region Helpers

    // UI thread only
    private StatusDto BuildStatus()
    {
        var running = _timer.IsRunning;
        var remaining = _timer.Remaining;
        var schedule = _timer.CurrentSchedule();
        return new StatusDto(
            _timer.State,
            running ? _timer.Mode : null,
            running ? (int)remaining.TotalSeconds : 0,
            running ? DateTimeOffset.Now + remaining : null,
            _settings.Action,
            running ? _settings.GetZone(remaining) : null,
            _timer.LastInterruption,
            schedule.Status,
            schedule.AvailableSeconds,
            _timer.UsedSecondsToday);
    }

    // UI thread only
    private SettingsDto BuildSettings() => new(
        _settings.Action,
        _settings.YellowThreshold,
        _settings.RedThreshold,
        _settings.AlertPoints.Select(a => new AlertPointDto(a.Minutes, a.Enabled)).ToList(),
        _settings.ColorGreen,
        _settings.ColorYellow,
        _settings.ColorRed,
        _settings.Language);

    // UI thread only
    private ScheduleDto BuildSchedule() => new(
        _settings.ScheduleEnabled,
        (_settings.Week ?? new List<DaySchedule>()).Select(d =>
            new DayDto(d.Allowed, d.LimitMinutes, d.WindowStartMinutes, d.WindowEndMinutes)).ToList());

    /// <summary>Shared by v1 PUT /api/schedule and v2 schedule.put.</summary>
    private string? ValidateSchedule(ScheduleDto? update)
    {
        if (update?.Week == null || update.Week.Count != 7)
            return "invalid_request";
        foreach (var d in update.Week)
        {
            if (d == null || d.LimitMinutes is < 0 or > 1440
                || d.WindowStartMinutes is < 0 or > 1440 || d.WindowEndMinutes is < 0 or > 1440)
                return "invalid_schedule";
        }
        return null;
    }

    // UI thread only
    private void ApplySchedule(ScheduleDto update)
    {
        _settings.ScheduleEnabled = update.Enabled;
        _settings.Week = update.Week.Select(d => new DaySchedule
        {
            Allowed = d.Allowed,
            LimitMinutes = d.LimitMinutes,
            WindowStartMinutes = d.WindowStartMinutes,
            WindowEndMinutes = d.WindowEndMinutes
        }).ToList();
        _settingsService.Save(_settings);
        _settingsService.NotifyChanged(this);
        _timer.ReevaluateSchedule();
    }

    private static string? Validate(SettingsUpdate u)
    {
        if (u.YellowThreshold is < 1 or > MaxMinutes || u.RedThreshold is < 1 or > MaxMinutes)
            return "invalid_threshold";

        foreach (var color in new[] { u.ColorGreen, u.ColorYellow, u.ColorRed })
            if (color != null && !HexColor.IsMatch(color))
                return "invalid_color";

        if (u.Language != null && !Languages.Contains(u.Language))
            return "invalid_language";

        if (u.AlertPoints != null)
        {
            if (u.AlertPoints.Count > MaxAlertPoints
                || u.AlertPoints.Any(a => a == null || a.Minutes < 1 || a.Minutes > MaxMinutes)
                || u.AlertPoints.Select(a => a.Minutes).Distinct().Count() != u.AlertPoints.Count)
                return "invalid_alerts";
        }

        return null;
    }

    // UI thread only
    private void Apply(SettingsUpdate u)
    {
        if (u.Action is TimerAction action) _settings.Action = action;
        if (u.YellowThreshold is int yellow) _settings.YellowThreshold = yellow;
        if (u.RedThreshold is int red) _settings.RedThreshold = red;
        if (u.ColorGreen != null) _settings.ColorGreen = u.ColorGreen.ToUpperInvariant();
        if (u.ColorYellow != null) _settings.ColorYellow = u.ColorYellow.ToUpperInvariant();
        if (u.ColorRed != null) _settings.ColorRed = u.ColorRed.ToUpperInvariant();
        if (u.Language != null) _settings.Language = u.Language;
        if (u.AlertPoints != null)
            _settings.AlertPoints = u.AlertPoints
                .OrderByDescending(a => a.Minutes)
                .Select(a => new AlertPoint { Minutes = a.Minutes, Enabled = a.Enabled })
                .ToList();
    }

    private static IResult Error(int status, string code) =>
        Results.Json(new { error = code }, statusCode: status);

    #endregion
}
