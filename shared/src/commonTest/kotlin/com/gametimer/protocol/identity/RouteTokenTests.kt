package com.gametimer.protocol.identity

import com.gametimer.protocol.noise.PROTOCOL_VECTORS_JSON
import com.gametimer.protocol.noise.TestSupport
import com.gametimer.protocol.noise.hex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Relay route tokens (§10.1) against the Python vectors. */
class RouteTokenTests {
    @BeforeTest
    fun initSodium() = TestSupport.initSodium()

    @Test
    fun tokensMatchTheVectors() {
        val list = Json.parseToJsonElement(PROTOCOL_VECTORS_JSON).jsonObject["routeTokens"]!!.jsonArray
        assertTrue(list.size >= 4)
        for (v in list.map { it.jsonObject }) {
            fun s(n: String) = v[n]!!.jsonPrimitive.content
            assertContentEquals(s("bytes").hex(), RouteToken.deriveBytes(s("ak").hex(), s("date")))
            val token = RouteToken.derive(s("ak").hex(), s("date"))
            assertEquals(s("token"), token)
            assertEquals(RouteToken.TEXT_LENGTH, token.length)
        }
    }

    @Test
    fun forNowUsesTheUtcDate() {
        val ak = ByteArray(32)
        assertEquals("2FBAIUXVWNHBQ67I7AADDIWXAY", RouteToken.forNow(ak, 1_792_886_399_999)) // 2026-10-24 23:59:59.999 UTC
        assertNotEquals(RouteToken.forNow(ak, 1_792_886_399_999), RouteToken.forNow(ak, 1_792_886_400_000))
    }

    @Test
    fun badInputIsRefused() {
        assertFailsWith<IllegalArgumentException> { RouteToken.derive(ByteArray(31), "2026-10-24") }
        assertFailsWith<IllegalArgumentException> { RouteToken.derive(ByteArray(32), "2026-10-24T00") }
    }
}
