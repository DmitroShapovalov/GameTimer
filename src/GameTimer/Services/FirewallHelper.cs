using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace GameTimer.Services;

/// <summary>
/// Windows Firewall rule that lets phones on the home network reach the embedded server.
/// Reading works without admin rights; changing the rule runs this exe elevated with
/// <see cref="ConfigureArg"/> (one UAC prompt), which calls <see cref="Apply"/> and exits.
/// The rule allows TCP on the remote port for this exe only, from the local subnet only, on the
/// private and public profiles (Windows 11 often marks home Wi-Fi as public).
/// </summary>
public static class FirewallHelper
{
    /// <summary>Neutral name, like the exe, so the child doesn't spot it in the firewall list.</summary>
    public const string RuleName = "SystemMonitor-Remote";

    /// <summary>Command-line switch handled in App.OnStartup before the single-instance check.</summary>
    public const string ConfigureArg = "--configure-firewall";

    private static string? ExePath => Environment.ProcessPath;

    /// <summary>True if our rule exists for this exe and port. Does not need admin rights.</summary>
    public static async Task<bool> IsConfiguredAsync(int port)
    {
        var exe = ExePath;
        if (exe == null) return false;
        try
        {
            var (code, output) = await Task.Run(() =>
                Netsh("advfirewall", "firewall", "show", "rule", $"name={RuleName}", "verbose"));
            // netsh labels are localized; the exit code, the port and the path are not
            return code == 0
                && Regex.IsMatch(output, $@"(?<!\d){port}(?!\d)")
                && output.Contains(exe, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException or IOException)
        {
            return false;
        }
    }

    /// <summary>Runs this exe elevated to (re)create the rule. False if the user declined UAC or it failed.</summary>
    public static async Task<bool> ConfigureElevatedAsync(int port)
    {
        var exe = ExePath;
        if (exe == null) return false;
        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = exe,
                Arguments = $"{ConfigureArg} {port.ToString(CultureInfo.InvariantCulture)}",
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden
            });
            if (process == null) return false;
            await process.WaitForExitAsync();
            return process.ExitCode == 0;
        }
        catch (Win32Exception)
        {
            return false; // 1223: the user cancelled the UAC prompt
        }
    }

    /// <summary>
    /// Elevated part: removes our old rule (any path / port) and the rules Windows created for this exe
    /// from its own "allow access?" prompt (a "Cancel" there creates block rules that would win over ours),
    /// then adds the allow rule. Returns the exit code of the add (0 = success).
    /// </summary>
    public static int Apply(int port)
    {
        var exe = ExePath;
        if (exe == null) return 1;

        Netsh("advfirewall", "firewall", "delete", "rule", $"name={RuleName}");
        Netsh("advfirewall", "firewall", "delete", "rule", "name=all", "dir=in", $"program={exe}");
        var (code, _) = Netsh("advfirewall", "firewall", "add", "rule",
            $"name={RuleName}", "dir=in", "action=allow", "protocol=TCP",
            $"localport={port.ToString(CultureInfo.InvariantCulture)}",
            "remoteip=localsubnet", "profile=private,public", $"program={exe}", "enable=yes");
        return code;
    }

    private static (int ExitCode, string Output) Netsh(params string[] args)
    {
        var info = new ProcessStartInfo("netsh")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = ConsoleEncoding()
        };
        foreach (var arg in args) info.ArgumentList.Add(arg);

        using var process = Process.Start(info) ?? throw new InvalidOperationException("netsh did not start");
        var output = process.StandardOutput.ReadToEnd();
        process.StandardError.ReadToEnd();
        process.WaitForExit();
        return (process.ExitCode, output);
    }

    // netsh writes in the OEM code page (e.g. 866 on Russian Windows)
    private static Encoding ConsoleEncoding()
    {
        try
        {
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
            return Encoding.GetEncoding(CultureInfo.CurrentCulture.TextInfo.OEMCodePage);
        }
        catch (ArgumentException)
        {
            return Encoding.UTF8;
        }
    }
}
