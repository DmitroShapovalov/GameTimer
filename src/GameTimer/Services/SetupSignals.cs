namespace GameTimer.Services;

/// <summary>
/// Kernel object names shared between the app and the installer (<c>installer/setup.iss</c>, [Code]).
/// "Local\" = per Windows session. Keep both sides in sync.
/// </summary>
public static class SetupSignals
{
    /// <summary>Held by the running app for its whole life (single instance; Setup checks it).</summary>
    public const string InstanceMutex = @"Local\SystemMonitor_7F3A2C1E_Instance";

    /// <summary>Set by a second launch: bring the running window to the front.</summary>
    public const string ShowEvent = @"Local\SystemMonitor_7F3A2C1E_Show";

    /// <summary>Set by Setup: close for an update (a running timer is kept and resumed).</summary>
    public const string UpdateEvent = @"Local\SystemMonitor_7F3A2C1E_Exit";

    /// <summary>Set by the uninstaller: close for removal (a running timer is stopped and cleared).</summary>
    public const string UninstallEvent = @"Local\SystemMonitor_7F3A2C1E_Uninstall";

    /// <summary>
    /// Held by Setup / the uninstaller while it waits for the app to close. After the parent confirms,
    /// the app closes only if this still exists — so a late confirmation after Setup gave up, or a
    /// signal sent without any installer, doesn't leave the PC without its timer.
    /// </summary>
    public const string SetupWaitingMutex = @"Local\SystemMonitor_7F3A2C1E_SetupWaiting";

    public static bool SetupIsWaiting()
    {
        try
        {
            Mutex.OpenExisting(SetupWaitingMutex).Dispose();
            return true;
        }
        catch (UnauthorizedAccessException)
        {
            // Created by an elevated Setup ("Run as administrator"): it exists, we just can't open it
            return true;
        }
        catch (WaitHandleCannotBeOpenedException)
        {
            return false;
        }
    }
}

/// <summary>Version of the running build, from &lt;Version&gt; (CI passes the tag), without the "+commit" suffix.</summary>
public static class AppInfo
{
    public static string Version { get; } =
        (typeof(AppInfo).Assembly
             .GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
             .FirstOrDefault() as System.Reflection.AssemblyInformationalVersionAttribute)?
        .InformationalVersion.Split('+')[0] ?? "?";
}
