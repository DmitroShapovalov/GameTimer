using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace GameTimer.Services;

/// <summary>Where the store remembers, outside the files, what it last wrote (a registry key on Windows).</summary>
public interface IStateMarks
{
    string? Get(string name);
    void Set(string name, string value);
    void Remove(string name);
    /// <summary>All names that have a value.</summary>
    IReadOnlyCollection<string> Names();
}

/// <summary>
/// Tamper-resistant persistence for the small state files (settings, usage, timer state).
/// The app runs under the child's own Windows account, so file permissions cannot stop the child
/// from writing the same files the app writes. Instead:
/// - every file is encrypted with Windows DPAPI (per-user): the file on disk is opaque, and any hand
///   edit breaks decryption. A protected file starts with a 4-byte magic;
/// - each write keeps the previous content as "&lt;path&gt;.bak", so a damaged file can be rolled back;
/// - every write carries a counter inside the encrypted part, one more than the last, and the store
///   keeps the counter of the last write outside the folder (<see cref="IStateMarks"/>, the "mark").
///   A file that is missing, older than the mark (a saved copy or the backup put back) or in plaintext
///   while a mark exists is tampering: it is reported, and the backup is used if it is the write just
///   before the mark or newer. The mark is written after the file, so a crash or a lost registry
///   write only leaves the mark behind the file, which is fine.
/// Reports are kept with the marks until they are drained, so they survive the app being killed.
/// Not a defence against someone who runs code as the user (DPAPI is reversible for that user, and
/// the marks can be removed too), but deleting, editing or putting back a file is noticed.
/// This file has no Windows types: the DPAPI calls and the registry are in ProtectedStore.Windows.cs,
/// so the rules are tested on any platform (tests/GameTimer.Tests).
/// </summary>
public static partial class ProtectedStore
{
    private static readonly byte[] MagicV1 = { 0x47, 0x54, 0x50, 0x31 }; // "GTP1": the content alone (until v1.9)
    private static readonly byte[] MagicV2 = { 0x47, 0x54, 0x50, 0x32 }; // "GTP2": an 8-byte counter, then the content
    private const int MagicLength = 4;
    private const string PendingTampers = "pending-tampers";
    private const int MaxPending = 20;
    private const int TombstoneGap = 1000;

    internal static Func<byte[], byte[]> Protector = _ => throw new InvalidOperationException("no protector");
    internal static Func<byte[], byte[]?> Unprotector = _ => null;
    internal static IStateMarks Marks = new NoMarks();

    private sealed class NoMarks : IStateMarks
    {
        public string? Get(string name) => null;
        public void Set(string name, string value) { }
        public void Remove(string name) { }
        public IReadOnlyCollection<string> Names() => Array.Empty<string>();
    }

    private static readonly object Gate = new();
    private static readonly List<DateTimeOffset> _tampers = new();
    private static readonly Dictionary<string, long> _lastCounter = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>For tests: forgets what this process remembers (as after a restart).</summary>
    internal static void ResetForTests()
    {
        lock (Gate) { _tampers.Clear(); _lastCounter.Clear(); }
    }

    /// <summary>
    /// Tamper events detected during loads — in this run, or in an earlier one that ended before they were
    /// drained; drained and logged at startup.
    /// </summary>
    public static DateTimeOffset[] DrainTamperReports()
    {
        lock (Gate)
        {
            var all = new List<DateTimeOffset>(_tampers);
            _tampers.Clear();
            try
            {
                if (Marks.Get(PendingTampers) is { Length: > 0 } saved)
                {
                    foreach (var part in saved.Split(';', StringSplitOptions.RemoveEmptyEntries))
                        if (DateTimeOffset.TryParse(part, System.Globalization.CultureInfo.InvariantCulture,
                                System.Globalization.DateTimeStyles.RoundtripKind, out var when)) all.Add(when);
                    Marks.Remove(PendingTampers);
                }
            }
            catch { /* without marks: this run's reports only */ }
            return all.ToArray();
        }
    }

    private static void ReportTamper()
    {
        var now = DateTimeOffset.Now;
        try
        {
            var saved = (Marks.Get(PendingTampers) ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries).ToList();
            saved.Add(now.ToString("o", System.Globalization.CultureInfo.InvariantCulture));
            Marks.Set(PendingTampers, string.Join(';', saved.TakeLast(MaxPending)));
            if (Marks.Get(PendingTampers) is { Length: > 0 }) return; // kept: it survives a kill before the drain
        }
        catch { /* fall through: in memory */ }
        _tampers.Add(now);
    }

    /// <summary>Encrypts and writes atomically; the file it replaces becomes the backup.</summary>
    public static void Write(string path, string json)
    {
        lock (Gate) Write(path, json, rotate: true);
    }

    private static void Write(string path, string json, bool rotate)
    {
        // One more than anything known: this process's last write, the mark, the file on disk
        long last = Math.Max(_lastCounter.GetValueOrDefault(path, -1), GetMark(path)?.Counter ?? -1);
        if (!_lastCounter.ContainsKey(path) && TryLoad(path) is { } onDisk) last = Math.Max(last, onDisk.Counter);
        long counter = last + 1;

        var body = Encoding.UTF8.GetBytes(json);
        var plain = new byte[8 + body.Length];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(plain, counter);
        body.CopyTo(plain, 8);
        var blob = Protector(plain);
        var content = new byte[MagicLength + blob.Length];
        MagicV2.CopyTo(content, 0);
        blob.CopyTo(content, MagicLength);
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, content);
        // Rotate the last good protected file into .bak before replacing it (never a plaintext / legacy
        // one, and never the altered file a rollback is replacing)
        if (rotate)
        {
            try { if (File.Exists(path) && IsProtected(path)) File.Copy(path, path + ".bak", overwrite: true); }
            catch { /* best effort */ }
        }
        File.Move(tmp, path, overwrite: true);
        _lastCounter[path] = counter;
        SetMark(path, counter); // after the file: a mark behind the file is harmless, one ahead of it would not be
    }

    /// <summary>The file's content, or null when there is none (the caller uses defaults).</summary>
    public static string? Read(string path) => Read(path, allowLegacyPlaintext: true);

    /// <param name="allowLegacyPlaintext">
    /// A plaintext file from before v1.6 is taken once, when nothing says the store ever protected this
    /// file. False for files that were never plaintext.
    /// </param>
    /// <param name="tolerance">
    /// How many writes behind the mark a file may be. For files written every few seconds: after a hard
    /// power-off the registry may have kept a later mark than the disk kept of the file, which is not
    /// tampering. Keep it small: it is also how old a copy can be put back unnoticed.
    /// </param>
    public static string? Read(string path, bool allowLegacyPlaintext, int tolerance = 0)
    {
        lock (Gate)
        {
            var mark = GetMark(path);
            // No mark for this path, but one for the same file elsewhere: the app folder was copied or is run
            // through another path, where the marks don't follow. Reported; a real second install pays one report.
            bool elsewhere = mark == null && KnownElsewhere(path);
            if (!File.Exists(path))
            {
                if (mark is { Deleted: true }) return null; // meant to be gone
                if (mark != null || File.Exists(path + ".bak")) return Rollback(path, mark, tolerance); // deleted
                if (elsewhere)
                {
                    ReportTamper();
                    SetMarkText(path, "d0"); // told once: from now on this path has its own record
                }
                return null;
            }
            byte[] raw;
            try { raw = File.ReadAllBytes(path); } catch { return null; }

            if (HasMagic(raw))
            {
                var loaded = Decode(raw);
                if (loaded == null) return Rollback(path, mark, tolerance); // a protected file was altered
                var (counter, json) = loaded.Value;
                if (mark is { Deleted: true })
                {
                    // Removed on purpose (a stopped timer) and back again: a saved copy, unless it is a newer write
                    if (counter <= mark.Value.Counter) { ReportTamper(); return null; }
                }
                else if (mark != null && counter < mark.Value.Counter - tolerance)
                {
                    return Rollback(path, mark, tolerance); // older than the last write: a saved copy (or the backup) put back
                }
                if (elsewhere) ReportTamper();
                _lastCounter[path] = Math.Max(counter, _lastCounter.GetValueOrDefault(path, -1));
                if (raw[3] == MagicV1[3])
                {
                    // From before the counters: written again at once, so the old file can't be put back later
                    try { Write(path, json, rotate: false); } catch { SetMark(path, counter); }
                }
                else if (mark == null || mark.Value.Deleted || counter > mark.Value.Counter)
                {
                    SetMark(path, counter); // first run of this scheme, or a mark left behind
                }
                return json;
            }

            // No magic: a genuine legacy plaintext file (first run after an update from before v1.6), or a
            // plaintext file planted to replace a protected one
            if (!allowLegacyPlaintext || mark != null || elsewhere || File.Exists(path + ".bak")) return Rollback(path, mark, tolerance);
            try { return Encoding.UTF8.GetString(raw); } catch { return null; } // one-time legacy migration
        }
    }

    /// <summary>
    /// Removes the file and its backup (state that is meant to go, e.g. a stopped timer). The mark stays as a
    /// tombstone with the last counter: a copy saved earlier and put back later is noticed.
    /// </summary>
    public static void Delete(string path)
    {
        lock (Gate)
        {
            long last = Math.Max(_lastCounter.GetValueOrDefault(path, -1), GetMark(path)?.Counter ?? -1);
            if (TryLoad(path) is { } onDisk) last = Math.Max(last, onDisk.Counter);
            foreach (var p in new[] { path, path + ".bak", path + ".tmp" })
                try { if (File.Exists(p)) File.Delete(p); } catch { /* harmless */ }
            if (last < 0) return; // never written
            // Well past any reader's tolerance: the next state's first writes can't be mistaken for the old one
            last += TombstoneGap;
            _lastCounter[path] = last;
            SetMarkText(path, "d" + last.ToString(System.Globalization.CultureInfo.InvariantCulture));
        }
    }

    // Reports the tampering and restores the best copy left: the backup when it is the write just before the
    // mark or newer; otherwise the newest valid copy on disk, however old (a folder restored from a backup, a
    // downgrade and back) — still reported. Null when nothing decrypts, or the state was meant to be gone: the
    // caller uses defaults. The mark stays: what is saved next continues the count.
    private static string? Rollback(string path, (long Counter, bool Deleted)? mark, int tolerance)
    {
        ReportTamper();
        if (mark is { Deleted: true }) return null;
        try
        {
            var bak = TryLoad(path + ".bak");
            var main = TryLoad(path);
            (long Counter, string Json)? best;
            if (bak != null && (mark == null || bak.Value.Counter >= mark.Value.Counter - 1 - tolerance)) best = bak;
            else if (bak != null && main != null) best = bak.Value.Counter >= main.Value.Counter ? bak : main;
            else best = bak ?? main;
            if (best != null)
            {
                try { Write(path, best.Value.Json, rotate: false); } catch { /* keep the value even if rewrite fails */ }
                return best.Value.Json;
            }
        }
        catch { /* fall through to defaults */ }
        return null;
    }

    private static (long Counter, string Json)? TryLoad(string path)
    {
        try { return File.Exists(path) ? Decode(File.ReadAllBytes(path)) : null; } catch { return null; }
    }

    // A protected file's counter and content; a v1 file (before the counters) counts as 0
    private static (long Counter, string Json)? Decode(byte[] raw)
    {
        if (!HasMagic(raw)) return null;
        var plain = Unprotector(raw.AsSpan(MagicLength).ToArray());
        if (plain == null) return null;
        if (raw[3] == MagicV1[3]) return (0, Encoding.UTF8.GetString(plain));
        if (plain.Length < 8) return null;
        long counter = System.Buffers.Binary.BinaryPrimitives.ReadInt64BigEndian(plain);
        return counter < 0 ? null : (counter, Encoding.UTF8.GetString(plain, 8, plain.Length - 8));
    }

    // Two installs under one account keep their marks apart
    private static string MarkName(string path)
    {
        var full = Path.GetFullPath(path).ToUpperInvariant();
        return Path.GetFileName(path) + "-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(full)), 0, 4);
    }

    // "123": the counter of the last write; "d123": removed on purpose after that write
    private static (long Counter, bool Deleted)? GetMark(string path)
    {
        try
        {
            var text = Marks.Get(MarkName(path));
            if (string.IsNullOrEmpty(text)) return null;
            bool deleted = text[0] == 'd';
            return long.TryParse(deleted ? text[1..] : text, out var n) && n >= 0 ? (n, deleted) : null;
        }
        catch { return null; }
    }

    private static bool KnownElsewhere(string path)
    {
        try
        {
            var mine = MarkName(path);
            var prefix = Path.GetFileName(path) + "-";
            return Marks.Names().Any(n => n != mine && n.StartsWith(prefix, StringComparison.OrdinalIgnoreCase));
        }
        catch { return false; }
    }

    private static void SetMark(string path, long counter) =>
        SetMarkText(path, counter.ToString(System.Globalization.CultureInfo.InvariantCulture));

    private static void SetMarkText(string path, string value)
    {
        try { Marks.Set(MarkName(path), value); }
        catch { /* without marks the store works as before them */ }
    }

    private static bool IsProtected(string path)
    {
        try
        {
            var head = new byte[MagicLength];
            using var fs = File.OpenRead(path);
            return fs.Read(head, 0, head.Length) == head.Length && HasMagic(head);
        }
        catch { return false; }
    }

    private static bool HasMagic(byte[] data) =>
        data.Length >= MagicLength && data[0] == MagicV1[0] && data[1] == MagicV1[1] && data[2] == MagicV1[2]
        && (data[3] == MagicV1[3] || data[3] == MagicV2[3]);
}
