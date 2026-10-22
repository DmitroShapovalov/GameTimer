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

    /// <param name="pattern">IK for sessions, IKpsk1 for pairing.</param>
    /// <param name="initiator">True for the hub, false for the agent.</param>
    /// <param name="prologue">Protocol §4.1: ASCII <c>GameTimer/2</c>.</param>
    /// <param name="localStatic">This side's static key pair.</param>
    /// <param name="remoteStatic">Initiator: the agent's pinned public key (required). Responder: must be null — it is learned from message 1.</param>
    /// <param name="psk">32-byte pairing secret for PSK patterns; null otherwise.</param>
    /// <param name="ephemeralForTesting">Fixed ephemeral key, for test vectors only. Never set in production.</param>
    public HandshakeState(NoisePattern pattern, bool initiator, ReadOnlySpan<byte> prologue, KeyPair localStatic,
        byte[]? remoteStatic = null, byte[]? psk = null, KeyPair? ephemeralForTesting = null)
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
        _ss.MixHash(initiator ? _rs! : _s.PublicKey);
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
                        output.Write(_e.PublicKey);
                        _ss.MixHash(_e.PublicKey);
                        if (_pattern.IsPsk) _ss.MixKey(_e.PublicKey);
                        break;
                    case Token.S:
                        output.Write(_ss.EncryptAndHash(_s.PublicKey));
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
            _failed = true;
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
            _failed = true;
            throw;
        }
    }

    /// <summary>
    /// After the handshake: (send, receive) for this side. The initiator sends with the first Noise
    /// CipherState, the responder with the second.
    /// </summary>
    public (CipherState Send, CipherState Receive) Split()
    {
        if (_failed || !IsHandshakeComplete) throw new InvalidOperationException("handshake not complete");
        var (c1, c2) = _ss.Split();
        return _initiator ? (c1, c2) : (c2, c1);
    }

    // es / se / ee / ss, from this side's point of view (Noise §5.3)
    private void MixDh(Token token)
    {
        byte[] shared = token switch
        {
            Token.EE => Primitives.Dh(_e!.PrivateKey, _re!),
            Token.ES => _initiator ? Primitives.Dh(_e!.PrivateKey, _rs!) : Primitives.Dh(_s.PrivateKey, _re!),
            Token.SE => _initiator ? Primitives.Dh(_s.PrivateKey, _re!) : Primitives.Dh(_e!.PrivateKey, _rs!),
            Token.SS => Primitives.Dh(_s.PrivateKey, _rs!),
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
