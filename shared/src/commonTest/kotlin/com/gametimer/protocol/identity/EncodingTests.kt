package com.gametimer.protocol.identity

import com.gametimer.protocol.encodings.Base32
import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.noise.PROTOCOL_VECTORS_JSON
import com.gametimer.protocol.noise.TestSupport
import com.gametimer.protocol.noise.hex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ids, pairing codes and encodings against tests/vectors/protocol-vectors.json (independent Python). */
class EncodingTests {
    @BeforeTest
    fun initSodium() = TestSupport.initSodium()

    private val vectors = Json.parseToJsonElement(PROTOCOL_VECTORS_JSON).jsonObject

    private fun list(name: String) = vectors[name]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.s(name: String) = this[name]!!.jsonPrimitive.content

    @Test
    fun idsMatchTheVectors() {
        for (v in list("ids")) {
            val key = v.s("publicKey").hex()
            assertEquals(v.s("agentId"), Ids.agentId(key))
            assertEquals(v.s("hubId"), Ids.hubId(key))
        }
    }

    @Test
    fun pairingCodesMatchTheVectors() {
        for (v in list("pairingCodes")) assertEquals(v.s("code"), PairingCode.derive(v.s("handshakeHash").hex()))
    }

    @Test
    fun encodingsMatchTheVectors() {
        for (v in list("base32")) assertEquals(v.s("text"), Base32.encode(v.s("hex").hex()))
        for (v in list("base64url")) {
            assertEquals(v.s("text"), Base64Url.encode(v.s("hex").hex()))
            assertContentEquals(v.s("hex").hex(), Base64Url.tryDecode(v.s("text")))
        }
    }

    @Test
    fun base64UrlDecodingIsStrict() {
        for (s in listOf("Zm9vYg==", "Zm9vYg+/", "Zm9vYh", "Z")) assertNull(Base64Url.tryDecode(s), s)
    }
}
