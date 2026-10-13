using System.Security.Cryptography;
using System.Text;
using GameTimer.Models;

namespace GameTimer.Services;

/// <summary>
/// Paired phones, persisted in <see cref="AppSettings.Devices"/>.
/// Add / Revoke run on the UI thread (they change settings); token lookups are thread-safe
/// and read an immutable snapshot, so request handlers never touch the settings list.
/// </summary>
public sealed class DeviceStore
{
    private const int TokenBytes = 32; // 256-bit
    private static readonly TimeSpan LastSeenSaveInterval = TimeSpan.FromMinutes(5);

    private readonly AppSettings _settings;
    private readonly Action _save;
    private readonly UiThread _ui;
    private volatile PairedDevice[] _snapshot;

    /// <summary>Raised on the UI thread when the list or a device's last-seen time changes.</summary>
    public event Action? Changed;

    public DeviceStore(AppSettings settings, Action save, UiThread ui)
    {
        _settings = settings;
        _save = save;
        _ui = ui;
        _snapshot = settings.Devices.ToArray();
    }

    public IReadOnlyList<PairedDevice> Devices => _snapshot;

    /// <summary>Registers a new device and returns its token. The token is shown once and never stored.</summary>
    public (PairedDevice Device, string Token) Add(string name)
    {
        _ui.VerifyAccess();

        var token = Base64Url(RandomNumberGenerator.GetBytes(TokenBytes));
        var now = DateTime.UtcNow;
        var device = new PairedDevice
        {
            Id = Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant(),
            Name = name,
            TokenHash = Convert.ToBase64String(Hash(token)),
            Created = now,
            LastSeen = now
        };

        _settings.Devices.Add(device);
        Commit();
        return (device, token);
    }

    public bool Revoke(string id)
    {
        _ui.VerifyAccess();

        var device = _settings.Devices.FirstOrDefault(d => d.Id == id);
        if (device == null) return false;

        _settings.Devices.Remove(device);
        Commit();
        return true;
    }

    /// <summary>Finds the device that owns this token, or null. Safe to call from any thread.</summary>
    public PairedDevice? FindByToken(string? token)
    {
        if (string.IsNullOrEmpty(token) || token.Length > 256) return null;

        var hash = Hash(token);
        PairedDevice? match = null;
        foreach (var device in _snapshot) // no early exit: same work whether or not it matches
        {
            byte[] stored;
            try { stored = Convert.FromBase64String(device.TokenHash); }
            catch (FormatException) { continue; }

            if (CryptographicOperations.FixedTimeEquals(stored, hash))
                match = device;
        }
        return match;
    }

    /// <summary>Records that a device just made a request. Persists at most every few minutes.</summary>
    public void Touch(PairedDevice device)
    {
        var now = DateTime.UtcNow;
        var previous = device.LastSeen;
        device.LastSeen = now;

        if (now - previous >= LastSeenSaveInterval)
            _ui.Post(() =>
            {
                _save();
                Changed?.Invoke();
            });
    }

    private void Commit()
    {
        _snapshot = _settings.Devices.ToArray();
        _save();
        Changed?.Invoke();
    }

    private static byte[] Hash(string token) => SHA256.HashData(Encoding.UTF8.GetBytes(token));

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
