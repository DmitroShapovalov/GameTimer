package com.gametimer.protocol.noise

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs the published Noise test vectors (cacophony, snow) from tests/vectors — the same file the C#
 * tests use, embedded at build time (NOISE_VECTORS_JSON). Common code: runs on JVM and the iOS simulator.
 * Both sides are driven with the vector's fixed keys and must reproduce its ciphertexts.
 */
class NoiseVectorTests {
    @BeforeTest
    fun initSodium() = TestSupport.initSodium()

    private fun JsonObject.bytes(name: String): ByteArray = this[name]?.jsonPrimitive?.content?.hex() ?: ByteArray(0)

    private fun JsonObject.psk(name: String): ByteArray? =
        this[name]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.hex()

    @Test
    fun allVectorsMatch() {
        val vectors = Json.parseToJsonElement(NOISE_VECTORS_JSON).jsonObject["vectors"]!!.jsonArray
        val names = vectors.map { it.jsonObject["protocol_name"]!!.jsonPrimitive.content }
        assertTrue(vectors.size >= 4, "expected at least 4 vectors")
        assertTrue("Noise_IK_25519_ChaChaPoly_BLAKE2b" in names && "Noise_IKpsk1_25519_ChaChaPoly_BLAKE2b" in names)
        for (element in vectors) runVector(element.jsonObject)
    }

    private fun runVector(v: JsonObject) {
        val name = v["protocol_name"]!!.jsonPrimitive.content
        val label = "${v["source"]?.jsonPrimitive?.content}: $name"
        val pattern = when (name) {
            "Noise_IK_25519_ChaChaPoly_BLAKE2b" -> NoisePattern.IK
            "Noise_IKpsk1_25519_ChaChaPoly_BLAKE2b" -> NoisePattern.IKpsk1
            "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2b" -> NoisePattern.IKpsk2
            else -> error(name)
        }

        val initiator = HandshakeState(
            pattern, true, v.bytes("init_prologue"), KeyPair.fromPrivateKey(v.bytes("init_static")),
            remoteStatic = v.bytes("init_remote_static"), psk = v.psk("init_psks"),
            ephemeralForTesting = KeyPair.fromPrivateKey(v.bytes("init_ephemeral")),
        )
        val responder = HandshakeState(
            pattern, false, v.bytes("resp_prologue"), KeyPair.fromPrivateKey(v.bytes("resp_static")),
            remoteStatic = null, psk = v.psk("resp_psks"), ephemeralForTesting = KeyPair.fromPrivateKey(v.bytes("resp_ephemeral")),
        )

        var iSend: CipherState? = null
        var iRecv: CipherState? = null
        var rSend: CipherState? = null
        var rRecv: CipherState? = null
        val messages = v["messages"]!!.jsonArray
        for ((i, m) in messages.withIndex()) {
            val payload = m.jsonObject.bytes("payload")
            val expected = m.jsonObject.bytes("ciphertext")
            val fromInitiator = i % 2 == 0

            if (!initiator.isHandshakeComplete) {
                val (writer, reader) = if (fromInitiator) initiator to responder else responder to initiator
                assertEquals(expected.hex(), writer.writeMessage(payload).hex(), "$label msg $i")
                assertEquals(payload.hex(), reader.readMessage(expected).hex(), "$label msg $i read")
                if (initiator.isHandshakeComplete) {
                    assertTrue(responder.isHandshakeComplete, label)
                    assertEquals(initiator.handshakeHash().hex(), responder.handshakeHash().hex(), label)
                    if (v.containsKey("handshake_hash")) { // cacophony has it, snow doesn't
                        assertEquals(v.bytes("handshake_hash").hex(), initiator.handshakeHash().hex(), "$label hash")
                    }
                    assertEquals(
                        KeyPair.fromPrivateKey(v.bytes("init_static")).publicKey.hex(),
                        responder.remoteStaticPublicKey!!.hex(), label,
                    )
                    initiator.split().let { iSend = it.first; iRecv = it.second }
                    responder.split().let { rSend = it.first; rRecv = it.second }
                }
                continue
            }

            val (send, recv) = if (fromInitiator) iSend!! to rRecv!! else rSend!! to iRecv!!
            assertEquals(expected.hex(), send.encrypt(payload).hex(), "$label transport $i")
            assertEquals(payload.hex(), recv.decrypt(expected).hex(), "$label transport $i read")
        }
        assertTrue(initiator.isHandshakeComplete, "$label: handshake did not complete")
    }
}
