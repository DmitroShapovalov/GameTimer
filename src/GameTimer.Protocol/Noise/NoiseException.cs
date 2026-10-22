namespace GameTimer.Protocol.Noise;

/// <summary>
/// Any failure of the Noise layer: bad MAC, wrong key, malformed or oversized message, a degenerate
/// DH result. Per protocol §3, a peer that hits this before the handshake is confirmed closes the
/// connection silently. Misuse (wrong turn, reuse after failure or after Split) is
/// <see cref="InvalidOperationException"/> instead.
/// </summary>
public sealed class NoiseException : Exception
{
    public NoiseException(string message) : base(message) { }
    public NoiseException(string message, Exception inner) : base(message, inner) { }
}
