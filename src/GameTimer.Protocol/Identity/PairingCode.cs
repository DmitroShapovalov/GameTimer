using System.Buffers.Binary;

namespace GameTimer.Protocol.Identity;

/// <summary>
/// The 6-digit pairing confirmation code (protocol §8.2):
/// <c>uint32_be( BLAKE2b(outlen 32, input = "GameTimer pair code v2" ‖ h)[0..4) ) mod 1 000 000</c>,
/// zero-padded, where <c>h</c> is the final Noise handshake hash.
/// </summary>
public static class PairingCode
{
    private static readonly byte[] Label = "GameTimer pair code v2"u8.ToArray();

    public static string Derive(ReadOnlySpan<byte> handshakeHash)
    {
        var input = new byte[Label.Length + handshakeHash.Length];
        Label.CopyTo(input, 0);
        handshakeHash.CopyTo(input.AsSpan(Label.Length));
        var hash = Sodium.GenericHash.Hash(input, (byte[]?)null, 32);
        uint n = BinaryPrimitives.ReadUInt32BigEndian(hash);
        return (n % 1_000_000).ToString("D6", System.Globalization.CultureInfo.InvariantCulture);
    }
}
