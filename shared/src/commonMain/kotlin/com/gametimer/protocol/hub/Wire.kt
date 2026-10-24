package com.gametimer.protocol.hub

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.noise.CipherState
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal val PROLOGUE: ByteArray = "GameTimer/2".encodeToByteArray()

internal const val PROTOCOL_MAJOR = 2
internal const val PROTOCOL_MINOR = 0

internal fun JsonObject.type(): String? = (this["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun encodeJson(message: JsonObject): ByteArray = Json.encodeToString(JsonObject.serializer(), message).encodeToByteArray()

/** Parses a decrypted message; anything that isn't a JSON object is a protocol violation. */
internal fun decodeJson(plain: ByteArray): JsonObject =
    try {
        Json.parseToJsonElement(plain.decodeToString(throwOnInvalidSequence = true)).jsonObject
    } catch (e: Exception) {
        throw HubProtocolException("agent sent something that isn't a JSON object")
    }

internal suspend fun FrameSocket.sendJson(send: CipherState, message: JsonObject) = send(send.encrypt(encodeJson(message)))

internal suspend fun FrameSocket.receiveJson(recv: CipherState): JsonObject? {
    val frame = receive() ?: return null
    return decodeJson(recv.decrypt(frame))
}

/** §5 idempotency key: 16 random bytes, base64url. */
public fun newRid(): String = Base64Url.encode(LibsodiumRandom.buf(16).asByteArray())

/** The agent broke the protocol (bad JSON, wrong key, unexpected message). */
public class HubProtocolException(message: String) : Exception(message)
