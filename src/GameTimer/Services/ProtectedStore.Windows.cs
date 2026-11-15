using Microsoft.Win32;

namespace GameTimer.Services;

// The Windows side of the store: DPAPI (per-user) and the marks in the user's registry.
public static partial class ProtectedStore
{
    static ProtectedStore()
    {
        Protector = Protect;
        Unprotector = TryUnprotect;
        Marks = new RegistryMarks();
    }

    /// <summary>
    /// HKCU\Software\GameTimer\State: one value per state file (the counter of its last write) and the tamper
    /// reports not drained yet. The uninstaller removes the timer state's mark with its file.
    /// </summary>
    private sealed class RegistryMarks : IStateMarks
    {
        private const string KeyPath = @"Software\GameTimer\State";

        public string? Get(string name)
        {
            using var key = Registry.CurrentUser.OpenSubKey(KeyPath);
            return key?.GetValue(name) as string;
        }

        public void Set(string name, string value)
        {
            using var key = Registry.CurrentUser.CreateSubKey(KeyPath);
            key.SetValue(name, value, RegistryValueKind.String);
        }

        public void Remove(string name)
        {
            using var key = Registry.CurrentUser.OpenSubKey(KeyPath, writable: true);
            key?.DeleteValue(name, throwOnMissingValue: false);
        }

        public IReadOnlyCollection<string> Names()
        {
            using var key = Registry.CurrentUser.OpenSubKey(KeyPath);
            return key?.GetValueNames() ?? Array.Empty<string>();
        }
    }

    private static byte[] Protect(byte[] data)
    {
        var provider = new global::Windows.Security.Cryptography.DataProtection.DataProtectionProvider("LOCAL=user");
        var buffer = global::Windows.Security.Cryptography.CryptographicBuffer.CreateFromByteArray(data);
        var result = provider.ProtectAsync(buffer).GetAwaiter().GetResult();
        global::Windows.Security.Cryptography.CryptographicBuffer.CopyToByteArray(result, out var bytes);
        return bytes;
    }

    private static byte[]? TryUnprotect(byte[] data)
    {
        try
        {
            var provider = new global::Windows.Security.Cryptography.DataProtection.DataProtectionProvider();
            var buffer = global::Windows.Security.Cryptography.CryptographicBuffer.CreateFromByteArray(data);
            var result = provider.UnprotectAsync(buffer).GetAwaiter().GetResult();
            global::Windows.Security.Cryptography.CryptographicBuffer.CopyToByteArray(result, out var bytes);
            return bytes;
        }
        catch { return null; }
    }
}
