package com.gametimer.protocol.agent

/** Frames on the agent's relay socket (docs/protocol.md §10.2): a type byte, then big-endian fields. Mirrors the C# `RelayFrames`. */
public object RelayFrames {
    public const val REGISTER: Byte = 0x01
    public const val OPEN: Byte = 0x02
    public const val DATA: Byte = 0x03
    public const val CLOSE: Byte = 0x04
    public const val PING: Byte = 0x05
    public const val PONG: Byte = 0x06
    public const val WAKE: Byte = 0x07
    public const val STREAM_HEADER: Int = 5
    public const val MAX_NOISE: Int = 65_535
    public const val MAX_FRAME: Int = STREAM_HEADER + MAX_NOISE
    public const val MAX_PING_DATA: Int = 8

    public fun register(claims: List<ByteArray>): ByteArray {
        val f = ByteArray(1 + 16 * claims.size)
        f[0] = REGISTER
        claims.forEachIndexed { i, c ->
            require(c.size == 16) { "a claim is 16 bytes" }
            c.copyInto(f, 1 + 16 * i)
        }
        return f
    }

    public fun data(stream: UInt, noise: ByteArray): ByteArray {
        val f = withStream(DATA, stream, noise.size)
        noise.copyInto(f, STREAM_HEADER)
        return f
    }

    public fun close(stream: UInt, code: Int): ByteArray {
        val f = withStream(CLOSE, stream, 2)
        f[STREAM_HEADER] = (code shr 8).toByte()
        f[STREAM_HEADER + 1] = code.toByte()
        return f
    }

    public fun ping(data: ByteArray = ByteArray(0)): ByteArray = simple(PING, data)

    public fun pong(data: ByteArray): ByteArray = simple(PONG, data)

    /** §10.6: wake the phone with this push token (printable ASCII). */
    public fun wake(pushToken: String): ByteArray = simple(WAKE, pushToken.encodeToByteArray())

    public fun streamOf(frame: ByteArray): UInt =
        ((frame[1].toUInt() and 0xffu) shl 24) or ((frame[2].toUInt() and 0xffu) shl 16) or
            ((frame[3].toUInt() and 0xffu) shl 8) or (frame[4].toUInt() and 0xffu)

    private fun withStream(type: Byte, stream: UInt, extra: Int): ByteArray {
        val f = ByteArray(STREAM_HEADER + extra)
        f[0] = type
        f[1] = (stream shr 24).toByte(); f[2] = (stream shr 16).toByte(); f[3] = (stream shr 8).toByte(); f[4] = stream.toByte()
        return f
    }

    private fun simple(type: Byte, data: ByteArray): ByteArray {
        val f = ByteArray(1 + data.size)
        f[0] = type
        data.copyInto(f, 1)
        return f
    }
}
