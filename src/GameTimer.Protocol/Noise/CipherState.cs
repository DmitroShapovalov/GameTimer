namespace GameTimer.Protocol.Noise;

/// <summary>
/// Noise CipherState (§5.1): a key and a 64-bit nonce counter. After the handshake one is used per
/// direction. Decrypting a replayed or reordered message fails because the counter has moved on.
/// </summary>
public sealed class CipherState
{
    private byte[]? _key;
    private ulong _nonce;

    public bool HasKey => _key != null;

    internal void InitializeKey(byte[]? key)
    {
        _key = key;
        _nonce = 0;
    }

    public byte[] EncryptWithAd(ReadOnlySpan<byte> ad, ReadOnlySpan<byte> plaintext)
    {
        if (_key == null) return plaintext.ToArray();
        // 2^64-1 is reserved (§5.1): refuse rather than wrap
        if (_nonce == ulong.MaxValue) throw new NoiseException("nonce exhausted");
        var ciphertext = Primitives.Encrypt(_key, _nonce, ad, plaintext);
        _nonce++;
        return ciphertext;
    }

    public byte[] DecryptWithAd(ReadOnlySpan<byte> ad, ReadOnlySpan<byte> ciphertext)
    {
        if (_key == null) return ciphertext.ToArray();
        if (_nonce == ulong.MaxValue) throw new NoiseException("nonce exhausted");
        var plaintext = Primitives.Decrypt(_key, _nonce, ad, ciphertext); // throws without advancing
        _nonce++;
        return plaintext;
    }

    /// <summary>Transport encryption: no associated data (Noise §5.2 Split, then EncryptWithAd with empty ad).</summary>
    public byte[] Encrypt(ReadOnlySpan<byte> plaintext) => EncryptWithAd(ReadOnlySpan<byte>.Empty, plaintext);

    public byte[] Decrypt(ReadOnlySpan<byte> ciphertext) => DecryptWithAd(ReadOnlySpan<byte>.Empty, ciphertext);
}
