using System.Windows;
using Wpf.Ui.Controls;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
using GameTimer.Models;
using GameTimer.Services;
using GameTimer.Localization;

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

    // Track timer state for localization
    private string _timerStateKey = "Timer_NotStarted";

    public MainWindow()
    {
        InitializeComponent();
        _settings = _settingsService.Load();

        // Set language before loading UI
        Loc.SetLanguage(_settings.Language);

        _timerService = new TimerService(_settings);
        _timerService.Tick += OnTimerTick;
        _timerService.AlertTriggered += OnAlertTriggered;
        _timerService.TimerExpired += OnTimerExpired;

        LoadSettingsToUI();
        ApplyLocalization();
        SetupTrayIcon();

        _isLoading = false;
        ValidateTimerInput();

        Loaded += (_, _) => FocusTimeInput();
        StartButton.ToolTip = "Enter";
    }

    #region Localization

    private void ApplyLocalization()
    {
        // Tab headers
        TabTimerText.Text = Loc.Get("Tab_Timer");
        TabDisplayText.Text = Loc.Get("Tab_Display");
        TabNotificationsText.Text = Loc.Get("Tab_Notifications");
        TabSettingsText.Text = Loc.Get("Tab_Settings");

        // Timer tab
        ModeLabel.Text = Loc.Get("Mode");
        ModeInterval.Content = Loc.Get("Mode_Interval");
        ModeAbsolute.Content = Loc.Get("Mode_TimeOfDay");
        HoursLabel.Text = Loc.Get("Hours");
        MinutesLabel.Text = Loc.Get("Minutes");
        HourLabel.Text = Loc.Get("Hour");
        StartButton.Content = Loc.Get("Start");
        StopButton.Content = Loc.Get("Stop");
        ResetButton.Content = Loc.Get("Reset");
        StatusText.Text = Loc.Get(_timerStateKey);

        // Display tab
        OverlayPositionLabel.Text = Loc.Get("OverlayPosition");
        FontSizeSectionLabel.Text = Loc.Get("FontSize");
        OpacitySectionLabel.Text = Loc.Get("Opacity");
        ShowBackgroundCheck.Content = Loc.Get("ShowBackground");
        ThresholdsLabel.Text = Loc.Get("Thresholds");
        WarningFromLabel.Text = Loc.Get("WarningFrom");
        AlertFromLabel.Text = Loc.Get("AlertFrom");
        MinLabel1.Text = Loc.Get("Min");
        MinLabel2.Text = Loc.Get("Min");
        ZoneColorsLabel.Text = Loc.Get("ZoneColors");
        NormalZoneLabel.Text = Loc.Get("Zone_Normal");
        WarningZoneLabel.Text = Loc.Get("Zone_Warning");
        AlertZoneLabel.Text = Loc.Get("Zone_Alert");

        // Notifications tab
        AlertPointsLabel.Text = Loc.Get("AlertPoints");
        AddButton.Content = Loc.Get("Add");
        SoundFileLabel.Text = Loc.Get("SoundFile");
        SoundBuiltIn.Content = Loc.Get("BuiltIn");
        SoundCustom.Content = Loc.Get("CustomFile");
        BrowseButton.Content = Loc.Get("Browse");
        PreviewButton.Content = Loc.Get("Preview");
        NewAlertMinutes.PlaceholderText = Loc.Get("MinPlaceholder");

        // Refresh alerts list to update "min before timer" text
        RefreshAlertsList();

        // Settings tab
        LanguageLabel.Text = Loc.Get("Language");
        ActionLabel.Text = Loc.Get("ActionOnExpiry");
        ActionShutdown.Content = Loc.Get("Action_Shutdown");
        ActionLock.Content = Loc.Get("Action_Lock");
        ActionLogoff.Content = Loc.Get("Action_Logoff");
        ProtectionInfo.Title = Loc.Get("Protection");
        ProtectionInfo.Message = Loc.Get("ProtectionInfo");
        AboutLabel.Text = Loc.Get("About");
        AppDescriptionText.Text = Loc.Get("AppDescription");

        // Tray menu
        UpdateTrayMenu();
    }

    private void RefreshAlertsList()
    {
        AlertsList.ItemsSource = null;
        AlertsList.ItemsSource = _settings.AlertPoints;
    }

    private void Language_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (_isLoading || LanguageCombo.SelectedItem is not ComboBoxItem item) return;

        var lang = item.Tag?.ToString() ?? "en";
        _settings.Language = lang;
        Loc.SetLanguage(lang);
        ApplyLocalization();
        SaveSettings();
    }

    #endregion

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

        // Settings tab - language
        var langTag = _settings.Language;
        for (int i = 0; i < LanguageCombo.Items.Count; i++)
        {
            if (LanguageCombo.Items[i] is ComboBoxItem ci && ci.Tag?.ToString() == langTag)
            {
                LanguageCombo.SelectedIndex = i;
                break;
            }
        }

        // Settings tab - action
        _lastActionIndex = (int)_settings.Action;
        ActionCombo.SelectedIndex = _lastActionIndex;
    }

    private void SaveSettings()
    {
        if (_isLoading) return;
        _settingsService.Save(_settings);
    }

    #endregion


    private static int ParseInt(string text, int fallback = 0)
    {
        return int.TryParse(text, out var val) ? val : fallback;
    }

    private void ValidateTimerInput()
    {
        if (_isLoading) return;

        string? error = null;
        bool isInterval = ModeInterval.IsChecked == true;

        if (isInterval)
        {
            int h = ParseInt(IntervalHours.Text);
            int m = ParseInt(IntervalMinutes.Text);
            if (h < 0 || m < 0 || m > 59)
                error = Loc.Get("InvalidTime");
            else if (h == 0 && m == 0)
                error = Loc.Get("InvalidTime");
        }
        else
        {
            int h = ParseInt(AbsoluteHour.Text, -1);
            int m = ParseInt(AbsoluteMinute.Text, -1);
            if (h < 0 || h > 23 || m < 0 || m > 59)
                error = Loc.Get("InvalidTimeRange");
        }

        if (error != null)
        {
            ValidationText.Text = error;
            ValidationText.Visibility = Visibility.Visible;
            StartButton.IsEnabled = false;
        }
        else
        {
            ValidationText.Visibility = Visibility.Collapsed;
            if (!_timerService.IsRunning)
                StartButton.IsEnabled = true;
        }
    }
    #region Keyboard

    private void FocusTimeInput()
    {
        if (MainTabs.SelectedIndex != 0 || _timerService.IsRunning) return;
        var target = ModeInterval.IsChecked == true ? IntervalHours : AbsoluteHour;
        Dispatcher.BeginInvoke(() => { target.Focus(); Keyboard.Focus(target); target.SelectAll(); },
            System.Windows.Threading.DispatcherPriority.Input);
    }

    private void Window_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        bool ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);

        // Ctrl+1..4 — switch tabs
        if (ctrl)
        {
            int tab = e.Key switch
            {
                Key.D1 or Key.NumPad1 => 0,
                Key.D2 or Key.NumPad2 => 1,
                Key.D3 or Key.NumPad3 => 2,
                Key.D4 or Key.NumPad4 => 3,
                _ => -1
            };
            if (tab >= 0)
            {
                MainTabs.SelectedIndex = tab;
                if (tab == 0) FocusTimeInput();
                e.Handled = true;
                return;
            }
        }

        // Esc — hide to tray (unless a dropdown is open: then Esc just closes it)
        if (e.Key == Key.Escape && !LanguageCombo.IsDropDownOpen && !ActionCombo.IsDropDownOpen)
        {
            Close(); // Window_Closing turns this into "minimize to tray"
            e.Handled = true;
            return;
        }

        // Enter on the Timer tab — start the timer.
        // Buttons keep their own Enter behaviour, so focus on Stop/Reset isn't hijacked.
        if (e.Key == Key.Enter && MainTabs.SelectedIndex == 0
            && Keyboard.FocusedElement is not System.Windows.Controls.Button)
        {
            if (StartButton.IsEnabled)
                Start_Click(StartButton, new RoutedEventArgs());
            else
                ValidateTimerInput();
            e.Handled = true;
        }
    }

    private (int min, int max, bool wrap) GetRange(object sender)
    {
        if (sender == IntervalHours) return (0, 23, false);
        if (sender == AbsoluteHour) return (0, 23, true);
        return (0, 59, true); // minutes
    }

    // Up/Down — change value by 1, Shift+Up/Down — by 10
    private void TimeInput_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (sender is not System.Windows.Controls.TextBox box) return;
        if (e.Key != Key.Up && e.Key != Key.Down) return;

        var (min, max, wrap) = GetRange(sender);
        int step = Keyboard.Modifiers.HasFlag(ModifierKeys.Shift) ? 10 : 1;
        int value = ParseInt(box.Text) + (e.Key == Key.Up ? step : -step);

        if (wrap)
        {
            int span = max - min + 1;
            value = ((value - min) % span + span) % span + min;
        }
        else
        {
            value = Math.Clamp(value, min, max);
        }

        box.Text = sender == AbsoluteMinute ? value.ToString("D2") : value.ToString();
        box.SelectAll();
        e.Handled = true;
    }

    // Digits only. In "time of day" mode typing ":" in the hour field jumps to minutes.
    private void TimeInput_PreviewTextInput(object sender, TextCompositionEventArgs e)
    {
        if (e.Text is ":" or "." or ",")
        {
            if (sender == AbsoluteHour) { AbsoluteMinute.Focus(); AbsoluteMinute.SelectAll(); }
            else if (sender == IntervalHours) { IntervalMinutes.Focus(); IntervalMinutes.SelectAll(); }
            e.Handled = true;
            return;
        }
        e.Handled = !e.Text.All(char.IsDigit);
    }

    // Selecting all on focus lets the user just type a new value over the old one
    private void TimeInput_GotFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        if (sender is System.Windows.Controls.TextBox box)
            Dispatcher.BeginInvoke(box.SelectAll, System.Windows.Threading.DispatcherPriority.Input);
    }

    // Enter in the "new alert" field — add the alert point
    private void NewAlertMinutes_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        AddAlert_Click(sender, new RoutedEventArgs());
        e.Handled = true;
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
        ValidateTimerInput();
        FocusTimeInput();
    }

    private void TimerInput_Changed(object sender, TextChangedEventArgs e)
    {
        if (_isLoading) return;

        _settings.LastIntervalHours = ParseInt(IntervalHours.Text);
        _settings.LastIntervalMinutes = ParseInt(IntervalMinutes.Text);
        _settings.LastAbsoluteHour = ParseInt(AbsoluteHour.Text);
        _settings.LastAbsoluteMinute = ParseInt(AbsoluteMinute.Text);
        SaveSettings();
        ValidateTimerInput();
    }

    private void Start_Click(object sender, RoutedEventArgs e)
    {
        if (_timerService.IsRunning) return;

        if (ModeInterval.IsChecked == true)
        {
            int h = ParseInt(IntervalHours.Text);
            int m = ParseInt(IntervalMinutes.Text);
            if (h == 0 && m == 0) { ValidateTimerInput(); return; }
            _timerService.StartInterval(h, m);
        }
        else
        {
            int h = ParseInt(AbsoluteHour.Text, -1);
            int m = ParseInt(AbsoluteMinute.Text, -1);
            if (h < 0 || h > 23 || m < 0 || m > 59) { ValidateTimerInput(); return; }
            _timerService.StartAbsolute(h, m);
        }

        ValidationText.Visibility = Visibility.Collapsed;
        ShowOverlay();
        UpdateButtonStates(running: true);
        _timerStateKey = "Timer_Started";
        StatusText.Text = Loc.Get(_timerStateKey);
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (!await RequireAuth()) return;

        _timerService.Stop();
        ShutdownService.CancelShutdown();
        HideOverlay();
        UpdateButtonStates(running: false);
        _timerStateKey = "Timer_Stopped";
        StatusText.Text = Loc.Get(_timerStateKey);
        RemainingText.Text = "";
    }

    private async void Reset_Click(object sender, RoutedEventArgs e)
    {
        if (!await RequireAuth()) return;

        _timerService.Stop();
        ShutdownService.CancelShutdown();
        HideOverlay();
        UpdateButtonStates(running: false);
        _timerStateKey = "Timer_NotStarted";
        StatusText.Text = Loc.Get(_timerStateKey);
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
        _timerStateKey = "Timer_Expired";
        StatusText.Text = Loc.Get(_timerStateKey);
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
            if (_settings.AlertPoints.Any(a => a.Minutes == minutes))
            {
                System.Windows.MessageBox.Show(Loc.Get("AlertExists"), Loc.Get("Error"),
                    System.Windows.MessageBoxButton.OK);
                return;
            }

            _settings.AlertPoints.Add(new AlertPoint { Minutes = minutes, Enabled = true });
            _settings.AlertPoints = _settings.AlertPoints.OrderByDescending(a => a.Minutes).ToList();
            RefreshAlertsList();
            NewAlertMinutes.Clear();
            SaveSettings();
        }
    }

    private void RemoveAlert_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is AlertPoint alert)
        {
            _settings.AlertPoints.Remove(alert);
            RefreshAlertsList();
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
            Filter = Loc.Get("WAVFilter"),
            Title = Loc.Get("SelectSoundFile")
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
        var legPen = new System.Drawing.Pen(System.Drawing.Color.White, 2f);
        g.DrawLine(legPen, 7, 5, 10, 8);
        g.DrawLine(legPen, 25, 5, 22, 8);
        g.FillEllipse(new System.Drawing.SolidBrush(System.Drawing.Color.FromArgb(0, 120, 212)), 6, 6, 20, 20);
        g.DrawEllipse(new System.Drawing.Pen(System.Drawing.Color.White, 1.5f), 6, 6, 20, 20);
        var handPen = new System.Drawing.Pen(System.Drawing.Color.White, 2f);
        g.DrawLine(handPen, 16, 16, 16, 10);
        g.DrawLine(handPen, 16, 16, 21, 16);
        g.FillEllipse(System.Drawing.Brushes.White, 14, 26, 4, 4);
        _trayIcon.Icon = System.Drawing.Icon.FromHandle(bitmap.GetHicon());

        UpdateTrayMenu();
        _trayIcon.DoubleClick += (_, _) => ShowFromTray();
    }

    private void UpdateTrayMenu()
    {
        if (_trayIcon == null) return;
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add(Loc.Get("TrayOpen"), null, (_, _) => ShowFromTray());
        menu.Items.Add(Loc.Get("TrayExit"), null, (_, _) => TrayExit());
        _trayIcon.ContextMenuStrip = menu;
    }

    private void ShowFromTray() => BringToFront();

    /// <summary>
    /// Shows the window from the tray / minimized state and brings it to the foreground.
    /// Called when the user launches a second copy of the app.
    /// </summary>
    public void BringToFront()
    {
        Show();
        if (WindowState == WindowState.Minimized)
            WindowState = WindowState.Normal;

        // Windows refuses a plain Activate() from a background process;
        // a short topmost flip reliably brings the window forward.
        Topmost = true;
        Activate();
        Topmost = false;
        Focus();

        if (_trayIcon != null) _trayIcon.Visible = false;
        FocusTimeInput();
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
                    Loc.Get("AuthPrompt"));
                return result == global::Windows.Security.Credentials.UI.UserConsentVerificationResult.Verified;
            }
        }
        catch { }

        var dialog = new PasswordDialog { Owner = this };
        return dialog.ShowDialog() == true;
    }

    #endregion
}
