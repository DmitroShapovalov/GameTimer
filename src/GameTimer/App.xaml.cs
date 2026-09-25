using System.Threading;
using System.Windows.Media;
using Wpf.Ui.Appearance;
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

    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
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

        var mainWindow = new MainWindow();
        MainWindow = mainWindow;
        mainWindow.Show();

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
        if (_instanceMutex != null)
        {
            _instanceMutex.ReleaseMutex();
            _instanceMutex.Dispose();
        }
        base.OnExit(e);
    }
}
