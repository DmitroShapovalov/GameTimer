@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.PairingCode
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** /v2/pair (protocol §8.2). Port of the C# PairingTests. */
class PairingTests {
    @BeforeTest
    fun init() = TestSupport.initSodium()

    private fun hello(name: String = "Phone") = AgentFixture.pairHello(name)

    @Test
    fun theFirstHubPairsAsAdminWithItsOwnAk() = agentTest { f ->
        var paired: Peer? = null
        f.host.onPaired = { paired = it }
        val (key, result) = f.pair("Dmytro's phone")

        assertEquals("true", result.s("ok"))
        assertEquals("admin", result.s("role"))
        assertEquals(Ids.agentId(f.host.agentPublicKey), result.obj("agent").s("id"))
        assertEquals(32, Base64Url.tryDecode(result.s("ak"))!!.size)

        val peer = f.host.peerList.single()
        assertEquals(Ids.hubId(key.publicKey), peer.id)
        assertEquals("Dmytro's phone", peer.name)
        assertEquals(PeerRole.Admin, peer.role)
        assertEquals(listOf("Dmytro's phone"), f.ui.asked)
        assertEquals(peer.id, paired?.id)
        assertEquals(peer.id, f.store.state!!.peers.single().id) // persisted
    }

    @Test
    fun theSecondHubPairsAsParent() = agentTest { f ->
        f.pair("A")
        assertEquals("parent", f.pair("B").second.s("role"))
    }

    @Test
    fun aKnownHubPairingAgainKeepsItsRoleAndGetsANewAk() = agentTest { f ->
        f.pair("A")
        val offer = f.host.startPairing("PC")
        val key = KeyPair.generate()
        suspend fun pairWith(k: KeyPair): String? {
            val (hub, _) = f.handshake(k, pairing = true, psk = offer.secret)
            val code = PairingCode.derive(hub.handshakeHash!!)
            f.ui.answer = { code }
            hub.sendJson(hello("B"))
            val r = hub.receiveJson()
            assertEquals("parent", r.s("role"))
            return r.s("ak")
        }
        val ak1 = pairWith(key)
        f.host.setRoleByOwner(Ids.hubId(key.publicKey), PeerRole.Parent)
        val ak2 = pairWith(key)
        assertTrue(ak1 != ak2)
        assertEquals(2, f.host.peerList.size)
    }

    @Test
    fun aWrongCodeIsDeniedAndNothingIsStored() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        val (hub, ok) = f.handshake(KeyPair.generate(), true, offer.secret)
        assertTrue(ok)
        val right = PairingCode.derive(hub.handshakeHash!!)
        f.ui.answer = { if (right == "000000") "000001" else "000000" }
        hub.sendJson(hello())
        val bye = hub.receiveJson()
        assertEquals("bye", bye.t())
        assertEquals("denied", bye.s("code"))
        assertTrue(f.host.peerList.isEmpty())
    }

    @Test
    fun theOwnerCancellingIsDenied() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        val (hub, _) = f.handshake(KeyPair.generate(), true, offer.secret)
        hub.sendJson(hello()) // default UI answer: cancel
        assertEquals("denied", hub.receiveJson().s("code"))
        assertTrue(f.host.peerList.isEmpty())
    }

    @Test
    fun theOwnerHas60Seconds() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        f.ui.answer = { awaitCancellation() }
        val (hub, _) = f.handshake(KeyPair.generate(), true, offer.secret)
        hub.sendJson(hello())
        advanceTimeBy(61.seconds)
        assertEquals("denied", hub.receiveJson().s("code"))
    }

    @Test
    fun aWrongSecretClosesSilentlyAtMessage1() = agentTest { f ->
        f.host.startPairing("PC")
        val (hub, gotMsg2) = f.handshake(KeyPair.generate(), true, ByteArray(32))
        assertFalse(gotMsg2)
        hub.run.join()
    }

    @Test
    fun noOpenPairingWindowClosesSilently() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        f.host.cancelPairing()
        val (_, gotMsg2) = f.handshake(KeyPair.generate(), true, offer.secret)
        assertFalse(gotMsg2)
    }

    @Test
    fun theWindowClosesAfter2Minutes() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        advanceTimeBy(2.minutes + 1.seconds)
        val (_, gotMsg2) = f.handshake(KeyPair.generate(), true, offer.secret)
        assertFalse(gotMsg2)
    }

    @Test
    fun twoPhonesWithOneQrCancelThePairing() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        val asked = CompletableDeferred<Unit>()
        f.ui.answer = {
            asked.complete(Unit)
            awaitCancellation() // the owner is still typing
        }

        // The child photographed the QR and pairs first; the owner is asked for the code...
        val (child, _) = f.handshake(KeyPair.generate(), true, offer.secret)
        child.sendJson(hello("Child"))
        withTimeout(10.seconds) { asked.await() }

        // ...then the parent's phone scans the same QR
        val (parent, ok) = f.handshake(KeyPair.generate(), true, offer.secret, ip = "192.168.1.21")
        assertTrue(ok) // it knows s, so the handshake works
        parent.sendJson(hello("Parent"))

        assertEquals("code_used", parent.receiveJson().s("code"))
        assertEquals("denied", child.receiveJson().s("code"))
        eventually { f.ui.aborts.isNotEmpty() }
        assertEquals(listOf(PairingAbortReason.TwoPhones), f.ui.aborts)
        assertTrue(f.host.peerList.isEmpty())
    }

    @Test
    fun aReplayedPairingMessage1DoesNotUseUpTheSecret() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        val (sniffed, _) = f.handshake(KeyPair.generate(), true, offer.secret)
        sniffed.end.close()

        // Replay of that msg 1: the agent answers msg 2, but nobody can continue
        val (_, gotMsg2) = f.handshake(KeyPair.generate(), true, ip = "192.168.1.66", replayMsg1 = sniffed.msg1)
        assertTrue(gotMsg2)

        // The real phone still pairs
        val (phone, ok) = f.handshake(KeyPair.generate(), true, offer.secret)
        assertTrue(ok)
        val code = PairingCode.derive(phone.handshakeHash!!)
        f.ui.answer = { code }
        phone.sendJson(hello())
        assertEquals("pair.result", phone.receiveJson().t())
    }

    @Test
    fun pairingAddsAPeerAndTellsExistingHubs() = agentTest { f ->
        val (firstKey, _) = f.pair("First")
        val (first, _) = f.connect(firstKey)

        f.pair("Second")
        val ev = first.receiveJson()
        assertEquals("ev.peers", ev.t())
        assertEquals("added", ev.s("change"))
        assertEquals(2, (ev["peers"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun aLateAnswerFromADialogThatIgnoresCancelDoesNotCommitAfterTwoPhones() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        val release = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<Unit>()
        var childCode: String? = null
        f.ui.answer = {
            asked.complete(Unit)
            withContext(NonCancellable) { release.await() } // ignores cancellation, then "types" the right code
            childCode
        }

        val (child, _) = f.handshake(KeyPair.generate(), true, offer.secret)
        childCode = PairingCode.derive(child.handshakeHash!!)
        child.sendJson(hello("Child"))
        withTimeout(10.seconds) { asked.await() }

        val (parent, _) = f.handshake(KeyPair.generate(), true, offer.secret, ip = "192.168.1.21")
        parent.sendJson(hello("Parent"))
        assertEquals("code_used", parent.receiveJson().s("code"))
        assertEquals("denied", child.receiveJson().s("code")) // at once, without waiting for the dialog
        release.complete(Unit)
        eventually { true }
        assertTrue(f.host.peerList.isEmpty())
    }

    @Test
    fun hubNamesTheOwnerCouldMisreadAreRefused() {
        for (name in listOf("Dad‮enohp", "tab\there", "   ", "x".repeat(65))) {
            agentTest { f ->
                val offer = f.host.startPairing("PC")
                val (hub, _) = f.handshake(KeyPair.generate(), true, offer.secret)
                hub.sendJson(hello(name))
                assertNull(hub.tryReceiveJson())
                assertTrue(f.ui.asked.isEmpty())
            }
        }
    }

    @Test
    fun aBadPairHelloClosesSilently() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        val (hub, _) = f.handshake(KeyPair.generate(), true, offer.secret)
        hub.sendJson(buildJsonObject { put("t", "pair.hello"); put("protocol", 1); put("name", "Phone") })
        assertNull(hub.tryReceiveJson())
        assertTrue(f.ui.asked.isEmpty())
    }

    @Test
    fun thePhoneSpeakingWhileTheOwnerTypesEndsTheAttempt() = agentTest { f ->
        val offer = f.host.startPairing("PC")
        val asked = CompletableDeferred<Unit>()
        f.ui.answer = { asked.complete(Unit); awaitCancellation() }
        val (hub, _) = f.handshake(KeyPair.generate(), true, offer.secret)
        hub.sendJson(hello())
        withTimeout(10.seconds) { asked.await() }
        hub.sendJson(buildJsonObject { put("t", "ping") })
        assertEquals("denied", hub.receiveJson().s("code"))
    }

    @Test
    fun theQrFragmentCarriesTheKeySecretAndName() = agentTest { f ->
        val offer = f.host.startPairing("Kid's phone")
        val parts = offer.qrFragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("2", parts["v"])
        assertEquals(Base64Url.encode(f.host.agentPublicKey), parts["k"])
        assertEquals(32, Base64Url.tryDecode(parts["s"])!!.size)
        assertEquals("Kid%27s%20phone", parts["n"])
        // The hub's own parser reads it back
        val link = com.gametimer.protocol.hub.PairingLink.parse("http://192.168.1.5:5123/pair#" + offer.qrFragment)!!
        assertEquals("Kid's phone", link.pcName)
        assertEquals(f.host.agentPublicKey.toList(), link.agentKey.toList())
    }
}
