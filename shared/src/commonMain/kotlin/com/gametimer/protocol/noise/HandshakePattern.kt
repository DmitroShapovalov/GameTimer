package com.gametimer.protocol.noise

/** The handshake patterns protocol v2 uses (§4.1). IKpsk2 exists only to test the psk logic generally. */
public enum class NoisePattern(internal val patternName: String, internal val messages: List<List<Token>>) {
    /** `/v2` sessions.  IK:  <- s  ...  -> e, es, s, ss   <- e, ee, se */
    IK("IK", listOf(listOf(Token.E, Token.ES, Token.S, Token.SS), listOf(Token.E, Token.EE, Token.SE))),

    /** `/v2/pair` pairing: PSK mixed in at the end of message 1. */
    IKpsk1("IKpsk1", listOf(listOf(Token.E, Token.ES, Token.S, Token.SS, Token.PSK), listOf(Token.E, Token.EE, Token.SE))),

    /** Not used by the protocol; covered by tests so the PSK handling is checked at a second position. */
    IKpsk2("IKpsk2", listOf(listOf(Token.E, Token.ES, Token.S, Token.SS), listOf(Token.E, Token.EE, Token.SE, Token.PSK))),
    ;

    internal val isPsk: Boolean get() = messages.any { Token.PSK in it }

    internal val protocolName: String get() = "Noise_${patternName}_25519_ChaChaPoly_BLAKE2b"
}

internal enum class Token { E, S, EE, ES, SE, SS, PSK }
