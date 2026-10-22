using System.Security.Cryptography;

namespace GameTimer.Protocol.Noise;

/// <summary>An X25519 key pair (static or ephemeral). Not usable after <see cref="Dispose"/>.</summary>
public sealed class KeyPair : IDisposable
{
    private readonly byte[] _privateKey;
    private readonly byte[] _publicKey;
    private bool _disposed;

    private KeyPair(byte[] privateKey)
    {
        _privateKey = privateKey;
        _publicKey = Primitives.PublicKeyFor(privateKey);
    }

    /// <summary>A copy of the private key, for storing it (DPAPI on the agent).</summary>
    public byte[] PrivateKey => (byte[])PrivateKeyRef.Clone();

    /// <summary>A copy of the public key.</summary>
    public byte[] PublicKey => (byte[])PublicKeyRef.Clone();

    internal byte[] PrivateKeyRef
    {
        get
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            return _privateKey;
        }
    }

    internal byte[] PublicKeyRef
    {
        get
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            return _publicKey;
        }
    }

    /// <summary>A new key pair from the OS CSPRNG.</summary>
    public static KeyPair Generate() => new(RandomNumberGenerator.GetBytes(Primitives.DhLen));

    /// <summary>Rebuilds a key pair from a stored private key (the public key is derived).</summary>
    public static KeyPair FromPrivateKey(ReadOnlySpan<byte> privateKey)
    {
        if (privateKey.Length != Primitives.DhLen) throw new ArgumentException("X25519 private key must be 32 bytes");
        return new KeyPair(privateKey.ToArray());
    }

    /// <summary>Overwrites the private key in memory; any later use throws.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        CryptographicOperations.ZeroMemory(_privateKey);
    }
}
