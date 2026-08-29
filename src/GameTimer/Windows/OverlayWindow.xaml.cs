using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;
using GameTimer.Helpers;
using GameTimer.Models;

namespace GameTimer.Windows;

public partial class OverlayWindow : Window
{
    private AppSettings _settings;

    public OverlayWindow(AppSettings settings)
    {
        InitializeComponent();
        _settings = settings;
        ApplySettings();
    }

    private void Window_Loaded(object sender, RoutedEventArgs e)
    {
        NativeHelper.MakeOverlay(this);
        PositionOnScreen();
    }

    public void ApplySettings()
    {
        TimerText.FontSize = _settings.FontSize;
        TimerContainer.Opacity = _settings.Opacity / 100.0;

        if (_settings.ShowBackground)
        {
            TimerContainer.Background = new SolidColorBrush(Color.FromArgb(100, 0, 0, 0));
        }
        else
        {
            TimerContainer.Background = Brushes.Transparent;
        }
    }

    public void ApplySettings(AppSettings settings)
    {
        _settings = settings;
        ApplySettings();
        PositionOnScreen();
    }

    private void PositionOnScreen()
    {
        var screenWidth = SystemParameters.PrimaryScreenWidth;
        var screenHeight = SystemParameters.PrimaryScreenHeight;

        // OverlayX/Y are 0..1 mapping to [0, screenWidth-overlayWidth]
        Left = (screenWidth - ActualWidth) * _settings.OverlayX;
        Top = (screenHeight - ActualHeight) * _settings.OverlayY;

        // Clamp to screen bounds (safety net)
        Left = Math.Clamp(Left, 0, screenWidth - ActualWidth);
        Top = Math.Clamp(Top, 0, screenHeight - ActualHeight);
    }

    public void UpdateTime(TimeSpan remaining)
    {
        if (remaining.TotalSeconds < 0)
            remaining = TimeSpan.Zero;

        // Format: always show hours:minutes, show seconds too
        if (remaining.TotalHours >= 1)
            TimerText.Text = $"{(int)remaining.TotalHours:D2}:{remaining.Minutes:D2}:{remaining.Seconds:D2}";
        else
            TimerText.Text = $"{remaining.Minutes:D2}:{remaining.Seconds:D2}";

        // Color based on thresholds
        string colorHex;
        if (remaining.TotalMinutes <= _settings.RedThreshold)
            colorHex = _settings.ColorRed;
        else if (remaining.TotalMinutes <= _settings.YellowThreshold)
            colorHex = _settings.ColorYellow;
        else
            colorHex = _settings.ColorGreen;

        try
        {
            var color = (Color)ColorConverter.ConvertFromString(colorHex);
            TimerText.Foreground = new SolidColorBrush(color);
        }
        catch
        {
            TimerText.Foreground = Brushes.White;
        }
    }

    public void PlayPulse()
    {
        var storyboard = (Storyboard)FindResource("PulseAnimation");
        storyboard.Begin();
    }
}
