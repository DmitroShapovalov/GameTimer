using System.IO;
using System.Text;

namespace GameTimer.Services;

/// <summary>
/// Tamper-resistant persistence for the small state files (settings, usage, timer state).
/// The app runs under the child's own Windows account, so file permissions cannot stop the child
/// from writing the same files the app writes. Instead every file is encrypted with Windows DPAPI
/// (per-user): the file on disk is opaque, and any hand edit breaks decryption, which we treat as
/// tampering. A protected file starts with a 4-byte magic; each successful write also keeps the
/// previous good content as "&lt;path&gt;.bak", so a tampered file can be rolled back to the last
/// valid version. Not a defence against a determined attacker who runs code as the user (DPAPI is
/// reversible for that user), but it defeats the realistic case: editing a file in a text editor.
/// </summary>
public static class ProtectedStore
{
    private static readonly byte[] Magic = { 0x47, 0x54, 0x50, 0x31 }; // "GTP1"

    private static readonly List<DateTimeOffset> _tampers = new();

    /// <summary>Tamper events detected during loads this process; drained and logged at startup.</summary>
    public static DateTimeOffset[] DrainTamperReports()
    {
        lock (_tampers) { var r = _tampers.ToArray(); _tampers.Clear(); return r; }
    }

    private static void ReportTamper()
    {
        lock (_tampers) _tampers.Add(DateTimeOffset.Now);
    }

    /// <summary>Encrypts and writes <paramref name="json"/>, keeping the previous good file as .bak.</summary>
    public static void Write(string path, string json)
    {
        var blob = Protect(Encoding.UTF8.GetBytes(json));
        var tmp = path + ".tmp";
        using (var fs = new FileStream(tmp, FileMode.Create, FileAccess.Write))
        {
            fs.Write(Magic, 0, Magic.Length);
            fs.Write(blob, 0, blob.Length);
        }
        // Rotate the last good protected file into .bak before replacing it (never a plaintext/legacy one)
        try { if (File.Exists(path) && IsProtected(path)) File.Copy(path, path + ".bak", overwrite: true); }
        catch { /* best effort */ }
        File.Move(tmp, path, overwrite: true);
    }

    /// <summary>
    /// Reads and decrypts the file, or returns null if it does not exist. On tampering it restores
    /// the last good .bak when possible, records a tamper report, and returns the restored content
    /// (or null if nothing good remains, so the caller uses defaults). A legacy plaintext file
    /// (pre-DPAPI, with no .bak yet) is returned as-is once for migration; the caller re-saves it protected.
    /// </summary>
    public static string? Read(string path)
    {
        if (!File.Exists(path)) return null;
        byte[] raw;
        try { raw = File.ReadAllBytes(path); } catch { return null; }

        if (HasMagic(raw))
        {
            var plain = TryUnprotect(raw.AsSpan(Magic.Length).ToArray());
            if (plain != null) return Encoding.UTF8.GetString(plain);
            return Rollback(path); // a protected file was altered
        }

        // No magic: a genuine legacy plaintext file (first run after update), or a plaintext file
        // planted to replace a protected one. If we were protecting this file before (.bak exists),
        // a plaintext file now is tampering.
        if (File.Exists(path + ".bak")) return Rollback(path);

        try { return Encoding.UTF8.GetString(raw); } catch { return null; } // one-time legacy migration
    }

    /// <summary>Removes the file and its .bak / .tmp siblings.</summary>
    public static void Delete(string path)
    {
        foreach (var p in new[] { path, path + ".bak", path + ".tmp" })
            try { if (File.Exists(p)) File.Delete(p); } catch { /* harmless */ }
    }

    private static string? Rollback(string path)
    {
        ReportTamper();
        var bak = path + ".bak";
        try
        {
            if (File.Exists(bak))
            {
                var raw = File.ReadAllBytes(bak);
                if (HasMagic(raw))
                {
                    var plain = TryUnprotect(raw.AsSpan(Magic.Length).ToArray());
                    if (plain != null)
                    {
                        var json = Encoding.UTF8.GetString(plain);
                        try { Write(path, json); } catch { /* keep the value even if rewrite fails */ }
                        return json;
                    }
                }
            }
        }
        catch { /* fall through to defaults */ }
        return null;
    }

    private static bool IsProtected(string path)
    {
        try
        {
            var head = new byte[Magic.Length];
            using var fs = File.OpenRead(path);
            return fs.Read(head, 0, head.Length) == head.Length && HasMagic(head);
        }
        catch { return false; }
    }

    private static bool HasMagic(byte[] data)
    {
        if (data.Length < Magic.Length) return false;
        for (int i = 0; i < Magic.Length; i++)
            if (data[i] != Magic[i]) return false;
        return true;
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
