package com.gametimer.protocol.hub

import com.gametimer.protocol.identity.Ids
import com.gametimer.protocol.identity.PairingCode
import com.gametimer.protocol.noise.HandshakeState
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.NoiseException
import com.gametimer.protocol.noise.NoisePattern
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a pairing attempt ended (§8.2). The app tells the user why, never just "try again". */
public sealed interface PairOutcome {
    public data class Paired(val agent: PairedAgent) : PairOutcome

    /** The owner typed a wrong code, cancelled, or didn't answer on the PC in time. */
    public data object Denied : PairOutcome

    /** Another phone used the same QR: it may have been photographed. Start again on the PC. */
    public data object CodeUsed : PairOutcome

    /** The PC closed the connection at once: the QR is expired, already replaced, or not from this PC. */
    public data object Rejected : PairOutcome

    public data class Failed(val reason: String) : PairOutcome
}

/**
 * Pairs this hub with an agent (§8.2): IKpsk1 to `/v2/pair` with the QR's key and secret, `pair.hello`,
 * then [onCode] shows the 6-digit code the owner must type on the PC, then `pair.result`.
 */
public suspend fun pair(
    link: PairingLink,
    hubKey: KeyPair,
    hubName: String,
    appName: String,
    connector: Connector,
    onCode: (String) -> Unit,
): PairOutcome {
    val socket = try { connector.connect(link.host, link.port, "/v2/pair") } catch (e: Exception) {
        return PairOutcome.Failed("cannot reach ${link.host}:${link.port}: ${e.message}")
    }
    try {
        val hs = HandshakeState(NoisePattern.IKpsk1, true, PROLOGUE, hubKey, link.agentKey, link.secret)
        socket.send(hs.writeMessage(encodeJson(buildJsonObject { put("t", "pair") })))
        val msg2 = withTimeoutOrNull(10.seconds) { socket.receive() } ?: return PairOutcome.Rejected
        try { hs.readMessage(msg2) } catch (e: NoiseException) { return PairOutcome.Failed("the PC's key doesn't match the QR") }
        val (send, recv) = hs.split()

        socket.sendJson(send, buildJsonObject {
            put("t", "pair.hello"); put("protocol", PROTOCOL_MAJOR); put("minor", PROTOCOL_MINOR)
            put("name", hubName.trim().take(64)); put("app", appName)
        })
        onCode(PairingCode.derive(hs.handshakeHash()))

        // The owner has 60 s on the PC (then the PC says bye denied); allow for the network on top
        val reply = withTimeoutOrNull(75.seconds) { socket.receiveJson(recv) }
            ?: return PairOutcome.Failed("the connection to the PC was lost")
        return when (reply.type()) {
            "pair.result" -> paired(reply, link)
            "bye" -> when (val code = reply.string("code")) {
                "code_used" -> PairOutcome.CodeUsed
                "denied" -> PairOutcome.Denied
                else -> PairOutcome.Failed("the PC ended the pairing ($code)")
            }
            else -> PairOutcome.Failed("unexpected ${reply.type()}")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: HubProtocolException) {
        return PairOutcome.Failed(e.message ?: "protocol error")
    } catch (e: NoiseException) {
        return PairOutcome.Failed("decryption failed")
    } catch (e: Exception) {
        return PairOutcome.Failed("network error: ${e.message}") // Wi-Fi dropped, connection reset
    } finally {
        withContext(NonCancellable) { socket.close() }
    }
}

private fun paired(reply: JsonObject, link: PairingLink): PairOutcome {
    val agent = reply["agent"] as? JsonObject ?: return PairOutcome.Failed("pair.result without agent")
    val id = agent.string("id")
    // §4.4: the id must be derived from the key we pinned from the QR
    if (id != Ids.agentId(link.agentKey)) return PairOutcome.Failed("the PC's id doesn't match its key")
    val ak = reply.string("ak") ?: return PairOutcome.Failed("pair.result without ak")
    if ((reply["ok"] as? JsonPrimitive)?.content != "true") return PairOutcome.Denied
    return PairOutcome.Paired(
        PairedAgent(
            agentId = id,
            agentKey = com.gametimer.protocol.encodings.Base64Url.encode(link.agentKey),
            name = agent.string("name") ?: link.pcName,
            platform = agent.string("platform") ?: "unknown",
            host = link.host,
            port = link.port,
            ak = ak,
            role = reply.string("role") ?: "parent",
        ),
    )
}
