using GameTimer.Services;
using Xunit;

namespace GameTimer.Tests;

/// <summary>The state store's rules (docs/SECURITY.md): what counts as tampering and what is restored.</summary>
public sealed class ProtectedStoreTests : IDisposable
{
    private sealed class MemoryMarks : IStateMarks
    {
        public readonly Dictionary<string, string> Values = new();
        public string? Get(string name) => Values.GetValueOrDefault(name);
        public void Set(string name, string value) => Values[name] = value;
        public void Remove(string name) => Values.Remove(name);
        public IReadOnlyCollection<string> Names() => Values.Keys.ToArray();
    }

    private sealed class FailingMarks : IStateMarks
    {
        public string? Get(string name) => throw new UnauthorizedAccessException();
        public void Set(string name, string value) => throw new UnauthorizedAccessException();
        public void Remove(string name) => throw new UnauthorizedAccessException();
        public IReadOnlyCollection<string> Names() => throw new UnauthorizedAccessException();
    }

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "gt-store-" + Guid.NewGuid().ToString("N"));
    private MemoryMarks _marks = new();
    private int _nonce;

    public ProtectedStoreTests()
    {
        Directory.CreateDirectory(_dir);
        // A stand-in for DPAPI: reversible, different output for every call, garbage in → null out
        ProtectedStore.Protector = data => new[] { (byte)0xA5, (byte)++_nonce }.Concat(data.Select(b => (byte)(b ^ 0x5A))).ToArray();
        ProtectedStore.Unprotector = data => data.Length >= 2 && data[0] == 0xA5 ? data.Skip(2).Select(b => (byte)(b ^ 0x5A)).ToArray() : null;
        ProtectedStore.Marks = _marks;
        ProtectedStore.ResetForTests();
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { /* temp */ }
    }

    private string File_(string name = "usage.json") => Path.Combine(_dir, name);
    private static int Tampers() => ProtectedStore.DrainTamperReports().Length;

    /// <summary>The app starts again: nothing is remembered in memory.</summary>
    private static void Restart() => ProtectedStore.ResetForTests();

    /// <summary>A file as versions before the counters wrote it ("GTP1" + the protected content).</summary>
    private void WriteV1(string path, string json)
    {
        var blob = ProtectedStore.Protector(System.Text.Encoding.UTF8.GetBytes(json));
        File.WriteAllBytes(path, new byte[] { 0x47, 0x54, 0x50, 0x31 }.Concat(blob).ToArray());
    }

    [Fact]
    public void WritesAndReadsBack()
    {
        ProtectedStore.Write(File_(), "one");
        Restart();
        Assert.Equal("one", ProtectedStore.Read(File_()));
        Assert.NotEqual("one", File.ReadAllText(File_())); // not plaintext on disk
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void NoFileAndNoMarkIsAFreshStart()
    {
        Assert.Null(ProtectedStore.Read(File_()));
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void ADeletedFileIsReportedAndTheBackupRestored()
    {
        ProtectedStore.Write(File_(), "one");
        ProtectedStore.Write(File_(), "two");
        File.Delete(File_());
        Restart();
        Assert.Equal("one", ProtectedStore.Read(File_())); // the backup: the content before the last write
        Assert.Equal(1, Tampers());
        Restart();
        Assert.Equal("one", ProtectedStore.Read(File_())); // rewritten: fine from now on
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void ADeletedFileAndBackupIsReportedEvenIfTheAppIsKilledBeforeTheReportIsRead()
    {
        ProtectedStore.Write(File_(), "one");
        ProtectedStore.Write(File_(), "two");
        File.Delete(File_());
        File.Delete(File_() + ".bak");
        Restart();
        Assert.Null(ProtectedStore.Read(File_()));
        ProtectedStore.Write(File_(), "defaults"); // the caller saves its defaults
        Restart(); // killed before the report was drained
        Assert.Equal("defaults", ProtectedStore.Read(File_()));
        Assert.Equal(1, Tampers()); // the report was kept with the marks
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void ADeletedFileKeepsBeingReportedUntilSomethingIsSaved()
    {
        ProtectedStore.Write(File_(), "one");
        File.Delete(File_());
        Restart();
        Assert.Null(ProtectedStore.Read(File_()));
        Restart();
        Assert.Null(ProtectedStore.Read(File_()));
        Assert.Equal(2, Tampers());
    }

    [Fact]
    public void AnOlderCopyPutBackIsReported()
    {
        ProtectedStore.Write(File_(), "morning");
        var saved = File.ReadAllBytes(File_());
        ProtectedStore.Write(File_(), "noon");
        ProtectedStore.Write(File_(), "evening");
        File.WriteAllBytes(File_(), saved); // a valid, encrypted, but old file
        Restart();
        Assert.Equal("noon", ProtectedStore.Read(File_())); // the backup: the write just before the last
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void TheBackupCopiedOverTheFileIsReported()
    {
        ProtectedStore.Write(File_("settings.json"), "limit=120");
        ProtectedStore.Write(File_("settings.json"), "limit=30"); // the parent lowers the limit
        File.Copy(File_("settings.json") + ".bak", File_("settings.json"), overwrite: true);
        Restart();
        ProtectedStore.Read(File_("settings.json"));
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void WhenOnlyOldCopiesAreLeftTheNewestIsUsedAndReported()
    {
        // Also a folder restored from a backup, or a downgrade and back: old but valid beats defaults
        ProtectedStore.Write(File_("settings.json"), "morning");
        var morning = File.ReadAllBytes(File_("settings.json"));
        ProtectedStore.Write(File_("settings.json"), "noon");
        var noon = File.ReadAllBytes(File_("settings.json"));
        for (int i = 0; i < 4; i++) ProtectedStore.Write(File_("settings.json"), "evening " + i);
        File.WriteAllBytes(File_("settings.json"), morning);
        File.WriteAllBytes(File_("settings.json") + ".bak", noon);
        Restart();
        Assert.Equal("noon", ProtectedStore.Read(File_("settings.json")));
        Assert.Equal(1, Tampers());
        Restart();
        Assert.Equal("noon", ProtectedStore.Read(File_("settings.json"))); // rewritten above the mark: settled
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void ACopyOfTheAppFolderIsReported()
    {
        var installed = Path.Combine(_dir, "app"); var copy = Path.Combine(_dir, "copy");
        Directory.CreateDirectory(installed); Directory.CreateDirectory(copy);
        ProtectedStore.Write(Path.Combine(installed, "usage.json"), "used=3600");
        ProtectedStore.Write(Path.Combine(installed, "settings.json"), "limit=60");
        // The child copies the folder without today's usage and runs the copy
        File.Copy(Path.Combine(installed, "settings.json"), Path.Combine(copy, "settings.json"));
        Restart();
        Assert.Null(ProtectedStore.Read(Path.Combine(copy, "usage.json")));
        Assert.Equal("limit=60", ProtectedStore.Read(Path.Combine(copy, "settings.json")));
        Assert.Equal(2, Tampers());
        Restart();
        Assert.Equal("limit=60", ProtectedStore.Read(Path.Combine(copy, "settings.json"))); // adopted: told once
        Assert.Null(ProtectedStore.Read(Path.Combine(copy, "usage.json")));
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void AStateRemovedOnPurposeDoesNotComeBack()
    {
        var path = File_("timer-state.json");
        ProtectedStore.Write(path, "left=120");
        var saved = File.ReadAllBytes(path);
        ProtectedStore.Delete(path); // the parent stopped the timer
        File.WriteAllBytes(path, saved);
        Restart();
        Assert.Null(ProtectedStore.Read(path));
        Assert.Equal(1, Tampers());
        // A new timer counts on from there; the old copy stays old
        File.Delete(path);
        ProtectedStore.Write(path, "left=30");
        File.WriteAllBytes(path, saved);
        Restart();
        ProtectedStore.Read(path, allowLegacyPlaintext: true, tolerance: 4); // as the timer store reads it
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void AnEditedFileIsReportedAndRolledBack()
    {
        ProtectedStore.Write(File_(), "one");
        ProtectedStore.Write(File_(), "two");
        var raw = File.ReadAllBytes(File_());
        raw[4] ^= 0xFF; // breaks the stand-in's header, as an edit breaks DPAPI
        File.WriteAllBytes(File_(), raw);
        Restart();
        Assert.Equal("one", ProtectedStore.Read(File_()));
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void APlantedPlaintextFileIsRefusedOnceTheStoreProtectedIt()
    {
        ProtectedStore.Write(File_(), "one");
        File.Delete(File_() + ".bak");
        File.WriteAllText(File_(), "{\"UsedSeconds\":0}");
        Restart();
        Assert.Null(ProtectedStore.Read(File_()));
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void ALegacyPlaintextFileIsTakenOnlyWhenNothingWasEverProtected()
    {
        File.WriteAllText(File_("settings.json"), "legacy");
        Assert.Equal("legacy", ProtectedStore.Read(File_("settings.json")));
        Assert.Equal(0, Tampers());
        Assert.Null(ProtectedStore.Read(File_("settings.json"), allowLegacyPlaintext: false));
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void AFileFromBeforeTheCountersIsAdoptedThenGuarded()
    {
        // An update from v1.9: a valid protected file without a counter, no mark
        WriteV1(File_(), "one");
        Assert.Equal("one", ProtectedStore.Read(File_()));
        Assert.Equal(0, Tampers());
        ProtectedStore.Write(File_(), "two");
        var v2 = File.ReadAllBytes(File_());
        Restart();
        Assert.Equal("two", ProtectedStore.Read(File_()));
        // The old file put back after the update: older than the mark
        WriteV1(File_(), "one");
        File.WriteAllBytes(File_() + ".bak", v2);
        Restart();
        Assert.Equal("two", ProtectedStore.Read(File_()));
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void AFileFromBeforeTheCountersIsGuardedAtOnce()
    {
        // Settings are saved rarely: the adopted file is rewritten at the first read, not at the next save
        WriteV1(File_("settings.json"), "limit=30");
        var older = File.ReadAllBytes(File_("settings.json"));
        Assert.Equal("limit=30", ProtectedStore.Read(File_("settings.json")));
        File.WriteAllBytes(File_("settings.json"), older);
        Restart();
        ProtectedStore.Read(File_("settings.json"));
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void AMarkBehindTheFileIsNotTampering()
    {
        // A hard power-off: the registry kept an older mark than the file on disk (several writes)
        ProtectedStore.Write(File_(), "one");
        var mark = new Dictionary<string, string>(_marks.Values);
        for (int i = 0; i < 5; i++) ProtectedStore.Write(File_(), "later " + i);
        foreach (var kv in mark) _marks.Values[kv.Key] = kv.Value;
        Restart();
        Assert.Equal("later 4", ProtectedStore.Read(File_()));
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void AMarkSlightlyAheadIsToleratedForFilesWrittenOften()
    {
        // The other way round: the disk kept an older file than the registry's mark
        ProtectedStore.Write(File_(), "one");
        var old = File.ReadAllBytes(File_());
        for (int i = 0; i < 5; i++) ProtectedStore.Write(File_(), "later " + i);
        File.WriteAllBytes(File_(), old);
        Restart();
        Assert.Equal("one", ProtectedStore.Read(File_(), allowLegacyPlaintext: true, tolerance: 60));
        Assert.Equal(0, Tampers());
        ProtectedStore.Write(File_(), "next"); // the count goes on from the mark, not from the old file
        File.WriteAllBytes(File_(), old);
        Restart();
        ProtectedStore.Read(File_(), allowLegacyPlaintext: true, tolerance: 3);
        Assert.Equal(1, Tampers()); // beyond the tolerance it is an old copy
    }

    [Fact]
    public void FailedReplacementsAreNotTampering()
    {
        ProtectedStore.Write(File_(), "one");
        // The file can't be replaced (another program holds it): the write throws, nothing changes
        using (File.Open(File_(), FileMode.Open, FileAccess.Read, FileShare.None))
        {
            for (int i = 0; i < 2; i++)
                try { ProtectedStore.Write(File_(), "blocked " + i); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        }
        Restart();
        var read = ProtectedStore.Read(File_());
        Assert.True(read is "one" or "blocked 1"); // whether the lock holds the replace depends on the platform
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void TwoInstallsKeepTheirMarksApart()
    {
        var a = Path.Combine(_dir, "a"); var b = Path.Combine(_dir, "b");
        Directory.CreateDirectory(a); Directory.CreateDirectory(b);
        for (int i = 0; i < 3; i++) ProtectedStore.Write(Path.Combine(a, "usage.json"), "a" + i);
        ProtectedStore.Write(Path.Combine(b, "usage.json"), "b0");
        Restart();
        Assert.Equal("a2", ProtectedStore.Read(Path.Combine(a, "usage.json")));
        Assert.Equal("b0", ProtectedStore.Read(Path.Combine(b, "usage.json")));
        Assert.Equal(0, Tampers());
        for (int i = 0; i < 3; i++) ProtectedStore.Write(Path.Combine(b, "usage.json"), "b" + (i + 1));
        Restart();
        Assert.Equal("a2", ProtectedStore.Read(Path.Combine(a, "usage.json")));
        Assert.Equal("b3", ProtectedStore.Read(Path.Combine(b, "usage.json")));
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void DeleteIsNotTampering()
    {
        ProtectedStore.Write(File_("timer-state.json"), "running");
        ProtectedStore.Delete(File_("timer-state.json"));
        Restart();
        Assert.Null(ProtectedStore.Read(File_("timer-state.json")));
        Assert.Equal(0, Tampers());
    }

    [Fact]
    public void WithoutMarksItStillRollsBackDamage()
    {
        ProtectedStore.Marks = new FailingMarks();
        ProtectedStore.Write(File_(), "one");
        ProtectedStore.Write(File_(), "two");
        File.WriteAllText(File_(), "GTP2garbage");
        Restart();
        Assert.Equal("one", ProtectedStore.Read(File_()));
        Assert.Equal(1, Tampers());
    }

    [Fact]
    public void WritesFromSeveralThreadsStayConsistent()
    {
        Parallel.For(0, 200, i => ProtectedStore.Write(File_(), "v" + i));
        Restart();
        Assert.StartsWith("v", ProtectedStore.Read(File_()));
        Assert.Equal(0, Tampers());
    }
}
