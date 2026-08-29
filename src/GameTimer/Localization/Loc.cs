using System.Globalization;
using System.Resources;

namespace GameTimer.Localization;

public static class Loc
{
    private static readonly ResourceManager _rm =
        new("GameTimer.Localization.Strings", typeof(Loc).Assembly);

    private static CultureInfo _culture = CultureInfo.CurrentUICulture;

    public static string Get(string key)
    {
        return _rm.GetString(key, _culture) ?? key;
    }

    public static void SetLanguage(string langCode)
    {
        _culture = new CultureInfo(langCode);
        CultureInfo.CurrentUICulture = _culture;
    }

    public static string CurrentLanguage => _culture.TwoLetterISOLanguageName;
}
