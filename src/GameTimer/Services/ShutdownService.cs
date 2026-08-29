using System.Diagnostics;
using System.Runtime.InteropServices;
using GameTimer.Models;

namespace GameTimer.Services;

public static class ShutdownService
{
    [DllImport("user32.dll")]
    private static extern bool LockWorkStation();

    public static void Execute(TimerAction action)
    {
        switch (action)
        {
            case TimerAction.Shutdown:
                Process.Start(new ProcessStartInfo
                {
                    FileName = "shutdown",
                    Arguments = "/s /t 0",
                    CreateNoWindow = true,
                    UseShellExecute = false
                });
                break;

            case TimerAction.Lock:
                LockWorkStation();
                break;

            case TimerAction.Logoff:
                Process.Start(new ProcessStartInfo
                {
                    FileName = "shutdown",
                    Arguments = "/l",
                    CreateNoWindow = true,
                    UseShellExecute = false
                });
                break;
        }
    }

    public static void CancelShutdown()
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "shutdown",
                Arguments = "/a",
                CreateNoWindow = true,
                UseShellExecute = false
            });
        }
        catch
        {
            // No pending shutdown to cancel
        }
    }
}
