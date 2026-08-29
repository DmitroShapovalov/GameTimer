using System.Windows.Threading;
using GameTimer.Models;

namespace GameTimer.Services;

public class TimerService
{
    private readonly DispatcherTimer _timer;
    private DateTime _targetTime;
    private readonly HashSet<int> _firedAlerts = new();

    public event Action<TimeSpan>? Tick;
    public event Action<int>? AlertTriggered; // minutes remaining
    public event Action? TimerExpired;

    public bool IsRunning => _timer.IsEnabled;
    public TimeSpan Remaining => _targetTime - DateTime.Now;

    public AppSettings Settings { get; set; }

    public TimerService(AppSettings settings)
    {
        Settings = settings;
        _timer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(1)
        };
        _timer.Tick += OnTick;
    }

    public void Start(DateTime targetTime)
    {
        _targetTime = targetTime;
        _firedAlerts.Clear();
        _timer.Start();
    }

    public void StartAbsolute(int hour, int minute)
    {
        var now = DateTime.Now;
        var target = new DateTime(now.Year, now.Month, now.Day, hour, minute, 0);

        // If the time has already passed today, set for tomorrow
        if (target <= now)
            target = target.AddDays(1);

        Start(target);
    }

    public void StartInterval(int hours, int minutes)
    {
        var target = DateTime.Now.AddHours(hours).AddMinutes(minutes);
        Start(target);
    }

    public void Stop()
    {
        _timer.Stop();
        _firedAlerts.Clear();
    }

    private void OnTick(object? sender, EventArgs e)
    {
        var remaining = _targetTime - DateTime.Now;

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
