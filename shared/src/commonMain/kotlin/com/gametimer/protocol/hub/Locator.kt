package com.gametimer.protocol.hub

import kotlin.time.Duration

/** Where an agent was found. */
public data class AgentAddress(val host: String, val port: Int)

/**
 * Finds a paired agent on the LAN by its daily discovery tags (protocol §9): Android uses NsdManager,
 * iOS would use NWBrowser. Discovery only *finds* the PC; [AgentLink] trusts it only after the Noise
 * handshake with the pinned key succeeded there.
 */
public fun interface Locator {
    /**
     * Browses `_gametimer._tcp` for up to [timeout] and returns the address of a service whose TXT
     * (see [DiscoveryTxt.tags]) contains one of [tags], or null.
     */
    public suspend fun locate(tags: Set<String>, timeout: Duration): AgentAddress?
}

/** Reads the TXT of a `_gametimer._tcp` service (§9). */
public object DiscoveryTxt {
    public const val SERVICE_TYPE: String = "_gametimer._tcp"
    private const val MAX_KEYS = 64

    /**
     * The announced tags: `tags`, `tags2`, `tags3`, … (comma-separated), only when `v=2`. Keys are
     * compared case-insensitively (DNS-SD); values that aren't 13 base32 characters are skipped.
     */
    public fun tags(attributes: Map<String, ByteArray?>): Set<String> {
        val byKey = attributes.entries.associate { it.key.lowercase() to it.value?.decodeToString() }
        if (byKey["v"] != "2") return emptySet()
        val out = mutableSetOf<String>()
        for (i in 1..MAX_KEYS) {
            val value = byKey[if (i == 1) "tags" else "tags$i"] ?: break
            value.split(',').map { it.trim() }.filterTo(out) { TAG.matches(it) }
        }
        return out
    }

    private val TAG = Regex("^[A-Z2-7]{13}$")
}
