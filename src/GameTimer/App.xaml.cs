using System.Threading;
using System.Windows.Media;
using Wpf.Ui.Appearance;
using GameTimer.Localization;
using GameTimer.Services;
using GameTimer.Windows;

namespace GameTimer;

public partial class App : System.Windows.Application
{
    // Names are shared with the installer: see SetupSignals ("Local\" = per Windows session)
    private const string MutexName = SetupSignals.InstanceMutex;
    private const string ShowEventName = SetupSignals.ShowEvent;

    // Kept as fields so the GC never releases them while the app runs
    private Mutex? _instanceMutex;
    private EventWaitHandle? _showEvent;
    private readonly List<EventWaitHandle> _setupEvents = new();

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
        // Protocol v2 (native parent app): identity + paired apps in a DPAPI file; pairing asks the owner here
        var pairingUi = new GameTimer.Services.V2.WindowsPairingUi(ui);
        _remoteServer = new RemoteServer(deviceStore, pairing, ui, timerController, settings, settingsService, sessionLog,
            new GameTimer.Services.V2.AgentFileStore(), pairingUi);

        var agentId = _remoteServer.Agent.AgentId;
        if (settings.V2AgentId != agentId)
        {
            if (settings.V2AgentId != null)
            {
                DiagnosticLog.Write($"v2 identity changed ({settings.V2AgentId} → {agentId}): remote-v2.json lost or replaced");
                sessionLog.Add(new GameTimer.Models.SessionEvent
                {
                    Time = DateTimeOffset.Now,
                    Type = GameTimer.Models.SessionEventType.Tampered,
                    Source = GameTimer.Models.ActionSource.System
                });
            }
            settings.V2AgentId = agentId;
            settingsService.Save(settings);
        }

        var mainWindow = new MainWindow(settingsService, settings, timerController, _remoteServer, deviceStore, pairing, sessionLog, pairingUi);
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

        // Installer listeners: close for an update / uninstall once the parent confirms (MainWindow.ExitForSetup)
        ListenForSetup(SetupSignals.UpdateEvent, () => mainWindow.ExitForSetup(uninstall: false));
        ListenForSetup(SetupSignals.UninstallEvent, () => mainWindow.ExitForSetup(uninstall: true));
    }

    private void ListenForSetup(string eventName, Action onSignal)
    {
        try
        {
            // Create-or-open. If something else squatted the name (other object type, hostile DACL) this
            // throws: then updates fall back to "exit from the tray" — never a crash.
            var signal = new EventWaitHandle(false, EventResetMode.AutoReset, eventName);
            _setupEvents.Add(signal);
            var thread = new Thread(() =>
            {
                while (signal.WaitOne())
                    Dispatcher.BeginInvoke(onSignal);
            })
            { IsBackground = true, Name = "SetupListener" };
            thread.Start();
        }
        catch (Exception ex)
        {
            DiagnosticLog.Write($"setup listener {eventName} unavailable: {ex.Message}");
        }
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

        // The instance mutex is deliberately NOT released here: Windows releases it when the process is
        // torn down, together with unmapping the exe. Setup waits for the mutex to disappear before
        // replacing files, so releasing it early would let Setup hit a still-locked exe ("file in use").
        base.OnExit(e);
    }
}
