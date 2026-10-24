package com.gametimer.protocol.identity

import com.gametimer.protocol.encodings.Base32
import com.gametimer.protocol.noise.Sodium
import com.ionspin.kotlin.crypto.generichash.GenericHash

/**
 * The daily discovery tag of this hub on one agent (protocol §9):
 * `base32( BLAKE2b(outlen 32, key = ak, input = "GameTimer discovery v2" ‖ UTC date "YYYY-MM-DD")[0..8) )`.
 */
public object DiscoveryTag {
    private val LABEL = "GameTimer discovery v2".encodeToByteArray()
    private const val DAY_MILLIS = 86_400_000L

    /** [utcDate] is `YYYY-MM-DD`. */
    public fun derive(ak: ByteArray, utcDate: String): String {
        require(ak.size == 32) { "ak must be 32 bytes" }
        require(DATE.matches(utcDate)) { "date must be YYYY-MM-DD" }
        Sodium.requireInitialized()
        val hash = GenericHash.genericHash((LABEL + utcDate.encodeToByteArray()).asUByteArray(), 32, ak.asUByteArray())
        return Base32.encode(hash.asByteArray().copyOf(8))
    }

    /**
     * The tags the agent may be announcing at [nowEpochMillis]: yesterday, today and tomorrow (UTC), so
     * the search works around midnight and with a PC clock that is somewhat off.
     */
    public fun candidates(ak: ByteArray, nowEpochMillis: Long): Set<String> =
        (-1..1).map { derive(ak, utcDate(nowEpochMillis + it * DAY_MILLIS)) }.toSet()

    /** `YYYY-MM-DD` of an instant, in UTC (proleptic Gregorian; H. Hinnant's civil-from-days). */
    public fun utcDate(epochMillis: Long): String {
        val z = epochMillis.floorDiv(DAY_MILLIS) + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
