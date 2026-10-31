using System.Buffers.Binary;

namespace GameTimer.Relay;

/// <summary>Frame types on the agent socket (docs/protocol.md §10.2).</summary>
internal enum FrameType : byte
{
    Register = 0x01,
    Open = 0x02,
    Data = 0x03,
    Close = 0x04,
    Ping = 0x05,
    Pong = 0x06,
    Wake = 0x07,
}

/// <summary>Builds agent-socket frames: a type byte, then big-endian fields (§10.2).</summary>
internal static class Frames
{
    public const int ClaimSize = 16;
    public const int MaxClaims = 64;
    public const int MaxNoise = 65_535;
    public const int StreamHeader = 5;
    public const int MaxFrame = StreamHeader + MaxNoise;
    public const int MaxPingData = 8;
    public const int MaxPushToken = 4096;

    public static byte[] Open(uint stream, ReadOnlySpan<byte> route)
    {
        var b = WithStream(FrameType.Open, stream, route.Length);
        route.CopyTo(b.AsSpan(StreamHeader));
        return b;
    }

    public static byte[] Data(uint stream, ReadOnlySpan<byte> noise)
    {
        var b = WithStream(FrameType.Data, stream, noise.Length);
        noise.CopyTo(b.AsSpan(StreamHeader));
        return b;
    }

    public static byte[] Close(uint stream, int code)
    {
        var b = WithStream(FrameType.Close, stream, 2);
        BinaryPrimitives.WriteUInt16BigEndian(b.AsSpan(StreamHeader), (ushort)code);
        return b;
    }

    public static byte[] Ping(ReadOnlySpan<byte> data) => Simple(FrameType.Ping, data);

    public static byte[] Pong(ReadOnlySpan<byte> data) => Simple(FrameType.Pong, data);

    public static uint StreamOf(ReadOnlySpan<byte> frame) => BinaryPrimitives.ReadUInt32BigEndian(frame[1..]);

    /// <summary>
    /// A close code passed between hub and agent keeps its value only if an endpoint may send it
    /// (1000, 1001, 1008, 1011, 3000–4999); anything else becomes 1000 (§10.2).
    /// </summary>
    public static int MapCode(int code) =>
        code is 1000 or 1001 or 1008 or 1011 or (>= 3000 and <= 4999) ? code : 1000;

    private static byte[] WithStream(FrameType type, uint stream, int extra)
    {
        var b = new byte[StreamHeader + extra];
        b[0] = (byte)type;
        BinaryPrimitives.WriteUInt32BigEndian(b.AsSpan(1), stream);
        return b;
    }

    private static byte[] Simple(FrameType type, ReadOnlySpan<byte> data)
    {
        var b = new byte[1 + data.Length];
        b[0] = (byte)type;
        data.CopyTo(b.AsSpan(1));
        return b;
    }
}
