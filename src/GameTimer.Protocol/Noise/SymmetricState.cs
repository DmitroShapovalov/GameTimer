using System.Text;

namespace GameTimer.Protocol.Noise;

/// <summary>Noise SymmetricState (§5.2): chaining key, handshake hash, and the CipherState they key.</summary>
internal sealed class SymmetricState
{
    private readonly CipherState _cipher = new();
    private byte[] _ck;
    private byte[] _h;

    public SymmetricState(string protocolName)
    {
        var name = Encoding.ASCII.GetBytes(protocolName);
        if (name.Length <= Primitives.HashLen)
        {
            _h = new byte[Primitives.HashLen];
            name.CopyTo(_h, 0);
        }
        else
        {
            _h = Primitives.Hash(name);
        }
        _ck = (byte[])_h.Clone();
    }

    public bool HasKey => _cipher.HasKey;

    public byte[] HandshakeHash => (byte[])_h.Clone();

    public void MixKey(ReadOnlySpan<byte> inputKeyMaterial)
    {
        var o = Primitives.Hkdf(_ck, inputKeyMaterial, 2);
        _ck = o[0];
        _cipher.InitializeKey(o[1][..Primitives.KeyLen]); // HASHLEN 64 → truncate to 32
    }

    public void MixHash(ReadOnlySpan<byte> data)
    {
        var buf = new byte[_h.Length + data.Length];
        _h.CopyTo(buf, 0);
        data.CopyTo(buf.AsSpan(_h.Length));
        _h = Primitives.Hash(buf);
    }

    public void MixKeyAndHash(ReadOnlySpan<byte> inputKeyMaterial)
    {
        var o = Primitives.Hkdf(_ck, inputKeyMaterial, 3);
        _ck = o[0];
        MixHash(o[1]);
        _cipher.InitializeKey(o[2][..Primitives.KeyLen]);
    }

    public byte[] EncryptAndHash(ReadOnlySpan<byte> plaintext)
    {
        var ciphertext = _cipher.EncryptWithAd(_h, plaintext);
        MixHash(ciphertext);
        return ciphertext;
    }

    public byte[] DecryptAndHash(ReadOnlySpan<byte> ciphertext)
    {
        var plaintext = _cipher.DecryptWithAd(_h, ciphertext);
        MixHash(ciphertext);
        return plaintext;
    }

    public (CipherState First, CipherState Second) Split()
    {
        var o = Primitives.Hkdf(_ck, ReadOnlySpan<byte>.Empty, 2);
        var c1 = new CipherState();
        var c2 = new CipherState();
        c1.InitializeKey(o[0][..Primitives.KeyLen]);
        c2.InitializeKey(o[1][..Primitives.KeyLen]);
        return (c1, c2);
    }
}
