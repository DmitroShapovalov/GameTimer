namespace GameTimer.Protocol.Encodings;

/// <summary>RFC 4648 §5 base64url without padding (protocol §4.5). Decoding is strict.</summary>
public static class Base64Url
{
    public static string Encode(ReadOnlySpan<byte> data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>Decodes, or returns null for anything that is not canonical unpadded base64url.</summary>
    public static byte[]? TryDecode(string? s)
    {
        if (s == null || s.Length % 4 == 1) return null;
        foreach (var c in s)
            if (!(c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_')) return null;
        var b64 = s.Replace('-', '+').Replace('_', '/');
        b64 += (b64.Length % 4) switch { 2 => "==", 3 => "=", _ => "" };
        byte[] bytes;
        try { bytes = Convert.FromBase64String(b64); }
        catch (FormatException) { return null; }
        // Reject non-canonical input (stray bits in the last character)
        return Encode(bytes) == s ? bytes : null;
    }
}
