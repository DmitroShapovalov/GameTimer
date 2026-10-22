package com.gametimer.protocol.noise

/**
 * Noise CipherState (§5.1): a key and a 64-bit nonce counter. After the handshake one is used per
 * direction. Decrypting a replayed or reordered message fails because the counter has moved on.
 */
public class CipherState internal constructor() {
    private var key: ByteArray? = null
    private var nonce: ULong = 0u

    public val hasKey: Boolean get() = key != null

    internal fun initializeKey(k: ByteArray?) {
        key = k
        nonce = 0u
    }

    public fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext.copyOf()
        // 2^64-1 is reserved (§5.1): refuse rather than wrap
        if (nonce == ULong.MAX_VALUE) throw NoiseException("nonce exhausted")
        val ciphertext = Primitives.encrypt(k, nonce, ad, plaintext)
        nonce++
        return ciphertext
    }

    public fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext.copyOf()
        if (nonce == ULong.MAX_VALUE) throw NoiseException("nonce exhausted")
        val plaintext = Primitives.decrypt(k, nonce, ad, ciphertext) // throws without advancing
        nonce++
        return plaintext
    }

    /** Transport encryption: no associated data. */
    public fun encrypt(plaintext: ByteArray): ByteArray = encryptWithAd(EMPTY, plaintext)

    public fun decrypt(ciphertext: ByteArray): ByteArray = decryptWithAd(EMPTY, ciphertext)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
