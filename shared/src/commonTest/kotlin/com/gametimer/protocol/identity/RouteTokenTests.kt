package com.gametimer.protocol.identity

import com.gametimer.protocol.noise.PROTOCOL_VECTORS_JSON
import com.gametimer.protocol.noise.TestSupport
import com.gametimer.protocol.noise.hex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Relay route claims and routes (§10.1) against the Python vectors. */
class RouteTokenTests {
    @BeforeTest
    fun initSodium() = TestSupport.initSodium()

    @Test
    fun claimsAndRoutesMatchTheVectors() {
        val list = Json.parseToJsonElement(PROTOCOL_VECTORS_JSON).jsonObject["routeTokens"]!!.jsonArray
        assertTrue(list.size >= 6)
        for (v in list.map { it.jsonObject }) {
            fun s(n: String) = v[n]!!.jsonPrimitive.content
            val claim = RouteToken.claim(s("ak").hex(), s("date"))
            assertContentEquals(s("claim").hex(), claim)
            assertContentEquals(s("route").hex(), RouteToken.route(claim))
            val token = RouteToken.derive(s("ak").hex(), s("date"))
            assertEquals(s("token"), token)
            assertEquals(RouteToken.TEXT_LENGTH, token.length)
        }
    }

    @Test
    fun forNowIsTodayThenTomorrowThenYesterdayInUtc() {
        val ak = ByteArray(32)
        val t = 1_792_886_399_999L // 2026-10-24 23:59:59.999 UTC
        assertEquals(listOf("2026-10-24", "2026-10-25", "2026-10-23").map { RouteToken.derive(ak, it) }, RouteToken.forNow(ak, t))
        assertEquals(RouteToken.derive(ak, "2026-10-25"), RouteToken.forNow(ak, t + 1).first())
    }

    @Test
    fun badInputIsRefused() {
        assertFailsWith<IllegalArgumentException> { RouteToken.claim(ByteArray(31), "2026-10-24") }
        assertFailsWith<IllegalArgumentException> { RouteToken.claim(ByteArray(32), "2026-10-24T00") }
        assertFailsWith<IllegalArgumentException> { RouteToken.route(ByteArray(15)) }
    }
}
