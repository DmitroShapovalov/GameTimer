package com.gametimer.protocol.noise

import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/** An X25519 key pair (static or ephemeral). Not usable after [destroy]. */
public class KeyPair private constructor(private val priv: ByteArray) {
    private val pub: ByteArray = Primitives.publicKeyFor(priv)
    private var destroyed = false

    /** A copy of the private key, for storing it in the platform keystore. */
    public val privateKey: ByteArray get() = privateKeyRef.copyOf()

    /** A copy of the public key. */
    public val publicKey: ByteArray get() = publicKeyRef.copyOf()

    internal val privateKeyRef: ByteArray
        get() {
            check(!destroyed) { "key pair destroyed" }
            return priv
        }

    internal val publicKeyRef: ByteArray
        get() {
            check(!destroyed) { "key pair destroyed" }
            return pub
        }

    /** Overwrites the private key in memory; any later use throws. */
    public fun destroy() {
        if (destroyed) return
        destroyed = true
        priv.fill(0)
    }

    public companion object {
        /** A new key pair from libsodium's CSPRNG. */
        public fun generate(): KeyPair {
            Sodium.requireInitialized()
            return KeyPair(LibsodiumRandom.buf(Primitives.DH_LEN).asByteArray())
        }

        /** Rebuilds a key pair from a stored private key (the public key is derived). */
        public fun fromPrivateKey(privateKey: ByteArray): KeyPair {
            require(privateKey.size == Primitives.DH_LEN) { "X25519 private key must be 32 bytes" }
            return KeyPair(privateKey.copyOf())
        }
    }
}
