namespace GameTimer.Protocol.Noise;

/// <summary>
/// Any failure of the Noise layer: bad MAC, wrong key, malformed or oversized message, wrong turn,
/// a degenerate DH result. Per protocol §3, a peer that hits this before the handshake is confirmed
/// closes the connection silently.
/// </summary>
public sealed class NoiseException : Exception
{
    public NoiseException(string message) : base(message) { }
    public NoiseException(string message, Exception inner) : base(message, inner) { }
}
