using System.Security.Cryptography;
using System.Text;
using GameTimer.Models;

namespace GameTimer.Services;

/// <summary>
/// One-time pairing codes shown in the "Connect phone" QR. Only one code is active at a time;
/// it expires after <see cref="CodeLifetime"/> and is consumed by the first successful pairing.
/// Thread-safe.
/// </summary>
public sealed class PairingService
{
    public static readonly TimeSpan CodeLifetime = TimeSpan.FromMinutes(2);

    // No 0/O/1/I, so the code also reads well if typed by hand
    private const string Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private const int CodeLength = 8; // 32^8 = 2^40 combinations, plus rate limiting and a 2-minute lifetime

    private readonly object _lock = new();
    private string? _code;
    private long _expiresAtMs;

    /// <summary>Raised on the UI thread after a phone paired with the current code.</summary>
    public event Action<PairedDevice>? Paired;

    /// <summary>Creates a new code, replacing any previous one.</summary>
    public string Begin()
    {
        var chars = new char[CodeLength];
        for (int i = 0; i < chars.Length; i++)
            chars[i] = Alphabet[RandomNumberGenerator.GetInt32(Alphabet.Length)];
        var code = new string(chars);

        lock (_lock)
        {
            _code = code;
            _expiresAtMs = Environment.TickCount64 + (long)CodeLifetime.TotalMilliseconds;
        }
        return code;
    }

    /// <summary>Time left on the active code, or zero if there is none.</summary>
    public TimeSpan Remaining
    {
        get
        {
            lock (_lock)
            {
                if (_code == null) return TimeSpan.Zero;
                var ms = _expiresAtMs - Environment.TickCount64;
                return ms > 0 ? TimeSpan.FromMilliseconds(ms) : TimeSpan.Zero;
            }
        }
    }

    public void Cancel()
    {
        lock (_lock) _code = null;
    }

    /// <summary>Checks the code and, if valid, consumes it so it can't be used again.</summary>
    public bool TryConsume(string? code)
    {
        if (string.IsNullOrWhiteSpace(code)) return false;
        var candidate = Encoding.ASCII.GetBytes(code.Trim().ToUpperInvariant());

        lock (_lock)
        {
            if (_code == null) return false;
            if (Environment.TickCount64 >= _expiresAtMs)
            {
                _code = null;
                return false;
            }
            if (!CryptographicOperations.FixedTimeEquals(candidate, Encoding.ASCII.GetBytes(_code)))
                return false;

            _code = null;
            return true;
        }
    }

    /// <summary>Call on the UI thread after the device was stored.</summary>
    public void NotifyPaired(PairedDevice device) => Paired?.Invoke(device);
}
