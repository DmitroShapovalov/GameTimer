package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.hub.encodeJson
import com.gametimer.protocol.identity.blake2b256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** §7.2: capability (null = always available), whether a `rid` is required, who executes it. */
private class Command(val capability: String?, val needsRid: Boolean, val host: Boolean)

private val COMMANDS: Map<String, Command> = mapOf(
    "state.get" to Command(null, false, false),
    "timer.start" to Command("timer", true, false),
    "timer.stop" to Command("timer", true, false),
    "timer.extend" to Command("timer", true, false),
    "timer.end" to Command("timer", true, false), // minor 1
    "timer.setEnd" to Command("timer", true, false), // minor 1
    "timer.answerRequest" to Command("timer", true, false), // minor 3
    "interruption.ack" to Command("timer", true, false),
    "settings.get" to Command("settings", false, false),
    "settings.put" to Command("settings", true, false),
    "schedule.get" to Command("schedule", false, false),
    "schedule.put" to Command("schedule", true, false),
    "history.get" to Command("history", false, false),
    "apps.get" to Command("apps", false, false), // minor 4: the phone's apps and the limited ones
    "apps.put" to Command("apps", true, false),
    "peers.list" to Command("peers", false, true),
    "peers.revoke" to Command("peers", true, true),
    "peers.setRole" to Command("peers", true, true),
    "push.set" to Command("push", true, true), // §10.6
)

internal suspend fun AgentHost.handleMessage(session: Session, plain: ByteArray) {
    val root = StrictJson.parse(plain)
    val type = root.str("t") ?: throw ProtocolViolation("message without t")

    when (type) {
        "ping" -> { session.enqueue(JsonObject(mapOf("t" to JsonPrimitive("pong")))); return }
        "pong" -> return
        "bye" -> { session.abort(); return }
    }

    val idEl = root["id"] as? JsonPrimitive
    val id = if (idEl == null || idEl.isString) null else idEl.content.toLongOrNull()
    if (id == null || id <= 0) throw ProtocolViolation("request without a valid id")
    if (id <= session.lastId) throw ProtocolViolation("request id not strictly increasing")
    session.lastId = id

    runRequest(session, type, root, id)
}

/** Runs one request, sends its reply, then delivers the events it caused (§5 ordering). */
private suspend fun AgentHost.runRequest(session: Session, type: String, root: JsonObject, id: Long) {
    val origin = RequestOrigin(session)
    val reply = try {
        process(session, type, root, id, origin)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // a timeout inside the backend is its failure, not ours
        errorMessage(id, "internal")
    } catch (e: Exception) {
        errorMessage(id, "internal") // the host's own failure (e.g. the store); the session survives
    }
    session.enqueue(reply)
    origin.close()?.let { session.bye(it) }
}

private suspend fun AgentHost.process(session: Session, type: String, root: JsonObject, id: Long, origin: RequestOrigin): JsonObject {
    // The hub's current role, and whether it is still paired at all: a revoke or role change may have happened
    // since this session started (the session is closing then, but a frame may be in flight)
    lock.withLock {
        val peer = peers.firstOrNull { it.id == session.hubId }
        if (peer == null || session.isClosing) return errorMessage(id, "forbidden")
        session.role = peer.role
    }
    val command = COMMANDS[type] ?: return errorMessage(id, "unknown_command")
    if (command.capability != null && command.capability !in capabilities) return errorMessage(id, "unsupported")
    if (!command.needsRid) return toMessage(id, execute(session, command, type, root, origin))

    val rid = root.str("rid")
    if (Base64Url.tryDecode(rid)?.size != 16) return errorMessage(id, "bad_request")
    val hash = bodyHash(root)

    val gate = lock.withLock { ridLocks.getOrPut(session.hubId) { Mutex() } }
    return gate.withLock {
        val cached: JsonObject? = lock.withLock {
            ridEntriesLocked(session.hubId).firstOrNull { it.rid == rid }?.let { known ->
                if (known.bodyHash != hash) errorMessage(id, "bad_request")
                else withRe(Json.parseToJsonElement(known.reply).jsonObject, id)
            }
        }
        if (cached != null) return@withLock cached

        val reply = execute(session, command, type, root, origin)
        val message = toMessage(id, reply)
        if (!(reply.isError && (reply.code == "busy" || reply.code == "internal"))) {
            val withoutRe = JsonObject(message.filterKeys { it != "re" })
            val stored = lock.withLock {
                // The hub may have been revoked meanwhile (e.g. it revoked itself): keep nothing for it
                val paired = peers.any { it.id == session.hubId }
                if (paired) {
                    val entries = ridEntriesLocked(session.hubId)
                    entries.add(RidEntry(rid!!, hash, Json.encodeToString(JsonObject.serializer(), withoutRe), nowMillis()))
                    while (entries.size > AgentHost.MAX_RIDS_PER_HUB) entries.removeAt(0)
                }
                paired
            }
            if (stored) persist()
        }
        message
    }
}

private suspend fun AgentHost.execute(session: Session, command: Command, type: String, root: JsonObject, origin: RequestOrigin): Reply =
    try {
        if (command.host) hostCommand(session, type, root, origin)
        else backendRef.handle(type, root, RequestContext(session.hubId, session.hubName, session.role, origin))
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // e.g. a withTimeout inside the backend: answered internal, like C#
        Reply.error("internal")
    } catch (e: Exception) {
        Reply.error("internal")
    }

/** The live entries for a hub, oldest first, expired ones dropped. Under the lock. */
private fun AgentHost.ridEntriesLocked(hubId: String): MutableList<RidEntry> {
    val list = rids.getOrPut(hubId) { mutableListOf() }
    val cutoff = nowMillis() - AgentHost.RID_LIFETIME.inWholeMilliseconds
    list.removeAll { it.at < cutoff }
    return list
}

/** BLAKE2b-256 of the request without its per-session `id`: a resend in a new session matches. */
private fun bodyHash(root: JsonObject): String =
    Base64Url.encode(blake2b256(encodeJson(JsonObject(root.filterKeys { it != "id" }))))

// ---------------- peers.*, push.set (§7.2, §8.1, §10.6) ----------------

private fun AgentHost.hostCommand(session: Session, type: String, root: JsonObject, origin: RequestOrigin): Reply {
    when (type) {
        "push.set" -> {
            // §10.6: this hub's own token only; null (or absent) = no wake-ups
            var token: String? = null
            val t = root["token"]
            if (t != null && t !is JsonNull) {
                val s = t.asStringOrNull()
                if (s == null || !AgentHost.isPushToken(s)) return Reply.error("bad_request")
                token = s
            }
            // Optional: only these kinds wake this hub (the ones its user wants to be told about)
            var events: List<String>? = null
            val ev = root["events"]
            if (ev != null && ev !is JsonNull) {
                if (ev !is JsonArray || ev.size > 16) return Reply.error("bad_request")
                events = ev.map { e ->
                    val name = e.asStringOrNull()
                    if (name == null || name.length !in 1..32 || !name.all { it in 'a'..'z' || it in 'A'..'Z' }) return Reply.error("bad_request")
                    name
                }
            }
            lock.withLock {
                updatePeerLocked(session.hubId) { it.copy(pushToken = token, pushEvents = if (token == null) null else events) }
                    ?: return Reply.error("forbidden")
            }
            persist()
            return Reply.ok()
        }

        "peers.list" -> return Reply.ok(JsonObject(mapOf("peers" to peersJson(session.hubId))))

        "peers.revoke" -> {
            val target = root.str("peer")?.let { findPeer(it) } ?: return Reply.error("bad_request")
            if (session.role != PeerRole.Admin && target.id != session.hubId) return Reply.error("forbidden")
            return when (revoke(target.id, origin, protectLastAdmin = true)) {
                AgentHost.PeerChange.Done -> Reply.ok(JsonObject(mapOf("peers" to peersJson(session.hubId))))
                AgentHost.PeerChange.LastAdmin -> Reply.error("last_admin")
                else -> Reply.error("bad_request")
            }
        }

        "peers.setRole" -> {
            if (session.role != PeerRole.Admin) return Reply.error("forbidden")
            val target = root.str("peer")?.let { findPeer(it) }
            val role = when (root.str("role")) { "admin" -> PeerRole.Admin; "parent" -> PeerRole.Parent; else -> null }
            if (target == null || role == null) return Reply.error("bad_request")
            return when (setRole(target.id, role, origin, protectLastAdmin = true)) {
                AgentHost.PeerChange.Done -> Reply.ok(JsonObject(mapOf("peers" to peersJson(session.hubId))))
                AgentHost.PeerChange.LastAdmin -> Reply.error("last_admin")
                else -> Reply.error("bad_request")
            }
        }
    }
    return Reply.error("unknown_command")
}

// ---------------- reply shapes ----------------

private fun toMessage(id: Long, reply: Reply): JsonObject {
    val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    if (reply.isError) {
        m["t"] = JsonPrimitive("error"); m["re"] = JsonPrimitive(id); m["code"] = JsonPrimitive(reply.code)
    } else {
        m["t"] = JsonPrimitive("result"); m["re"] = JsonPrimitive(id)
    }
    for ((k, v) in reply.fields) if (k != "t" && k != "re" && k != "code") m[k] = v
    return JsonObject(m)
}

private fun errorMessage(id: Long, code: String): JsonObject = toMessage(id, Reply.error(code))

private fun withRe(stored: JsonObject, id: Long): JsonObject {
    val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    stored["t"]?.let { m["t"] = it }
    m["re"] = JsonPrimitive(id)
    for ((k, v) in stored) if (k != "t") m[k] = v
    return JsonObject(m)
}

/** Constant-time comparison of two pairing codes. */
internal fun codesEqual(a: String, b: String): Boolean {
    val x = a.encodeToByteArray()
    val y = b.encodeToByteArray()
    if (x.size != y.size) return false
    var diff = 0
    for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
    return diff == 0
}
