using System.Windows;
using Wpf.Ui.Controls;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
using GameTimer.Models;
using GameTimer.Services;

namespace GameTimer.Windows;

public partial class MainWindow : FluentWindow
{
    private readonly SettingsService _settingsService = new();
    private readonly SoundService _soundService = new();
    private readonly TimerService _timerService;
    private AppSettings _settings;
    private OverlayWindow? _overlay;
    private System.Windows.Forms.NotifyIcon? _trayIcon;

    // Drag state for position marker
    private bool _isDragging;
    private Point _dragOffset;

    // Track whether we're loading settings (suppress change events)
    private bool _isLoading = true;

    // Track the last action combo index to revert on failed password
    private int _lastActionIndex;

    public MainWindow()
    {
        InitializeComponent();
        _settings = _settingsService.Load();
        _timerService = new TimerService(_settings);
        _timerService.Tick += OnTimerTick;
        _timerService.AlertTriggered += OnAlertTriggered;
        _timerService.TimerExpired += OnTimerExpired;

        LoadSettingsToUI();
        SetupTrayIcon();

        _isLoading = false;
    }

    #region Settings ↔ UI

    private void LoadSettingsToUI()
    {
        // Timer tab
        if (_settings.LastMode == TimerMode.Interval)
            ModeInterval.IsChecked = true;
        else
            ModeAbsolute.IsChecked = true;

        IntervalHours.Text = _settings.LastIntervalHours.ToString();
        IntervalMinutes.Text = _settings.LastIntervalMinutes.ToString();
        AbsoluteHour.Text = _settings.LastAbsoluteHour.ToString();
        AbsoluteMinute.Text = _settings.LastAbsoluteMinute.ToString("D2");

        // Display tab
        FontSizeSlider.Value = _settings.FontSize;
        OpacitySlider.Value = _settings.Opacity;
        ShowBackgroundCheck.IsChecked = _settings.ShowBackground;
        YellowThreshold.Text = _settings.YellowThreshold.ToString();
        RedThreshold.Text = _settings.RedThreshold.ToString();
        UpdateColorPreviews();

        // Position marker (deferred until canvas has actual size)
        PositionCanvas.Loaded += (_, _) =>
        {
            var rangeX = PositionCanvas.ActualWidth - PositionMarker.ActualWidth;
            var rangeY = PositionCanvas.ActualHeight - PositionMarker.ActualHeight;
            if (rangeX > 0) Canvas.SetLeft(PositionMarker, _settings.OverlayX * rangeX);
            if (rangeY > 0) Canvas.SetTop(PositionMarker, _settings.OverlayY * rangeY);
        };

        // Notifications tab
        AlertsList.ItemsSource = null;
        AlertsList.ItemsSource = _settings.AlertPoints;

        if (string.IsNullOrEmpty(_settings.CustomSoundPath))
        {
            SoundBuiltIn.IsChecked = true;
            CustomSoundPanel.Visibility = Visibility.Collapsed;
        }
        else
        {
            SoundCustom.IsChecked = true;
            CustomSoundPath.Text = _settings.CustomSoundPath;
            CustomSoundPanel.Visibility = Visibility.Visible;
            _soundService.SetCustomSound(_settings.CustomSoundPath);
        }

        // Settings tab
        _lastActionIndex = (int)_settings.Action;
        ActionCombo.SelectedIndex = _lastActionIndex;
    }

    private void SaveSettings()
    {
        if (_isLoading) return;
        _settingsService.Save(_settings);
    }

    #endregion

    #region Timer Tab

    private void Mode_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;

        bool isInterval = ModeInterval.IsChecked == true;
        IntervalPanel.Visibility = isInterval ? Visibility.Visible : Visibility.Collapsed;
        AbsolutePanel.Visibility = isInterval ? Visibility.Collapsed : Visibility.Visible;

        _settings.LastMode = isInterval ? TimerMode.Interval : TimerMode.Absolute;
        SaveSettings();
    }

    private void TimerInput_Changed(object sender, TextChangedEventArgs e)
    {
        if (_isLoading) return;

        if (int.TryParse(IntervalHours.Text, out var ih)) _settings.LastIntervalHours = ih;
        if (int.TryParse(IntervalMinutes.Text, out var im)) _settings.LastIntervalMinutes = im;
        if (int.TryParse(AbsoluteHour.Text, out var ah)) _settings.LastAbsoluteHour = ah;
        if (int.TryParse(AbsoluteMinute.Text, out var am)) _settings.LastAbsoluteMinute = am;
        SaveSettings();
    }

    private void Start_Click(object sender, RoutedEventArgs e)
    {
        if (_timerService.IsRunning) return;

        if (ModeInterval.IsChecked == true)
        {
            if (!int.TryParse(IntervalHours.Text, out var h) || !int.TryParse(IntervalMinutes.Text, out var m)
                || (h == 0 && m == 0))
            {
                System.Windows.MessageBox.Show("Введите корректное время.", "Ошибка", System.Windows.MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            _timerService.StartInterval(h, m);
        }
        else
        {
            if (!int.TryParse(AbsoluteHour.Text, out var h) || !int.TryParse(AbsoluteMinute.Text, out var m)
                || h < 0 || h > 23 || m < 0 || m > 59)
            {
                System.Windows.MessageBox.Show("Введите корректное время (0-23 : 0-59).", "Ошибка",
                    System.Windows.MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            _timerService.StartAbsolute(h, m);
        }

        ShowOverlay();
        UpdateButtonStates(running: true);
        StatusText.Text = "Таймер запущен";
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (!await RequireAuth()) return;

        _timerService.Stop();
        ShutdownService.CancelShutdown();
        HideOverlay();
        UpdateButtonStates(running: false);
        StatusText.Text = "Таймер остановлен";
        RemainingText.Text = "";
    }

    private async void Reset_Click(object sender, RoutedEventArgs e)
    {
        if (!await RequireAuth()) return;

        _timerService.Stop();
        ShutdownService.CancelShutdown();
        HideOverlay();
        UpdateButtonStates(running: false);
        StatusText.Text = "Таймер не запущен";
        RemainingText.Text = "";
    }

    private void UpdateButtonStates(bool running)
    {
        StartButton.IsEnabled = !running;
        StopButton.IsEnabled = running;
        ResetButton.IsEnabled = running;
    }

    #endregion

    #region Timer Events

    private void OnTimerTick(TimeSpan remaining)
    {
        _overlay?.UpdateTime(remaining);

        if (remaining.TotalHours >= 1)
            RemainingText.Text = $"{(int)remaining.TotalHours:D2}:{remaining.Minutes:D2}:{remaining.Seconds:D2}";
        else
            RemainingText.Text = $"{remaining.Minutes:D2}:{remaining.Seconds:D2}";
    }

    private void OnAlertTriggered(int minutes)
    {
        _soundService.PlayAlert();
        _overlay?.PlayPulse();
    }

    private void OnTimerExpired()
    {
        HideOverlay();
        UpdateButtonStates(running: false);
        StatusText.Text = "Время вышло!";
        RemainingText.Text = "00:00";

        ShutdownService.Execute(_settings.Action);
    }

    #endregion

    #region Overlay Management

    private void ShowOverlay()
    {
        _overlay?.Close();
        _overlay = new OverlayWindow(_settings);
        _overlay.Show();
    }

    private void HideOverlay()
    {
        _overlay?.Close();
        _overlay = null;
    }

    #endregion

    #region Display Tab

    private void FontSize_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isLoading || FontSizeLabel == null) return;
        var size = (int)FontSizeSlider.Value;
        FontSizeLabel.Text = $"{size} px";
        _settings.FontSize = size;
        _overlay?.ApplySettings(_settings);
        SaveSettings();
    }

    private void Opacity_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isLoading || OpacityLabel == null) return;
        var opacity = (int)OpacitySlider.Value;
        OpacityLabel.Text = $"{opacity}%";
        _settings.Opacity = opacity;
        _overlay?.ApplySettings(_settings);
        SaveSettings();
    }

    private void DisplaySetting_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShowBackground = ShowBackgroundCheck.IsChecked == true;
        _overlay?.ApplySettings(_settings);
        SaveSettings();
    }

    private void Threshold_Changed(object sender, TextChangedEventArgs e)
    {
        if (_isLoading) return;
        if (int.TryParse(YellowThreshold.Text, out var y) && y > 0) _settings.YellowThreshold = y;
        if (int.TryParse(RedThreshold.Text, out var r) && r > 0) _settings.RedThreshold = r;
        SaveSettings();
    }

    private void UpdateColorPreviews()
    {
        TrySetBackground(GreenPreview, _settings.ColorGreen);
        TrySetBackground(YellowPreview, _settings.ColorYellow);
        TrySetBackground(RedPreview, _settings.ColorRed);
    }

    private static void TrySetBackground(Border border, string hex)
    {
        try
        {
            var color = (Color)ColorConverter.ConvertFromString(hex);
            border.Background = new SolidColorBrush(color);
        }
        catch { }
    }

    private void ColorPicker_Click(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (_isLoading || sender is not Border border) return;

        string currentHex;
        if (border == GreenPreview) currentHex = _settings.ColorGreen;
        else if (border == YellowPreview) currentHex = _settings.ColorYellow;
        else currentHex = _settings.ColorRed;

        var dialog = new System.Windows.Forms.ColorDialog();
        try
        {
            var c = (Color)ColorConverter.ConvertFromString(currentHex);
            dialog.Color = System.Drawing.Color.FromArgb(c.R, c.G, c.B);
        }
        catch { }
        dialog.FullOpen = true;

        if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK)
        {
            var hex = $"#{dialog.Color.R:X2}{dialog.Color.G:X2}{dialog.Color.B:X2}";
            if (border == GreenPreview) _settings.ColorGreen = hex;
            else if (border == YellowPreview) _settings.ColorYellow = hex;
            else _settings.ColorRed = hex;

            UpdateColorPreviews();
            _overlay?.ApplySettings(_settings);
            SaveSettings();
        }
    }

    // Position marker drag
    private void Canvas_MouseDown(object sender, MouseButtonEventArgs e)
    {
        var pos = e.GetPosition(PositionCanvas);
        _isDragging = true;
        _dragOffset = new Point(
            pos.X - Canvas.GetLeft(PositionMarker),
            pos.Y - Canvas.GetTop(PositionMarker));
        PositionCanvas.CaptureMouse();
    }

    private void Canvas_MouseMove(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (!_isDragging) return;
        var pos = e.GetPosition(PositionCanvas);
        var x = Math.Clamp(pos.X - _dragOffset.X, 0, PositionCanvas.ActualWidth - PositionMarker.ActualWidth);
        var y = Math.Clamp(pos.Y - _dragOffset.Y, 0, PositionCanvas.ActualHeight - PositionMarker.ActualHeight);
        Canvas.SetLeft(PositionMarker, x);
        Canvas.SetTop(PositionMarker, y);
    }

    private void Canvas_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (!_isDragging) return;
        _isDragging = false;
        PositionCanvas.ReleaseMouseCapture();

        // Save as percentage (0 = left/top edge, 1 = right/bottom edge)
        var rangeX = PositionCanvas.ActualWidth - PositionMarker.ActualWidth;
        var rangeY = PositionCanvas.ActualHeight - PositionMarker.ActualHeight;
        if (rangeX > 0 && rangeY > 0)
        {
            _settings.OverlayX = Canvas.GetLeft(PositionMarker) / rangeX;
            _settings.OverlayY = Canvas.GetTop(PositionMarker) / rangeY;
            _overlay?.ApplySettings(_settings);
            SaveSettings();
        }
    }

    #endregion

    #region Notifications Tab

    private void AlertEnabled_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        SaveSettings();
    }

    private void AddAlert_Click(object sender, RoutedEventArgs e)
    {
        if (int.TryParse(NewAlertMinutes.Text, out var minutes) && minutes > 0)
        {
            // Check for duplicates
            if (_settings.AlertPoints.Any(a => a.Minutes == minutes))
            {
                System.Windows.MessageBox.Show("Такая точка уже существует.", "Ошибка", System.Windows.MessageBoxButton.OK);
                return;
            }

            _settings.AlertPoints.Add(new AlertPoint { Minutes = minutes, Enabled = true });
            _settings.AlertPoints = _settings.AlertPoints.OrderByDescending(a => a.Minutes).ToList();
            AlertsList.ItemsSource = null;
            AlertsList.ItemsSource = _settings.AlertPoints;
            NewAlertMinutes.Clear();
            SaveSettings();
        }
    }

    private void RemoveAlert_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is AlertPoint alert)
        {
            _settings.AlertPoints.Remove(alert);
            AlertsList.ItemsSource = null;
            AlertsList.ItemsSource = _settings.AlertPoints;
            SaveSettings();
        }
    }

    private void SoundSource_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;

        bool isCustom = SoundCustom.IsChecked == true;
        CustomSoundPanel.Visibility = isCustom ? Visibility.Visible : Visibility.Collapsed;

        if (!isCustom)
        {
            _settings.CustomSoundPath = null;
            _soundService.SetCustomSound(null);
            SaveSettings();
        }
    }

    private void BrowseSound_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Filter = "WAV files (*.wav)|*.wav",
            Title = "Выберите звуковой файл"
        };

        if (dialog.ShowDialog() == true)
        {
            CustomSoundPath.Text = dialog.FileName;
            _settings.CustomSoundPath = dialog.FileName;
            _soundService.SetCustomSound(dialog.FileName);
            SaveSettings();
        }
    }

    private void PreviewSound_Click(object sender, RoutedEventArgs e)
    {
        _soundService.PlayPreview();
    }

    #endregion

    #region Settings Tab

    private async void Action_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (_isLoading || ActionCombo.SelectedIndex < 0) return;

        int newIndex = ActionCombo.SelectedIndex;
        if (newIndex == _lastActionIndex) return;

        if (!await RequireAuth())
        {
            // Revert selection
            _isLoading = true;
            ActionCombo.SelectedIndex = _lastActionIndex;
            _isLoading = false;
            return;
        }

        _lastActionIndex = newIndex;
        _settings.Action = (TimerAction)newIndex;
        SaveSettings();
    }

    #endregion

    #region Tray Icon

    private void SetupTrayIcon()
    {
        _trayIcon = new System.Windows.Forms.NotifyIcon
        {
            Text = "GameTimer",
            Visible = false
        };

        // Draw alarm clock icon
        using var bitmap = new System.Drawing.Bitmap(32, 32);
        using var g = System.Drawing.Graphics.FromImage(bitmap);
        g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        g.Clear(System.Drawing.Color.Transparent);
        // Bell legs
        var legPen = new System.Drawing.Pen(System.Drawing.Color.White, 2f);
        g.DrawLine(legPen, 7, 5, 10, 8);
        g.DrawLine(legPen, 25, 5, 22, 8);
        // Clock body
        g.FillEllipse(new System.Drawing.SolidBrush(System.Drawing.Color.FromArgb(0, 120, 212)), 6, 6, 20, 20);
        g.DrawEllipse(new System.Drawing.Pen(System.Drawing.Color.White, 1.5f), 6, 6, 20, 20);
        // Clock hands
        var handPen = new System.Drawing.Pen(System.Drawing.Color.White, 2f);
        g.DrawLine(handPen, 16, 16, 16, 10); // hour
        g.DrawLine(handPen, 16, 16, 21, 16); // minute
        // Clapper
        g.FillEllipse(System.Drawing.Brushes.White, 14, 26, 4, 4);
        _trayIcon.Icon = System.Drawing.Icon.FromHandle(bitmap.GetHicon());

        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Открыть", null, (_, _) => ShowFromTray());
        menu.Items.Add("Выход", null, (_, _) => TrayExit());
        _trayIcon.ContextMenuStrip = menu;
        _trayIcon.DoubleClick += (_, _) => ShowFromTray();
    }

    private void ShowFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
        if (_trayIcon != null) _trayIcon.Visible = false;
    }

    private async void TrayExit()
    {
        if (_timerService.IsRunning)
        {
            if (!await RequireAuth()) return;
            _timerService.Stop();
            ShutdownService.CancelShutdown();
        }

        HideOverlay();
        if (_trayIcon != null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
        }
        System.Windows.Application.Current.Shutdown();
    }

    #endregion

    #region Window Close

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        // Always minimize to tray — exit only via tray menu
        e.Cancel = true;
        Hide();
        if (_trayIcon != null) _trayIcon.Visible = true;
    }

    #endregion

    #region Authentication

    private async Task<bool> RequireAuth()
    {
        try
        {
            var availability = await global::Windows.Security.Credentials.UI.UserConsentVerifier.CheckAvailabilityAsync();
            if (availability == global::Windows.Security.Credentials.UI.UserConsentVerifierAvailability.Available)
            {
                var result = await global::Windows.Security.Credentials.UI.UserConsentVerifier.RequestVerificationAsync(
                    "GameTimer: подтвердите вашу личность");
                return result == global::Windows.Security.Credentials.UI.UserConsentVerificationResult.Verified;
            }
        }
        catch { }

        // Fallback: password dialog with Windows account validation
        var dialog = new PasswordDialog { Owner = this };
        return dialog.ShowDialog() == true;
    }

    #endregion
}
