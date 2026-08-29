using System.Runtime.InteropServices;

namespace GameTimer.Helpers;

public static class PasswordHelper
{
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool LogonUser(string lpszUsername, string lpszDomain, string lpszPassword,
        int dwLogonType, int dwLogonProvider, out IntPtr phToken);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr hObject);

    private static int _failedAttempts;
    private static DateTime? _lockoutUntil;

    public static bool IsLockedOut => _lockoutUntil.HasValue && DateTime.Now < _lockoutUntil.Value;

    public static TimeSpan LockoutRemaining =>
        _lockoutUntil.HasValue ? _lockoutUntil.Value - DateTime.Now : TimeSpan.Zero;

    public static bool Validate(string password)
    {
        if (IsLockedOut)
            return false;

        bool result = LogonUser(
            Environment.UserName,
            Environment.UserDomainName,
            password,
            2,  // LOGON32_LOGON_INTERACTIVE
            0,  // LOGON32_PROVIDER_DEFAULT
            out IntPtr token);

        if (result)
        {
            CloseHandle(token);
            _failedAttempts = 0;
            _lockoutUntil = null;
            return true;
        }

        _failedAttempts++;
        if (_failedAttempts >= 3)
        {
            _lockoutUntil = DateTime.Now.AddSeconds(30);
            _failedAttempts = 0;
        }

        return false;
    }
}
