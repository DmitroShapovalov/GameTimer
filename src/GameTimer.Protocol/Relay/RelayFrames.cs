using System.Buffers.Binary;

namespace GameTimer.Protocol.Relay;

/// <summary>Frames on the agent's relay socket (docs/protocol.md §10.2): a type byte, then big-endian fields.</summary>
public static class RelayFrames
{
    public const byte Register = 0x01, Open = 0x02, Data = 0x03, Close = 0x04, Ping = 0x05, Pong = 0x06, Wake = 0x07;
    public const int StreamHeader = 5;
    public const int MaxNoise = 65_535;
    public const int MaxFrame = StreamHeader + MaxNoise;
    public const int MaxPingData = 8;

    public static byte[] RegisterFrame(IEnumerable<byte[]> claims)
    {
        var list = claims.ToList();
        var f = new byte[1 + 16 * list.Count];
        f[0] = Register;
        for (int i = 0; i < list.Count; i++) list[i].CopyTo(f, 1 + 16 * i);
        return f;
    }

    public static byte[] DataFrame(uint stream, ReadOnlySpan<byte> noise)
    {
        var f = WithStream(Data, stream, noise.Length);
        noise.CopyTo(f.AsSpan(StreamHeader));
        return f;
    }

    public static byte[] CloseFrame(uint stream, int code)
    {
        var f = WithStream(Close, stream, 2);
        BinaryPrimitives.WriteUInt16BigEndian(f.AsSpan(StreamHeader), (ushort)code);
        return f;
    }

    public static byte[] PingFrame(ReadOnlySpan<byte> data) => Simple(Ping, data);

    /// <summary>§10.6: wake the phone with this push token (printable ASCII).</summary>
    public static byte[] WakeFrame(string pushToken) => Simple(Wake, System.Text.Encoding.ASCII.GetBytes(pushToken));

    public static byte[] PongFrame(ReadOnlySpan<byte> data) => Simple(Pong, data);

    public static uint StreamOf(ReadOnlySpan<byte> frame) => BinaryPrimitives.ReadUInt32BigEndian(frame[1..]);

    private static byte[] WithStream(byte type, uint stream, int extra)
    {
        var f = new byte[StreamHeader + extra];
        f[0] = type;
        BinaryPrimitives.WriteUInt32BigEndian(f.AsSpan(1), stream);
        return f;
    }

    private static byte[] Simple(byte type, ReadOnlySpan<byte> data)
    {
        var f = new byte[1 + data.Length];
        f[0] = type;
        data.CopyTo(f.AsSpan(1));
        return f;
    }
}
