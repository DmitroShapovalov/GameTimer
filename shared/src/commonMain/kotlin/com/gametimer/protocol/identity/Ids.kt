package com.gametimer.protocol.identity

import com.gametimer.protocol.encodings.Base32
import com.gametimer.protocol.noise.Sodium
import com.ionspin.kotlin.crypto.generichash.GenericHash

/**
 * Public, non-secret identifiers (protocol §4.4):
 * `id = base32( BLAKE2b(outlen 32, input = TAG ‖ publicKey)[0..10) )` → 16 characters.
 * The hub computes the agent id from the pinned key and checks it against `hello.agent.id`.
 */
public object Ids {
    private val AGENT_TAG = "GameTimer agent id v2".encodeToByteArray()
    private val HUB_TAG = "GameTimer hub id v2".encodeToByteArray()

    public fun agentId(publicKey: ByteArray): String = derive(AGENT_TAG, publicKey)

    public fun hubId(publicKey: ByteArray): String = derive(HUB_TAG, publicKey)

    private fun derive(tag: ByteArray, publicKey: ByteArray): String {
        require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
        return Base32.encode(blake2b256(tag + publicKey).copyOf(10))
    }
}

internal fun blake2b256(input: ByteArray): ByteArray {
    Sodium.requireInitialized()
    return GenericHash.genericHash(input.asUByteArray(), 32, null).asByteArray()
}
