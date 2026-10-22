package com.gametimer.protocol.noise

import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/** An X25519 key pair (static or ephemeral). */
public class KeyPair private constructor(privateKey: ByteArray) {
    private val priv: ByteArray = privateKey
    public val publicKey: ByteArray = Primitives.publicKeyFor(privateKey)

    /** A copy of the private key, for storing it in the platform keystore. */
    public val privateKey: ByteArray get() = priv.copyOf()

    internal val privateKeyRef: ByteArray get() = priv

    /** Overwrites the private key in memory. The pair is unusable afterwards. */
    public fun destroy() {
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
