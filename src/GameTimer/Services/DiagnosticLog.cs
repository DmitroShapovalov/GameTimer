using System.IO;

namespace GameTimer.Services;

/// <summary>
/// Tiny append-only log next to the exe, for diagnosing resume / shutdown issues on the user's PC
/// (there is no console). Best-effort and self-trimming; never throws.
/// </summary>
public static class DiagnosticLog
{
    private static readonly string LogPath = Path.Combine(AppContext.BaseDirectory, "diagnostics.log");
    private static readonly object Gate = new();
    private const long MaxBytes = 256 * 1024;

    public static void Write(string message)
    {
        try
        {
            lock (Gate)
            {
                if (File.Exists(LogPath) && new FileInfo(LogPath).Length > MaxBytes)
                    File.Delete(LogPath);
                File.AppendAllText(LogPath, $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff}  {message}{Environment.NewLine}");
            }
        }
        catch
        {
            // Diagnostics must never affect the app
        }
    }
}
