using GameTimer.Protocol.Encodings;

namespace GameTimer.Protocol.Identity;

/// <summary>
/// Public, non-secret identifiers (protocol §4.4):
/// <c>id = base32( BLAKE2b(outlen 32, input = TAG ‖ publicKey)[0..10) )</c> → 16 characters.
/// </summary>
public static class Ids
{
    private static readonly byte[] AgentTag = "GameTimer agent id v2"u8.ToArray();
    private static readonly byte[] HubTag = "GameTimer hub id v2"u8.ToArray();

    public static string AgentId(ReadOnlySpan<byte> publicKey) => Derive(AgentTag, publicKey);

    public static string HubId(ReadOnlySpan<byte> publicKey) => Derive(HubTag, publicKey);

    private static string Derive(byte[] tag, ReadOnlySpan<byte> publicKey)
    {
        if (publicKey.Length != 32) throw new ArgumentException("X25519 public key must be 32 bytes");
        var input = new byte[tag.Length + 32];
        tag.CopyTo(input, 0);
        publicKey.CopyTo(input.AsSpan(tag.Length));
        var hash = Sodium.GenericHash.Hash(input, (byte[]?)null, 32);
        return Base32.Encode(hash.AsSpan(0, 10));
    }
}
