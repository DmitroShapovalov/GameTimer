using System.Threading;
using System.Windows.Media;
using Wpf.Ui.Appearance;
using GameTimer.Localization;
using GameTimer.Services;
using GameTimer.Windows;

namespace GameTimer;

public partial class App : System.Windows.Application
{
    // "Local\" = per Windows session, so different users on one PC don't block each other
    private const string MutexName = @"Local\SystemMonitor_7F3A2C1E_Instance";
    private const string ShowEventName = @"Local\SystemMonitor_7F3A2C1E_Show";

    // Kept as fields so the GC never releases them while the app runs
    private Mutex? _instanceMutex;
    private EventWaitHandle? _showEvent;

    private RemoteServer? _remoteServer;

    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
        // Elevated helper run (UAC) to set up the firewall rule: do the work and exit,
        // before the single-instance check so it doesn't just wake the running copy
        if (e.Args.Length == 2 && e.Args[0] == FirewallHelper.ConfigureArg
            && int.TryParse(e.Args[1], out var firewallPort) && firewallPort is > 0 and < 65536)
        {
            Shutdown(FirewallHelper.Apply(firewallPort));
            return;
        }

        // The signal event is create-or-open: whichever process gets here first creates it.
        // This closes the race when several copies launch before any of them has a window.
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);

        // Mutex ownership is atomic in the kernel: exactly one process wins,
        // no matter how many are started at the same moment.
        _instanceMutex = new Mutex(true, MutexName, out bool isFirstInstance);

        if (!isFirstInstance)
        {
            // Ask the running copy to show itself, then quit quietly
            _showEvent.Set();
            _instanceMutex.Dispose();
            _instanceMutex = null;
            Shutdown();
            return;
        }

        base.OnStartup(e);
        ApplicationAccentColorManager.Apply(Color.FromRgb(0x00, 0x78, 0xD4));

        // Log unhandled errors and keep the app from showing a Windows crash dialog on the UI thread
        DispatcherUnhandledException += (_, args) =>
        {
            DiagnosticLog.Write("UI exception: " + args.Exception);
            args.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
            DiagnosticLog.Write("fatal exception: " + args.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            DiagnosticLog.Write("task exception: " + args.Exception);
            args.SetObserved();
        };

        // Core services live here, not in the window, so other control surfaces
        // (the phone API from Phase 1) can share the same TimerController.
        var settingsService = new SettingsService();
        var settings = settingsService.Load();
        Loc.SetLanguage(settings.Language);

        // Parental-control resilience: stop the child ending the app from Task Manager (opt-in)
        if (settings.TamperProtection)
            ProcessProtection.DenyTermination();

        var timerStateStore = new TimerStateStore();
        var sessionLog = new SessionLog();
        var usageTracker = new UsageTracker();
        var timerController = new TimerController(settings, timerStateStore, sessionLog, usageTracker);

        var ui = new UiThread(new System.Windows.Threading.DispatcherSynchronizationContext(Dispatcher));
        var deviceStore = new DeviceStore(settings, () => settingsService.Save(settings), ui);
        var pairing = new PairingService();
        _remoteServer = new RemoteServer(deviceStore, pairing, ui, timerController, settings, settingsService, sessionLog);

        var mainWindow = new MainWindow(settingsService, settings, timerController, _remoteServer, deviceStore, pairing, sessionLog);
        MainWindow = mainWindow;
        mainWindow.Show();

        // Resume a timer that was still running when the app last stopped (close, kill, reboot, logoff).
        // After Show(), so the window and overlay react to the resulting state change. Guarded so a
        // resume problem can never stop the app from launching.
        try { timerController.TryResume(); }
        catch (Exception ex) { DiagnosticLog.Write("resume threw: " + ex); }

        // Daily schedule: auto-start the day's allowance at launch, and again each time the session is
        // unlocked (child sits back down). Enforcement while running is handled inside the controller.
        try { timerController.AutoStartIfScheduled(); }
        catch (Exception ex) { DiagnosticLog.Write("schedule autostart threw: " + ex); }

        // Surface tampering detected while loading the protected state files (settings / usage / timer),
        // so the parent sees it in the history on the PC and the phone.
        try
        {
            foreach (var when in ProtectedStore.DrainTamperReports())
                sessionLog.Add(new GameTimer.Models.SessionEvent
                {
                    Time = when,
                    Type = GameTimer.Models.SessionEventType.Tampered,
                    Source = GameTimer.Models.ActionSource.System
                });
        }
        catch (Exception ex) { DiagnosticLog.Write("tamper report threw: " + ex); }
        Microsoft.Win32.SystemEvents.SessionSwitch += (_, args) =>
        {
            if (args.Reason == Microsoft.Win32.SessionSwitchReason.SessionUnlock)
                Dispatcher.BeginInvoke(() =>
                {
                    try { timerController.AutoStartIfScheduled(); }
                    catch (Exception ex) { DiagnosticLog.Write("schedule unlock threw: " + ex); }
                });
        };

        // Background listener: when another copy is launched, bring our window forward
        var listener = new Thread(() =>
        {
            while (_showEvent.WaitOne())
                Dispatcher.BeginInvoke(() => mainWindow.BringToFront());
        })
        { IsBackground = true, Name = "SingleInstanceListener" };
        listener.Start();
    }

    protected override void OnExit(System.Windows.ExitEventArgs e)
    {
        // Stop the embedded server off the UI thread so its shutdown can't wait on the dispatcher
        try
        {
            if (_remoteServer is { IsRunning: true } server)
                Task.Run(server.StopAsync).Wait(TimeSpan.FromSeconds(5));
        }
        catch (Exception ex)
        {
            DiagnosticLog.Write("server stop on exit failed: " + ex);
        }

        if (_instanceMutex != null)
        {
            _instanceMutex.ReleaseMutex();
            _instanceMutex.Dispose();
        }
        base.OnExit(e);
    }
}
