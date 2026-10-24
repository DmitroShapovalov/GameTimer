using System.IO;
using System.Text.Json;
using GameTimer.Models;

namespace GameTimer.Services;

/// <summary>
/// Append-only history of timer events (start / stop / extend / expiry / close / resume), stored as
/// one JSON object per line in sessions.jsonl next to the exe. Shown on the PC and the phone.
/// Best-effort; capped so the file can't grow without bound. Call on the UI thread.
/// </summary>
public sealed class SessionLog
{
    private static readonly string LogPath = Path.Combine(AppContext.BaseDirectory, "sessions.jsonl");
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        Converters = { new System.Text.Json.Serialization.JsonStringEnumConverter(System.Text.Json.JsonNamingPolicy.CamelCase) }
    };
    private const int MaxEntries = 1000;
    private const int TrimTo = 800;

    private int _lineCount = -1; // lazily counted on first append

    /// <summary>Raised after an event is recorded, so the UI can refresh.</summary>
    public event Action? Changed;

    /// <summary>Raised with the event itself after it was recorded (UI thread).</summary>
    public event Action<SessionEvent>? Added;

    public void Add(SessionEvent e)
    {
        try
        {
            if (_lineCount < 0) _lineCount = CountLines();

            File.AppendAllText(LogPath, JsonSerializer.Serialize(e, JsonOptions) + Environment.NewLine);
            if (++_lineCount > MaxEntries)
            {
                TrimToLast(TrimTo);
                _lineCount = TrimTo;
            }
        }
        catch
        {
            // History is non-critical
        }
        Added?.Invoke(e);
        Changed?.Invoke();
    }

    /// <summary>The most recent events, newest first, up to <paramref name="limit"/>.</summary>
    public IReadOnlyList<SessionEvent> Recent(int limit = 200)
    {
        var result = new List<SessionEvent>();
        try
        {
            if (!File.Exists(LogPath)) return result;
            foreach (var line in File.ReadLines(LogPath))
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                try
                {
                    var e = JsonSerializer.Deserialize<SessionEvent>(line, JsonOptions);
                    if (e != null) result.Add(e);
                }
                catch (JsonException) { /* skip a bad line */ }
            }
        }
        catch
        {
            // Return whatever parsed
        }
        result.Reverse();
        return result.Count > limit ? result.GetRange(0, limit) : result;
    }

    private int CountLines()
    {
        try { return File.Exists(LogPath) ? File.ReadLines(LogPath).Count() : 0; }
        catch { return 0; }
    }

    private void TrimToLast(int keep)
    {
        try
        {
            var lines = File.ReadAllLines(LogPath);
            if (lines.Length <= keep) return;
            File.WriteAllLines(LogPath, lines[^keep..]);
        }
        catch
        {
            // If trimming fails the file just stays larger; not critical
        }
    }
}
