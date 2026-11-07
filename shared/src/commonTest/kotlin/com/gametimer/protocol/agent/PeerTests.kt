@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.protocol.agent

import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.noise.TestSupport
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Roles, revocation and the last-admin rule (protocol §8.1). Port of the C# PeerTests. */
class PeerTests {
    @BeforeTest
    fun init() = TestSupport.initSodium()

    private fun target(id: String, role: String? = null) = buildJsonObject { put("peer", id); if (role != null) put("role", role) }

    @Test
    fun aParentCannotRevokeOrPromoteOthersButCanRevokeItself() = agentTest { f ->
        val (admin, _) = f.pair("Admin")
        val (parent, _) = f.pair("Parent")
        val (p, _) = f.connect(parent)
        val (a, _) = f.connect(admin)

        assertEquals("forbidden", p.request("peers.revoke", target(Ids.hubId(admin.publicKey)), withRid = true).s("code"))
        assertEquals("forbidden", p.request("peers.setRole", target(p.id, "admin"), withRid = true).s("code"))

        assertEquals("result", p.request("peers.revoke", target(p.id), withRid = true).t())
        assertEquals("revoked", p.receiveJson().s("code")) // result first, then bye
        assertEquals(1, f.host.peerList.size)

        val ev = a.receiveJson()
        assertEquals("ev.peers", ev.t())
        assertEquals("revoked", ev.s("change"))
        assertEquals("true", (ev["peers"] as JsonArray)[0].jsonObject.s("current"))
    }

    @Test
    fun theLastAdminIsProtectedFromHubs() = agentTest { f ->
        val (admin, _) = f.pair("Admin")
        val (parent, _) = f.pair("Parent")
        val (a, _) = f.connect(admin)

        assertEquals("last_admin", a.request("peers.revoke", target(a.id), withRid = true).s("code"))
        assertEquals("last_admin", a.request("peers.setRole", target(a.id, "parent"), withRid = true).s("code"))

        // Promote the other parent; then the first admin may step down
        val parentId = Ids.hubId(parent.publicKey)
        assertEquals("result", a.request("peers.setRole", target(parentId, "admin"), withRid = true).t())
        assertEquals("result", a.request("peers.setRole", target(a.id, "parent"), withRid = true).t())
        assertEquals("forbidden", a.request("peers.revoke", target(parentId), withRid = true).s("code"))
    }

    @Test
    fun theOwnerCanRevokeTheLastAdminAndTheNextPairingBecomesAdmin() = agentTest { f ->
        val (admin, _) = f.pair("Lost phone")
        val (a, _) = f.connect(admin)

        assertTrue(f.host.revokeByOwner(a.id))
        assertEquals("revoked", a.receiveJson().s("code"))
        assertTrue(f.host.peerList.isEmpty())
        assertFalse(f.host.revokeByOwner(a.id))

        // The revoked key can't connect any more
        val (_, gotMsg2) = f.handshake(admin, pairing = false)
        assertFalse(gotMsg2)

        assertEquals("admin", f.pair("New phone").second.s("role"))
    }

    @Test
    fun theOwnerChangesRolesWithoutTheLastAdminRule() = agentTest { f ->
        val (admin, _) = f.pair("A")
        val id = Ids.hubId(admin.publicKey)
        var changes = 0
        f.host.onPeersChanged = { changes++ }
        assertTrue(f.host.setRoleByOwner(id, PeerRole.Parent))
        assertEquals(PeerRole.Parent, f.host.peerList.single().role)
        assertEquals(PeerRole.Parent, f.store.state!!.peers.single().role)
        assertEquals(1, changes)
        assertFalse(f.host.setRoleByOwner("nobody", PeerRole.Admin))
    }

    @Test
    fun revokingAHubDropsItsCachedReplies() = agentTest { f ->
        f.pair("Admin")
        val (parent, _) = f.pair("Parent")
        val (p, _) = f.connect(parent)
        p.request("timer.extend", buildJsonObject { put("minutes", 5) }, withRid = true)
        assertTrue(f.store.state!!.rids.containsKey(p.id))

        f.host.revokeByOwner(p.id)
        assertFalse(f.store.state!!.rids.containsKey(p.id))
    }

    @Test
    fun twoAdminsRevokingEachOtherAtOnceLeaveOneAdmin() = runTest {
        repeat(10) {
            val f = AgentFixture(this)
            val (k1, _) = f.pair("A")
            val (k2, _) = f.pair("B")
            val (h1, _) = f.connect(k1)
            assertEquals("result", h1.request("peers.setRole", target(Ids.hubId(k2.publicKey), "admin"), withRid = true).t())
            val (h2, _) = f.connect(k2)

            val r1 = async { runCatching { h1.request("peers.revoke", target(h2.id), withRid = true) } }
            val r2 = async { runCatching { h2.request("peers.revoke", target(h1.id), withRid = true) } }
            r1.await(); r2.await() // one of them ends with bye revoked
            assertEquals(1, f.host.peerList.size)
            assertEquals(PeerRole.Admin, f.host.peerList[0].role)
            f.host.close()
        }
    }

    @Test
    fun peersListMarksTheCurrentHub() = agentTest { f ->
        val (a, _) = f.pair("A")
        f.pair("B")
        val (hub, _) = f.connect(a)
        val peers = (hub.request("peers.list")["peers"] as JsonArray).map { it as JsonObject }
        assertEquals(2, peers.size)
        assertEquals("A", peers.single { it.s("current") == "true" }.s("name"))
        assertEquals("parent", peers.single { it.s("current") == "false" }.s("role"))
        // ISO 8601 UTC times
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(peers[0].s("pairedAt")!!))
    }
}
