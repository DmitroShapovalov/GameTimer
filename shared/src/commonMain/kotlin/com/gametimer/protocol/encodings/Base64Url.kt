package com.gametimer.protocol.encodings

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** RFC 4648 §5 base64url without padding (protocol §4.5). Decoding is strict. Mirrors the C# `Base64Url`. */
@OptIn(ExperimentalEncodingApi::class)
public object Base64Url {
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    public fun encode(data: ByteArray): String = codec.encode(data)

    /** Decodes, or returns null for anything that is not canonical unpadded base64url. */
    public fun tryDecode(s: String?): ByteArray? {
        if (s == null || s.length % 4 == 1) return null
        if (s.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
        val bytes = try { codec.decode(s) } catch (e: IllegalArgumentException) { return null }
        return if (encode(bytes) == s) bytes else null
    }
}
