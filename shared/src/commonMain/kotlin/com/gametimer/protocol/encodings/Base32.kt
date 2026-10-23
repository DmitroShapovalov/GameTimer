package com.gametimer.protocol.encodings

/** RFC 4648 base32, uppercase, no padding (protocol §4.5). Mirrors the C# `Base32`. */
public object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    public fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1) // keep only the unread bits
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}
