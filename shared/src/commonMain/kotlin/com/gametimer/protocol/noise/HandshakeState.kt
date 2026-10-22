package com.gametimer.protocol.noise

/**
 * Noise HandshakeState (§5.3) for the IK family (protocol §4.1): the hub is the initiator and knows
 * the agent's static public key in advance (the `<- s` pre-message). Mirrors the C# `HandshakeState`.
 *
 * Usage: alternate [writeMessage] / [readMessage] until [isHandshakeComplete], then [split].
 * Any exception leaves the state unusable.
 *
 * @param pattern IK for sessions, IKpsk1 for pairing.
 * @param initiator true for the hub, false for the agent.
 * @param prologue protocol §4.1: ASCII `GameTimer/2`.
 * @param localStatic this side's static key pair.
 * @param remoteStatic initiator: the agent's pinned public key (required). Responder: must be null.
 * @param psk 32-byte pairing secret for PSK patterns; null otherwise.
 */
public class HandshakeState internal constructor(
    private val pattern: NoisePattern,
    private val initiator: Boolean,
    prologue: ByteArray,
    private val localStatic: KeyPair,
    remoteStatic: ByteArray?,
    psk: ByteArray?,
    /** Fixed ephemeral key, for test vectors only. Internal on purpose: a reused ephemeral key reuses
     *  the message-1 keystream across sessions. */
    private val ephemeralForTesting: KeyPair?,
) {
    public constructor(
        pattern: NoisePattern,
        initiator: Boolean,
        prologue: ByteArray,
        localStatic: KeyPair,
        remoteStatic: ByteArray? = null,
        psk: ByteArray? = null,
    ) : this(pattern, initiator, prologue, localStatic, remoteStatic, psk, ephemeralForTesting = null)

    private val ss: SymmetricState
    private val psk: ByteArray?
    private var e: KeyPair? = null
    private var rs: ByteArray? = null
    private var re: ByteArray? = null
    private var messageIndex = 0
    private var failed = false
    private var splitDone = false

    init {
        if (initiator) {
            require(remoteStatic != null && remoteStatic.size == Primitives.DH_LEN) {
                "IK initiator needs the responder's 32-byte static public key"
            }
            rs = remoteStatic.copyOf()
        } else {
            require(remoteStatic == null) { "IK responder learns the initiator's static key from message 1" }
        }

        if (pattern.isPsk) {
            require(psk != null && psk.size == 32) { "PSK pattern needs a 32-byte PSK" }
            this.psk = psk.copyOf()
        } else {
            require(psk == null) { "PSK given for a non-PSK pattern" }
            this.psk = null
        }

        ss = SymmetricState(pattern.protocolName)
        ss.mixHash(prologue)
        // Pre-message "<- s": the responder's static public key
        ss.mixHash(if (initiator) rs!! else localStatic.publicKeyRef)
    }

    public val isHandshakeComplete: Boolean get() = messageIndex == pattern.messages.size

    /** The peer's static public key: known up front for the initiator, after message 1 for the responder. */
    public val remoteStaticPublicKey: ByteArray? get() = rs?.copyOf()

    /** The final handshake hash `h` (protocol §8.2 derives the pairing code from it). */
    public fun handshakeHash(): ByteArray {
        check(isHandshakeComplete) { "handshake not complete" }
        return ss.handshakeHash
    }

    private val isMyTurnToWrite: Boolean get() = (messageIndex % 2 == 0) == initiator

    public fun writeMessage(payload: ByteArray): ByteArray {
        guard(write = true)
        try {
            var out = ByteArray(0)
            for (token in pattern.messages[messageIndex]) {
                when (token) {
                    Token.E -> {
                        val eph = ephemeralForTesting ?: KeyPair.generate()
                        e = eph
                        out += eph.publicKeyRef
                        ss.mixHash(eph.publicKeyRef)
                        if (pattern.isPsk) ss.mixKey(eph.publicKeyRef)
                    }
                    Token.S -> out += ss.encryptAndHash(localStatic.publicKeyRef)
                    Token.PSK -> ss.mixKeyAndHash(psk!!)
                    else -> mixDh(token)
                }
            }
            out += ss.encryptAndHash(payload)
            if (out.size > MAX_MESSAGE_LENGTH) throw NoiseException("message too long")
            messageIndex++
            return out
        } catch (t: Throwable) {
            fail()
            throw t
        }
    }

    public fun readMessage(message: ByteArray): ByteArray {
        guard(write = false)
        try {
            if (message.size > MAX_MESSAGE_LENGTH) throw NoiseException("message too long")
            var offset = 0
            fun take(count: Int): ByteArray {
                if (message.size - offset < count) throw NoiseException("message too short")
                val part = message.copyOfRange(offset, offset + count)
                offset += count
                return part
            }
            for (token in pattern.messages[messageIndex]) {
                when (token) {
                    Token.E -> {
                        val remoteE = take(Primitives.DH_LEN)
                        re = remoteE
                        ss.mixHash(remoteE)
                        if (pattern.isPsk) ss.mixKey(remoteE)
                    }
                    Token.S -> {
                        val len = if (ss.hasKey) Primitives.DH_LEN + Primitives.TAG_LEN else Primitives.DH_LEN
                        rs = ss.decryptAndHash(take(len))
                    }
                    Token.PSK -> ss.mixKeyAndHash(psk!!)
                    else -> mixDh(token)
                }
            }
            val payload = ss.decryptAndHash(message.copyOfRange(offset, message.size))
            messageIndex++
            return payload
        } catch (t: Throwable) {
            fail()
            throw t
        }
    }

    /**
     * After the handshake: (send, receive) for this side. The initiator sends with the first Noise
     * CipherState, the responder with the second. Allowed once: a second pair would restart the
     * nonces at 0 under the same keys.
     */
    public fun split(): Pair<CipherState, CipherState> {
        check(!failed && isHandshakeComplete) { "handshake not complete" }
        check(!splitDone) { "split already called" }
        splitDone = true
        val (c1, c2) = ss.split()
        wipeSecrets() // the ephemeral key and the PSK are not needed any more
        return if (initiator) c1 to c2 else c2 to c1
    }

    private fun fail() {
        failed = true
        wipeSecrets()
    }

    private fun wipeSecrets() {
        if (ephemeralForTesting == null) e?.destroy()
        e = null
        psk?.fill(0)
    }

    // es / se / ee / ss, from this side's point of view (Noise §5.3)
    private fun mixDh(token: Token) {
        val eph = e
        val shared = when (token) {
            Token.EE -> Primitives.dh(eph!!.privateKeyRef, re!!)
            Token.ES -> if (initiator) Primitives.dh(eph!!.privateKeyRef, rs!!) else Primitives.dh(localStatic.privateKeyRef, re!!)
            Token.SE -> if (initiator) Primitives.dh(localStatic.privateKeyRef, re!!) else Primitives.dh(eph!!.privateKeyRef, rs!!)
            Token.SS -> Primitives.dh(localStatic.privateKeyRef, rs!!)
            else -> error(token.name)
        }
        ss.mixKey(shared)
        shared.fill(0)
    }

    private fun guard(write: Boolean) {
        check(!failed) { "handshake already failed" }
        check(!isHandshakeComplete) { "handshake already complete" }
        check(write == isMyTurnToWrite) { if (write) "not this side's turn to write" else "not this side's turn to read" }
    }

    public companion object {
        /** Noise §3: maximum message length. */
        public const val MAX_MESSAGE_LENGTH: Int = 65535
    }
}
