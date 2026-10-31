package com.gametimer.protocol.identity

import com.gametimer.protocol.encodings.Base32
import com.gametimer.protocol.noise.Sodium
import com.ionspin.kotlin.crypto.generichash.GenericHash

/**
 * The daily relay route token of this hub on one agent (protocol §10.1):
 * `BLAKE2b(outlen 32, key = ak, input = "GameTimer route v2" ‖ UTC date "YYYY-MM-DD")[0..16)`,
 * sent as 26 base32 characters in `wss://<relay>/v2/hub/<token>`.
 */
public object RouteToken {
    public const val SIZE: Int = 16
    public const val TEXT_LENGTH: Int = 26

    private val LABEL = "GameTimer route v2".encodeToByteArray()

    /** [utcDate] is `YYYY-MM-DD`. */
    public fun deriveBytes(ak: ByteArray, utcDate: String): ByteArray {
        require(ak.size == 32) { "ak must be 32 bytes" }
        require(DATE.matches(utcDate)) { "date must be YYYY-MM-DD" }
        Sodium.requireInitialized()
        val hash = GenericHash.genericHash((LABEL + utcDate.encodeToByteArray()).asUByteArray(), 32, ak.asUByteArray())
        return hash.asByteArray().copyOf(SIZE)
    }

    /** [utcDate] is `YYYY-MM-DD`. */
    public fun derive(ak: ByteArray, utcDate: String): String = Base32.encode(deriveBytes(ak, utcDate))

    /**
     * The token to dial with at [nowEpochMillis]: today's by this phone's clock. The agent registers
     * yesterday's, today's and tomorrow's, so clocks a day apart still meet.
     */
    public fun forNow(ak: ByteArray, nowEpochMillis: Long): String = derive(ak, DiscoveryTag.utcDate(nowEpochMillis))

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
