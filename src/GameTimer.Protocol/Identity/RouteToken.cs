using System.Security.Cryptography;
using GameTimer.Protocol.Encodings;

namespace GameTimer.Protocol.Identity;

/// <summary>
/// Relay route tokens of one hub (protocol §10.1), in two parts so that a token seen in a log can't be
/// registered by someone else:
/// <list type="bullet">
/// <item><c>claim = BLAKE2b(outlen 32, key = ak, input = "GameTimer route v2" ‖ UTC date "YYYY-MM-DD")[0..16)</c>:
/// what the agent registers; only the hub and the agent can compute it.</item>
/// <item><c>route = BLAKE2b(outlen 32, input = "GameTimer route id v2" ‖ claim)[0..16)</c>: what the hub dials
/// (26 base32 characters in <c>/v2/hub/&lt;token&gt;</c>); the relay computes it from the claim.</item>
/// </list>
/// </summary>
public static class RouteToken
{
    public const int Size = 16;
    public const int TextLength = 26;

    private static readonly byte[] ClaimLabel = "GameTimer route v2"u8.ToArray();
    private static readonly byte[] RouteLabel = "GameTimer route id v2"u8.ToArray();

    /// <summary>The claim the agent registers for this hub and day.</summary>
    public static byte[] Claim(ReadOnlySpan<byte> ak, DateOnly utcDate)
    {
        if (ak.Length != 32) throw new ArgumentException("ak must be 32 bytes");
        var date = System.Text.Encoding.ASCII.GetBytes(utcDate.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture));
        var key = ak.ToArray();
        try { return Sodium.GenericHash.Hash(Concat(ClaimLabel, date), key, 32).AsSpan(0, Size).ToArray(); }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    /// <summary>The public route of a claim (what the hub dials, and the relay's index).</summary>
    public static byte[] Route(ReadOnlySpan<byte> claim)
    {
        if (claim.Length != Size) throw new ArgumentException("claim must be 16 bytes");
        return Sodium.GenericHash.Hash(Concat(RouteLabel, claim), null, 32).AsSpan(0, Size).ToArray();
    }

    /// <summary>The route as the hub puts it in <c>/v2/hub/&lt;token&gt;</c>.</summary>
    public static string Derive(ReadOnlySpan<byte> ak, DateOnly utcDate) => Base32.Encode(Route(Claim(ak, utcDate)));

    private static byte[] Concat(byte[] label, ReadOnlySpan<byte> data)
    {
        var input = new byte[label.Length + data.Length];
        label.CopyTo(input, 0);
        data.CopyTo(input.AsSpan(label.Length));
        return input;
    }
}
