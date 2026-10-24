package com.gametimer.protocol.hub

import com.gametimer.protocol.encodings.Base64Url
import io.ktor.http.Url
import io.ktor.http.parseQueryString

/**
 * The v2 part of the pairing QR (protocol §8.2):
 * `http://<lan-ip>:<port>/pair?code=<v1 code>#v=2&k=<agent public key>&s=<secret>&n=<PC name>`.
 * Only the fragment is used; the v1 code is for the browser app.
 */
public class PairingLink(
    public val host: String,
    public val port: Int,
    public val agentKey: ByteArray,
    public val secret: ByteArray,
    public val pcName: String,
) {
    public companion object {
        /** Parses a scanned QR text, or returns null if it isn't a GameTimer v2 pairing link. */
        public fun parse(text: String): PairingLink? {
            val url = try { Url(text.trim()) } catch (e: Exception) { return null }
            if (url.protocol.name != "http" || url.host.isEmpty()) return null
            val fragment = try { parseQueryString(url.fragment) } catch (e: Exception) { return null }
            if (fragment["v"] != "2") return null
            val k = Base64Url.tryDecode(fragment["k"]) ?: return null
            val s = Base64Url.tryDecode(fragment["s"]) ?: return null
            if (k.size != 32 || s.size != 32) return null
            val name = fragment["n"]?.trim()?.take(64).orEmpty().ifEmpty { url.host }
            return PairingLink(url.host, url.port, k, s, name)
        }
    }
}
