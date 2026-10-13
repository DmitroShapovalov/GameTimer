using System.IO;
using System.Text.Json;
using GameTimer.Models;

namespace GameTimer.Services;

public class SettingsService
{
    private static readonly string SettingsPath = Path.Combine(
        AppDomain.CurrentDomain.BaseDirectory, "settings.json");

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    /// <summary>
    /// Raised (on the UI thread) after user-facing settings were changed, with the object that made
    /// the change, so a client can ignore its own changes. Not raised for device bookkeeping saves.
    /// </summary>
    public event Action<object>? SettingsChanged;

    public void NotifyChanged(object source) => SettingsChanged?.Invoke(source);

    public AppSettings Load()
    {
        try
        {
            if (File.Exists(SettingsPath))
            {
                var json = File.ReadAllText(SettingsPath);
                return JsonSerializer.Deserialize<AppSettings>(json, JsonOptions) ?? new AppSettings();
            }
        }
        catch
        {
            // Corrupted file — use defaults
        }

        return new AppSettings();
    }

    public void Save(AppSettings settings)
    {
        try
        {
            var json = JsonSerializer.Serialize(settings, JsonOptions);
            File.WriteAllText(SettingsPath, json);
        }
        catch
        {
            // Silently fail — non-critical
        }
    }
}
