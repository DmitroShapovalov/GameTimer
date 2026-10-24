package com.gametimer.protocol.noise

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/** Failure paths the protocol relies on (docs/protocol.md §4). Mirrors the C# NoiseBehaviourTests. */
class NoiseBehaviourTests {
    @BeforeTest
    fun initSodium() = TestSupport.initSodium()

    private val prologue = TestSupport.prologue

    private class Sides(val hub: HandshakeState, val agent: HandshakeState, val hubKey: KeyPair)

    private fun pair(pattern: NoisePattern = NoisePattern.IK, hubPsk: ByteArray? = null, agentPsk: ByteArray? = null): Sides {
        val hubKey = KeyPair.generate()
        val agentKey = KeyPair.generate()
        return Sides(
            HandshakeState(pattern, true, prologue, hubKey, agentKey.publicKey, hubPsk),
            HandshakeState(pattern, false, prologue, agentKey, null, agentPsk),
            hubKey,
        )
    }

    @Test
    fun fullSessionRoundTripAndRemoteKeyLearned() {
        val s = pair()
        s.agent.readMessage(s.hub.writeMessage("""{"t":"connect"}""".encodeToByteArray()))
        assertContentEquals(s.hubKey.publicKey, s.agent.remoteStaticPublicKey)
        s.hub.readMessage(s.agent.writeMessage(ByteArray(0)))
        assertContentEquals(s.hub.handshakeHash(), s.agent.handshakeHash())

        val (hubSend, hubRecv) = s.hub.split()
        val (agentSend, agentRecv) = s.agent.split()
        assertEquals("ready", agentRecv.decrypt(hubSend.encrypt("ready".encodeToByteArray())).decodeToString())
        assertEquals("hello", hubRecv.decrypt(agentSend.encrypt("hello".encodeToByteArray())).decodeToString())
    }

    @Test
    fun pairingWithWrongSecretFailsAtMessage1() {
        val secret = ByteArray(32).also { it[0] = 1 }
        val wrong = ByteArray(32).also { it[0] = 2 }
        val s = pair(NoisePattern.IKpsk1, hubPsk = wrong, agentPsk = secret)
        val msg1 = s.hub.writeMessage("""{"t":"pair"}""".encodeToByteArray())
        assertFailsWith<NoiseException> { s.agent.readMessage(msg1) }
    }

    @Test
    fun pairingWithRightSecretSucceeds() {
        val secret = ByteArray(32) { it.toByte() }
        val s = pair(NoisePattern.IKpsk1, secret, secret)
        s.agent.readMessage(s.hub.writeMessage("""{"t":"pair"}""".encodeToByteArray()))
        s.hub.readMessage(s.agent.writeMessage(ByteArray(0)))
        assertContentEquals(s.hub.handshakeHash(), s.agent.handshakeHash())
    }

    @Test
    fun hubRefusesADifferentAgentKey() {
        // A fake "PC" on the network has its own key; the hub pinned the real one
        val realAgent = KeyPair.generate()
        val hub = HandshakeState(NoisePattern.IK, true, prologue, KeyPair.generate(), realAgent.publicKey)
        val fake = HandshakeState(NoisePattern.IK, false, prologue, KeyPair.generate())
        assertFailsWith<NoiseException> { fake.readMessage(hub.writeMessage(ByteArray(0))) }
    }

    @Test
    fun tamperedMessage1IsRejected() {
        val s = pair()
        val msg1 = s.hub.writeMessage("""{"t":"connect"}""".encodeToByteArray())
        msg1[msg1.size - 1] = (msg1[msg1.size - 1].toInt() xor 1).toByte()
        assertFailsWith<NoiseException> { s.agent.readMessage(msg1) }
    }

    @Test
    fun allZeroDhResultAborts() {
        val hub = HandshakeState(NoisePattern.IK, true, prologue, KeyPair.generate(), ByteArray(32))
        assertFailsWith<NoiseException> { hub.writeMessage(ByteArray(0)) }
    }

    @Test
    fun replayedTransportMessageIsRejected() {
        val s = pair()
        s.agent.readMessage(s.hub.writeMessage(ByteArray(0)))
        s.hub.readMessage(s.agent.writeMessage(ByteArray(0)))
        val (hubSend, _) = s.hub.split()
        val (_, agentRecv) = s.agent.split()
        val c = hubSend.encrypt("timer.extend".encodeToByteArray())
        agentRecv.decrypt(c)
        assertFailsWith<NoiseException> { agentRecv.decrypt(c) }
    }

    @Test
    fun wrongTurnIsRefused() {
        val s = pair()
        assertFailsWith<IllegalStateException> { s.agent.writeMessage(ByteArray(0)) }
    }

    @Test
    fun oversizedMessageIsRefused() {
        val s = pair()
        tooLong { s.hub.writeMessage(ByteArray(HandshakeState.MAX_MESSAGE_LENGTH)) }
    }

    @Test
    fun failedStateCannotBeReused() {
        val s = pair()
        val msg1 = s.hub.writeMessage(ByteArray(0))
        val bad = msg1.copyOf().also { it[40] = (it[40].toInt() xor 0xff).toByte() }
        assertFailsWith<NoiseException> { s.agent.readMessage(bad) }
        assertFailsWith<IllegalStateException> { s.agent.readMessage(msg1) }
    }

    private fun session(): Pair<Pair<CipherState, CipherState>, Pair<CipherState, CipherState>> {
        val s = pair()
        s.agent.readMessage(s.hub.writeMessage(ByteArray(0)))
        s.hub.readMessage(s.agent.writeMessage(ByteArray(0)))
        return s.hub.split() to s.agent.split()
    }

    @Test
    fun tamperedMessage2IsRejected() {
        val s = pair()
        s.agent.readMessage(s.hub.writeMessage(ByteArray(0)))
        val msg2 = s.agent.writeMessage("""{"t":"hello"}""".encodeToByteArray())
        msg2[0] = (msg2[0].toInt() xor 1).toByte() // the agent's ephemeral key
        assertFailsWith<NoiseException> { s.hub.readMessage(msg2) }
    }

    @Test
    fun failedTransportDecryptDoesNotAdvanceTheCounter() {
        val (hub, agent) = session()
        val good = hub.first.encrypt("ready".encodeToByteArray())
        val bad = good.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<NoiseException> { agent.second.decrypt(bad) }
        assertEquals("ready", agent.second.decrypt(good).decodeToString())
    }

    private fun tooLong(block: () -> Unit) =
        assertEquals("message too long", assertFailsWith<NoiseException> { block() }.message)

    @Test
    fun oversizedMessageIsRefusedOnReadAndInTransport() {
        // Each case asserts the length check itself, not just any NoiseException (a MAC failure would also throw)
        val max = HandshakeState.MAX_MESSAGE_LENGTH
        tooLong { pair().agent.readMessage(ByteArray(max + 1)) }

        // IK msg 1 = e (32) + encrypted s (48) + payload + tag (16): the payload fits a CipherState,
        // but the whole message would be 65536 bytes
        tooLong { pair().hub.writeMessage(ByteArray(max - 96 + 1)) }
        assertEquals(max, pair().hub.writeMessage(ByteArray(max - 96)).size)

        val (hub, _) = session()
        tooLong { hub.first.encrypt(ByteArray(max - 15)) }
        tooLong { hub.second.decrypt(ByteArray(max + 1)) }
        assertEquals(max, hub.first.encrypt(ByteArray(max - 16)).size)
    }

    @Test
    fun splitIsAllowedOnce() {
        // A second split would hand out the same keys with nonces back at 0 (keystream reuse)
        val s = pair()
        s.agent.readMessage(s.hub.writeMessage(ByteArray(0)))
        s.hub.readMessage(s.agent.writeMessage(ByteArray(0)))
        s.hub.split()
        assertFailsWith<IllegalStateException> { s.hub.split() }
    }

    @Test
    fun destroyedKeyPairCannotBeUsed() {
        val key = KeyPair.generate()
        key.destroy()
        assertFailsWith<IllegalStateException> { key.privateKey }
        assertFailsWith<IllegalStateException> { HandshakeState(NoisePattern.IK, false, prologue, key) }
    }

    @Test
    fun keyGettersReturnCopies() {
        val key = KeyPair.generate()
        val pub = key.publicKey
        pub[0] = (pub[0].toInt() xor 0xff).toByte()
        assertFalse(pub.contentEquals(key.publicKey))
        val priv = key.privateKey
        priv[0] = (priv[0].toInt() xor 0xff).toByte()
        assertFalse(priv.contentEquals(key.privateKey))
    }
}
