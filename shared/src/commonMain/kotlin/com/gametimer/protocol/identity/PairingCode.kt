package com.gametimer.protocol.identity

/**
 * The 6-digit pairing confirmation code (protocol §8.2), shown on the phone and typed in on the PC:
 * `uint32_be( BLAKE2b(outlen 32, input = "GameTimer pair code v2" ‖ h)[0..4) ) mod 1 000 000`, zero-padded.
 */
public object PairingCode {
    private val LABEL = "GameTimer pair code v2".encodeToByteArray()

    public fun derive(handshakeHash: ByteArray): String {
        val h = blake2b256(LABEL + handshakeHash)
        val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }
}
