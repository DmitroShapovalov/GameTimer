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
            File.WriteAllText(StatePath, JsonSerializer.Serialize(state, JsonOptions));
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
            if (File.Exists(StatePath))
                return JsonSerializer.Deserialize<PersistedTimer>(File.ReadAllText(StatePath), JsonOptions);
        }
        catch
        {
            // Corrupt or unreadable — treat as no saved timer
        }
        return null;
    }

    public void Clear()
    {
        try
        {
            if (File.Exists(StatePath)) File.Delete(StatePath);
        }
        catch
        {
            // Leaving a stale file is harmless; it is validated on load
        }
    }
}
