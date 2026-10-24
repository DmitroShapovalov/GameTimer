package com.gametimer.protocol.identity

import com.gametimer.protocol.hub.DiscoveryTxt
import com.gametimer.protocol.noise.PROTOCOL_VECTORS_JSON
import com.gametimer.protocol.noise.TestSupport
import com.gametimer.protocol.noise.hex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Discovery tags (§9) against the Python vectors, the UTC date, and the TXT reader. */
class DiscoveryTests {
    @BeforeTest
    fun initSodium() = TestSupport.initSodium()

    @Test
    fun tagsMatchTheVectors() {
        val list = Json.parseToJsonElement(PROTOCOL_VECTORS_JSON).jsonObject["discoveryTags"]!!.jsonArray
        assertTrue(list.size >= 4)
        for (v in list.map { it.jsonObject }) {
            fun s(n: String) = v[n]!!.jsonPrimitive.content
            assertEquals(s("tag"), DiscoveryTag.derive(s("ak").hex(), s("date")))
        }
    }

    @Test
    fun utcDates() {
        assertEquals("1970-01-01", DiscoveryTag.utcDate(0))
        assertEquals("1969-12-31", DiscoveryTag.utcDate(-1))
        assertEquals("2000-02-29", DiscoveryTag.utcDate(951_782_400_000))
        assertEquals("2026-10-24", DiscoveryTag.utcDate(1_792_886_399_999)) // 23:59:59.999 UTC
        assertEquals("2026-10-25", DiscoveryTag.utcDate(1_792_886_400_000))
        assertEquals("2027-01-01", DiscoveryTag.utcDate(1_798_761_600_000))
    }

    @Test
    fun candidatesCoverYesterdayTodayAndTomorrow() {
        val ak = ByteArray(32)
        val c = DiscoveryTag.candidates(ak, 1_792_886_399_999)
        assertEquals(setOf("2026-10-23", "2026-10-24", "2026-10-25").map { DiscoveryTag.derive(ak, it) }.toSet(), c)
        assertTrue("LUUJIIXZF3BDE" in c) // the vector for 2026-10-24
    }

    @Test
    fun txtTagsAreReadAcrossKeysAndOnlyForV2() {
        fun txt(vararg kv: Pair<String, String?>) = kv.associate { it.first to it.second?.encodeToByteArray() }
        assertEquals(
            setOf("LUUJIIXZF3BDE", "SFFYZQOCUF7IU", "AAAAAAAAAAAAA"),
            DiscoveryTxt.tags(txt("v" to "2", "tags" to "LUUJIIXZF3BDE,SFFYZQOCUF7IU", "TAGS2" to "AAAAAAAAAAAAA")),
        )
        assertEquals(emptySet(), DiscoveryTxt.tags(txt("v" to "3", "tags" to "LUUJIIXZF3BDE")))
        assertEquals(emptySet(), DiscoveryTxt.tags(txt("tags" to "LUUJIIXZF3BDE")))
        assertEquals(emptySet(), DiscoveryTxt.tags(txt("v" to "2", "tags" to "")))
        assertEquals(setOf("LUUJIIXZF3BDE"), DiscoveryTxt.tags(txt("v" to "2", "tags" to "short,LUUJIIXZF3BDE,luujiixzf3bde,X", "tags3" to "AAAAAAAAAAAAA")))
    }
}
