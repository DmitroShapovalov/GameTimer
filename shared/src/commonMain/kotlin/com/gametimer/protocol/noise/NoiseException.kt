package com.gametimer.protocol.noise

/**
 * Any failure of the Noise layer: bad MAC, wrong key, malformed or oversized message, a degenerate
 * DH result. Per protocol §3, a peer that hits this before the handshake is confirmed closes the
 * connection silently. Misuse (wrong turn, reuse after failure) is IllegalStateException instead.
 */
public class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)
