package com.gametimer.protocol.hub

import com.gametimer.protocol.encodings.Base64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairingLinkTests {
    private val k = Base64Url.encode(ByteArray(32) { 1 })
    private val s = Base64Url.encode(ByteArray(32) { 2 })

    @Test
    fun parsesTheQrLink() {
        val link = assertNotNull(PairingLink.parse("http://192.168.1.10:5123/pair?code=AB12CD34#v=2&k=$k&s=$s&n=Kids%20PC"))
        assertEquals("192.168.1.10", link.host)
        assertEquals(5123, link.port)
        assertContentEquals(ByteArray(32) { 1 }, link.agentKey)
        assertContentEquals(ByteArray(32) { 2 }, link.secret)
        assertEquals("Kids PC", link.pcName)
    }

    @Test
    fun rejectsAnythingElse() {
        assertNull(PairingLink.parse("http://192.168.1.10:5123/pair?code=AB12CD34")) // v1-only QR
        assertNull(PairingLink.parse("http://192.168.1.10:5123/pair#v=3&k=$k&s=$s"))
        assertNull(PairingLink.parse("http://192.168.1.10:5123/pair#v=2&k=$k"))
        assertNull(PairingLink.parse("http://192.168.1.10:5123/pair#v=2&k=AAAA&s=$s"))
        assertNull(PairingLink.parse("https://example.com/pair#v=2&k=$k&s=$s"))
        assertNull(PairingLink.parse("not a link"))
    }
}
