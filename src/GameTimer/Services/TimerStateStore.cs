using System.IO;
using System.Text.Json;
using GameTimer.Models;

namespace GameTimer.Services;

/// <summary>
/// Persists the running timer (<see cref="PersistedTimer"/>) next to the exe, so it can be resumed
/// after the app is closed, the PC reboots or the user logs off. Best-effort: all failures are swallowed.
/// </summary>
public sealed class TimerStateStore
{
    private static readonly string StatePath = Path.Combine(
        AppContext.BaseDirectory, "timer-state.json");

    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public void Save(PersistedTimer state)
    {
        try
        {
            // ProtectedStore encrypts and writes via a temp file + Move, so a kill mid-write leaves
            // the old file intact, never a partial one.
            ProtectedStore.Write(StatePath, JsonSerializer.Serialize(state, JsonOptions));
        }
        catch
        {
            // Non-critical: resume is a best-effort feature
        }
    }

    public PersistedTimer? Load()
    {
        try
        {
            var json = ProtectedStore.Read(StatePath);
            if (json != null)
                return JsonSerializer.Deserialize<PersistedTimer>(json, JsonOptions);
        }
        catch (Exception ex)
        {
            DiagnosticLog.Write("timer-state load failed: " + ex.Message);
        }
        return null;
    }

    public void Clear()
    {
        try
        {
            ProtectedStore.Delete(StatePath);
        }
        catch
        {
            // Leaving a stale file is harmless; it is validated on load
        }
    }
}
