using GameTimer.Protocol.Encodings;

namespace GameTimer.Protocol.Identity;

/// <summary>
/// The daily discovery tag of one hub (protocol §9):
/// <c>base32( BLAKE2b(outlen 32, key = ak, input = "GameTimer discovery v2" ‖ UTC date "YYYY-MM-DD")[0..8) )</c>
/// → 13 characters. Meaningless without the hub's <c>ak</c>, and different every day.
/// </summary>
public static class DiscoveryTag
{
    private static readonly byte[] Label = "GameTimer discovery v2"u8.ToArray();

    public static string Derive(ReadOnlySpan<byte> ak, DateOnly utcDate)
    {
        if (ak.Length != 32) throw new ArgumentException("ak must be 32 bytes");
        var date = System.Text.Encoding.ASCII.GetBytes(utcDate.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture));
        var input = new byte[Label.Length + date.Length];
        Label.CopyTo(input, 0);
        date.CopyTo(input, Label.Length);
        var hash = Sodium.GenericHash.Hash(input, ak.ToArray(), 32);
        return Base32.Encode(hash.AsSpan(0, 8));
    }
}
