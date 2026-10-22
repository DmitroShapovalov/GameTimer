namespace GameTimer.Protocol.Noise;

/// <summary>The handshake patterns protocol v2 uses (§4.1). IKpsk2 exists only to test the psk logic generally.</summary>
public enum NoisePattern
{
    /// <summary><c>/v2</c> sessions.</summary>
    IK,
    /// <summary><c>/v2/pair</c> pairing: PSK mixed in at the end of message 1.</summary>
    IKpsk1,
    /// <summary>Not used by the protocol; covered by tests so the PSK handling is checked at a second position.</summary>
    IKpsk2,
}

internal enum Token { E, S, EE, ES, SE, SS, Psk }

/// <summary>Noise §7: the token sequence of each message, and whether it is a PSK pattern.</summary>
internal sealed class HandshakePattern
{
    public required string Name { get; init; }
    public required Token[][] Messages { get; init; }
    public bool IsPsk => Messages.Any(m => m.Contains(Token.Psk));

    // IK:  <- s   ...   -> e, es, s, ss   <- e, ee, se
    private static readonly HandshakePattern Ik = new()
    {
        Name = "IK",
        Messages = new[] { new[] { Token.E, Token.ES, Token.S, Token.SS }, new[] { Token.E, Token.EE, Token.SE } },
    };

    private static readonly HandshakePattern IkPsk1 = new()
    {
        Name = "IKpsk1",
        Messages = new[] { new[] { Token.E, Token.ES, Token.S, Token.SS, Token.Psk }, new[] { Token.E, Token.EE, Token.SE } },
    };

    private static readonly HandshakePattern IkPsk2 = new()
    {
        Name = "IKpsk2",
        Messages = new[] { new[] { Token.E, Token.ES, Token.S, Token.SS }, new[] { Token.E, Token.EE, Token.SE, Token.Psk } },
    };

    public static HandshakePattern For(NoisePattern pattern) => pattern switch
    {
        NoisePattern.IK => Ik,
        NoisePattern.IKpsk1 => IkPsk1,
        NoisePattern.IKpsk2 => IkPsk2,
        _ => throw new ArgumentOutOfRangeException(nameof(pattern)),
    };

    public static string ProtocolName(NoisePattern pattern) => $"Noise_{For(pattern).Name}_25519_ChaChaPoly_BLAKE2b";
}
