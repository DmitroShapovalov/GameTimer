using System.Buffers.Binary;
using System.Security.Cryptography;

namespace GameTimer.Protocol.Noise;

/// <summary>
/// The three Noise primitives for <c>25519_ChaChaPoly_BLAKE2b</c> (Noise spec rev 34 §4), on libsodium,
/// plus HMAC / HKDF built exactly as §4.3 defines them. Nothing here is a custom construction.
/// </summary>
internal static class Primitives
{
    public const int DhLen = 32;        // X25519
    public const int KeyLen = 32;       // ChaCha20-Poly1305 key
    public const int TagLen = 16;       // Poly1305 tag
    public const int HashLen = 64;      // BLAKE2b
    private const int BlockLen = 128;   // BLAKE2b block size, for HMAC

    // ---------- DH: X25519 ----------

    public static byte[] PublicKeyFor(byte[] privateKey) => Sodium.ScalarMult.Base(privateKey);

    /// <summary>X25519. An all-zero result (low-order point) aborts the handshake (protocol §4.5).</summary>
    public static byte[] Dh(byte[] privateKey, byte[] publicKey)
    {
        if (publicKey.Length != DhLen) throw new NoiseException("bad public key length");
        byte[] shared;
        try
        {
            shared = Sodium.ScalarMult.Mult(privateKey, publicKey);
        }
        catch (Exception ex)
        {
            throw new NoiseException("X25519 failed", ex);
        }
        if (CryptographicOperations.FixedTimeEquals(shared, new byte[DhLen]))
            throw new NoiseException("X25519 produced an all-zero result");
        return shared;
    }

    // ---------- Cipher: ChaCha20-Poly1305 (IETF), Noise nonce encoding ----------

    /// <summary>Noise §5.1: 32 bits of zeros followed by the 64-bit counter, little-endian.</summary>
    private static byte[] Nonce(ulong n)
    {
        var nonce = new byte[12];
        BinaryPrimitives.WriteUInt64LittleEndian(nonce.AsSpan(4), n);
        return nonce;
    }

    public static byte[] Encrypt(byte[] key, ulong n, ReadOnlySpan<byte> ad, ReadOnlySpan<byte> plaintext) =>
        Sodium.SecretAeadChaCha20Poly1305IETF.Encrypt(plaintext.ToArray(), Nonce(n), key, ad.ToArray());

    public static byte[] Decrypt(byte[] key, ulong n, ReadOnlySpan<byte> ad, ReadOnlySpan<byte> ciphertext)
    {
        if (ciphertext.Length < TagLen) throw new NoiseException("ciphertext shorter than the tag");
        try
        {
            return Sodium.SecretAeadChaCha20Poly1305IETF.Decrypt(ciphertext.ToArray(), Nonce(n), key, ad.ToArray());
        }
        catch (Exception ex)
        {
            throw new NoiseException("decryption failed", ex);
        }
    }

    // ---------- Hash: BLAKE2b-512, HMAC, HKDF (Noise §4.3) ----------

    public static byte[] Hash(ReadOnlySpan<byte> data) => Sodium.GenericHash.Hash(data.ToArray(), (byte[]?)null, HashLen);

    /// <summary>HMAC (RFC 2104) over BLAKE2b with its 128-byte block — the Noise HMAC, not keyed BLAKE2b.</summary>
    public static byte[] Hmac(ReadOnlySpan<byte> key, ReadOnlySpan<byte> data)
    {
        var k = new byte[BlockLen];
        if (key.Length > BlockLen) Hash(key).CopyTo(k, 0);
        else key.CopyTo(k);

        var inner = new byte[BlockLen + data.Length];
        var outer = new byte[BlockLen + HashLen];
        for (int i = 0; i < BlockLen; i++)
        {
            inner[i] = (byte)(k[i] ^ 0x36);
            outer[i] = (byte)(k[i] ^ 0x5c);
        }
        data.CopyTo(inner.AsSpan(BlockLen));
        Hash(inner).CopyTo(outer, BlockLen);
        var result = Hash(outer);
        CryptographicOperations.ZeroMemory(k);
        return result;
    }

    /// <summary>Noise HKDF: 2 or 3 outputs of HASHLEN bytes.</summary>
    public static byte[][] Hkdf(ReadOnlySpan<byte> chainingKey, ReadOnlySpan<byte> inputKeyMaterial, int outputs)
    {
        if (outputs is < 2 or > 3) throw new ArgumentOutOfRangeException(nameof(outputs));
        var tempKey = Hmac(chainingKey, inputKeyMaterial);
        var o1 = Hmac(tempKey, new byte[] { 0x01 });
        var o2 = Hmac(tempKey, Concat(o1, 0x02));
        if (outputs == 2)
        {
            CryptographicOperations.ZeroMemory(tempKey);
            return new[] { o1, o2 };
        }
        var o3 = Hmac(tempKey, Concat(o2, 0x03));
        CryptographicOperations.ZeroMemory(tempKey);
        return new[] { o1, o2, o3 };
    }

    private static byte[] Concat(byte[] a, byte b)
    {
        var r = new byte[a.Length + 1];
        a.CopyTo(r, 0);
        r[^1] = b;
        return r;
    }
}
