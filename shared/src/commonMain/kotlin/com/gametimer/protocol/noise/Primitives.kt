package com.gametimer.protocol.noise

import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication

/**
 * The three Noise primitives for `25519_ChaChaPoly_BLAKE2b` (Noise spec rev 34 §4) on libsodium,
 * plus HMAC / HKDF built exactly as §4.3 defines them. Mirrors the C# `Primitives`.
 */
internal object Primitives {
    const val DH_LEN = 32
    const val KEY_LEN = 32
    const val TAG_LEN = 16
    const val HASH_LEN = 64
    private const val BLOCK_LEN = 128

    // ---------- DH: X25519 ----------

    fun publicKeyFor(privateKey: ByteArray): ByteArray {
        Sodium.requireInitialized()
        return ScalarMultiplication.scalarMultiplicationBase(privateKey.asUByteArray()).asByteArray()
    }

    /** X25519. An all-zero result (low-order point) aborts the handshake (protocol §4.5). */
    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        Sodium.requireInitialized()
        if (publicKey.size != DH_LEN) throw NoiseException("bad public key length")
        val shared = try {
            ScalarMultiplication.scalarMultiplication(privateKey.asUByteArray(), publicKey.asUByteArray()).asByteArray()
        } catch (e: Exception) {
            throw NoiseException("X25519 failed", e)
        }
        if (constantTimeEquals(shared, ByteArray(DH_LEN))) throw NoiseException("X25519 produced an all-zero result")
        return shared
    }

    // ---------- Cipher: ChaCha20-Poly1305 (IETF), Noise nonce encoding ----------

    /** Noise §5.1: 32 bits of zeros followed by the 64-bit counter, little-endian. */
    private fun nonce(n: ULong): UByteArray {
        val nonce = UByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (n shr (8 * i)).toUByte()
        return nonce
    }

    fun encrypt(key: ByteArray, n: ULong, ad: ByteArray, plaintext: ByteArray): ByteArray {
        Sodium.requireInitialized()
        return AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfEncrypt(
            plaintext.asUByteArray(), ad.asUByteArray(), nonce(n), key.asUByteArray(),
        ).asByteArray()
    }

    fun decrypt(key: ByteArray, n: ULong, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        Sodium.requireInitialized()
        if (ciphertext.size < TAG_LEN) throw NoiseException("ciphertext shorter than the tag")
        return try {
            AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfDecrypt(
                ciphertext.asUByteArray(), ad.asUByteArray(), nonce(n), key.asUByteArray(),
            ).asByteArray()
        } catch (e: Exception) {
            throw NoiseException("decryption failed", e)
        }
    }

    // ---------- Hash: BLAKE2b-512, HMAC, HKDF (Noise §4.3) ----------

    fun hash(data: ByteArray): ByteArray {
        Sodium.requireInitialized()
        return GenericHash.genericHash(data.asUByteArray(), HASH_LEN, null).asByteArray()
    }

    /** HMAC (RFC 2104) over BLAKE2b with its 128-byte block — the Noise HMAC, not keyed BLAKE2b. */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val k = ByteArray(BLOCK_LEN)
        (if (key.size > BLOCK_LEN) hash(key) else key).copyInto(k)
        val inner = ByteArray(BLOCK_LEN + data.size)
        val outer = ByteArray(BLOCK_LEN + HASH_LEN)
        for (i in 0 until BLOCK_LEN) {
            inner[i] = (k[i].toInt() xor 0x36).toByte()
            outer[i] = (k[i].toInt() xor 0x5c).toByte()
        }
        data.copyInto(inner, BLOCK_LEN)
        hash(inner).copyInto(outer, BLOCK_LEN)
        val result = hash(outer)
        k.fill(0)
        return result
    }

    /** Noise HKDF: 2 or 3 outputs of HASHLEN bytes. */
    fun hkdf(chainingKey: ByteArray, inputKeyMaterial: ByteArray, outputs: Int): List<ByteArray> {
        require(outputs in 2..3) { "outputs must be 2 or 3" }
        val tempKey = hmac(chainingKey, inputKeyMaterial)
        val o1 = hmac(tempKey, byteArrayOf(0x01))
        val o2 = hmac(tempKey, o1 + 0x02.toByte())
        if (outputs == 2) {
            tempKey.fill(0)
            return listOf(o1, o2)
        }
        val o3 = hmac(tempKey, o2 + 0x03.toByte())
        tempKey.fill(0)
        return listOf(o1, o2, o3)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
