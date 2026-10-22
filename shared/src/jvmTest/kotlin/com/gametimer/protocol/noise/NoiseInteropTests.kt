package com.gametimer.protocol.noise

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live Kotlin <-> C# test (docs/protocol.md §4.6): this Kotlin core against the C# core, driven through
 * tests/interop/NoisePeer. Runs when Gradle gets -PcsharpPeer=<NoisePeer.dll>; CI always passes it.
 */
class NoiseInteropTests {
    @BeforeTest
    fun initSodium() = TestSupport.initSodium()

    private val prologue = TestSupport.prologue
    private val peers = mutableListOf<Peer>()

    /** One C# process = one agent (or hub) with its own static key. */
    private class Peer(dll: String) {
        private val process = ProcessBuilder("dotnet", dll).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val out: BufferedWriter = process.outputStream.bufferedWriter()
        private val inp: BufferedReader = process.inputStream.bufferedReader()

        fun call(vararg cmd: String): String {
            out.write(cmd.joinToString(" ")); out.newLine(); out.flush()
            return inp.readLine() ?: error("C# peer exited")
        }

        /** Expects "ok <hex>" and returns the bytes. */
        fun ok(vararg cmd: String): ByteArray {
            val r = call(*cmd)
            check(r.startsWith("ok")) { "${cmd.first()}: $r" }
            val v = r.removePrefix("ok").trim()
            return if (v.isEmpty() || v == "-") ByteArray(0) else v.hex()
        }

        fun fails(vararg cmd: String): Boolean = call(*cmd).startsWith("fail")

        fun close() {
            runCatching { call("quit") }
            process.waitFor()
        }
    }

    private fun peer(): Peer? {
        val dll = System.getProperty("csharpPeer")
        if (dll == null) {
            // Locally optional; in CI a missing property must not silently skip the interop test
            check(System.getenv("CI") != "true") { "CI run without -PcsharpPeer" }
            println("csharpPeer not set: interop skipped")
            return null
        }
        check(File(dll).isFile) { "csharpPeer not found: $dll" }
        return Peer(dll).also { peers += it }
    }

    @AfterTest
    fun closePeers() = peers.forEach { it.close() }

    private fun ByteArray.h(): String = if (isEmpty()) "-" else hex()

    @Test
    fun pairingRequestResponse() {
        val agent = peer() ?: return
        val agentPub = agent.ok("key")
        val psk = ByteArray(32) { (it * 7 + 3).toByte() }
        val hubKey = KeyPair.generate()
        val hub = HandshakeState(NoisePattern.IKpsk1, true, prologue, hubKey, agentPub, psk)

        agent.ok("new", "resp", "IKpsk1", "-", psk.h())
        val hello = """{"t":"pair.hello"}""".encodeToByteArray()
        assertContentEquals(hello, agent.ok("read", hub.writeMessage(hello).h()))
        assertContentEquals(hubKey.publicKey, agent.ok("rs"))
        val reply = """{"t":"pair.ok"}""".encodeToByteArray()
        assertContentEquals(reply, hub.readMessage(agent.ok("write", reply.h())))
        // Both sides derive the same h → the same 6-digit code on the PC and the phone
        assertContentEquals(hub.handshakeHash(), agent.ok("hash"))

        val (send, recv) = hub.split()
        agent.ok("split")
        val req = """{"t":"timer.extend","id":1,"min":15}""".encodeToByteArray()
        assertContentEquals(req, agent.ok("dec", send.encrypt(req).h()))
        val res = """{"t":"ok","re":1}""".encodeToByteArray()
        assertContentEquals(res, recv.decrypt(agent.ok("enc", res.h())))
    }

    @Test
    fun pairingWithWrongSecretIsRejectedByCSharp() {
        val agent = peer() ?: return
        val hub = HandshakeState(NoisePattern.IKpsk1, true, prologue, KeyPair.generate(), agent.ok("key"), ByteArray(32) { 1 })
        agent.ok("new", "resp", "IKpsk1", "-", ByteArray(32) { 2 }.h())
        assertTrue(agent.fails("read", hub.writeMessage(ByteArray(0)).h()))
    }

    @Test
    fun sessionWithReadyThenReplayedMsg1NeverBecomesASession() {
        val agent = peer() ?: return
        val agentPub = agent.ok("key")
        val hubKey = KeyPair.generate()
        val hub = HandshakeState(NoisePattern.IK, true, prologue, hubKey, agentPub)

        // Genuine session: msg1, msg2, then the hub's first transport message "ready" (protocol: ready rule)
        agent.ok("new", "resp", "IK", "-", "-")
        val msg1 = hub.writeMessage("""{"t":"connect"}""".encodeToByteArray())
        agent.ok("read", msg1.h())
        hub.readMessage(agent.ok("write", "-"))
        val (send, recv) = hub.split()
        agent.ok("split")
        val ready = send.encrypt("""{"t":"ready"}""".encodeToByteArray())
        assertEquals("""{"t":"ready"}""", agent.ok("dec", ready.h()).decodeToString())
        for (i in 1..3) { // several messages each way: counters stay in step
            val r = """{"t":"timer.get","id":$i}""".encodeToByteArray()
            assertContentEquals(r, agent.ok("dec", send.encrypt(r).h()))
            val a = """{"t":"ok","re":$i}""".encodeToByteArray()
            assertContentEquals(a, recv.decrypt(agent.ok("enc", a.h())))
        }

        // An attacker replays the recorded msg1 and the recorded "ready" to the same agent.
        // Noise accepts msg1 (IK has no replay protection there), but the agent's new ephemeral key
        // changes the session keys: the old "ready" cannot decrypt, so no session ever starts.
        agent.ok("new", "resp", "IK", "-", "-")
        agent.ok("read", msg1.h())
        agent.ok("write", "-")
        agent.ok("split")
        assertTrue(agent.fails("dec", ready.h()), "a replayed ready must not decrypt")
    }

    @Test
    fun csharpInitiatorKotlinResponder() {
        val hubSide = peer() ?: return
        val hubPub = hubSide.ok("key")
        val agentKey = KeyPair.generate()
        val agent = HandshakeState(NoisePattern.IK, false, prologue, agentKey)

        hubSide.ok("new", "init", "IK", agentKey.publicKey.h(), "-")
        agent.readMessage(hubSide.ok("write", "-"))
        assertContentEquals(hubPub, agent.remoteStaticPublicKey)
        hubSide.ok("read", agent.writeMessage(ByteArray(0)).h())
        assertContentEquals(agent.handshakeHash(), hubSide.ok("hash"))
        val (send, recv) = agent.split()
        hubSide.ok("split")
        assertEquals("ping", recv.decrypt(hubSide.ok("enc", "ping".encodeToByteArray().h())).decodeToString())
        assertEquals("pong", hubSide.ok("dec", send.encrypt("pong".encodeToByteArray()).h()).decodeToString())
    }
}
