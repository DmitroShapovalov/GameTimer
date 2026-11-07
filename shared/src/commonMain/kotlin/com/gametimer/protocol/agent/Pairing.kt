package com.gametimer.protocol.agent

import com.gametimer.protocol.encodings.Base64Url
import com.gametimer.protocol.hub.encodeJson
import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.PairingCode
import com.gametimer.protocol.noise.CipherState
import com.gametimer.protocol.noise.HandshakeState
import com.gametimer.protocol.noise.NoiseException
import com.gametimer.protocol.noise.NoisePattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class PairHello(val send: CipherState, val recv: CipherState, val hubKey: ByteArray, val handshakeHash: ByteArray, val hubName: String)

/**
 * `/v2/pair` (§8.2): IKpsk1 with the current pairing secret, `pair.hello`, the owner types the 6-digit code
 * on this device, then `pair.result`. Only pair.hello, pair.result and bye are allowed.
 */
internal suspend fun AgentHost.runPairing(transport: FrameTransport, ticket: HandshakeTicket) {
    val ip = ticket.remoteIp
    var offer: PairingOffer? = lock.withLock { pairing }
    if (offer != null && expired(offer)) offer = null
    // No window open: still read msg 1 and fail it like a wrong secret, so the timing doesn't tell whether a
    // pairing window is open (§3: no oracle)
    val psk = offer?.secret?.copyOf() ?: AgentHost.randomBytes(32)
    val signals = listOfNotNull(offer?.aborted)
    var counted = false

    try {
        val step: Step<PairHello?>? = try {
            untilSignalled(scope, signals) {
                withTimeoutOrNull(AgentHost.HANDSHAKE_TIMEOUT) {
                    Step(pairHandshake(transport, ip, offer, psk) { counted = true })
                }
            }
        } catch (e: ProtocolViolation) {
            recordFailure(ip)
            return
        }
        if (step == null) {
            if (offer?.aborted?.isCancelled != true) recordFailure(ip) // timed out (an abort is not the phone's fault)
            return
        }
        val hello = step.value ?: return
        val theOffer = offer!!

        // pair.hello: only now has a live phone proven the secret (§8.2 step 4)
        ticket.release()
        val keyText = Base64Url.encode(hello.hubKey)
        val attempt: CompletableJob
        var replaced: CompletableJob? = null
        val twoPhones: Boolean
        lock.withLock {
            if (theOffer.usedBy == null) theOffer.usedBy = keyText
            twoPhones = theOffer.usedBy != keyText
            attempt = Job(theOffer.aborted)
            if (!twoPhones) {
                replaced = theOffer.activeAttempt
                theOffer.activeAttempt = attempt
            } else {
                // Close the window and abort the waiting attempt before anything else can run, so an owner
                // confirming at this very moment can't commit the first phone
                if (pairing === theOffer) pairing = null
                theOffer.abort()
            }
        }

        if (twoPhones) {
            // A second, different phone with the same QR: it may have been photographed (§8.2)
            sendDirect(transport, hello.send, byeMessage("code_used"))
            attempt.cancel()
            try { pairingUiRef.pairingAborted(PairingAbortReason.TwoPhones) } catch (e: Exception) { /* the app's UI */ }
            return
        }
        replaced?.cancel() // the same phone started over: its older attempt ends

        try {
            confirmAndCommit(transport, hello, theOffer, attempt, keyText)
        } finally {
            attempt.cancel()
        }
    } finally {
        if (counted) lock.withLock { offer!!.attempts-- }
        psk.fill(0)
    }
}

/** msg 1 → msg 2 → pair.hello, or null (counted as a failure where the C# agent counts it). */
private suspend fun AgentHost.pairHandshake(
    transport: FrameTransport, ip: String, offer: PairingOffer?, psk: ByteArray, onCounted: () -> Unit,
): PairHello? {
    val msg1 = transport.receive() ?: return null
    if (overFailureLimit(ip)) return null

    val hs = HandshakeState(NoisePattern.IKpsk1, false, AgentHost.PROLOGUE, key, null, psk)
    val payload = try { hs.readMessage(msg1) } catch (e: NoiseException) { recordFailure(ip); return null } // wrong secret (or no window)
    if (offer == null || !isBareMessage(payload, "pair")) { recordFailure(ip); return null }

    // A valid msg 1 may be a replay: it does not use up the secret, but takes an attempt slot
    lock.withLock {
        if (pairing !== offer || expired(offer) || offer.attempts >= AgentHost.MAX_PAIRING_ATTEMPTS) return null
        offer.attempts++
        onCounted()
    }

    transport.send(hs.writeMessage(ByteArray(0)))
    val (send, recv) = hs.split()
    val hubKey = hs.remoteStaticPublicKey!!
    val hash = hs.handshakeHash()

    val first = transport.receive() ?: run { recordFailure(ip); return null }
    val name = readPairHello(recv, first) ?: run { recordFailure(ip); return null }
    return PairHello(send, recv, hubKey, hash, name)
}

private suspend fun AgentHost.confirmAndCommit(
    transport: FrameTransport, hello: PairHello, offer: PairingOffer, attempt: CompletableJob, keyText: String,
) {
    val expected = PairingCode.derive(hello.handshakeHash)

    // The phone must stay quiet while the owner types; anything it sends (or a close) ends the attempt.
    // The owner's answer is awaited only as long as the attempt lives (60 s, abort, the phone).
    coroutineScope {
        val confirm = Job(attempt)
        val watch = async { watchQuiet(transport, confirm) }
        val answer = try {
            untilSignalled(scope, listOf(confirm), awaitCancelled = false) {
                withTimeoutOrNull(AgentHost.PAIRING_CONFIRM_TIMEOUT) { Step(askQuietly(hello.hubName)) }
            }?.value
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // only the caller's own cancellation goes on
            null
        } catch (e: Exception) {
            null // a broken dialog counts as a cancel
        }

        // Check and commit in one critical section: a second phone or a cancel can't slip in between
        val peer: Peer? = lock.withLock {
            if (answer != null && !confirm.isCancelled && pairing === offer && offer.usedBy == keyText &&
                !offer.aborted.isCancelled && codesEqual(answer.trim(), expected)
            ) commitPeerLocked(keyText, hello.hubName) else null
        }
        confirm.cancel()
        watch.await()
        if (peer == null) {
            sendDirect(transport, hello.send, byeMessage("denied"))
            return@coroutineScope
        }

        // Committed, then sent: if the phone vanishes in between it is paired without knowing it and simply
        // pairs again (same key: same role, a new ak). The opposite order could tell a phone "paired" for a hub
        // this device never stored.
        persist()
        sendDirect(transport, hello.send, buildJsonObject {
            put("t", "pair.result")
            put("ok", true)
            put("role", AgentHost.roleName(peer.role))
            put("agent", agentJson())
            put("ak", peer.ak)
        })
        publishPeers("added", peer.id, origin = null)
        firePeersChanged()
        firePaired(peer.forApp())
    }
}

/** The owner's code, or null if cancelled; a failing dialog counts as a cancel. */
private suspend fun AgentHost.askQuietly(hubName: String): String? =
    try {
        pairingUiRef.askForCode(hubName)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // the dialog's own cancellation (e.g. its timeout) is a cancel
        null
    } catch (e: Exception) {
        null
    }

/** Stores the hub (§8.2 step 7). A hub key paired before keeps its role and gets a new ak. Under the lock. */
private fun AgentHost.commitPeerLocked(keyText: String, hubName: String): Peer {
    val existing = peers.firstOrNull { it.publicKey == keyText }
    val role = existing?.role ?: if (peers.any { it.role == PeerRole.Admin }) PeerRole.Parent else PeerRole.Admin
    val peer = Peer(
        id = Ids.hubId(Base64Url.tryDecode(keyText)!!),
        publicKey = keyText,
        name = hubName,
        role = role,
        pairedAt = existing?.pairedAt ?: nowMillis(),
        lastSeen = existing?.lastSeen,
        ak = Base64Url.encode(AgentHost.randomBytes(32)),
    )
    if (existing != null) peers.remove(existing)
    peers.add(peer)
    return peer
}

/** The hub's name from a valid `pair.hello`, or null. */
private fun readPairHello(recv: CipherState, frame: ByteArray): String? {
    val plain = try { recv.decrypt(frame) } catch (e: NoiseException) { return null }
    val r = StrictJson.parse(plain)
    if (r.str("t") != "pair.hello") return null
    if (r.int("protocol") != AgentHost.PROTOCOL_MAJOR) return null
    val n = r.str("name")?.trim() ?: return null
    // No control or format characters (e.g. U+202E right-to-left override) in a name the owner reads
    if (n.isEmpty() || n.length > AgentHost.MAX_HUB_NAME_LENGTH || n.any { it.isISOControl() || it.category == CharCategory.FORMAT }) return null
    return n
}

/** Returns when the phone sends anything or closes, or when [confirm] is cancelled; then cancels [confirm]. */
private suspend fun AgentHost.watchQuiet(transport: FrameTransport, confirm: CompletableJob) {
    try {
        untilSignalled(scope, listOf(confirm)) { transport.receive() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // closed or a protocol violation: ends the attempt the same way
    }
    confirm.cancel()
}

private suspend fun sendDirect(transport: FrameTransport, send: CipherState, message: JsonObject) {
    try { withContext(NonCancellable) { transport.send(send.encrypt(encodeJson(message))) } } catch (e: Exception) { /* the phone is gone */ }
}

private fun byeMessage(code: String): JsonObject = buildJsonObject { put("t", "bye"); put("code", code) }

private fun AgentHost.expired(offer: PairingOffer): Boolean = nowMillis() >= offer.expiresAt
