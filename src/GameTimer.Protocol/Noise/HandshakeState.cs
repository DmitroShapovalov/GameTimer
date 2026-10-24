namespace GameTimer.Protocol.Noise;

/// <summary>
/// Noise HandshakeState (§5.3) for the IK family (§4.1 of the protocol): the hub is the initiator and
/// knows the agent's static public key in advance (the <c>&lt;- s</c> pre-message).
/// Usage: alternate <see cref="WriteMessage"/> / <see cref="ReadMessage"/> until
/// <see cref="IsHandshakeComplete"/>, then <see cref="Split"/>. Any exception leaves the state unusable.
/// </summary>
public sealed class HandshakeState
{
    /// <summary>Noise §3: maximum message length.</summary>
    public const int MaxMessageLength = 65535;

    private readonly HandshakePattern _pattern;
    private readonly bool _initiator;
    private readonly SymmetricState _ss;
    private readonly KeyPair _s;
    private readonly byte[]? _psk;
    private readonly KeyPair? _fixedEphemeral;
    private KeyPair? _e;
    private byte[]? _rs;
    private byte[]? _re;
    private int _messageIndex;
    private bool _failed;
    private bool _split;

    /// <param name="pattern">IK for sessions, IKpsk1 for pairing.</param>
    /// <param name="initiator">True for the hub, false for the agent.</param>
    /// <param name="prologue">Protocol §4.1: ASCII <c>GameTimer/2</c>.</param>
    /// <param name="localStatic">This side's static key pair.</param>
    /// <param name="remoteStatic">Initiator: the agent's pinned public key (required). Responder: must be null — it is learned from message 1.</param>
    /// <param name="psk">32-byte pairing secret for PSK patterns; null otherwise.</param>
    public HandshakeState(NoisePattern pattern, bool initiator, ReadOnlySpan<byte> prologue, KeyPair localStatic,
        byte[]? remoteStatic = null, byte[]? psk = null)
        : this(pattern, initiator, prologue, localStatic, remoteStatic, psk, ephemeralForTesting: null)
    {
    }

    /// <param name="ephemeralForTesting">Fixed ephemeral key, for test vectors only. Internal on purpose:
    /// a reused ephemeral key reuses the message-1 keystream across sessions.</param>
    internal HandshakeState(NoisePattern pattern, bool initiator, ReadOnlySpan<byte> prologue, KeyPair localStatic,
        byte[]? remoteStatic, byte[]? psk, KeyPair? ephemeralForTesting)
    {
        _pattern = HandshakePattern.For(pattern);
        _initiator = initiator;
        _s = localStatic ?? throw new ArgumentNullException(nameof(localStatic));
        _fixedEphemeral = ephemeralForTesting;

        if (initiator)
        {
            if (remoteStatic is not { Length: Primitives.DhLen })
                throw new ArgumentException("IK initiator needs the responder's 32-byte static public key", nameof(remoteStatic));
            _rs = (byte[])remoteStatic.Clone();
        }
        else if (remoteStatic != null)
        {
            throw new ArgumentException("IK responder learns the initiator's static key from message 1", nameof(remoteStatic));
        }

        if (_pattern.IsPsk)
        {
            if (psk is not { Length: 32 }) throw new ArgumentException("PSK pattern needs a 32-byte PSK", nameof(psk));
            _psk = (byte[])psk.Clone();
        }
        else if (psk != null)
        {
            throw new ArgumentException("PSK given for a non-PSK pattern", nameof(psk));
        }

        _ss = new SymmetricState(HandshakePattern.ProtocolName(pattern));
        _ss.MixHash(prologue);
        // Pre-message "<- s": the responder's static public key
        _ss.MixHash(initiator ? _rs! : _s.PublicKeyRef);
    }

    public bool IsHandshakeComplete => _messageIndex == _pattern.Messages.Length;

    /// <summary>The peer's static public key: known up front for the initiator, after message 1 for the responder.</summary>
    public byte[]? RemoteStaticPublicKey => _rs == null ? null : (byte[])_rs.Clone();

    /// <summary>The final handshake hash <c>h</c> (protocol §8.2 derives the pairing code from it).</summary>
    public byte[] GetHandshakeHash()
    {
        if (!IsHandshakeComplete) throw new InvalidOperationException("handshake not complete");
        return _ss.HandshakeHash;
    }

    private bool IsMyTurnToWrite => (_messageIndex % 2 == 0) == _initiator;

    public byte[] WriteMessage(ReadOnlySpan<byte> payload)
    {
        Guard(write: true);
        try
        {
            using var output = new MemoryStream();
            foreach (var token in _pattern.Messages[_messageIndex])
            {
                switch (token)
                {
                    case Token.E:
                        _e = _fixedEphemeral ?? KeyPair.Generate();
                        output.Write(_e.PublicKeyRef);
                        _ss.MixHash(_e.PublicKeyRef);
                        if (_pattern.IsPsk) _ss.MixKey(_e.PublicKeyRef);
                        break;
                    case Token.S:
                        output.Write(_ss.EncryptAndHash(_s.PublicKeyRef));
                        break;
                    case Token.Psk:
                        _ss.MixKeyAndHash(_psk!);
                        break;
                    default:
                        MixDh(token);
                        break;
                }
            }
            output.Write(_ss.EncryptAndHash(payload));
            if (output.Length > MaxMessageLength) throw new NoiseException("message too long");
            _messageIndex++;
            return output.ToArray();
        }
        catch
        {
            Fail();
            throw;
        }
    }

    public byte[] ReadMessage(ReadOnlySpan<byte> message)
    {
        Guard(write: false);
        try
        {
            if (message.Length > MaxMessageLength) throw new NoiseException("message too long");
            int offset = 0;
            foreach (var token in _pattern.Messages[_messageIndex])
            {
                switch (token)
                {
                    case Token.E:
                        _re = Take(message, ref offset, Primitives.DhLen);
                        _ss.MixHash(_re);
                        if (_pattern.IsPsk) _ss.MixKey(_re);
                        break;
                    case Token.S:
                        int len = _ss.HasKey ? Primitives.DhLen + Primitives.TagLen : Primitives.DhLen;
                        _rs = _ss.DecryptAndHash(Take(message, ref offset, len));
                        break;
                    case Token.Psk:
                        _ss.MixKeyAndHash(_psk!);
                        break;
                    default:
                        MixDh(token);
                        break;
                }
            }
            var payload = _ss.DecryptAndHash(message[offset..]);
            _messageIndex++;
            return payload;
        }
        catch
        {
            Fail();
            throw;
        }
    }

    /// <summary>
    /// After the handshake: (send, receive) for this side. The initiator sends with the first Noise
    /// CipherState, the responder with the second. Allowed once: a second pair would restart the
    /// nonces at 0 under the same keys.
    /// </summary>
    public (CipherState Send, CipherState Receive) Split()
    {
        if (_failed || !IsHandshakeComplete) throw new InvalidOperationException("handshake not complete");
        if (_split) throw new InvalidOperationException("Split already called");
        _split = true;
        var (c1, c2) = _ss.Split();
        WipeSecrets(); // the ephemeral key and the PSK are not needed any more
        return _initiator ? (c1, c2) : (c2, c1);
    }

    private void Fail()
    {
        _failed = true;
        WipeSecrets();
    }

    private void WipeSecrets()
    {
        if (_fixedEphemeral == null) _e?.Dispose();
        _e = null;
        if (_psk != null) System.Security.Cryptography.CryptographicOperations.ZeroMemory(_psk);
    }

    // es / se / ee / ss, from this side's point of view (Noise §5.3)
    private void MixDh(Token token)
    {
        byte[] shared = token switch
        {
            Token.EE => Primitives.Dh(_e!.PrivateKeyRef, _re!),
            Token.ES => _initiator ? Primitives.Dh(_e!.PrivateKeyRef, _rs!) : Primitives.Dh(_s.PrivateKeyRef, _re!),
            Token.SE => _initiator ? Primitives.Dh(_s.PrivateKeyRef, _re!) : Primitives.Dh(_e!.PrivateKeyRef, _rs!),
            Token.SS => Primitives.Dh(_s.PrivateKeyRef, _rs!),
            _ => throw new InvalidOperationException(token.ToString()),
        };
        _ss.MixKey(shared);
        System.Security.Cryptography.CryptographicOperations.ZeroMemory(shared);
    }

    private void Guard(bool write)
    {
        if (_failed) throw new InvalidOperationException("handshake already failed");
        if (IsHandshakeComplete) throw new InvalidOperationException("handshake already complete");
        if (write != IsMyTurnToWrite) throw new InvalidOperationException(write ? "not this side's turn to write" : "not this side's turn to read");
    }

    private static byte[] Take(ReadOnlySpan<byte> message, ref int offset, int count)
    {
        if (message.Length - offset < count) throw new NoiseException("message too short");
        var part = message.Slice(offset, count).ToArray();
        offset += count;
        return part;
    }
}
