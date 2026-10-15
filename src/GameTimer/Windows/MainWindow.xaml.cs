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
    private readonly SettingsService _settingsService;
    private readonly SoundService _soundService = new();
    private readonly TimerController _timer;
    private readonly RemoteServer _remote;
    private readonly DeviceStore _devices;
    private readonly PairingService _pairing;
    private readonly SessionLog _sessionLog;
    private bool _isRemoteBusy;
    private bool? _firewallOk; // null = not checked yet
    private bool _isTamperBusy;
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

    // Schedule tab: editing is gated behind Windows Hello; controls built once in code
    private bool _scheduleUnlocked;
    private readonly System.Windows.Controls.CheckBox[] _dayAllowed = new System.Windows.Controls.CheckBox[7];
    private readonly Wpf.Ui.Controls.TextBox[] _dayLimit = new Wpf.Ui.Controls.TextBox[7];
    private readonly Wpf.Ui.Controls.TextBox[] _dayFrom = new Wpf.Ui.Controls.TextBox[7];
    private readonly Wpf.Ui.Controls.TextBox[] _dayTo = new Wpf.Ui.Controls.TextBox[7];
    private readonly System.Windows.Controls.TextBlock[] _dayName = new System.Windows.Controls.TextBlock[7];
    private System.Windows.Controls.TextBlock? _schHeadDay, _schHeadOn, _schHeadLimit, _schHeadFrom, _schHeadTo;
    // Display order Monday..Sunday, mapped to (int)DayOfWeek (0 = Sunday)
    private static readonly int[] ScheduleDayOrder = { 1, 2, 3, 4, 5, 6, 0 };

    public MainWindow(SettingsService settingsService, AppSettings settings, TimerController timer,
        RemoteServer remote, DeviceStore devices, PairingService pairing, SessionLog sessionLog)
    {
        InitializeComponent();
        _settingsService = settingsService;
        _settings = settings;

        // The window is only a client of the controller: it sends commands and reacts to events
        _timer = timer;
        _timer.Tick += OnTimerTick;
        _timer.AlertTriggered += OnAlertTriggered;
        _timer.StateChanged += OnTimerStateChanged;
        _timer.InterruptionChanged += UpdateInterruptionBar;
        _timer.ScheduleStateChanged += () => Dispatcher.BeginInvoke(UpdateScheduleStatus);
        _remote = remote;
        _devices = devices;
        _pairing = pairing;
        _sessionLog = sessionLog;
        _sessionLog.Changed += () => Dispatcher.BeginInvoke(RefreshHistory);
        _devices.Changed += RefreshDevicesList;
        _settingsService.SettingsChanged += OnSettingsChanged;

        LoadSettingsToUI();
        ApplyLocalization();
        SetupTrayIcon();

        _isLoading = false;
        ValidateTimerInput();

        Loaded += (_, _) => FocusTimeInput();
        StartButton.ToolTip = "Enter";

        // The setting was approved (Windows Hello) when it was turned on, so no prompt at launch
        if (_settings.RemoteEnabled)
            _ = StartRemoteOnLaunchAsync();
    }

    #region Localization

    private void ApplyLocalization()
    {
        // Tab headers
        TabTimerText.Text = Loc.Get("Tab_Timer");
        TabDisplayText.Text = Loc.Get("Tab_Display");
        TabNotificationsText.Text = Loc.Get("Tab_Notifications");
        TabSettingsText.Text = Loc.Get("Tab_Settings");
        TabHistoryText.Text = Loc.Get("Tab_History");
        TabScheduleText.Text = Loc.Get("Tab_Schedule");
        HistoryEmpty.Text = Loc.Get("History_Empty");
        RefreshHistory();

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
        PhoneControlLabel.Text = Loc.Get("PhoneControl");
        RemoteToggle.Content = Loc.Get("Remote_Toggle");
        ConnectPhoneButton.Content = Loc.Get("ConnectPhone");
        TamperLabel.Text = Loc.Get("Tamper_Label");
        TamperToggle.Content = Loc.Get("Tamper_Toggle");
        TamperHint.Text = Loc.Get("Tamper_Hint");
        FirewallText.Text = Loc.Get("Remote_FirewallMissing");
        FirewallButton.Content = Loc.Get("Remote_FirewallFix");
        PairedDevicesLabel.Text = Loc.Get("PairedDevices");
        NoDevicesText.Text = Loc.Get("PairedDevices_None");
        UpdateRemoteStatus();
        RefreshDevicesList();
        ProtectionInfo.Title = Loc.Get("Protection");
        ProtectionInfo.Message = Loc.Get("ProtectionInfo");
        AboutLabel.Text = Loc.Get("About");
        AppDescriptionText.Text = Loc.Get("AppDescription");
        VersionText.Text = $"GameTimer v{AppVersion}";

        // Tray menu
        UpdateTrayMenu();

        ApplyScheduleLabels();
        UpdateInterruptionBar();
    }

    // From <Version> (CI passes the tag: -p:Version=1.2.0); drop the "+commit" suffix the SDK appends
    private static string AppVersion =>
        (typeof(MainWindow).Assembly
             .GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
             .FirstOrDefault() as System.Reflection.AssemblyInformationalVersionAttribute)?
        .InformationalVersion.Split('+')[0] ?? "?";

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
        SelectLanguageInCombo(_settings.Language);

        // Settings tab - action
        _lastActionIndex = (int)_settings.Action;
        ActionCombo.SelectedIndex = _lastActionIndex;

        // Settings tab - protection
        TamperToggle.IsChecked = _settings.TamperProtection;

        // Settings tab - phone control
        RemoteToggle.IsChecked = _settings.RemoteEnabled;

        // Schedule tab
        BuildScheduleRows();
        LoadScheduleToUi();
    }

    private void SaveSettings()
    {
        if (_isLoading) return;
        _settingsService.Save(_settings);
        _settingsService.NotifyChanged(this);
    }

    // Settings changed by another client (the phone API): refresh the controls it can change
    private void OnSettingsChanged(object source)
    {
        if (source == this) return;

        _isLoading = true;
        try
        {
            YellowThreshold.Text = _settings.YellowThreshold.ToString();
            RedThreshold.Text = _settings.RedThreshold.ToString();
            UpdateColorPreviews();

            _lastActionIndex = (int)_settings.Action;
            ActionCombo.SelectedIndex = _lastActionIndex;

            SelectLanguageInCombo(_settings.Language);
            Loc.SetLanguage(_settings.Language);
            ApplyLocalization(); // also refreshes the alerts list
            LoadScheduleToUi();
        }
        finally
        {
            _isLoading = false;
        }

        _overlay?.ApplySettings(_settings);
    }

    private void SelectLanguageInCombo(string langTag)
    {
        for (int i = 0; i < LanguageCombo.Items.Count; i++)
        {
            if (LanguageCombo.Items[i] is ComboBoxItem ci && ci.Tag?.ToString() == langTag)
            {
                LanguageCombo.SelectedIndex = i;
                break;
            }
        }
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
            if (!_timer.IsRunning)
                StartButton.IsEnabled = true;
        }
    }
    #region Keyboard

    private void FocusTimeInput()
    {
        if (MainTabs.SelectedIndex != 0 || _timer.IsRunning) return;
        var target = ModeInterval.IsChecked == true ? IntervalHours : AbsoluteHour;
        Dispatcher.BeginInvoke(() => { target.Focus(); Keyboard.Focus(target); target.SelectAll(); },
            System.Windows.Threading.DispatcherPriority.Input);
    }

    private void Window_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        bool ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);

#if DEBUG
        // Temporary debug hotkeys for TimerController.Extend: Ctrl+Shift+Plus / Ctrl+Shift+Minus = ±1 min
        if (ctrl && Keyboard.Modifiers.HasFlag(ModifierKeys.Shift))
        {
            int delta = e.Key switch
            {
                Key.OemPlus or Key.Add => 1,
                Key.OemMinus or Key.Subtract => -1,
                _ => 0
            };
            if (delta != 0)
            {
                _timer.Extend(delta);
                e.Handled = true;
                return;
            }
        }
#endif

        // Ctrl+1..4 — switch tabs
        if (ctrl)
        {
            int tab = e.Key switch
            {
                Key.D1 or Key.NumPad1 => 0,
                Key.D2 or Key.NumPad2 => 1,
                Key.D3 or Key.NumPad3 => 2,
                Key.D4 or Key.NumPad4 => 3,
                Key.D5 or Key.NumPad5 => 4,
                Key.D6 or Key.NumPad6 => 5,
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
        if (_timer.IsRunning) return;

        if (ModeInterval.IsChecked == true)
        {
            int h = ParseInt(IntervalHours.Text);
            int m = ParseInt(IntervalMinutes.Text);
            if (!_timer.StartInterval(h, m)) ValidateTimerInput();
        }
        else
        {
            int h = ParseInt(AbsoluteHour.Text, -1);
            int m = ParseInt(AbsoluteMinute.Text, -1);
            if (!_timer.StartAbsolute(h, m)) ValidateTimerInput();
        }
        // UI updates happen in OnTimerStateChanged
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (!await RequireAuth()) return;
        _timer.Stop();
    }

    private async void Reset_Click(object sender, RoutedEventArgs e)
    {
        if (!await RequireAuth()) return;
        _timer.Reset();
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

    // Fires for every transition, whoever caused it (this window now, the phone API later).
    // The shutdown / lock / logoff on expiry is executed by TimerController itself.
    // Shows "the app was closed while a timer ran" after a tamper-style restart; the parent dismisses it
    private void UpdateInterruptionBar()
    {
        bool show = _timer.LastInterruption is DateTimeOffset;
        if (show)
        {
            var when = _timer.LastInterruption!.Value.LocalDateTime;
            InterruptionBar.Title = Loc.Get("Interruption_Title");
            InterruptionBar.Message = string.Format(Loc.Get("Interruption_Message"), when.ToString("g"));
            InterruptionDismiss.Content = Loc.Get("Interruption_Dismiss");
        }
        InterruptionBar.IsOpen = show;
        InterruptionDismiss.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
    }

    private void InterruptionDismiss_Click(object sender, RoutedEventArgs e)
    {
        _timer.AcknowledgeInterruption();
    }

    private sealed record HistoryRow(string When, string Text, string Who);

    private void RefreshHistory()
    {
        var rows = _sessionLog.Recent().Select(ToRow).ToList();
        HistoryList.ItemsSource = rows;
        HistoryEmpty.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private static HistoryRow ToRow(GameTimer.Models.SessionEvent e)
    {
        var when = e.Time.LocalDateTime.ToString("g");
        string text = e.Type switch
        {
            GameTimer.Models.SessionEventType.Started =>
                $"{Loc.Get("Ev_Started")}: {FormatDuration(e.Minutes)} ({Loc.Get(e.Mode == GameTimer.Models.TimerMode.Interval ? "Mode_Interval" : "Mode_TimeOfDay")})",
            GameTimer.Models.SessionEventType.Stopped => Loc.Get("Ev_Stopped"),
            GameTimer.Models.SessionEventType.Reset => Loc.Get("Ev_Reset"),
            GameTimer.Models.SessionEventType.Extended =>
                string.Format(Loc.Get(e.Minutes >= 0 ? "Ev_Extended_Plus" : "Ev_Extended_Minus"), Math.Abs(e.Minutes ?? 0)),
            GameTimer.Models.SessionEventType.Expired =>
                $"{Loc.Get("Ev_Expired")}: {Loc.Get("Action_" + e.Action)}",
            GameTimer.Models.SessionEventType.Resumed => Loc.Get("Ev_Resumed"),
            GameTimer.Models.SessionEventType.Closed => Loc.Get("Ev_Closed"),
            GameTimer.Models.SessionEventType.Tampered => Loc.Get("Ev_Tampered"),
            _ => e.Type.ToString()
        };
        string who = e.Source switch
        {
            GameTimer.Models.ActionSource.Pc => Loc.Get("Who_Pc"),
            GameTimer.Models.ActionSource.Phone => e.Device ?? Loc.Get("Who_Phone"),
            _ => Loc.Get("Who_System")
        };
        return new HistoryRow(when, text, who);
    }

    private static string FormatDuration(int? minutes)
    {
        int m = minutes ?? 0;
        return m >= 60 ? $"{m / 60}:{m % 60:D2}" : $"{m} {Loc.Get("Min")}";
    }

    private void OnTimerStateChanged(TimerState state)
    {
        switch (state)
        {
            case TimerState.Running:
                ValidationText.Visibility = Visibility.Collapsed;
                ShowOverlay();
                UpdateButtonStates(running: true);
                _timerStateKey = "Timer_Started";
                break;

            case TimerState.Stopped:
                HideOverlay();
                UpdateButtonStates(running: false);
                _timerStateKey = "Timer_Stopped";
                RemainingText.Text = "";
                break;

            case TimerState.NotStarted:
                HideOverlay();
                UpdateButtonStates(running: false);
                _timerStateKey = "Timer_NotStarted";
                RemainingText.Text = "";
                break;

            case TimerState.Expired:
                HideOverlay();
                UpdateButtonStates(running: false);
                _timerStateKey = "Timer_Expired";
                RemainingText.Text = "00:00";
                break;
        }

        StatusText.Text = Loc.Get(_timerStateKey);
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

    // Turning phone control on or off requires auth, like other protected settings
    // Enabling denies termination now; disabling re-allows it. Both require Windows Hello.
    private async void TamperToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading || _isTamperBusy) return;

        bool enable = TamperToggle.IsChecked == true;
        if (enable == _settings.TamperProtection) return;

        _isTamperBusy = true; // reverting IsChecked below re-enters this handler
        TamperToggle.IsEnabled = false;
        try
        {
            if (!await RequireAuth())
            {
                TamperToggle.IsChecked = _settings.TamperProtection;
                return;
            }

            bool ok = enable ? ProcessProtection.DenyTermination() : ProcessProtection.AllowTermination();
            if (!ok)
            {
                System.Windows.MessageBox.Show(Loc.Get("Tamper_Failed"), Loc.Get("Error"),
                    System.Windows.MessageBoxButton.OK);
                TamperToggle.IsChecked = _settings.TamperProtection;
                return;
            }

            _settings.TamperProtection = enable;
            SaveSettings();
        }
        finally
        {
            _isTamperBusy = false;
            TamperToggle.IsEnabled = true;
        }
    }

    private async void RemoteToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading || _isRemoteBusy) return;

        bool enable = RemoteToggle.IsChecked == true;
        if (enable == _settings.RemoteEnabled) return;

        _isRemoteBusy = true; // reverting IsChecked below re-enters this handler
        RemoteToggle.IsEnabled = false;
        try
        {
            if (!await RequireAuth())
            {
                RemoteToggle.IsChecked = _settings.RemoteEnabled;
                return;
            }

            if (enable)
            {
                // Add the rule before the first listen, so Windows doesn't show its own prompt
                // (where "Cancel" would create a block rule). One UAC prompt; declining is not fatal.
                _firewallOk = await FirewallHelper.IsConfiguredAsync(_settings.RemotePort)
                              || await FirewallHelper.ConfigureElevatedAsync(_settings.RemotePort);

                if (!await _remote.StartAsync(_settings.RemotePort))
                {
                    RemoteToggle.IsChecked = false;
                    return;
                }
            }
            else
            {
                await _remote.StopAsync();
            }

            _settings.RemoteEnabled = enable;
            SaveSettings();
        }
        finally
        {
            _isRemoteBusy = false;
            RemoteToggle.IsEnabled = true;
            UpdateRemoteStatus();
        }
    }

    private async Task StartRemoteOnLaunchAsync()
    {
        // On failure (e.g. port busy) the setting stays on, so the next launch tries again
        await _remote.StartAsync(_settings.RemotePort);
        UpdateRemoteStatus();

        // No UAC prompt at launch: only show the warning and the button if the rule is missing
        _firewallOk = await FirewallHelper.IsConfiguredAsync(_settings.RemotePort);
        UpdateRemoteStatus();
    }

    private async void FirewallButton_Click(object sender, RoutedEventArgs e)
    {
        FirewallButton.IsEnabled = false;
        try
        {
            await FirewallHelper.ConfigureElevatedAsync(_settings.RemotePort);
            _firewallOk = await FirewallHelper.IsConfiguredAsync(_settings.RemotePort);
        }
        finally
        {
            FirewallButton.IsEnabled = true;
            UpdateRemoteStatus();
        }
    }

    private async void ConnectPhone_Click(object sender, RoutedEventArgs e)
    {
        if (!_remote.IsRunning || _remote.Port is not int port) return;
        if (!await RequireAuth()) return;

        var address = NetworkFilter.GetLanAddress();
        if (address == null)
        {
            System.Windows.MessageBox.Show(Loc.Get("ConnectPhone_NoNetwork"), Loc.Get("Error"),
                System.Windows.MessageBoxButton.OK);
            return;
        }

        new ConnectPhoneDialog(_pairing, address.ToString(), port) { Owner = this }.ShowDialog();
    }

    private async void RevokeDevice_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: PairedDevice device }) return;
        if (!await RequireAuth()) return;
        _devices.Revoke(device.Id); // its next request gets 401
    }

    private void RefreshDevicesList()
    {
        DevicesList.ItemsSource = null;
        DevicesList.ItemsSource = _devices.Devices;
        NoDevicesText.Visibility = _devices.Devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateRemoteStatus()
    {
        ConnectPhoneButton.IsEnabled = _remote.IsRunning;

        FirewallPanel.Visibility = _remote.IsRunning && _firewallOk == false ? Visibility.Visible : Visibility.Collapsed;

        if (_remote.IsRunning)
        {
            // The address the phone uses, so a parent can compare it after a router change
            var host = NetworkFilter.GetLanAddress()?.ToString() ?? "localhost";
            RemoteStatusText.Text = string.Format(Loc.Get("Remote_Running"), $"http://{host}:{_remote.Port}");
        }
        else if (_remote.LastError != null)
            RemoteStatusText.Text = string.Format(Loc.Get("Remote_StartFailed"), _settings.RemotePort, _remote.LastError);
        else
            RemoteStatusText.Text = Loc.Get("Remote_Off");
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
        if (_timer.IsRunning)
        {
            if (!await RequireAuth()) return;
            _timer.Stop();
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
        LockSchedule(); // require Windows Hello again next time the window opens
        Hide();
        if (_trayIcon != null) _trayIcon.Visible = true;
    }

    #endregion

    #region Schedule

    // The day rows are identical, so they are built once in code rather than 7x in XAML.
    private void BuildScheduleRows()
    {
        if (_dayName[ScheduleDayOrder[0]] != null) return; // already built

        ScheduleDaysPanel.Children.Add(BuildScheduleHeader());

        foreach (var dow in ScheduleDayOrder)
        {
            var grid = NewScheduleGrid();
            grid.Margin = new Thickness(0, 3, 0, 3);

            var name = new System.Windows.Controls.TextBlock { VerticalAlignment = System.Windows.VerticalAlignment.Center };
            Grid.SetColumn(name, 0);
            _dayName[dow] = name;

            var allowed = new System.Windows.Controls.CheckBox
            {
                VerticalAlignment = System.Windows.VerticalAlignment.Center,
                HorizontalAlignment = System.Windows.HorizontalAlignment.Center
            };
            allowed.Checked += Schedule_Toggle;
            allowed.Unchecked += Schedule_Toggle;
            Grid.SetColumn(allowed, 1);
            _dayAllowed[dow] = allowed;

            var limit = new Wpf.Ui.Controls.TextBox { Width = 46, VerticalAlignment = System.Windows.VerticalAlignment.Center };
            limit.TextChanged += Schedule_Text;
            Grid.SetColumn(limit, 2);
            _dayLimit[dow] = limit;

            var from = new Wpf.Ui.Controls.TextBox { Width = 54, VerticalAlignment = System.Windows.VerticalAlignment.Center };
            from.TextChanged += Schedule_Text;
            Grid.SetColumn(from, 3);
            _dayFrom[dow] = from;

            var dash = new System.Windows.Controls.TextBlock
            {
                Text = "\u2013",
                HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
                VerticalAlignment = System.Windows.VerticalAlignment.Center,
                Foreground = (System.Windows.Media.Brush)FindResource("TextFillColorSecondaryBrush")
            };
            Grid.SetColumn(dash, 4);

            var to = new Wpf.Ui.Controls.TextBox { Width = 54, VerticalAlignment = System.Windows.VerticalAlignment.Center };
            to.TextChanged += Schedule_Text;
            Grid.SetColumn(to, 5);
            _dayTo[dow] = to;

            grid.Children.Add(name);
            grid.Children.Add(allowed);
            grid.Children.Add(limit);
            grid.Children.Add(from);
            grid.Children.Add(dash);
            grid.Children.Add(to);
            ScheduleDaysPanel.Children.Add(grid);
        }
    }

    private Grid BuildScheduleHeader()
    {
        var grid = NewScheduleGrid();
        grid.Margin = new Thickness(0, 0, 0, 2);
        _schHeadDay = HeaderCell(0);
        _schHeadOn = HeaderCell(1);
        _schHeadLimit = HeaderCell(2);
        _schHeadFrom = HeaderCell(3);
        _schHeadTo = HeaderCell(5);
        foreach (var c in new[] { _schHeadDay, _schHeadOn, _schHeadLimit, _schHeadFrom, _schHeadTo })
            grid.Children.Add(c);
        return grid;

        System.Windows.Controls.TextBlock HeaderCell(int col)
        {
            var t = new System.Windows.Controls.TextBlock
            {
                FontSize = 11,
                Foreground = (System.Windows.Media.Brush)FindResource("TextFillColorSecondaryBrush"),
                VerticalAlignment = System.Windows.VerticalAlignment.Center
            };
            Grid.SetColumn(t, col);
            return t;
        }
    }

    private static Grid NewScheduleGrid()
    {
        var grid = new Grid();
        foreach (var w in new[] { 76.0, 46.0, 54.0, 56.0, 16.0, 56.0 })
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(w) });
        return grid;
    }

    private void ApplyScheduleLabels()
    {
        ScheduleLabel.Text = Loc.Get("Sched_Label");
        ScheduleIntro.Text = Loc.Get("Sched_Intro");
        ScheduleEnabledToggle.Content = Loc.Get("Sched_Enable");
        ScheduleHint.Text = Loc.Get("Sched_Hint");
        ScheduleUnlockButton.Content = Loc.Get(_scheduleUnlocked ? "Sched_Lock" : "Sched_Unlock");

        if (_schHeadDay != null) _schHeadDay.Text = Loc.Get("Sched_Col_Day");
        if (_schHeadOn != null) _schHeadOn.Text = Loc.Get("Sched_Col_On");
        if (_schHeadLimit != null) _schHeadLimit.Text = Loc.Get("Sched_Col_Limit");
        if (_schHeadFrom != null) _schHeadFrom.Text = Loc.Get("Sched_Col_From");
        if (_schHeadTo != null) _schHeadTo.Text = Loc.Get("Sched_Col_To");

        var dtf = System.Globalization.CultureInfo.GetCultureInfo(Loc.CurrentLanguage).DateTimeFormat;
        for (int d = 0; d < 7; d++)
        {
            if (_dayName[d] == null) continue;
            var n = dtf.GetAbbreviatedDayName((DayOfWeek)d);
            if (n.Length > 0) n = char.ToUpper(n[0]) + n.Substring(1);
            _dayName[d].Text = n;
        }

        UpdateScheduleStatus();
    }

    private void LoadScheduleToUi()
    {
        if (_settings.Week == null || _settings.Week.Count != 7)
            _settings.Week = AppSettings.DefaultWeek();

        ScheduleEnabledToggle.IsChecked = _settings.ScheduleEnabled;
        for (int d = 0; d < 7; d++)
        {
            if (_dayAllowed[d] == null) continue;
            var day = _settings.Week[d];
            _dayAllowed[d].IsChecked = day.Allowed;
            _dayLimit[d].Text = day.LimitMinutes.ToString();
            _dayFrom[d].Text = FormatClock(day.WindowStartMinutes);
            _dayTo[d].Text = FormatClock(day.WindowEndMinutes);
        }
        UpdateScheduleStatus();
    }

    private void Schedule_Toggle(object sender, RoutedEventArgs e) => ApplyScheduleFromUi();
    private void Schedule_Text(object sender, TextChangedEventArgs e) => ApplyScheduleFromUi();
    private void ScheduleEnabled_Changed(object sender, RoutedEventArgs e) => ApplyScheduleFromUi();

    private void ApplyScheduleFromUi()
    {
        if (_isLoading || !_scheduleUnlocked) return;
        if (_settings.Week == null || _settings.Week.Count != 7)
            _settings.Week = AppSettings.DefaultWeek();

        _settings.ScheduleEnabled = ScheduleEnabledToggle.IsChecked == true;
        for (int d = 0; d < 7; d++)
        {
            if (_dayAllowed[d] == null) continue;
            var day = _settings.Week[d];
            day.Allowed = _dayAllowed[d].IsChecked == true;
            day.LimitMinutes = Math.Clamp(ParseInt(_dayLimit[d].Text), 0, 24 * 60);
            day.WindowStartMinutes = ParseClock(_dayFrom[d].Text, 0);
            day.WindowEndMinutes = ParseClock(_dayTo[d].Text, 24 * 60);
        }
        SaveSettings();
        _timer.ReevaluateSchedule();
        UpdateScheduleStatus();
    }

    private async void ScheduleUnlock_Click(object sender, RoutedEventArgs e)
    {
        if (_scheduleUnlocked) { LockSchedule(); return; }
        if (!await RequireAuth()) return;
        _scheduleUnlocked = true;
        ScheduleEditPanel.IsEnabled = true;
        ScheduleUnlockButton.Content = Loc.Get("Sched_Lock");
    }

    private void LockSchedule()
    {
        _scheduleUnlocked = false;
        if (ScheduleEditPanel != null) ScheduleEditPanel.IsEnabled = false;
        if (ScheduleUnlockButton != null) ScheduleUnlockButton.Content = Loc.Get("Sched_Unlock");
    }

    private void UpdateScheduleStatus()
    {
        if (ScheduleStatusBar == null) return;
        var dec = _timer.CurrentSchedule();
        string msg;
        var sev = Wpf.Ui.Controls.InfoBarSeverity.Informational;
        switch (dec.Status)
        {
            case ScheduleStatus.Off:
                msg = Loc.Get("Sched_St_Off");
                break;
            case ScheduleStatus.Allowed:
                msg = string.Format(Loc.Get("Sched_St_Allowed"), dec.AvailableSeconds / 60);
                sev = Wpf.Ui.Controls.InfoBarSeverity.Success;
                break;
            case ScheduleStatus.OutsideWindow:
                msg = Loc.Get("Sched_St_OutsideWindow");
                sev = Wpf.Ui.Controls.InfoBarSeverity.Warning;
                break;
            case ScheduleStatus.DayDisabled:
                msg = Loc.Get("Sched_St_DayOff");
                sev = Wpf.Ui.Controls.InfoBarSeverity.Warning;
                break;
            case ScheduleStatus.LimitReached:
                msg = Loc.Get("Sched_St_LimitReached");
                sev = Wpf.Ui.Controls.InfoBarSeverity.Warning;
                break;
            default:
                msg = "";
                break;
        }
        ScheduleStatusBar.Message = msg;
        ScheduleStatusBar.Severity = sev;
    }

    // "HH:MM" (24:00 for end-of-day); minutes from midnight
    private static string FormatClock(int minutes)
    {
        minutes = Math.Clamp(minutes, 0, 24 * 60);
        return $"{minutes / 60:D2}:{minutes % 60:D2}";
    }

    // Parses "H", "H:MM" or "HH:MM" to minutes from midnight (0..1440); fallback on anything else
    private static int ParseClock(string text, int fallback)
    {
        if (string.IsNullOrWhiteSpace(text)) return fallback;
        text = text.Trim();
        int h, m = 0;
        var colon = text.IndexOf(':');
        if (colon < 0)
        {
            if (!int.TryParse(text, out h)) return fallback;
        }
        else
        {
            if (!int.TryParse(text.Substring(0, colon), out h)) return fallback;
            var rest = text.Substring(colon + 1);
            if (rest.Length > 0 && !int.TryParse(rest, out m)) return fallback;
        }
        return Math.Clamp(h * 60 + m, 0, 24 * 60);
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
