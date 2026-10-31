using System.Security.Cryptography;
using GameTimer.Protocol.Encodings;

namespace GameTimer.Protocol.Identity;

/// <summary>
/// The daily relay route token of one hub (protocol §10.1):
/// <c>BLAKE2b(outlen 32, key = ak, input = "GameTimer route v2" ‖ UTC date "YYYY-MM-DD")[0..16)</c>.
/// 16 bytes on the agent's relay socket (<c>register</c>, <c>open</c>), 26 base32 characters in the hub's
/// relay URL. Only the hub and the agent can compute it, and it changes every day.
/// </summary>
public static class RouteToken
{
    public const int Size = 16;
    public const int TextLength = 26;

    private static readonly byte[] Label = "GameTimer route v2"u8.ToArray();

    public static byte[] DeriveBytes(ReadOnlySpan<byte> ak, DateOnly utcDate)
    {
        if (ak.Length != 32) throw new ArgumentException("ak must be 32 bytes");
        var date = System.Text.Encoding.ASCII.GetBytes(utcDate.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture));
        var input = new byte[Label.Length + date.Length];
        Label.CopyTo(input, 0);
        date.CopyTo(input, Label.Length);
        var key = ak.ToArray();
        try
        {
            var hash = Sodium.GenericHash.Hash(input, key, 32);
            return hash.AsSpan(0, Size).ToArray();
        }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    /// <summary>The token as the hub puts it in <c>/v2/hub/&lt;token&gt;</c>.</summary>
    public static string Derive(ReadOnlySpan<byte> ak, DateOnly utcDate) => Base32.Encode(DeriveBytes(ak, utcDate));
}
