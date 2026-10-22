using System.Security.Cryptography;

namespace GameTimer.Protocol.Noise;

/// <summary>An X25519 key pair (static or ephemeral).</summary>
public sealed class KeyPair : IDisposable
{
    public byte[] PrivateKey { get; }
    public byte[] PublicKey { get; }

    private KeyPair(byte[] privateKey)
    {
        PrivateKey = privateKey;
        PublicKey = Primitives.PublicKeyFor(privateKey);
    }

    /// <summary>A new key pair from the OS CSPRNG.</summary>
    public static KeyPair Generate() => new(RandomNumberGenerator.GetBytes(Primitives.DhLen));

    /// <summary>Rebuilds a key pair from a stored private key (the public key is derived).</summary>
    public static KeyPair FromPrivateKey(ReadOnlySpan<byte> privateKey)
    {
        if (privateKey.Length != Primitives.DhLen) throw new ArgumentException("X25519 private key must be 32 bytes");
        return new KeyPair(privateKey.ToArray());
    }

    public void Dispose() => CryptographicOperations.ZeroMemory(PrivateKey);
}
