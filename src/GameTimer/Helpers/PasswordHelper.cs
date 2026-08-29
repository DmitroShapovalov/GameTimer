namespace GameTimer.Helpers;

public static class PasswordHelper
{
    // Change this password before building
    private const string Password = "parent2026";

    private static int _failedAttempts;
    private static DateTime? _lockoutUntil;

    public static bool IsLockedOut => _lockoutUntil.HasValue && DateTime.Now < _lockoutUntil.Value;

    public static TimeSpan LockoutRemaining =>
        _lockoutUntil.HasValue ? _lockoutUntil.Value - DateTime.Now : TimeSpan.Zero;

    public static bool Validate(string input)
    {
        if (IsLockedOut)
            return false;

        if (input == Password)
        {
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
