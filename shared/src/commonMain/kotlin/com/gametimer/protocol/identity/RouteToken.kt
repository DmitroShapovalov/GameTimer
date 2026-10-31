package com.gametimer.protocol.identity

import com.gametimer.protocol.encodings.Base32
import com.gametimer.protocol.noise.Sodium
import com.ionspin.kotlin.crypto.generichash.GenericHash

/**
 * Relay route tokens of this hub on one agent (protocol §10.1):
 * - `claim = BLAKE2b(outlen 32, key = ak, input = "GameTimer route v2" ‖ UTC date "YYYY-MM-DD")[0..16)`,
 *   registered by the agent;
 * - `route = BLAKE2b(outlen 32, input = "GameTimer route id v2" ‖ claim)[0..16)`, dialled by the hub as
 *   26 base32 characters in `wss://<relay>/v2/hub/<token>`.
 */
public object RouteToken {
    public const val SIZE: Int = 16
    public const val TEXT_LENGTH: Int = 26

    private const val DAY_MILLIS = 86_400_000L
    private val CLAIM_LABEL = "GameTimer route v2".encodeToByteArray()
    private val ROUTE_LABEL = "GameTimer route id v2".encodeToByteArray()

    /** [utcDate] is `YYYY-MM-DD`. */
    public fun claim(ak: ByteArray, utcDate: String): ByteArray {
        require(ak.size == 32) { "ak must be 32 bytes" }
        require(DATE.matches(utcDate)) { "date must be YYYY-MM-DD" }
        Sodium.requireInitialized()
        val hash = GenericHash.genericHash((CLAIM_LABEL + utcDate.encodeToByteArray()).asUByteArray(), 32, ak.asUByteArray())
        return hash.asByteArray().copyOf(SIZE)
    }

    public fun route(claim: ByteArray): ByteArray {
        require(claim.size == SIZE) { "claim must be 16 bytes" }
        Sodium.requireInitialized()
        return GenericHash.genericHash((ROUTE_LABEL + claim).asUByteArray(), 32).asByteArray().copyOf(SIZE)
    }

    /** The token to dial for [utcDate] (`YYYY-MM-DD`). */
    public fun derive(ak: ByteArray, utcDate: String): String = Base32.encode(route(claim(ak, utcDate)))

    /**
     * The tokens to dial at [nowEpochMillis], in order: today's by this phone's clock, then tomorrow's and
     * yesterday's (tried once after a 404, for a PC clock that is a day off; §10.3).
     */
    public fun forNow(ak: ByteArray, nowEpochMillis: Long): List<String> =
        listOf(0, 1, -1).map { derive(ak, DiscoveryTag.utcDate(nowEpochMillis + it * DAY_MILLIS)) }

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
