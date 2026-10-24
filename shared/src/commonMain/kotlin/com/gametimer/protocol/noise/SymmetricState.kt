package com.gametimer.protocol.noise

/** Noise SymmetricState (§5.2): chaining key, handshake hash, and the CipherState they key. */
internal class SymmetricState(protocolName: String) {
    private val cipher = CipherState()
    private var ck: ByteArray
    private var h: ByteArray

    init {
        val name = protocolName.encodeToByteArray()
        h = if (name.size <= Primitives.HASH_LEN) name.copyOf(Primitives.HASH_LEN) else Primitives.hash(name)
        ck = h.copyOf()
    }

    val hasKey: Boolean get() = cipher.hasKey

    val handshakeHash: ByteArray get() = h.copyOf()

    fun mixKey(inputKeyMaterial: ByteArray) {
        val o = Primitives.hkdf(ck, inputKeyMaterial, 2)
        replaceCk(o[0])
        cipher.initializeKey(o[1].copyOf(Primitives.KEY_LEN)) // HASHLEN 64 → truncate to 32
        o[1].fill(0)
    }

    private fun replaceCk(newCk: ByteArray) {
        ck.fill(0)
        ck = newCk
    }

    fun mixHash(data: ByteArray) {
        h = Primitives.hash(h + data)
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val o = Primitives.hkdf(ck, inputKeyMaterial, 3)
        replaceCk(o[0])
        mixHash(o[1])
        cipher.initializeKey(o[2].copyOf(Primitives.KEY_LEN))
        o[2].fill(0)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /** Noise §5.2 Split. Afterwards the chaining key and the handshake cipher key are wiped. */
    fun split(): Pair<CipherState, CipherState> {
        val o = Primitives.hkdf(ck, ByteArray(0), 2)
        val c1 = CipherState().apply { initializeKey(o[0].copyOf(Primitives.KEY_LEN)) }
        val c2 = CipherState().apply { initializeKey(o[1].copyOf(Primitives.KEY_LEN)) }
        o.forEach { it.fill(0) }
        ck.fill(0)
        cipher.initializeKey(null)
        return c1 to c2
    }
}
