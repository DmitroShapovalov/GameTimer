package com.gametimer.protocol.noise

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
        assertFailsWith<NoiseException> { s.hub.writeMessage(ByteArray(HandshakeState.MAX_MESSAGE_LENGTH)) }
    }

    @Test
    fun failedStateCannotBeReused() {
        val s = pair()
        val msg1 = s.hub.writeMessage(ByteArray(0))
        val bad = msg1.copyOf().also { it[40] = (it[40].toInt() xor 0xff).toByte() }
        assertFailsWith<NoiseException> { s.agent.readMessage(bad) }
        assertFailsWith<IllegalStateException> { s.agent.readMessage(msg1) }
    }
}
