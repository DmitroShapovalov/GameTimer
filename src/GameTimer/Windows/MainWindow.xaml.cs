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
    private readonly GameTimer.Services.V2.WindowsPairingUi _pairingUi;
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

    // Schedule page: the week overview (a row per day, built once in code) and the editor of the selected day
    private readonly System.Windows.Controls.RadioButton[] _dayRow = new System.Windows.Controls.RadioButton[7];
    private readonly System.Windows.Controls.TextBlock[] _dayName = new System.Windows.Controls.TextBlock[7];
    private readonly System.Windows.Controls.TextBlock[] _daySummary = new System.Windows.Controls.TextBlock[7];
    private readonly ColumnDefinition[][] _dayBarCols = new ColumnDefinition[7][];
    private readonly Border[] _dayBarFill = new Border[7];
    private int _selectedDay = (int)DateTime.Now.DayOfWeek;
    // Set while the schedule controls are filled from the settings (their change events must not save)
    private bool _scheduleUiLoading;
    // The day "today" was last shown for (the bold day in the week, Today / Yesterday in History)
    private DateTime _shownDate = DateTime.Today;
    // Display order Monday..Sunday, mapped to (int)DayOfWeek (0 = Sunday)
    private static readonly int[] ScheduleDayOrder = { 1, 2, 3, 4, 5, 6, 0 };

    public MainWindow(SettingsService settingsService, AppSettings settings, TimerController timer,
        RemoteServer remote, DeviceStore devices, PairingService pairing, SessionLog sessionLog,
        GameTimer.Services.V2.WindowsPairingUi pairingUi)
    {
        InitializeComponent();
        // After midnight "today" moves: refresh it when the window is used again
        Activated += (_, _) =>
        {
            if (_shownDate == DateTime.Today) return;
            UpdateWeekOverview();
            RefreshHistory();
        };
        // The parent lock ends when the window is minimized or hidden, or when the user switches to another
        // app (back to the game): a parent who walks away after adding time leaves nothing unlocked behind
        StateChanged += (_, _) => { if (WindowState == WindowState.Minimized) SetUnlocked(false); };
        IsVisibleChanged += (_, _) => { if (!IsVisible) SetUnlocked(false); };
        // The whole app losing focus (not just this window: the overlay or a dialog may have had it)
        System.Windows.Application.Current.Activated += (_, _) => _appActive = true;
        // (not while our own prompt is open: the system's Windows Hello prompt is another app)
        System.Windows.Application.Current.Deactivated += (_, _) => { _appActive = false; if (!_verifying) SetUnlocked(false); };
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
        _pairingUi = pairingUi;
        _pairingUi.AskForCode = AskPairingCodeAsync;
        _remote.Agent.PeersChanged += () => Dispatcher.BeginInvoke(RefreshDevicesList);
        _remote.RelayStatusChanged += () => Dispatcher.BeginInvoke(UpdateRemoteStatus);
        _settingsService.SettingsChanged += OnSettingsChanged;

        LoadSettingsToUI();
        ApplyLocalization();
        SetupTrayIcon();

        _isLoading = false;
        ValidateTimerInput();
        UpdateMarkerPreview(); // skipped while loading

        Loaded += (_, _) => FocusTimeInput();
        StartButton.ToolTip = "Enter";

        // The setting was approved (Windows Hello) when it was turned on, so no prompt at launch
        if (_settings.RemoteEnabled)
            _ = StartRemoteOnLaunchAsync();
    }

    #region Localization

    private void ApplyLocalization()
    {
        UpdateLockUi();
        // Tab headers
        TabTimerText.Text = Loc.Get("Tab_Timer");
        TabDisplayText.Text = Loc.Get("Tab_Display");
        TabSettingsText.Text = Loc.Get("Tab_Settings");
        TabPhonesText.Text = Loc.Get("Tab_Phones");
        SettingsHeader.Text = Loc.Get("Tab_Settings");
        PhonesHeader.Text = Loc.Get("Tab_Phones");
        LanguageDesc.Text = Loc.Get("Language_Desc");
        ActionDesc.Text = Loc.Get("Action_Desc");
        ConnectPhoneTitle.Text = Loc.Get("ConnectPhone_Title");
        ConnectPhoneDesc.Text = Loc.Get(_remote.IsRunning ? "ConnectPhone_Desc" : "ConnectPhone_NeedsControl");
        PrivacyLink.Content = Loc.Get("Privacy_Link");
        SourceLink.Content = Loc.Get("Source_Link");
        TabHistoryText.Text = Loc.Get("Tab_History");
        TabScheduleText.Text = Loc.Get("Tab_Schedule");
        HistoryHeader.Text = Loc.Get("Tab_History");
        HistFilterAll.Content = Loc.Get("Hist_All");
        HistFilterTimer.Content = Loc.Get("Hist_Timer");
        HistFilterAlerts.Content = Loc.Get("Hist_Alerts");
        RefreshHistory(); // also the empty text

        // Timer tab
        ModeLabel.Text = Loc.Get("Mode");
        ModeInterval.Content = Loc.Get("Mode_Interval");
        ModeAbsolute.Content = Loc.Get("Mode_TimeOfDay");
        HoursLabel.Text = Loc.Get("Hours");
        MinutesLabel.Text = Loc.Get("Minutes");
        HourLabel.Text = Loc.Get("Hour");
        StatusHeader.Text = Loc.Get("Tab_Timer");
        StartButton.Content = Loc.Get("Start");
        StopButton.Content = Loc.Get("Lift");
        EndNowButton.Header = Loc.Get("EndNow");
        MoreButton.Content = Loc.Get("More");
        EndAtMenu.Header = Loc.Get("EndAt_Menu");
        StepLabel.Text = Loc.Get("Step_Label");
        EndAtLabel.Text = Loc.Get("EndAt_Label");
        EndAtButton.Content = Loc.Get("EndAt_Set");
        UpdateStepButtons();
        ResetButton.Header = Loc.Get("Reset");
        StatusText.Text = Loc.Get(_timerStateKey);
        UpdateStatusLines();

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
        OverlayHeader.Text = Loc.Get("Tab_Display");
        PreviewNormal.Content = Loc.Get("Zone_Normal");
        PreviewWarning.Content = Loc.Get("Zone_Warning");
        PreviewAlert.Content = Loc.Get("Zone_Alert");

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
        RemoteToggleText.Text = Loc.Get("Remote_Toggle");
        RemoteAccessTitle.Text = Loc.Get("RemoteAccess_Toggle");
        ConnectPhoneButton.Content = Loc.Get("ConnectPhone");
        TamperLabel.Text = Loc.Get("Tamper_Label");
        TamperToggle.ToolTip = Loc.Get("Tamper_Toggle");
        TamperHint.Text = Loc.Get("Tamper_Hint");
        FirewallInfo.Message = Loc.Get("Remote_FirewallMissing");
        TamperToggle.SetValue(System.Windows.Automation.AutomationProperties.HelpTextProperty, Loc.Get("Tamper_Toggle"));
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
    private static string AppVersion => AppInfo.Version;

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
        FontSizeLabel.Text = $"{_settings.FontSize} px"; // the handlers skip this while loading
        OpacityLabel.Text = $"{_settings.Opacity}%";
        ShowBackgroundCheck.IsChecked = _settings.ShowBackground;
        YellowThreshold.Text = _settings.YellowThreshold.ToString();
        RedThreshold.Text = _settings.RedThreshold.ToString();
        UpdateColorPreviews();

        // Position marker (deferred until canvas has actual size)
        PositionCanvas.Loaded += (_, _) => PlaceMarker();
        PositionCanvas.LostMouseCapture += (_, _) => _isDragging = false; // Alt+Tab mid-drag
        PositionCanvas.SizeChanged += (_, _) => PlaceMarker(); // the window is resizable

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
        RemoteAccessToggle.IsChecked = _settings.RemoteAccess;

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
        UpdateMarkerPreview();

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

        // Ctrl+1..6 — switch pages (Status, Schedule, History, Timer on screen, Phones, Settings)
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
        if (e.Key == Key.Escape && !LanguageCombo.IsDropDownOpen && !ActionCombo.IsDropDownOpen && !StepCombo.IsDropDownOpen)
        {
            Close(); // Window_Closing turns this into "minimize to tray"
            e.Handled = true;
            return;
        }

        // Enter in the End-at fields sets the new end
        if (e.Key == Key.Enter && (EndAtHour.IsKeyboardFocusWithin || EndAtMinute.IsKeyboardFocusWithin))
        {
            EndAt_Click(EndAtButton, new RoutedEventArgs());
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

    // "Lift restrictions": the timer stops without a lock; if the schedule limits today, the parent
    // chooses whether it is set aside until midnight (or the next timer) too
    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        var dec = _timer.CurrentSchedule();
        bool skip = false;
        if (dec.Restricts)
        {
            var text = (dec.Status == ScheduleStatus.Allowed
                ? string.Format(Loc.Get("Lift_Allowed"), dec.AvailableSeconds / 60)
                : Loc.Get("Lift_Blocked")) + "\n\n" + Loc.Get("Lift_Explain");
            // Own buttons in the app's language (a system MessageBox would say Yes / No in Windows' language)
            var box = new Wpf.Ui.Controls.MessageBox
            {
                Owner = this,
                Title = Loc.Get("Lift_Title"),
                Content = text,
                PrimaryButtonText = Loc.Get("Lift_Btn_Today"),
                SecondaryButtonText = _timer.IsRunning ? Loc.Get("Lift_Btn_TimerOnly") : "",
                IsSecondaryButtonEnabled = _timer.IsRunning,
                CloseButtonText = Loc.Get("Cancel"),
            };
            var answer = await box.ShowDialogAsync();
            if (answer == Wpf.Ui.Controls.MessageBoxResult.None) return;
            skip = answer == Wpf.Ui.Controls.MessageBoxResult.Primary;
        }
        if (!await RequireAuth()) return;
        _timer.Lift(skip);
        UpdateButtonStates(_timer.IsRunning);
    }

    // Ending early only restricts: no Windows Hello needed
    private void EndNow_Click(object sender, RoutedEventArgs e)
    {
        var action = Loc.Get("Action_" + _settings.Action);
        if (System.Windows.MessageBox.Show(this, string.Format(Loc.Get("EndNow_Confirm"), action), Loc.Get("EndNow"),
                System.Windows.MessageBoxButton.OKCancel) != System.Windows.MessageBoxResult.OK) return;
        _timer.EndNow();
    }

    private static readonly int[] Steps = { 1, 5, 10, 15, 30, 60 };

    private void UpdateStepButtons()
    {
        int step = Steps.Contains(_settings.AdjustStepMinutes) ? _settings.AdjustStepMinutes : 15;
        if (StepCombo.Items.Count == 0)
            foreach (var n in Steps) StepCombo.Items.Add(n);
        StepCombo.SelectedItem = step;
        MinusButton.Content = string.Format(Loc.Get("Minus_N"), step);
        PlusButton.Content = string.Format(Loc.Get("Plus_N"), step);
    }

    private void StepCombo_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (_isLoading || StepCombo.SelectedItem is not int step || step == _settings.AdjustStepMinutes) return;
        _settings.AdjustStepMinutes = step;
        SaveSettings();
        UpdateStepButtons();
    }

    private void Minus_Click(object sender, RoutedEventArgs e)
    {
        int step = _settings.AdjustStepMinutes;
        if (_timer.Remaining.TotalMinutes <= step)
        {
            var action = Loc.Get("Action_" + _settings.Action);
            if (System.Windows.MessageBox.Show(this, string.Format(Loc.Get("EndNow_Confirm"), action), Loc.Get("EndNow"),
                    System.Windows.MessageBoxButton.OKCancel) != System.Windows.MessageBoxResult.OK) return;
        }
        _timer.Extend(-step);
    }

    // More time: Windows Hello, like Stop (the child may be at the PC)
    private async void Plus_Click(object sender, RoutedEventArgs e)
    {
        if (!await RequireAuth()) return;
        if (!_timer.Extend(_settings.AdjustStepMinutes))
            System.Windows.MessageBox.Show(this, Loc.Get("Schedule_NoMore"), Loc.Get("Error"), System.Windows.MessageBoxButton.OK);
    }

    private async void EndAt_Click(object sender, RoutedEventArgs e)
    {
        int h = ParseInt(EndAtHour.Text, -1), m = ParseInt(EndAtMinute.Text, -1);
        if (h is < 0 or > 23 || m is < 0 or > 59)
        {
            System.Windows.MessageBox.Show(this, Loc.Get("InvalidTimeRange"), Loc.Get("Error"), System.Windows.MessageBoxButton.OK);
            return;
        }
        // Later than now ends: more time → Windows Hello (the 12-hour rule is checked first)
        var now = DateTime.Now;
        var target = now.Date.AddHours(h).AddMinutes(m);
        if (target <= now) target = target.AddDays(1);
        if (target - now > TimeSpan.FromHours(12) && target - now > _timer.Remaining)
        {
            System.Windows.MessageBox.Show(this, Loc.Get("EndAt_TooFar"), Loc.Get("Error"), System.Windows.MessageBoxButton.OK);
            return;
        }
        if (target - now > _timer.Remaining && !await RequireAuth()) return;
        var result = _timer.SetEnd(h, m);
        if (result is SetEndResult.InvalidTime or SetEndResult.ScheduleLimit)
            System.Windows.MessageBox.Show(this, Loc.Get(result == SetEndResult.ScheduleLimit ? "Schedule_NoMore" : "EndAt_TooFar"),
                Loc.Get("Error"), System.Windows.MessageBoxButton.OK);
    }

    private async void Reset_Click(object sender, RoutedEventArgs e)
    {
        if (!await RequireAuth()) return;
        _timer.Reset();
    }

    private void UpdateButtonStates(bool running)
    {
        StartButton.IsEnabled = !running;
        StartPanel.Visibility = running ? Visibility.Collapsed : Visibility.Visible;
        // Lift also works without a timer while the schedule limits today (e.g. it has locked the PC)
        StopButton.IsEnabled = running || _timer.CurrentSchedule().Restricts;
        StopButton.Visibility = StopButton.IsEnabled ? Visibility.Visible : Visibility.Collapsed;
        StopButton.Content = Loc.Get(running ? "Lift" : "LiftToday");
        MoreButton.Visibility = running ? Visibility.Visible : Visibility.Collapsed;
        if (!running) EndAtPanel.Visibility = Visibility.Collapsed;
        ResetButton.IsEnabled = running;
        EndNowButton.IsEnabled = running;
        RunningPanel.Visibility = running ? Visibility.Visible : Visibility.Collapsed;
        UpdateStatusLines();
        if (running && _timer.TargetTime is { } end && !EndAtHour.IsKeyboardFocusWithin && !EndAtMinute.IsKeyboardFocusWithin && !EndAtButton.IsPressed)
        {
            EndAtHour.Text = end.Hour.ToString("D2");
            EndAtMinute.Text = end.Minute.ToString("D2");
        }
    }

    private void MoreButton_Click(object sender, RoutedEventArgs e)
    {
        if (MoreButton.ContextMenu is not { } menu) return;
        menu.PlacementTarget = MoreButton;
        menu.Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom;
        menu.IsOpen = true;
    }

    private void EndAtMenu_Click(object sender, RoutedEventArgs e)
    {
        EndAtPanel.Visibility = Visibility.Visible;
        Dispatcher.BeginInvoke(() => { EndAtHour.Focus(); Keyboard.Focus(EndAtHour); },
            System.Windows.Threading.DispatcherPriority.Input);
    }

    // 0:30 / 1:00 / 1:30 / 2:00 fill the duration
    private void Preset_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: string tag } || !int.TryParse(tag, out var minutes)) return;
        IntervalHours.Text = (minutes / 60).ToString();
        IntervalMinutes.Text = (minutes % 60).ToString("D2");
        StartButton.Focus();
    }

    // ---- The ring and the lines next to it ----

    // The ring's full length = the time used since this timer started (or was resumed) + the time left,
    // on the monotonic clock: − / + from here or a phone and clock changes keep it right
    private long _ringStartedAt;

    private void UpdateRing(TimeSpan remaining)
    {
        double left = Math.Max(0, remaining.TotalSeconds);
        if (_ringStartedAt == 0) _ringStartedAt = Environment.TickCount64;
        double used = (Environment.TickCount64 - _ringStartedAt) / 1000.0;
        double total = used + left;
        DrawRing(total > 0 ? left / total : 0, ZoneBrush(remaining));
    }

    private void DrawRing(double fraction, Brush brush)
    {
        RingArc.Stroke = brush;
        fraction = Math.Clamp(fraction, 0, 1);
        if (fraction <= 0) { RingArc.Data = null; return; }
        const double c = 100, r = 88; // the centre line of the 12 px track inside the 200 px ring
        if (fraction >= 0.9999) { RingArc.Data = new EllipseGeometry(new Point(c, c), r, r); return; }
        double angle = fraction * 2 * Math.PI;
        var start = new Point(c, c - r);
        var end = new Point(c + r * Math.Sin(angle), c - r * Math.Cos(angle));
        var figure = new PathFigure { StartPoint = start, IsClosed = false };
        figure.Segments.Add(new ArcSegment(end, new Size(r, r), 0, fraction > 0.5, SweepDirection.Clockwise, true));
        RingArc.Data = new PathGeometry(new[] { figure });
    }

    // The overlay's zone colour for this time left (the parent's own colours)
    private Brush ZoneBrush(TimeSpan remaining)
    {
        string hex = remaining.TotalMinutes <= _settings.RedThreshold ? _settings.ColorRed
            : remaining.TotalMinutes <= _settings.YellowThreshold ? _settings.ColorYellow : _settings.ColorGreen;
        try { return new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex)); }
        catch { return (Brush)FindResource("SystemFillColorSuccessBrush"); }
    }

    private string _scheduleLine = "";

    /// <summary>The end time under the ring, "Then: …", today's schedule line and the state badge's colour.</summary>
    private void UpdateStatusLines()
    {
        if (EndTimeText == null) return;
        bool running = _timer.IsRunning;
        EndTimeText.Text = running && _timer.TargetTime is { } end ? string.Format(Loc.Get("Until_Time"), end.ToString("t")) : "";
        ThenText.Text = running ? string.Format(Loc.Get("Then_Action"), Loc.Get("Action_" + _settings.Action)) : "";
        var dec = _timer.CurrentSchedule();
        _scheduleLine = dec.Status switch
        {
            ScheduleStatus.Allowed => string.Format(Loc.Get("Sched_St_Allowed"), dec.AvailableSeconds / 60),
            ScheduleStatus.OutsideWindow => Loc.Get("Sched_St_OutsideWindow"),
            ScheduleStatus.DayDisabled => Loc.Get("Sched_St_DayOff"),
            ScheduleStatus.LimitReached => Loc.Get("Sched_St_LimitReached"),
            ScheduleStatus.SkippedToday => Loc.Get("Sched_St_Skipped"),
            _ => "", // no schedule: nothing to say
        };
        ScheduleLineText.Text = _scheduleLine;
        ScheduleLineText.Visibility = _scheduleLine.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        string key = running ? "SystemFillColorSuccessBrush"
            : _timer.State == TimerState.Expired ? "SystemFillColorCautionBrush" : "SubtleFillColorSecondaryBrush";
        if (TryFindResource(key) is Brush b)
        {
            StateBadge.Background = b.Clone();
            StateBadge.Background.Opacity = running || _timer.State == TimerState.Expired ? 0.25 : 1;
        }
        if (!running) DrawRing(0, RingArc.Stroke);
    }

    #endregion

    #region Timer Events

    private void OnTimerTick(TimeSpan remaining)
    {
        _overlay?.UpdateTime(remaining);

        // The end time follows extends (from here or a phone) unless the parent is typing a new one
        if (_timer.TargetTime is { } end && !EndAtHour.IsKeyboardFocusWithin && !EndAtMinute.IsKeyboardFocusWithin && !EndAtButton.IsPressed)
        {
            EndAtHour.Text = end.Hour.ToString("D2");
            EndAtMinute.Text = end.Minute.ToString("D2");
        }

        UpdateRing(remaining);
        UpdateStatusLines(); // the end moves with − / + (from here or a phone)
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

    private enum HistoryFilter { All, Timer, Alerts }
    private HistoryFilter _historyFilter = HistoryFilter.All;

    // Arrow keys move the focus between the chips: the focused chip is the filter
    private void HistoryFilter_GotKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        if (sender is System.Windows.Controls.RadioButton chip) chip.IsChecked = true;
    }

    private void HistoryFilter_Checked(object sender, RoutedEventArgs e)
    {
        _historyFilter = sender == HistFilterAlerts ? HistoryFilter.Alerts
            : sender == HistFilterTimer ? HistoryFilter.Timer
            : HistoryFilter.All;
        RefreshHistory();
    }

    private static bool HistoryAccepts(HistoryFilter filter, GameTimer.Models.SessionEventType type) => filter switch
    {
        HistoryFilter.Alerts => IsAlert(type),
        HistoryFilter.Timer => !IsAlert(type) && type != GameTimer.Models.SessionEventType.Updated,
        _ => true
    };

    private static bool IsAlert(GameTimer.Models.SessionEventType type) =>
        type is GameTimer.Models.SessionEventType.Closed or GameTimer.Models.SessionEventType.Tampered;

    // Grouped by day like on the phone: a header (Today, Yesterday, the date) and a card of the day's events
    private void RefreshHistory()
    {
        if (HistoryPanel == null || _sessionLog == null) return; // a filter chip is checked while the XAML loads
        var all = _sessionLog.Recent();
        var shown = all.Where(e => HistoryAccepts(_historyFilter, e.Type)).ToList();
        var culture = UiCulture();

        HistoryPanel.Children.Clear();
        DateTime? day = null;
        System.Windows.Controls.StackPanel? card = null;
        foreach (var e in shown)
        {
            var local = e.Time.LocalDateTime;
            if (day != local.Date || card == null)
            {
                day = local.Date;
                var header = new System.Windows.Controls.TextBlock
                {
                    Text = DayHeader(local.Date, culture),
                    FontWeight = FontWeights.SemiBold,
                    Margin = new Thickness(0, HistoryPanel.Children.Count == 0 ? 0 : 12, 0, 6)
                };
                card = new System.Windows.Controls.StackPanel();
                var border = new Border { Child = card, Style = (Style)FindResource("SettingCard") };
                HistoryPanel.Children.Add(header);
                HistoryPanel.Children.Add(border);
            }
            card.Children.Add(HistoryItem(e, local, culture, first: card.Children.Count == 0));
        }

        HistoryEmpty.Text = Loc.Get(all.Count > 0 ? "Hist_NoneOfKind" : "History_Empty");
        HistoryEmpty.Visibility = shown.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private static string DayHeader(DateTime date, System.Globalization.CultureInfo culture)
    {
        var today = DateTime.Today;
        if (date == today) return Loc.Get("Hist_Today");
        if (date == today.AddDays(-1)) return Loc.Get("Hist_Yesterday");
        var text = $"{Capitalize(culture.DateTimeFormat.GetDayName(date.DayOfWeek))}, {date.ToString("M", culture)}";
        return date.Year == today.Year ? text : $"{text} {date.Year}";
    }

    private static FrameworkElement HistoryItem(GameTimer.Models.SessionEvent e, DateTime local,
        System.Globalization.CultureInfo culture, bool first)
    {
        var (symbol, brush) = HistoryLook(e);
        var (text, who) = DescribeEvent(e);

        var grid = new Grid { Margin = new Thickness(0, first ? 0 : 10, 0, 0) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(32) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var icon = new SymbolIcon
        {
            Symbol = symbol,
            FontSize = 18,
            VerticalAlignment = System.Windows.VerticalAlignment.Top,
            HorizontalAlignment = System.Windows.HorizontalAlignment.Left,
            Margin = new Thickness(0, 1, 0, 0)
        };
        icon.SetResourceReference(System.Windows.Documents.TextElement.ForegroundProperty, brush);

        var lines = new System.Windows.Controls.StackPanel();
        Grid.SetColumn(lines, 1);
        lines.Children.Add(new System.Windows.Controls.TextBlock { Text = text, TextWrapping = TextWrapping.Wrap });
        var meta = new System.Windows.Controls.TextBlock
        {
            Text = $"{local.ToString("t", culture)} · {who}",
            FontSize = 12,
            TextWrapping = TextWrapping.Wrap
        };
        meta.SetResourceReference(System.Windows.Controls.TextBlock.ForegroundProperty, "TextFillColorSecondaryBrush");
        lines.Children.Add(meta);

        grid.Children.Add(icon);
        grid.Children.Add(lines);
        return grid;
    }

    // The icon and the state colour of an event, as on the phone: started green, changed blue (accent),
    // time up orange, alerts red, the rest neutral
    private static (SymbolRegular Symbol, string Brush) HistoryLook(GameTimer.Models.SessionEvent e) => e.Type switch
    {
        GameTimer.Models.SessionEventType.Started => (SymbolRegular.Play24, "SystemFillColorSuccessBrush"),
        GameTimer.Models.SessionEventType.Resumed => (SymbolRegular.ArrowClockwise24, "SystemFillColorSuccessBrush"),
        GameTimer.Models.SessionEventType.Extended =>
            ((e.Minutes ?? 0) >= 0 ? SymbolRegular.Add24 : SymbolRegular.Subtract24, "AccentFillColorDefaultBrush"),
        GameTimer.Models.SessionEventType.ScheduleSkipped or GameTimer.Models.SessionEventType.ScheduleRestored =>
            (SymbolRegular.CalendarLtr24, "AccentFillColorDefaultBrush"),
        GameTimer.Models.SessionEventType.Stopped or GameTimer.Models.SessionEventType.Reset =>
            (SymbolRegular.Dismiss24, "TextFillColorSecondaryBrush"),
        GameTimer.Models.SessionEventType.Updated => (SymbolRegular.Settings24, "TextFillColorSecondaryBrush"),
        GameTimer.Models.SessionEventType.Expired => (SymbolRegular.LockClosed24, "SystemFillColorCautionBrush"),
        GameTimer.Models.SessionEventType.Closed or GameTimer.Models.SessionEventType.Tampered =>
            (SymbolRegular.Warning24, "SystemFillColorCriticalBrush"),
        _ => (SymbolRegular.Edit24, "TextFillColorSecondaryBrush")
    };

    private static (string Text, string Who) DescribeEvent(GameTimer.Models.SessionEvent e)
    {
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
            GameTimer.Models.SessionEventType.Updated => Loc.Get("Ev_Updated"),
            GameTimer.Models.SessionEventType.ScheduleSkipped => Loc.Get("Ev_ScheduleSkipped"),
            GameTimer.Models.SessionEventType.ScheduleRestored => Loc.Get("Ev_ScheduleRestored"),
            _ => e.Type.ToString()
        };
        string who = e.Source switch
        {
            GameTimer.Models.ActionSource.Pc => Loc.Get("Who_Pc"),
            GameTimer.Models.ActionSource.Phone => e.Device ?? Loc.Get("Who_Phone"),
            _ => Loc.Get("Who_System")
        };
        return (text, who);
    }

    private static string FormatDuration(int? minutes)
    {
        int m = minutes ?? 0;
        return m >= 60 ? $"{m / 60}:{m % 60:D2}" : $"{m} {Loc.Get("Min")}";
    }

    private TimerState _lastShownState = TimerState.NotStarted;

    private void OnTimerStateChanged(TimerState state)
    {
        switch (state)
        {
            case TimerState.Running:
                ValidationText.Visibility = Visibility.Collapsed;
                if (_lastShownState != TimerState.Running) _ringStartedAt = 0; // a new (or resumed) timer: the ring starts full
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
        if (state != TimerState.Running && _lastShownState == TimerState.Running) FocusTimeInput(); // back to the start form
        _lastShownState = state;
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
        UpdateMarkerPreview();
        SaveSettings();
    }

    private void Opacity_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isLoading || OpacityLabel == null) return;
        var opacity = (int)OpacitySlider.Value;
        OpacityLabel.Text = $"{opacity}%";
        _settings.Opacity = opacity;
        _overlay?.ApplySettings(_settings);
        UpdateMarkerPreview();
        SaveSettings();
    }

    private void DisplaySetting_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShowBackground = ShowBackgroundCheck.IsChecked == true;
        _overlay?.ApplySettings(_settings);
        UpdateMarkerPreview();
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
        if (!GreenHex.IsKeyboardFocusWithin) GreenHex.Text = _settings.ColorGreen.ToUpperInvariant();
        if (!YellowHex.IsKeyboardFocusWithin) YellowHex.Text = _settings.ColorYellow.ToUpperInvariant();
        if (!RedHex.IsKeyboardFocusWithin) RedHex.Text = _settings.ColorRed.ToUpperInvariant();
        BuildPalette(GreenPalette, 'g', _settings.ColorGreen);
        BuildPalette(YellowPalette, 'y', _settings.ColorYellow);
        BuildPalette(RedPalette, 'r', _settings.ColorRed);
        UpdateMarkerPreview();
    }

    // Zone colours from a row of swatches (the same palette as the phone), or any #RRGGBB typed next to it
    private static readonly string[] Palette =
        { "#4CAF50", "#8BC34A", "#2196F3", "#9C27B0", "#FFEB3B", "#FFC107", "#FF9800", "#F44336", "#E91E63", "#FFFFFF" };

    private void BuildPalette(WrapPanel panel, char zone, string current)
    {
        panel.Children.Clear();
        foreach (var hex in Palette)
        {
            var swatch = new System.Windows.Controls.RadioButton
            {
                Style = (Style)FindResource("SwatchRadio"), GroupName = "swatch-" + zone, ToolTip = hex,
                Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex)),
                IsChecked = hex.Equals(current, StringComparison.OrdinalIgnoreCase),
            };
            System.Windows.Automation.AutomationProperties.SetName(swatch, hex);
            // after IsChecked above, so building doesn't apply; deferred: applying rebuilds this panel
            swatch.Checked += (_, _) => Dispatcher.BeginInvoke(() => SetZoneColor(zone, hex));
            panel.Children.Add(swatch);
        }
    }

    private void SetZoneColor(char zone, string hex)
    {
        if (_isLoading) return;
        string current = zone == 'g' ? _settings.ColorGreen : zone == 'y' ? _settings.ColorYellow : _settings.ColorRed;
        if (hex.Equals(current, StringComparison.OrdinalIgnoreCase)) return;
        if (zone == 'g') _settings.ColorGreen = hex;
        else if (zone == 'y') _settings.ColorYellow = hex;
        else _settings.ColorRed = hex;
        // The box shows the new colour even while focused, so leaving it later doesn't put the old one back
        (zone == 'g' ? GreenHex : zone == 'y' ? YellowHex : RedHex).Text = hex.ToUpperInvariant();
        UpdateColorPreviews();
        _overlay?.ApplySettings(_settings);
        SaveSettings();
    }

    private void ColorHex_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        ApplyHex(sender);
        e.Handled = true;
    }

    private void ColorHex_LostFocus(object sender, KeyboardFocusChangedEventArgs e) => ApplyHex(sender);

    private void ApplyHex(object sender)
    {
        if (sender is not System.Windows.Controls.TextBox box) return;
        char zone = box == GreenHex ? 'g' : box == YellowHex ? 'y' : 'r';
        var text = box.Text.Trim().TrimStart('#');
        if (System.Text.RegularExpressions.Regex.IsMatch(text, "^[0-9A-Fa-f]{6}$"))
        {
            var hex = "#" + text.ToUpperInvariant();
            string old = zone == 'g' ? _settings.ColorGreen : zone == 'y' ? _settings.ColorYellow : _settings.ColorRed;
            if (!hex.Equals(old, StringComparison.OrdinalIgnoreCase)) SetZoneColor(zone, hex);
            else box.Text = hex;
        }
        else box.Text = (zone == 'g' ? _settings.ColorGreen : zone == 'y' ? _settings.ColorYellow : _settings.ColorRed).ToUpperInvariant();
    }

    private void PreviewZone_Changed(object sender, RoutedEventArgs e) => UpdateMarkerPreview();

    /// <summary>The marker on the small screen looks like the countdown: zone colour, size, opacity, background.</summary>
    private void UpdateMarkerPreview()
    {
        // Checked handlers fire inside InitializeComponent, before every element (and the settings) exist
        if (_isLoading || _settings == null || PreviewText == null || PositionMarker == null || PreviewWarning == null || PreviewAlert == null) return;
        string hex = PreviewAlert.IsChecked == true ? _settings.ColorRed
            : PreviewWarning.IsChecked == true ? _settings.ColorYellow : _settings.ColorGreen;
        try { PreviewText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex)); }
        catch { PreviewText.Foreground = Brushes.White; }
        PreviewText.Text = PreviewAlert.IsChecked == true ? "02:00" : PreviewWarning.IsChecked == true ? "12:00" : "1:30:00";
        PreviewText.FontSize = Math.Max(9, _settings.FontSize * 0.35); // the preview screen is a small screen
        PositionMarker.Opacity = _settings.Opacity / 100.0;
        PositionMarker.Background = _settings.ShowBackground ? new SolidColorBrush(Color.FromArgb(100, 0, 0, 0)) : Brushes.Transparent;
        // The marker's size changed: keep it where the overlay is
        Dispatcher.BeginInvoke(PlaceMarker, System.Windows.Threading.DispatcherPriority.Loaded);
    }

    private void PlaceMarker()
    {
        if (_isDragging) return;
        var rangeX = PositionCanvas.ActualWidth - PositionMarker.ActualWidth;
        var rangeY = PositionCanvas.ActualHeight - PositionMarker.ActualHeight;
        if (rangeX > 0) Canvas.SetLeft(PositionMarker, _settings.OverlayX * rangeX);
        if (rangeY > 0) Canvas.SetTop(PositionMarker, _settings.OverlayY * rangeY);
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
            // Turning it off always asks: it frees the child the most (or cuts off the parent's phones)
            if (!await RequireAuth(fresh: !enable))
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
            // Turning it off always asks: it frees the child the most (or cuts off the parent's phones)
            if (!await RequireAuth(fresh: !enable))
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

    // "Control from anywhere" (protocol §10): Windows Hello both ways — on opens the PC to the internet
    // (through the relay), off would let the child cut a parent who is away from home
    private async void RemoteAccessToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        if (_isRemoteBusy)
        {
            // The other toggle is busy (or this one is reverting): show the real value
            if ((RemoteAccessToggle.IsChecked == true) != _settings.RemoteAccess) RemoteAccessToggle.IsChecked = _settings.RemoteAccess;
            return;
        }

        bool enable = RemoteAccessToggle.IsChecked == true;
        if (enable == _settings.RemoteAccess) return;

        _isRemoteBusy = true; // reverting IsChecked below re-enters this handler
        RemoteAccessToggle.IsEnabled = false;
        try
        {
            // Turning it off always asks: it frees the child the most (or cuts off the parent's phones)
            if (!await RequireAuth(fresh: !enable))
            {
                RemoteAccessToggle.IsChecked = _settings.RemoteAccess;
                return;
            }
            await _remote.SetRemoteAccessAsync(enable);
        }
        finally
        {
            _isRemoteBusy = false;
            RemoteAccessToggle.IsEnabled = true;
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

        new ConnectPhoneDialog(_pairing, _remote.Agent, _pairingUi, address.ToString(), port) { Owner = this }.ShowDialog();
    }

    /// <summary>Protocol v2 pairing: the owner types the phone's 6-digit code, then Windows Hello (§8.2).</summary>
    private Task<string?> AskPairingCodeAsync(string hubName, CancellationToken cancel)
    {
        Window owner = OwnedWindows.OfType<ConnectPhoneDialog>().FirstOrDefault() ?? (Window)this;
        PairingCodeDialog? dialog = null;
        dialog = new PairingCodeDialog(hubName, () => RequireAuth(owner: dialog, fresh: true)) { Owner = owner }; // a new parent: always asked
        var registration = cancel.Register(() => Dispatcher.BeginInvoke(dialog.Close));
        dialog.Closed += (_, _) => registration.Dispose();
        dialog.Show();
        dialog.Activate();
        return dialog.Result;
    }

    private async void RevokePeer_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: PeerItem peer }) return;
        if (!await RequireAuth(fresh: true)) return; // cutting a parent off: always asked
        _remote.Agent.RevokeByOwner(peer.Id); // closes its sessions at once (bye revoked)
    }

    private async void TogglePeerRole_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: PeerItem peer }) return;
        if (!await RequireAuth(fresh: true)) return;
        _remote.Agent.SetRoleByOwner(peer.Id,
            peer.IsAdmin ? GameTimer.Protocol.Agent.PeerRole.Parent : GameTimer.Protocol.Agent.PeerRole.Admin);
    }

    /// <summary>A paired v2 parent app, as the Settings list shows it.</summary>
    public sealed record PeerItem(string Id, string Name, string Subtitle, bool IsAdmin, string RoleButtonTip)
    {
        public string RevokeTip => Loc.Get("Delete");
    }

    private async void RevokeDevice_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: PairedDevice device }) return;
        if (!await RequireAuth(fresh: true)) return;
        _devices.Revoke(device.Id); // its next request gets 401
    }

    private void RefreshDevicesList()
    {
        DevicesList.ItemsSource = null;
        DevicesList.ItemsSource = _devices.Devices;

        var peers = _remote.Agent.Peers.Select(p =>
        {
            bool admin = p.Role == GameTimer.Protocol.Agent.PeerRole.Admin;
            var role = Loc.Get(admin ? "PeerRole_Admin" : "PeerRole_Parent");
            var seen = p.LastSeen is { } t ? " · " + string.Format(Loc.Get("Device_LastSeen"), t.ToLocalTime().ToString("g")) : "";
            return new PeerItem(p.Id, p.Name, string.Format(Loc.Get("Peer_App"), role) + seen, admin,
                Loc.Get(admin ? "Peer_MakeParent" : "Peer_MakeAdmin"));
        }).ToList();
        PeersList.ItemsSource = peers;
        NoDevicesText.Visibility = _devices.Devices.Count == 0 && peers.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateRemoteStatus()
    {
        ConnectPhoneButton.IsEnabled = _remote.IsRunning;
        // A disabled button says why: phone control has to be on first
        ConnectPhoneDesc.Text = Loc.Get(_remote.IsRunning ? "ConnectPhone_Desc" : "ConnectPhone_NeedsControl");

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

        RemoteAccessText.Text = !_settings.RemoteAccess ? Loc.Get("RemoteAccess_Hint")
            : _remote.RelayStatus switch
            {
                GameTimer.Protocol.Relay.RelayStatus.Connected => Loc.Get("RemoteAccess_Connected"),
                GameTimer.Protocol.Relay.RelayStatus.Connecting => Loc.Get("RemoteAccess_Connecting"),
                _ => Loc.Get("RemoteAccess_Waiting"),
            };
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

    // Exiting needs the parent while the app is enforcing something: a running timer or the daily schedule
    private bool ExitNeedsAuth => _timer.IsRunning || _settings.ScheduleEnabled;

    private async void TrayExit()
    {
        if (ExitNeedsAuth && !await RequireAuth(fresh: true)) return;
        if (_timer.IsRunning) _timer.Stop();
        CloseApp();
    }

    private bool _setupExitPending;

    /// <summary>
    /// The installer or uninstaller asked the app to close (named events, see App / SetupSignals). Same
    /// gate as the tray exit — a child sending the signal can't close the app without the parent.
    /// Update: a running timer is kept, saved and resumed by the new version.
    /// Uninstall: a running timer is stopped and its saved state cleared, so a later reinstall doesn't
    /// start with an "expired" timer and lock the PC.
    /// </summary>
    public async void ExitForSetup(bool uninstall)
    {
        if (_setupExitPending) return;
        _setupExitPending = true;
        try
        {
            BringToFront();
            bool asked = ExitNeedsAuth;
            if (asked && !await RequireAuth(uninstall ? "AuthPrompt_Uninstall" : "AuthPrompt_Update", fresh: true))
                return; // Setup offers Retry
            if (!SetupSignals.SetupIsWaiting())
            {
                // Setup gave up while the prompt was open, or the signal didn't come from Setup at all
                DiagnosticLog.Write("setup exit ignored: no installer is waiting");
                return;
            }
            if (uninstall)
            {
                if (_timer.IsRunning) _timer.Stop();
            }
            else
            {
                _timer.PrepareForUpdate(parentConfirmed: asked);
            }
            CloseApp();
        }
        finally
        {
            _setupExitPending = false;
        }
    }

    private void CloseApp()
    {
        HideOverlay();
        if (_trayIcon != null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            _trayIcon = null;
        }
        System.Windows.Application.Current.Shutdown();
    }

    #endregion

    #region Window Close

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        e.Cancel = true;
        SetUnlocked(false); // require Windows Hello again next time the window opens
        Hide();
        if (_trayIcon != null) _trayIcon.Visible = true;
    }

    #endregion

    #region Schedule

    private static readonly int[] WeekdayDays = { 1, 2, 3, 4, 5 };
    private static readonly int[] WeekendDays = { 6, 0 };
    private const int LimitStep = 15;
    private const int DefaultLimit = 120;

    // The week overview: one row per day (a radio button: click or arrow keys select the day for the
    // editor). The rows are identical, so they are built once in code rather than 7x in XAML.
    private void BuildScheduleRows()
    {
        if (_dayRow[ScheduleDayOrder[0]] != null) return; // already built

        foreach (var dow in ScheduleDayOrder)
        {
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(112) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(176) });

            var name = new System.Windows.Controls.TextBlock
            {
                VerticalAlignment = System.Windows.VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis
            };
            _dayName[dow] = name;

            // The day as a bar over 0-24 h: before the allowed hours | the allowed hours | after them
            var bar = new Grid { Height = 8, VerticalAlignment = System.Windows.VerticalAlignment.Center };
            var cols = new[] { new ColumnDefinition(), new ColumnDefinition(), new ColumnDefinition() };
            foreach (var c in cols) bar.ColumnDefinitions.Add(c);
            var track = new Border { CornerRadius = new CornerRadius(4) };
            track.SetResourceReference(Border.BackgroundProperty, "ControlStrongFillColorDisabledBrush");
            Grid.SetColumnSpan(track, 3);
            var fill = new Border { CornerRadius = new CornerRadius(4) };
            fill.SetResourceReference(Border.BackgroundProperty, "AccentFillColorDefaultBrush");
            Grid.SetColumn(fill, 1);
            bar.Children.Add(track);
            bar.Children.Add(fill);
            Grid.SetColumn(bar, 1);
            _dayBarCols[dow] = cols;
            _dayBarFill[dow] = fill;

            var summary = new System.Windows.Controls.TextBlock
            {
                VerticalAlignment = System.Windows.VerticalAlignment.Center,
                HorizontalAlignment = System.Windows.HorizontalAlignment.Right,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(12, 0, 0, 0)
            };
            Grid.SetColumn(summary, 2);
            _daySummary[dow] = summary;

            grid.Children.Add(name);
            grid.Children.Add(bar);
            grid.Children.Add(summary);

            var row = new System.Windows.Controls.RadioButton
            {
                GroupName = "ScheduleDay",
                Content = grid,
                Tag = dow,
                Style = (Style)FindResource("DayRow")
            };
            row.Checked += ScheduleDayRow_Checked;
            // Arrow keys move the focus between the rows: the focused row is the selected day
            row.GotKeyboardFocus += (_, _) => row.IsChecked = true;
            _dayRow[dow] = row;
            ScheduleDaysPanel.Children.Add(row);
        }
    }

    private static System.Globalization.CultureInfo UiCulture()
    {
        try { return System.Globalization.CultureInfo.GetCultureInfo(Loc.CurrentLanguage); }
        catch (System.Globalization.CultureNotFoundException) { return System.Globalization.CultureInfo.CurrentUICulture; }
    }

    private static string Capitalize(string s) => s.Length > 0 ? char.ToUpper(s[0]) + s.Substring(1) : s;

    private static string DayName(int dow) => Capitalize(UiCulture().DateTimeFormat.GetDayName((DayOfWeek)dow));

    // "45 min", "2 h", "1 h 30 min"
    private static string LimitText(int minutes)
    {
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return $"{m} {Loc.Get("Min")}";
        return m == 0 ? $"{h} {Loc.Get("HoursShort")}" : $"{h} {Loc.Get("HoursShort")} {m} {Loc.Get("Min")}";
    }

    private void EnsureWeek()
    {
        if (_settings.Week == null || _settings.Week.Count != 7)
            _settings.Week = AppSettings.DefaultWeek();
    }

    // The rows show what is saved (not what is being typed in the editor)
    private void UpdateWeekOverview()
    {
        _shownDate = DateTime.Today;
        if (_dayRow[0] == null) return;
        EnsureWeek();
        int today = (int)DateTime.Now.DayOfWeek;
        for (int d = 0; d < 7; d++)
        {
            var day = _settings.Week[d];
            int from = Math.Clamp(day.WindowStartMinutes, 0, 24 * 60);
            int to = Math.Clamp(day.WindowEndMinutes, 0, 24 * 60);
            // A reversed window (from an older version or a hand-edited file): the phone shows "Check" too
            bool bad = day.Allowed && to <= from;
            bool show = day.Allowed && !bad;

            var name = DayName(d);
            _dayName[d].Text = name;
            _dayName[d].FontWeight = d == today ? FontWeights.SemiBold : FontWeights.Normal;

            var cols = _dayBarCols[d];
            cols[0].Width = new GridLength(show ? from : 1, GridUnitType.Star);
            cols[1].Width = new GridLength(show ? to - from : 0, GridUnitType.Star);
            cols[2].Width = new GridLength(show ? 24 * 60 - to : 0, GridUnitType.Star);
            _dayBarFill[d].Opacity = _settings.ScheduleEnabled ? 1.0 : 0.4;

            string text, brush;
            if (!day.Allowed) { text = Loc.Get("Sched_Off"); brush = "TextFillColorSecondaryBrush"; }
            else if (bad) { text = Loc.Get("Sched_Check"); brush = "SystemFillColorCriticalBrush"; }
            else
            {
                var limit = day.LimitMinutes > 0 ? LimitText(day.LimitMinutes) : Loc.Get("Sched_NoLimit");
                text = from == 0 && to == 24 * 60 ? limit : $"{FormatClock(from)}–{FormatClock(to)} · {limit}";
                brush = "TextFillColorPrimaryBrush";
            }
            _daySummary[d].Text = text;
            _daySummary[d].SetResourceReference(System.Windows.Controls.TextBlock.ForegroundProperty, brush);
            System.Windows.Automation.AutomationProperties.SetName(_dayRow[d],
                d == today ? $"{name} ({Loc.Get("Sched_Today")}): {text}" : $"{name}: {text}");
        }
    }

    private void ApplyScheduleLabels()
    {
        ScheduleLabel.Text = Loc.Get("Tab_Schedule");
        ScheduleIntro.Text = Loc.Get("Sched_Intro");
        ScheduleEnableTitle.Text = Loc.Get("Sched_Enable");
        ScheduleHint.Text = Loc.Get("Sched_Hint");
        ScheduleUnlockButton.Content = Loc.Get(IsUnlocked ? "Sched_Lock" : "Sched_Unlock");

        SchedAllowedLabel.Text = Loc.Get("Sched_Allowed");
        SchedHoursLabel.Text = Loc.Get("Sched_Hours");
        SchedLimitLabel.Text = Loc.Get("Sched_DailyLimit");
        ScheduleLimitUnit.Text = Loc.Get("Min");
        SchedCopyLabel.Text = Loc.Get("Sched_CopyTo");
        ScheduleCopyWeekdays.Content = Loc.Get("Sched_Weekdays");
        ScheduleCopyWeekend.Content = Loc.Get("Sched_Weekend");
        ScheduleCopyEveryDay.Content = Loc.Get("Sched_EveryDay");
        ScheduleCopied.Text = Loc.Get("Sched_Copied");
        System.Windows.Automation.AutomationProperties.SetName(ScheduleDayFrom, $"{Loc.Get("Sched_Hours")}: {Loc.Get("Sched_From")}");
        System.Windows.Automation.AutomationProperties.SetName(ScheduleDayTo, $"{Loc.Get("Sched_Hours")}: {Loc.Get("Sched_To")}");
        System.Windows.Automation.AutomationProperties.SetName(ScheduleLimitMinus, $"−{LimitStep} {Loc.Get("Min")}");
        System.Windows.Automation.AutomationProperties.SetName(ScheduleLimitPlus, $"+{LimitStep} {Loc.Get("Min")}");

        LoadDayEditor(); // the day name in the title and the hints
        UpdateScheduleStatus(); // also the week overview
    }

    private void LoadScheduleToUi()
    {
        EnsureWeek();
        var was = _scheduleUiLoading;
        _scheduleUiLoading = true;
        try
        {
            ScheduleEnabledToggle.IsChecked = _settings.ScheduleEnabled;
            if (_dayRow[_selectedDay] is { } row)
            {
                row.IsChecked = true;
            }
        }
        finally
        {
            _scheduleUiLoading = was;
        }
        LoadDayEditor();
        UpdateScheduleStatus();
    }

    private void ScheduleDayRow_Checked(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: int d }) return;
        _selectedDay = d;
        // Only the selected day is a tab stop: Tab into the week lands on it (arrow keys reach the others)
        foreach (var r in _dayRow) if (r != null) r.IsTabStop = r == sender;
        ScheduleCopied.Visibility = Visibility.Collapsed;
        LoadDayEditor();
    }

    // The editor of the selected day shows what is saved for it
    private void LoadDayEditor()
    {
        if (ScheduleDayEditor == null || _settings == null) return;
        EnsureWeek();
        var day = _settings.Week[_selectedDay];
        var was = _scheduleUiLoading;
        _scheduleUiLoading = true;
        ScheduleCopied.Visibility = Visibility.Collapsed;
        try
        {
            ScheduleDayTitle.Text = DayName(_selectedDay);
            ScheduleDayAllowed.IsChecked = day.Allowed;
            ScheduleDayFrom.Text = FormatClock(day.WindowStartMinutes);
            ScheduleDayTo.Text = FormatClock(day.WindowEndMinutes);
            ScheduleDayLimitOn.IsChecked = day.LimitMinutes > 0;
            ScheduleDayLimit.Text = (day.LimitMinutes > 0 ? Math.Min(day.LimitMinutes, 24 * 60) : DefaultLimit).ToString();
        }
        finally
        {
            _scheduleUiLoading = was;
        }
        UpdateDayEditorState(announce: false);
    }

    // What the editor shows around the values: the details only for an allowed day, the limit row only
    // when there is a limit, the window error and the limit hint
    private void UpdateDayEditorState(bool announce = true)
    {
        bool allowed = ScheduleDayAllowed.IsChecked == true;
        ScheduleDayDetails.Visibility = allowed ? Visibility.Visible : Visibility.Collapsed;

        var from = TryParseClock(ScheduleDayFrom.Text, endOfDay: false);
        var to = TryParseClock(ScheduleDayTo.Text, endOfDay: true);
        string? windowError = null;
        if (from == null || to == null) windowError = Loc.Get("Sched_TimeFormat");
        else if (to <= from) windowError = Loc.Get("Sched_BadWindow");
        ScheduleWindowError.Visibility = windowError != null ? Visibility.Visible : Visibility.Collapsed;
        SetLiveText(ScheduleWindowError, windowError ?? "", announce);

        bool limitOn = ScheduleDayLimitOn.IsChecked == true;
        ScheduleLimitRow.Visibility = limitOn ? Visibility.Visible : Visibility.Collapsed;
        var limit = TryParseLimit(ScheduleDayLimit.Text);
        string hint, hintBrush = "TextFillColorSecondaryBrush";
        if (!limitOn) hint = Loc.Get("Sched_NoLimitHint");
        else if (limit == null) { hint = Loc.Get("Sched_LimitRange"); hintBrush = "SystemFillColorCriticalBrush"; }
        else if (from is int f && to is int t && t > f && limit > t - f) hint = Loc.Get("Sched_LimitOver");
        else hint = Loc.Get("Sched_LimitHint");
        SetLiveText(ScheduleLimitHint, hint, announce);
        ScheduleLimitHint.SetResourceReference(System.Windows.Controls.TextBlock.ForegroundProperty, hintBrush);
        ScheduleLimitMinus.IsEnabled = limit is not int lo || lo > LimitStep;
        ScheduleLimitPlus.IsEnabled = limit is not int hi || hi < 24 * 60;

        // Copying copies what is saved, so not while the editor shows something that is not saved
        bool savedMatches = !allowed || (windowError == null && (!limitOn || limit != null));
        ScheduleCopyWeekdays.IsEnabled = ScheduleCopyWeekend.IsEnabled = ScheduleCopyEveryDay.IsEnabled = savedMatches;
    }

    // Switches apply at once; the text boxes apply when they are left or on Enter, not on each keystroke
    // (typing "90" would otherwise save a limit of 9 first, and that could end the play at once)
    private void ScheduleDay_Changed(object sender, RoutedEventArgs e) => ApplyDayFromUi();

    private void ScheduleDay_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_scheduleUiLoading || ScheduleDayEditor == null) return;
        ScheduleCopied.Visibility = Visibility.Collapsed;
        UpdateDayEditorState();
    }

    // A hint that changes while the parent types: screen readers announce it (LiveSetting is set in XAML)
    private static void SetLiveText(System.Windows.Controls.TextBlock block, string text, bool announce)
    {
        if (block.Text == text) return;
        block.Text = text;
        if (!announce || text.Length == 0) return;
        var peer = System.Windows.Automation.Peers.UIElementAutomationPeer.FromElement(block)
                   ?? System.Windows.Automation.Peers.UIElementAutomationPeer.CreatePeerForElement(block);
        peer?.RaiseAutomationEvent(System.Windows.Automation.Peers.AutomationEvents.LiveRegionChanged);
    }

    private void CommitPendingScheduleText()
    {
        if (ScheduleDayEditor == null) return;
        if (ScheduleDayFrom.IsKeyboardFocusWithin || ScheduleDayTo.IsKeyboardFocusWithin || ScheduleDayLimit.IsKeyboardFocusWithin
            || FocusManager.GetFocusedElement(this) is DependencyObject f
               && (f == ScheduleDayFrom || f == ScheduleDayTo || f == ScheduleDayLimit))
            ApplyDayFromUi();
    }

    private void ScheduleDay_LostFocus(object sender, RoutedEventArgs e) => ApplyDayFromUi();

    private void ScheduleDay_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == Key.Enter) ApplyDayFromUi();
    }

    // Every change applies at once (under the unlock), like the other settings
    private void ApplyDayFromUi()
    {
        if (_scheduleUiLoading || ScheduleDayEditor == null) return;
        ScheduleCopied.Visibility = Visibility.Collapsed;
        UpdateDayEditorState();
        if (_isLoading || !IsUnlocked) return;
        EnsureWeek();

        var day = _settings.Week[_selectedDay];
        var before = (day.Allowed, day.LimitMinutes, day.WindowStartMinutes, day.WindowEndMinutes);
        day.Allowed = ScheduleDayAllowed.IsChecked == true;
        // An unreadable or reversed window is not saved: the saved one stays until the window is fixed
        // (the evaluator would take a reversed window as the whole day)
        if (TryParseClock(ScheduleDayFrom.Text, endOfDay: false) is int from
            && TryParseClock(ScheduleDayTo.Text, endOfDay: true) is int to && to > from)
        {
            day.WindowStartMinutes = from;
            day.WindowEndMinutes = to;
        }
        if (ScheduleDayLimitOn.IsChecked != true) day.LimitMinutes = 0;
        else if (TryParseLimit(ScheduleDayLimit.Text) is int limit) day.LimitMinutes = limit;
        if (before == (day.Allowed, day.LimitMinutes, day.WindowStartMinutes, day.WindowEndMinutes)) return;
        SaveSchedule();
    }

    private void ScheduleLimitStep_Click(object sender, RoutedEventArgs e)
    {
        int current = TryParseLimit(ScheduleDayLimit.Text) ?? DefaultLimit;
        // Steps land on the 15-minute grid: 50 goes up to 60 and down to 45
        int next = sender == ScheduleLimitPlus
            ? (current / LimitStep + 1) * LimitStep
            : ((current + LimitStep - 1) / LimitStep - 1) * LimitStep;
        ScheduleDayLimit.Text = Math.Clamp(next, LimitStep, 24 * 60).ToString();
        ApplyDayFromUi();
    }

    private void ScheduleCopy_Click(object sender, RoutedEventArgs e)
    {
        if (_isLoading || !IsUnlocked) return;
        EnsureWeek();
        var tag = (sender as FrameworkElement)?.Tag as string;
        int[] targets = tag switch
        {
            "weekdays" => WeekdayDays,
            "weekend" => WeekendDays,
            _ => ScheduleDayOrder
        };
        var src = _settings.Week[_selectedDay];
        foreach (var d in targets)
        {
            if (d == _selectedDay) continue;
            var t = _settings.Week[d];
            t.Allowed = src.Allowed;
            t.LimitMinutes = src.LimitMinutes;
            t.WindowStartMinutes = src.WindowStartMinutes;
            t.WindowEndMinutes = src.WindowEndMinutes;
        }
        SaveSchedule();
        ScheduleCopied.Visibility = Visibility.Visible;
    }

    // Under the unlock like every schedule edit (a day edit can free as much as switching it off)
    private void ScheduleEnabled_Changed(object sender, RoutedEventArgs e) => ApplyScheduleFromUi();

    private void ApplyScheduleFromUi()
    {
        if (_isLoading || _scheduleUiLoading || !IsUnlocked) return;
        EnsureWeek();
        _settings.ScheduleEnabled = ScheduleEnabledToggle.IsChecked == true;
        SaveSchedule();
    }

    private void SaveSchedule()
    {
        SaveSettings();
        _timer.ReevaluateSchedule();
        UpdateScheduleStatus();
    }

    // The schedule is editable while the app is unlocked (the parent lock), like every other protected change
    private async void ScheduleUnlock_Click(object sender, RoutedEventArgs e)
    {
        if (IsUnlocked) { SetUnlocked(false); return; }
        await RequireAuth();
    }

    private void UpdateScheduleLock()
    {
        if (ScheduleDayEditor == null) return;
        // Locked again: the editor shows what is saved
        if (!IsUnlocked && ScheduleDayEditor.IsEnabled) LoadScheduleToUi();
        ScheduleEditPanel.IsEnabled = IsUnlocked;
        ScheduleDayEditor.IsEnabled = IsUnlocked;
        ScheduleUnlockButton.Content = Loc.Get(IsUnlocked ? "Sched_Lock" : "Sched_Unlock");
        ScheduleUnlockButton.Icon = new SymbolIcon { Symbol = IsUnlocked ? SymbolRegular.LockClosed24 : SymbolRegular.LockOpen24 };
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
            case ScheduleStatus.SkippedToday:
                msg = Loc.Get("Sched_St_Skipped");
                break;
            default:
                msg = "";
                break;
        }
        ScheduleStatusBar.Message = msg;
        UpdateStatusLines(); // the line next to the ring on the Status page
        ScheduleStatusBar.Severity = sev;
        UpdateWeekOverview(); // the saved week, and which day is today
        UpdateButtonStates(_timer.IsRunning); // "Lift restrictions for today" depends on the schedule
    }

    // "HH:MM" (24:00 for end-of-day); minutes from midnight
    private static string FormatClock(int minutes)
    {
        minutes = Math.Clamp(minutes, 0, 24 * 60);
        return $"{minutes / 60:D2}:{minutes % 60:D2}";
    }

    // Parses "H", "H:MM" or "HH:MM" to minutes from midnight (0..1440); null when it is not a time of day.
    // For the end of the allowed hours 00:00 means midnight at the end of the day (24:00).
    private static int? TryParseClock(string text, bool endOfDay)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        var parts = text.Trim().Split(':');
        if (parts.Length > 2) return null;
        if (!int.TryParse(parts[0], System.Globalization.NumberStyles.None, null, out var h)) return null;
        int m = 0;
        if (parts.Length == 2 && (parts[1].Length != 2
            || !int.TryParse(parts[1], System.Globalization.NumberStyles.None, null, out m))) return null;
        if (h > 24 || m > 59 || (h == 24 && m != 0)) return null;
        int minutes = h * 60 + m;
        return endOfDay && minutes == 0 ? 24 * 60 : minutes;
    }

    // A daily limit in minutes: 1..1440, or null
    private static int? TryParseLimit(string text) =>
        int.TryParse(text?.Trim(), System.Globalization.NumberStyles.None, null, out var m) && m is >= 1 and <= 24 * 60 ? m : null;

    #endregion

    #region Authentication

    // ---- The parent lock: one Windows Hello unlock covers the parent's changes for a while ----

    /// <summary>How long one unlock lasts at most (owner's choice: 5 minutes); not extended by use.</summary>
    private static readonly TimeSpan UnlockFor = TimeSpan.FromMinutes(5);

    // Monotonic (TickCount64): changing the system clock can't stretch an unlock. 0 = locked.
    private long _unlockedUntil;
    private System.Windows.Threading.DispatcherTimer? _lockTimer;

    private bool _appActive = true;
    private bool _verifying;

    private bool IsUnlocked => _unlockedUntil != 0 && Environment.TickCount64 < _unlockedUntil;

    private void SetUnlocked(bool on)
    {
        // Leaving the app or hiding the window does not take the focus from a text box (no LostFocus):
        // what the parent typed is saved while still unlocked
        if (!on && IsUnlocked) CommitPendingScheduleText();
        _unlockedUntil = on ? Environment.TickCount64 + (long)UnlockFor.TotalMilliseconds : 0;
        if (on && _lockTimer == null)
        {
            _lockTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            _lockTimer.Tick += (_, _) =>
            {
                if (_unlockedUntil != 0 && !IsUnlocked) SetUnlocked(false);
                else UpdateLockUi();
            };
        }
        if (on) _lockTimer!.Start(); else _lockTimer?.Stop();
        UpdateLockUi();
        UpdateScheduleLock();
    }

    private void UpdateLockUi()
    {
        if (LockButton == null) return;
        bool on = IsUnlocked;
        LockIcon.Symbol = on ? Wpf.Ui.Controls.SymbolRegular.LockOpen24 : Wpf.Ui.Controls.SymbolRegular.LockClosed24;
        LockTitle.Text = Loc.Get(on ? "Lock_Unlocked" : "Lock_Locked");
        if (on)
        {
            var left = TimeSpan.FromMilliseconds(Math.Max(0, _unlockedUntil - Environment.TickCount64));
            LockSubtitle.Text = string.Format(Loc.Get("Lock_UnlockedFor"), $"{(int)left.TotalMinutes}:{left.Seconds:00}");
        }
        else LockSubtitle.Text = Loc.Get("Lock_ClickToUnlock");
        LockButton.ToolTip = Loc.Get("Lock_Tooltip");
    }

    private async void LockButton_Click(object sender, RoutedEventArgs e)
    {
        if (IsUnlocked) SetUnlocked(false);
        else await RequireAuth();
    }

    /// <summary>
    /// The parent's check before a change that helps the child. Inside an unlock it passes at once;
    /// otherwise Windows Hello (or the Windows password) unlocks for <see cref="UnlockFor"/> from now
    /// (not extended by use, so a child can't keep it open).
    /// <paramref name="fresh"/> always asks (exit, update, pairing a new phone) and doesn't use the unlock.
    /// </summary>
    private async Task<bool> RequireAuth(string promptKey = "AuthPrompt", Window? owner = null, bool fresh = false)
    {
        if (!fresh && IsUnlocked) return true;
        bool ok;
        _verifying = true;
        try { ok = await VerifyParent(promptKey, owner); }
        finally { _verifying = false; }
        if (ok && !fresh) SetUnlocked(true);
        if (IsUnlocked)
        {
            // The system prompt is another process: if focus went to another app (the game) instead of back
            // here, this action still runs but the unlock doesn't outlive it
            _ = Dispatcher.InvokeAsync(async () =>
            {
                await Task.Delay(1500);
                if (!_appActive) SetUnlocked(false);
            });
        }
        return ok;
    }

    private async Task<bool> VerifyParent(string promptKey, Window? owner)
    {
        try
        {
            var availability = await global::Windows.Security.Credentials.UI.UserConsentVerifier.CheckAvailabilityAsync();
            if (availability == global::Windows.Security.Credentials.UI.UserConsentVerifierAvailability.Available)
            {
                var result = await global::Windows.Security.Credentials.UI.UserConsentVerifier.RequestVerificationAsync(
                    Loc.Get(promptKey));
                return result == global::Windows.Security.Credentials.UI.UserConsentVerificationResult.Verified;
            }
        }
        catch { }

        var dialog = new PasswordDialog { Owner = owner ?? this };
        return dialog.ShowDialog() == true;
    }

    #endregion
}
