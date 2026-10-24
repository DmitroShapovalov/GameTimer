package com.gametimer.protocol.hub

import com.gametimer.protocol.encodings.Base64Url
import kotlinx.serialization.Serializable

/**
 * What the hub keeps about a paired agent (§8.2 step 8): its pinned key, id, the per-hub secret `ak`,
 * and the last known address. Not secret except [ak]; stored by the app.
 */
@Serializable
public data class PairedAgent(
    val agentId: String,
    /** The agent's static public key, base64url: the only key this hub accepts for it (§4.1). */
    val agentKey: String,
    val name: String,
    val platform: String,
    val host: String,
    val port: Int,
    /** This hub's own secret on that agent (§4.4), base64url. */
    val ak: String,
    /** This hub's role there, as of pairing or the last hello. */
    val role: String,
) {
    public fun agentKeyBytes(): ByteArray = Base64Url.tryDecode(agentKey) ?: error("bad agent key")
}
